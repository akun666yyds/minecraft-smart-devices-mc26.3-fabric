package com.akun.hajikun.computer;

import com.akun.hajikun.device.DeviceIdentity;
import com.akun.hajikun.device.DeviceRegistry;
import com.akun.hajikun.network.ModNetworking;
import com.akun.hajikun.registry.ModDataComponents;
import com.akun.hajikun.registry.ModItems;
import com.akun.hajikun.tv.TvBlockEntity;
import com.akun.hajikun.vm.PeerHost;
import com.akun.hajikun.vm.VirtualFileSystem;
import com.akun.hajikun.vm.VirtualMachine;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 一台电脑的方块实体：虚拟机本体、设备身份与开关机状态都保存在这里。
 *
 * <p>软关机只把 {@code powered} 置为 false，虚拟机仍保留运行状态（当前目录、文件、
 * 开机次数），下次开机直接恢复；硬关机额外清空会话并把目录复位到主目录。</p>
 *
 * <p>挖掘方块等同于硬关机：完整虚拟机写入掉落物，方块与设备目录条目立即注销；
 * 重新放下后从物品恢复，物品被销毁或丢入虚空后这份数据随物品彻底释放。</p>
 *
 * <p>同时实现 {@link PeerHost}：蓝牙扫描/配对、向另一台电脑发送文件、投屏到电视，
 * 这些需要访问世界的操作都不会进入虚拟机内核。</p>
 */
public class ComputerBlockEntity extends BlockEntity implements PeerHost {
    private static final double BLUETOOTH_RANGE = 64.0;
    private static final double BLUETOOTH_RANGE_SQR = BLUETOOTH_RANGE * BLUETOOTH_RANGE;

    private final ComputerKind kind;
    private UUID deviceId;
    private String name = "";
    private boolean powered;
    private boolean suspended;
    private long poweredAt;
    private int boots;
    private VirtualMachine machine = new VirtualMachine();

    public ComputerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, ComputerKind kind) {
        super(type, pos, state);
        this.kind = kind;
    }

    public ComputerKind kind() {
        return this.kind;
    }

    /** 首次访问时生成设备身份；UUID 与 MAC 一旦生成就写进存档，不再改变。 */
    public UUID ensureIdentity() {
        if (this.deviceId == null) {
            this.deviceId = UUID.randomUUID();
            this.setChanged();
        }
        return this.deviceId;
    }

    public String deviceId() {
        return this.ensureIdentity().toString();
    }

    /** 只读的设备 UUID；不触发身份生成，供目录校验使用。 */
    public String deviceIdOrNull() {
        return this.deviceId == null ? null : this.deviceId.toString();
    }

    /** 由设备 UUID 派生 MAC：本地管理位固定为 1，同一存档内不会重复。 */
    public String mac() {
        return DeviceIdentity.mac(this.ensureIdentity());
    }

    /** 把自己写进存档设备目录，供蓝牙扫描、文件传输和投屏查找。 */
    public void register() {
        if (this.level instanceof ServerLevel server) {
            // 原版会把物品上的自定义组件原样复制到方块实体，中键拾取时又复制出去。
            // 电脑的数据只走 device_carry，这里丢掉继承来的组件，避免数据泄漏与重复。
            this.setComponents(DataComponentMap.EMPTY);
            this.ensureIdentity();
            DeviceRegistry.get(server).register(new DeviceRegistry.Entry(
                    this.deviceId.toString(), this.name, this.mac(), this.kind.id(),
                    server.dimension().identifier().toString(),
                    this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ(),
                    this.powered));
        }
    }

    public void unregister() {
        if (this.level instanceof ServerLevel server && this.deviceId != null) {
            DeviceRegistry.get(server).remove(this.deviceId.toString());
        }
    }

    /**
     * 挖掉电脑时调用：硬关机并把整台虚拟机打进物品。
     *
     * <p>这里只改内存状态，绝不调用 {@code setBlock}——方块正在被移除，
     * 任何写回都会在原地留下一个没有数据的新方块。</p>
     */
    public ItemStack pickUpItem() {
        this.powered = false;
        this.suspended = false;
        this.machine.hardReset();
        this.setChanged();
        ItemStack stack = new ItemStack(this.kind == ComputerKind.LAPTOP
                ? ModItems.LAPTOP : ModItems.DESKTOP);
        stack.set(ModDataComponents.DEVICE_DATA, this.carryBytes());
        stack.set(ModDataComponents.DEVICE_SUMMARY, this.summaryJson());
        return stack;
    }

    /** 方块离开世界：注销设备目录，并清掉其它电脑的配对与电视的投屏引用。 */
    public void retire() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        String mac = this.mac();
        DeviceRegistry registry = DeviceRegistry.get(server);
        if (this.deviceId != null) {
            registry.remove(this.deviceId.toString());
        }
        registry.removeAt(server.dimension().identifier().toString(), this.worldPosition);
        DeviceRegistry.purgePeer(server, mac);
    }

    /**
     * 放置时防止复制物品造成两台机器共用一个 MAC：同一个 UUID 已经存在于别的坐标
     * 且那台机器还活着时，这台保留名字与文件，但换一份新的设备身份。
     */
    public void claimIdentity() {
        if (!(this.level instanceof ServerLevel server) || this.deviceId == null) {
            return;
        }
        DeviceRegistry.Entry existing = DeviceRegistry.get(server).byId(this.deviceId.toString());
        if (existing == null || existing.pos().equals(this.worldPosition)) {
            return;
        }
        if (!server.isLoaded(existing.pos())) {
            return;
        }
        if (server.getBlockEntity(existing.pos()) instanceof ComputerBlockEntity other && other != this) {
            this.deviceId = UUID.randomUUID();
            this.setChanged();
        }
    }

    public String deviceName() {
        return this.name;
    }

    public boolean named() {
        return !this.name.isBlank();
    }

    public boolean powered() {
        return this.powered;
    }

    public boolean suspended() {
        return this.suspended;
    }

    public int boots() {
        return this.boots;
    }

    public long uptime() {
        return this.powered && this.level != null ? this.level.getGameTime() - this.poweredAt : 0L;
    }

    public VirtualMachine machine() {
        return this.machine;
    }

    public VirtualFileSystem fs() {
        return this.machine.fs();
    }

    /** 执行一次设备动作；返回给界面的提示文字。 */
    public String act(String action, String arg) {
        String value = arg == null ? "" : arg;
        switch (action) {
            case "rename" -> {
                String cleaned = value.trim();
                if (cleaned.isEmpty()) {
                    return "设备名不能为空。";
                }
                if (cleaned.length() > 32) {
                    return "设备名最多 32 个字符。";
                }
                this.name = cleaned;
                this.setChanged();
                this.register();
                return "设备名已保存：" + this.name;
            }
            case "boot" -> {
                if (!this.named()) {
                    return "请先设置设备名称。";
                }
                if (this.powered) {
                    return "设备已经开机。";
                }
                boolean resumed = this.suspended;
                this.powered = true;
                this.suspended = false;
                this.poweredAt = this.level == null ? 0L : this.level.getGameTime();
                this.boots++;
                this.setChanged();
                this.syncPowerState(true);
                this.register();
                return resumed ? "已从软关机状态恢复。" : "已开机。";
            }
            case "softoff" -> {
                if (!this.powered) {
                    return "设备已经关机。";
                }
                this.powered = false;
                this.suspended = true;
                this.setChanged();
                this.syncPowerState(false);
                this.register();
                return "已软关机，虚拟机状态已保留。";
            }
            case "hardoff" -> {
                if (!this.powered) {
                    return "设备已经关机。";
                }
                this.powered = false;
                this.suspended = false;
                this.machine.hardReset();
                this.setChanged();
                this.syncPowerState(false);
                this.register();
                return "已硬关机，运行中的会话已丢失（磁盘文件仍保留）。";
            }
            case "shell" -> {
                if (!this.powered) {
                    return "设备处于关机状态。";
                }
                String output = this.machine.execute(value, this.name, this.mac(), this.uptime(), this);
                this.setChanged();
                return output;
            }
            case "write" -> {
                if (!this.powered) {
                    return "设备处于关机状态。";
                }
                int newline = value.indexOf('\n');
                if (newline <= 0) {
                    return "写入失败：缺少文件路径。";
                }
                String path = value.substring(0, newline);
                String content = value.substring(newline + 1);
                String absolute = VirtualFileSystem.normalize(this.machine.cwd(), path);
                if (this.machine.fs().write(absolute, content)) {
                    this.setChanged();
                    return "已保存 " + absolute;
                }
                return "保存失败：" + absolute;
            }
            case "refresh" -> {
                return "";
            }
            case "bt_toggle" -> {
                boolean next = value.isBlank() ? !this.machine.bluetooth()
                        : switch (value.toLowerCase(Locale.ROOT)) {
                            case "on", "true", "1" -> true;
                            default -> false;
                        };
                this.machine.setBluetooth(next);
                this.setChanged();
                return next ? "蓝牙已开启，正在扫描附近设备。" : "蓝牙已关闭。";
            }
            case "bt_pair" -> {
                if (!this.machine.bluetooth()) {
                    return "蓝牙已关闭，请先打开蓝牙。";
                }
                return this.pair(value);
            }
            case "bt_unpair" -> {
                this.machine.removePaired(value);
                this.setChanged();
                DeviceRegistry.Entry entry = this.lookup(value);
                if (entry != null && this.level != null
                        && this.level.getBlockEntity(entry.pos()) instanceof ComputerBlockEntity other) {
                    other.machine.removePaired(this.mac());
                    other.setChanged();
                }
                return "已取消配对：" + value;
            }
            case "bt_send" -> {
                int newline = value.indexOf('\n');
                if (newline <= 0) {
                    return "发送失败：没有选择文件。";
                }
                return this.send(value.substring(0, newline).trim(), value.substring(newline + 1));
            }
            case "cast" -> {
                return this.cast(value.trim());
            }
            case "fs_delete" -> {
                String path = VirtualFileSystem.normalize("/", value);
                if (VirtualFileSystem.isProtected(path)) {
                    return "系统文件受保护，不能删除。";
                }
                VirtualFileSystem.Node node = this.machine.fs().find(path);
                if (node == null) {
                    return "找不到：" + path;
                }
                boolean removed = this.machine.fs().delete(path);
                this.setChanged();
                return removed ? "已删除 " + VirtualFileSystem.fileName(path) : "删除失败：" + path;
            }
            case "fs_rename" -> {
                int newline = value.indexOf('\n');
                if (newline <= 0) {
                    return "重命名失败：缺少新名称。";
                }
                return this.renamePath(value.substring(0, newline), value.substring(newline + 1));
            }
            default -> {
                return "未知操作：" + action;
            }
        }
    }

    private void syncPowerState(boolean on) {
        if (this.level == null || this.level.isClientSide()) {
            return;
        }
        BlockState state = this.getBlockState();
        if (state.hasProperty(ComputerBlock.POWERED)) {
            this.level.setBlock(this.worldPosition, state.setValue(ComputerBlock.POWERED, on), 3);
        }
    }

    // ------------------------------------------------------------------ 设备网络

    private DeviceRegistry registry() {
        return this.level instanceof ServerLevel server ? DeviceRegistry.get(server) : null;
    }

    private DeviceRegistry.Entry lookup(String mac) {
        if (!(this.level instanceof ServerLevel server)) {
            return null;
        }
        // 每次查找前先剔除已经消失在世界里的旧条目，避免配对到幽灵设备。
        DeviceRegistry.reconcile(server);
        DeviceRegistry.Entry exactMac = DeviceRegistry.get(server).byMac(mac);
        if (exactMac != null) {
            return exactMac;
        }
        String query = mac == null ? "" : mac.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return null;
        }
        DeviceRegistry.Entry partial = null;
        int partials = 0;
        for (DeviceRegistry.Entry entry : DeviceRegistry.get(server).all()) {
            String label = entry.label() == null ? "" : entry.label().toLowerCase(Locale.ROOT);
            if (label.equals(query) || entry.mac().equalsIgnoreCase(query)) {
                return entry;
            }
            if (label.contains(query)) {
                partial = entry;
                partials++;
            }
        }
        // 名称重复时返回 null，让调用方提示用户改用 MAC，避免投错设备。
        return partials == 1 ? partial : null;
    }

    private boolean inRange(DeviceRegistry.Entry entry) {
        if (this.level == null || entry == null) {
            return false;
        }
        return entry.pos().distSqr(this.worldPosition) <= BLUETOOTH_RANGE_SQR;
    }

    private String kindLabel(String kind) {
        if (kind == null) {
            return "设备";
        }
        return switch (kind) {
            case "desktop" -> "台式机";
            case "laptop" -> "笔记本";
            case "tv_2x1" -> "电视 2×1";
            case "tv_5x3" -> "电视 5×3";
            case "tv_16x9" -> "电视 16×9";
            default -> "设备";
        };
    }

    @Override
    public String scan() {
        if (!(this.level instanceof ServerLevel server)) {
            return "设备网络不可用。";
        }
        DeviceRegistry.reconcile(server);
        String dimension = server.dimension().identifier().toString();
        StringBuilder out = new StringBuilder("附近的智能设备（64 格内）：");
        int count = 0;
        for (DeviceRegistry.Entry entry : DeviceRegistry.get(server).all()) {
            if (!dimension.equals(entry.dimension()) || entry.id().equals(this.deviceId())
                    || entry.mac().equalsIgnoreCase(this.mac())) {
                continue;
            }
            if (!this.inRange(entry)) {
                continue;
            }
            PeerInfo info = this.resolve(entry);
            if (info == null) {
                continue;
            }
            out.append("\n  ").append(info.name().isBlank() ? this.kindLabel(info.kind()) : info.name())
                    .append("  [").append(this.kindLabel(info.kind())).append("]")
                    .append("  ").append(info.mac())
                    .append(info.online() ? "  开机" : "  关机");
            count++;
        }
        if (count == 0) {
            return "附近 64 格内没有发现其它智能设备。";
        }
        return out.toString();
    }

    @Override
    public String find(String query) {
        if (!(this.level instanceof ServerLevel server)) {
            return "设备网络不可用。";
        }
        DeviceRegistry.reconcile(server);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String dimension = server.dimension().identifier().toString();
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (DeviceRegistry.Entry entry : DeviceRegistry.get(server).all()) {
            if (!dimension.equals(entry.dimension()) || entry.id().equals(this.deviceId())
                    || entry.mac().equalsIgnoreCase(this.mac()) || !this.inRange(entry)) {
                continue;
            }
            PeerInfo info = this.resolve(entry);
            if (info == null) {
                continue;
            }
            String label = info.name().isBlank() ? this.kindLabel(info.kind()) : info.name();
            if (!needle.isEmpty()
                    && !label.toLowerCase(Locale.ROOT).contains(needle)
                    && !info.mac().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.append("\n  ").append(label)
                    .append("  [").append(this.kindLabel(info.kind())).append("]")
                    .append("  ").append(info.mac())
                    .append(info.online() ? "  开机" : "  关机");
            count++;
        }
        if (count == 0) {
            return needle.isEmpty() ? "附近 64 格内没有发现其它智能设备。"
                    : "没有找到名称或 MAC 包含「" + query.trim() + "」的设备。可先用 bt scan 查看完整列表。";
        }
        return "找到 " + count + " 台设备：" + out;
    }

    @Override
    public String pair(String mac) {
        if (!this.machine.bluetooth()) {
            return "蓝牙已关闭，请先打开蓝牙。";
        }
        DeviceRegistry.Entry entry = this.lookup(mac);
        if (entry == null) {
            return "没有找到设备：" + mac + "（可用 bt scan 查看名称与 MAC）";
        }
        if (entry.id().equals(this.deviceId())) {
            return "不能和自己配对。";
        }
        if (!this.inRange(entry)) {
            return "设备距离太远：蓝牙范围是 64 格。";
        }
        this.machine.addPaired(entry.mac());
        this.setChanged();
        // 配对是双向的：对方的电脑也会记住这台设备；电视没有虚拟机，只在本机记录。
        boolean mutual = false;
        if (this.level != null && this.level.getBlockEntity(entry.pos()) instanceof ComputerBlockEntity other) {
            other.machine.addPaired(this.mac());
            other.setChanged();
            mutual = true;
        }
        return "已配对：" + entry.label() + "（" + entry.mac() + "）" + (mutual ? "，对方也已记录本机" : "");
    }

    @Override
    public String send(String mac, String path) {
        if (!this.machine.bluetooth()) {
            return "蓝牙已关闭，请先打开蓝牙。";
        }
        DeviceRegistry.Entry entry = this.lookup(mac);
        if (entry == null) {
            return "没有找到设备：" + mac + "（可用 bt scan 查看名称与 MAC）";
        }
        if (entry.kind().startsWith("tv_")) {
            return "电视不能接收文件，请对它使用投屏。";
        }
        if (!this.inRange(entry)) {
            return "设备距离太远：蓝牙范围是 64 格。";
        }
        if (!this.machine.isPaired(entry.mac())) {
            return "还没有和这台设备配对，请先配对再传输。";
        }
        if (!(this.level.getBlockEntity(entry.pos()) instanceof ComputerBlockEntity other)) {
            return "目标电脑不在加载范围内，靠近后再试。";
        }
        VirtualFileSystem.Node node = this.machine.fs().find(path);
        if (node == null) {
            return "找不到文件：" + path;
        }
        if (node.isDir()) {
            return "暂不支持直接发送目录，请发送具体文件。";
        }
        String fileName = VirtualFileSystem.fileName(path);
        String destination = "/home/user/下载/" + fileName;
        boolean copied;
        if (node.isBinary()) {
            byte[] bytes = this.machine.fs().readBytes(path);
            copied = bytes != null
                    && other.machine.fs().writeMedia(destination, node.mime, bytes) != null;
        } else {
            copied = other.machine.fs().write(destination, node.content == null ? "" : node.content);
        }
        if (!copied) {
            return "对方写入失败：" + destination;
        }
        other.setChanged();
        this.setChanged();
        return "已传输 " + fileName + " → " + other.deviceName() + ":" + destination;
    }

    @Override
    public String cast(String mac) {
        if (!this.machine.bluetooth()) {
            return "蓝牙已关闭，请先打开蓝牙。";
        }
        DeviceRegistry.Entry entry = this.lookup(mac);
        if (entry == null) {
            return "没有找到设备：" + mac + "（可用 bt scan 查看名称与 MAC）";
        }
        if (!entry.kind().startsWith("tv_")) {
            return "该设备不是电视，请改为传输文件。";
        }
        if (!this.inRange(entry)) {
            return "电视距离太远：投屏范围是 64 格。";
        }
        if (!(this.level.getBlockEntity(entry.pos()) instanceof TvBlockEntity tv)) {
            return "电视不在加载范围内，靠近后再试。";
        }
        tv.startCast(this.named() ? this.name : "未命名电脑", this.mac(), this.snapshot("").toString());
        return "已投屏到 " + entry.label() + "（" + entry.mac() + "），电视已自动开机。";
    }

    @Override
    public String castAll() {
        if (!this.machine.bluetooth()) {
            return "蓝牙已关闭，请先打开蓝牙。";
        }
        if (!(this.level instanceof ServerLevel server)) {
            return "设备网络不可用。";
        }
        DeviceRegistry.reconcile(server);
        String dimension = server.dimension().identifier().toString();
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (DeviceRegistry.Entry entry : DeviceRegistry.get(server).all()) {
            if (!dimension.equals(entry.dimension()) || !entry.kind().startsWith("tv_")
                    || !this.inRange(entry) || !server.isLoaded(entry.pos())) {
                continue;
            }
            if (!(server.getBlockEntity(entry.pos()) instanceof TvBlockEntity tv)) {
                continue;
            }
            tv.startCast(this.named() ? this.name : "未命名电脑", this.mac(), this.snapshot("").toString());
            out.append(count == 0 ? "" : "、").append(entry.label());
            count++;
        }
        if (count == 0) {
            return "附近 64 格内没有可投屏的电视。";
        }
        return "已广播投屏到 " + count + " 台电视：" + out + "。";
    }

    /** 本机状态变化后，刷新所有正在投屏本机的电视画面。 */
    public void syncCast() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        String myMac = this.mac();
        for (DeviceRegistry.Entry entry : DeviceRegistry.get(server).all()) {
            if (!entry.kind().startsWith("tv_") || !server.isLoaded(entry.pos())) {
                continue;
            }
            if (server.getBlockEntity(entry.pos()) instanceof TvBlockEntity tv
                    && tv.castMac().equalsIgnoreCase(myMac)) {
                tv.refreshCastSource();
            }
        }
    }

    /** 重命名：只允许改同一目录下的名字，系统路径与重名都会被拒绝。 */
    private String renamePath(String rawFrom, String rawTo) {
        String from = VirtualFileSystem.normalize(this.machine.cwd(), rawFrom);
        String name = rawTo == null ? "" : rawTo.trim();
        if (name.isEmpty()) {
            return "名称不能为空。";
        }
        if (name.contains("/") || name.contains("\\")) {
            return "名称里不能包含路径分隔符。";
        }
        if (VirtualFileSystem.isProtected(from)) {
            return "系统文件受保护，不能重命名。";
        }
        String parent = VirtualFileSystem.parent(from);
        String to = (parent.equals("/") ? "" : parent) + "/" + name;
        if (to.equals(from)) {
            return "名称没有变化。";
        }
        if (this.machine.fs().find(to) != null) {
            return "当前目录下已有同名文件或文件夹。";
        }
        boolean moved = this.machine.fs().move(from, to);
        this.setChanged();
        return moved ? "已重命名为 " + name : "重命名失败：" + from;
    }

    @Override
    public String paired() {
        if (!(this.level instanceof ServerLevel server)) {
            return "设备网络不可用。";
        }
        DeviceRegistry.reconcile(server);
        Set<String> macs = this.machine.pairedMacs();
        if (macs.isEmpty()) {
            return "还没有配对的设备。先 bt scan 再 bt pair <MAC>。";
        }
        StringBuilder out = new StringBuilder("已配对设备：");
        for (String mac : macs) {
            PeerInfo info = this.resolve(DeviceRegistry.get(server).byMac(mac));
            out.append("\n  ").append(info == null ? "（已注销）" : info.name().isBlank()
                            ? this.kindLabel(info.kind()) : info.name())
                    .append("  ").append(mac);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 存档

    /** 打包成客户端可读的 JSON 快照。 */
    public JsonObject snapshot(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", this.kind.id());
        root.addProperty("name", this.name);
        root.addProperty("mac", this.mac());
        root.addProperty("powered", this.powered);
        root.addProperty("suspended", this.suspended);
        root.addProperty("boots", this.boots);
        root.addProperty("uptime", this.uptime());
        root.addProperty("cwd", this.machine.cwd());
        root.add("fs", this.machine.fs().toJson());
        root.addProperty("bt", this.machine.bluetooth());
        root.add("paired", this.peerJson(true));
        root.add("nearby", this.peerJson(false));
        root.addProperty("message", message == null ? "" : message);
        return root;
    }

    /** 蓝牙列表里的一行：优先读实机，未加载时退回目录条目。 */
    private record PeerInfo(String mac, String name, String kind, boolean online) {
    }

    /**
     * 把目录条目解析成实时设备信息。
     *
     * <p>方块实体在加载范围内时直接读机器本身，名称与 MAC 永远和这台机器一致；
     * 已加载却发现方块实体不存在或 UUID 对不上，说明该条目已经过期，立即删除。</p>
     */
    private PeerInfo resolve(DeviceRegistry.Entry entry) {
        if (!(this.level instanceof ServerLevel server) || entry == null) {
            return null;
        }
        if (!entry.dimension().equals(server.dimension().identifier().toString())) {
            return null;
        }
        if (!server.isLoaded(entry.pos())) {
            return new PeerInfo(entry.mac(), entry.name(), entry.kind(), false);
        }
        BlockEntity blockEntity = server.getBlockEntity(entry.pos());
        if (blockEntity instanceof ComputerBlockEntity computer) {
            String id = computer.deviceIdOrNull();
            if (id == null || !id.equals(entry.id())) {
                DeviceRegistry.get(server).remove(entry.id());
                return null;
            }
            return new PeerInfo(computer.mac(), computer.deviceName(), computer.kind().id(),
                    computer.powered());
        }
        if (blockEntity instanceof TvBlockEntity tv) {
            String id = tv.deviceIdOrNull();
            if (id == null || !id.equals(entry.id())) {
                DeviceRegistry.get(server).remove(entry.id());
                return null;
            }
            return new PeerInfo(tv.mac(), tv.deviceName(), tv.kind().id(), tv.powered());
        }
        DeviceRegistry.get(server).remove(entry.id());
        return null;
    }

    /** 蓝牙面板需要的设备列表：已配对（可跨距离）与附近 64 格（可配对）。 */
    private JsonArray peerJson(boolean pairedOnly) {
        JsonArray array = new JsonArray();
        if (!(this.level instanceof ServerLevel server)) {
            return array;
        }
        DeviceRegistry registry = DeviceRegistry.get(server);
        String dimension = server.dimension().identifier().toString();
        List<PeerInfo> entries = new ArrayList<>();
        if (pairedOnly) {
            for (String mac : this.machine.pairedMacs()) {
                PeerInfo info = this.resolve(registry.byMac(mac));
                if (info == null || info.mac().equalsIgnoreCase(this.mac())) {
                    // 对方已经注销：配对记录没有意义，直接移除。
                    this.machine.removePaired(mac);
                    this.setChanged();
                    continue;
                }
                if (!info.mac().equalsIgnoreCase(mac)) {
                    this.machine.removePaired(mac);
                    this.machine.addPaired(info.mac());
                    this.setChanged();
                }
                entries.add(info);
            }
        } else {
            for (DeviceRegistry.Entry entry : registry.all()) {
                if (entry.id().equals(this.deviceId()) || entry.mac().equalsIgnoreCase(this.mac())
                        || !dimension.equals(entry.dimension()) || !this.inRange(entry)) {
                    continue;
                }
                PeerInfo info = this.resolve(entry);
                if (info == null || info.mac().equalsIgnoreCase(this.mac())) {
                    continue;
                }
                entries.add(info);
            }
            entries.sort(Comparator
                    .comparing((PeerInfo info) -> !info.online())
                    .thenComparing(info -> info.name().isBlank() ? this.kindLabel(info.kind()) : info.name())
                    .thenComparing(PeerInfo::mac));
        }
        for (PeerInfo info : entries) {
            JsonObject peer = new JsonObject();
            peer.addProperty("mac", info.mac());
            peer.addProperty("name", info.name());
            peer.addProperty("kind", info.kind());
            peer.addProperty("online", info.online());
            peer.addProperty("paired", this.machine.isPaired(info.mac()));
            array.add(peer);
        }
        return array;
    }

    /** 掉落物携带的完整虚拟机数据（兼容旧物品）。 */
    public String carryJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", this.deviceId == null ? "" : this.deviceId.toString());
        root.addProperty("name", this.name);
        root.addProperty("powered", this.powered);
        root.addProperty("suspended", this.suspended);
        root.addProperty("boots", this.boots);
        root.add("vm", this.machine.toJson());
        return root.toString();
    }

    /** 物品信息用的短摘要：名称、UUID、开机次数。 */
    public String summaryJson() {
        JsonObject root = new JsonObject();
        root.addProperty("id", this.deviceId == null ? "" : this.deviceId.toString());
        root.addProperty("name", this.name);
        root.addProperty("boots", this.boots);
        root.addProperty("files", this.machine.fs().list("/home/user").size());
        return root.toString();
    }

    /**
     * 新版掉落数据：设备信息 JSON + 媒体二进制一起压缩。
     *
     * <p>物品组件可以装字节数组，P2V 导入的图片、录音和录像不会受到
     * NBT 字符串 64KB 上限的影响，挖掘后仍能完整还原。</p>
     */
    public byte[] carryBytes() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             DataOutputStream out = new DataOutputStream(gzip)) {
            byte[] json = this.carryJson().getBytes(StandardCharsets.UTF_8);
            byte[] bundle = this.machine.fs().mediaBundle();
            out.writeInt(0x484B4350);
            out.writeInt(json.length);
            out.write(json);
            out.writeInt(bundle.length);
            out.write(bundle);
            out.flush();
            gzip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    /** 还原新版掉落数据；失败时交给调用方按空白机器处理。 */
    public void applyCarryBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != 0x484B4350) {
                return;
            }
            int jsonLength = in.readInt();
            if (jsonLength < 0 || jsonLength > 64 * 1024 * 1024) {
                return;
            }
            byte[] json = new byte[jsonLength];
            in.readFully(json);
            int bundleLength = in.readInt();
            if (bundleLength < 0 || bundleLength > 512 * 1024 * 1024) {
                return;
            }
            byte[] bundle = new byte[bundleLength];
            in.readFully(bundle);
            this.applyCarry(new String(json, StandardCharsets.UTF_8));
            this.machine.fs().loadMediaBundle(bundle);
            this.setChanged();
        } catch (IOException | RuntimeException ignored) {
            // 损坏的物品数据按空白机器处理。
        }
    }

    /**
     * P2V 导入/照相机传照片：把一段二进制写入虚拟机文件系统。
     *
     * @return 成功时返回落地路径，失败返回 null
     */
    public String receiveMedia(String directory, String name, String mime, byte[] data) {
        if (data == null || name == null || name.isBlank()) {
            return null;
        }
        String dir = directory == null || directory.isBlank() ? "/home/user/下载" : directory;
        if (!dir.startsWith("/")) {
            dir = "/" + dir;
        }
        String clean = name.replace('/', '_').replace('\\', '_');
        String path = (dir.equals("/") ? "" : dir) + "/" + clean;
        String result = this.machine.fs().writeMedia(path, mime, data);
        if (result != null) {
            this.setChanged();
            this.syncCast();
        }
        return result;
    }

    /** 读取一个文件的二进制内容，供客户端媒体查看器拉取。 */
    public byte[] readMedia(String path) {
        return this.machine.fs().readBytes(path);
    }

    public String mimeOf(String path) {
        return this.machine.fs().mimeOf(path);
    }

    /** 把掉落物里的虚拟机装回方块实体。
     *
     * <p>这里不登记目录：调用方要先 {@link #claimIdentity()} 处理复制物品导致的
     * 同一 UUID 冲突，再 {@link #register()}；否则登记会用新坐标覆盖原机器的条目。</p>
     */
    public void applyCarry(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("id") && !root.get("id").getAsString().isBlank()) {
                this.deviceId = UUID.fromString(root.get("id").getAsString());
            }
            this.name = root.has("name") ? root.get("name").getAsString() : "";
            this.powered = root.has("powered") && root.get("powered").getAsBoolean();
            this.suspended = root.has("suspended") && root.get("suspended").getAsBoolean();
            this.boots = root.has("boots") ? root.get("boots").getAsInt() : 0;
            this.poweredAt = this.level == null ? 0L : this.level.getGameTime();
            this.machine = VirtualMachine.fromJson(root.getAsJsonObject("vm"));
            this.setChanged();
        } catch (RuntimeException exception) {
            this.name = "";
            this.deviceId = null;
        }
    }

    /** 把方块实体清成一台从未注册的新机器：中键拾取或普通物品放下时使用。 */
    public void resetToBlank() {
        this.deviceId = null;
        this.name = "";
        this.powered = false;
        this.suspended = false;
        this.boots = 0;
        this.machine = new VirtualMachine();
        this.poweredAt = this.level == null ? 0L : this.level.getGameTime();
        this.setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // 不要用 "id"：方块实体框架已经用它保存类型 ID，覆盖后读档会解析失败。
        output.putString("deviceId", this.deviceId == null ? "" : this.deviceId.toString());
        output.putString("name", this.name);
        output.putBoolean("powered", this.powered);
        output.putBoolean("suspended", this.suspended);
        output.putInt("boots", this.boots);
        output.putString("vm", this.machine.toJson().toString());
        byte[] bundle = this.machine.fs().mediaBundle();
        if (bundle.length > 0) {
            output.store("media", ModDataComponents.BYTE_ARRAY, bundle);
        }
    }

    /** 区块同步只带空标签：媒体与文件状态始终由 {@code DeviceStateS2C} 单独发送。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return new CompoundTag();
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.deviceId = parseUuid(input.getStringOr("deviceId", ""));
        this.name = input.getStringOr("name", "");
        this.powered = input.getBooleanOr("powered", false);
        this.suspended = input.getBooleanOr("suspended", false);
        this.boots = input.getIntOr("boots", 0);
        String vm = input.getStringOr("vm", "");
        if (!vm.isBlank()) {
            try {
                this.machine = VirtualMachine.fromJson(JsonParser.parseString(vm).getAsJsonObject());
            } catch (RuntimeException ignored) {
                this.machine = new VirtualMachine();
            }
        }
        byte[] bundle = input.read("media", ModDataComponents.BYTE_ARRAY).orElse(null);
        if (bundle != null && bundle.length > 0) {
            this.machine.fs().loadMediaBundle(bundle);
        }
        this.poweredAt = this.level == null ? 0L : this.level.getGameTime();
        // 读档后如果处于开机状态，运行时长从读档时刻重新计算，避免跨存档得到负值。
        if (this.powered && this.poweredAt == 0L && this.level != null) {
            this.poweredAt = this.level.getGameTime();
        }
        if (this.level instanceof ServerLevel server) {
            // 物品里的方块实体数据可能沿用世界里另一台机器的 UUID；那种情况交给
            // setPlacedBy 重新分配身份，不能把旧设备条目挪到新坐标上。
            DeviceRegistry.Entry existing = this.deviceId == null ? null
                    : DeviceRegistry.get(server).byId(this.deviceId.toString());
            if (existing == null || existing.pos().equals(this.worldPosition)) {
                this.register();
            }
        }
    }

    /** 存档里的设备 UUID；损坏或为空时返回 null，让 ensureIdentity 重新生成。 */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 供方块交互与网络层使用：打开界面时把快照发回客户端。 */
    public void sendState(ServerPlayer player, boolean open, String message) {
        if (this.level == null) {
            return;
        }
        this.ensureIdentity();
        this.register();
        ModNetworking.sendState(player, this.getBlockPos(), this.snapshot(message).toString(), open);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "ComputerBlockEntity[%s, %s, powered=%s]",
                this.kind.id(), this.deviceId, this.powered);
    }
}
