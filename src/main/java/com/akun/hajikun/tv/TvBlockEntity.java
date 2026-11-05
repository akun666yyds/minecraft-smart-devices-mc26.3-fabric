package com.akun.hajikun.tv;

import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.device.DeviceIdentity;
import com.akun.hajikun.device.DeviceRegistry;
import com.akun.hajikun.network.ModNetworking;
import com.akun.hajikun.registry.ModBlocks;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 多格电视的主控方块实体：设备身份、开关机与投屏来源。
 *
 * <p>整块屏幕由一格主控 + 若干拼接格组成，主控把电源与投屏状态同步到所有拼接格，
 * 因此从任何一格看过去都是同一个画面。</p>
 */
public class TvBlockEntity extends BlockEntity {
    private UUID deviceId;
    private TvKind kind = TvKind.MEDIUM;
    private String name = "电视";
    private boolean powered;
    private String castName = "";
    private String castMac = "";
    /** 投屏源电脑的完整快照：电视直接镜像这块画面，而不是只显示一行文字。 */
    private String castSource = "";

    public TvBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public TvKind kind() {
        return this.kind;
    }

    public void setKind(TvKind kind) {
        this.kind = kind == null ? TvKind.MEDIUM : kind;
        this.setChanged();
    }

    public String mac() {
        return DeviceIdentity.mac(this.ensureIdentity());
    }

    public String deviceId() {
        return this.ensureIdentity().toString();
    }

    /** 只读的设备 UUID；不触发身份生成，供目录校验使用。 */
    public String deviceIdOrNull() {
        return this.deviceId == null ? null : this.deviceId.toString();
    }

    public String deviceName() {
        return this.name;
    }

    public boolean powered() {
        return this.powered;
    }

    public String castName() {
        return this.castName;
    }

    public String castMac() {
        return this.castMac;
    }

    public UUID ensureIdentity() {
        if (this.deviceId == null) {
            this.deviceId = UUID.randomUUID();
            this.setChanged();
        }
        return this.deviceId;
    }

    /** 把自己写进存档设备目录，蓝牙扫描和投屏才能按 MAC 找到这台电视。 */
    public void register() {
        if (this.level instanceof ServerLevel server) {
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

    /** 主控把电源/投屏状态写回整块屏幕。 */
    public void updateCells() {
        if (!(this.level instanceof ServerLevel)) {
            return;
        }
        BlockState state = this.getBlockState();
        if (!state.is(ModBlocks.TV) || !state.getValue(TvBlock.MASTER)) {
            return;
        }
        Direction facing = state.getValue(TvBlock.FACING);
        Direction right = facing.getClockWise();
        boolean casting = !this.castMac.isBlank();
        for (int i = 0; i < this.kind.width(); i++) {
            for (int j = 0; j < this.kind.height(); j++) {
                BlockPos cell = this.worldPosition.relative(right, i).below(j);
                BlockState current = this.level.getBlockState(cell);
                if (current.is(ModBlocks.TV)) {
                    this.level.setBlock(cell, current
                            .setValue(TvBlock.POWERED, this.powered)
                            .setValue(TvBlock.CASTING, casting), 3);
                }
            }
        }
    }

    public void startCast(String sourceName, String sourceMac, String sourceSnapshot) {
        this.castName = sourceName == null ? "" : sourceName;
        this.castMac = sourceMac == null ? "" : sourceMac;
        this.castSource = sourceSnapshot == null ? "" : sourceSnapshot;
        this.powered = true;
        this.updateCells();
        this.setChanged();
        this.register();
        this.pushToNearby();
    }

    public void stopCast() {
        this.castName = "";
        this.castMac = "";
        this.castSource = "";
        this.updateCells();
        this.setChanged();
        this.pushToNearby();
    }

    public String castSourceJson() {
        return this.castSource;
    }

    /**
     * 投屏源电脑发生活动（打开窗口、敲命令、改文件）后重新取一次快照。
     *
     * <p>源电脑不在加载范围时保留上一帧画面，避免电视突然变黑。</p>
     */
    public void refreshCastSource() {
        if (!(this.level instanceof ServerLevel server) || this.castMac.isBlank()) {
            return;
        }
        DeviceRegistry.Entry source = DeviceRegistry.get(server).byMac(this.castMac);
        if (source != null && server.isLoaded(source.pos())
                && server.getBlockEntity(source.pos()) instanceof ComputerBlockEntity computer) {
            String json = computer.snapshot("").toString();
            if (!json.equals(this.castSource)) {
                this.castSource = json;
                this.setChanged();
            }
        }
        this.pushToNearby();
    }

    /** 把当前电视状态推给附近玩家：投屏画面是实时镜像，而不是打开时才拍一张。 */
    private void pushToNearby() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        String json = this.snapshot("").toString();
        for (ServerPlayer player : server.players()) {
            if (player.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D,
                    this.worldPosition.getZ() + 0.5D) <= 64.0D * 64.0D) {
                ModNetworking.sendState(player, this.worldPosition, json, false);
            }
        }
    }

    public String act(String action) {
        switch (action) {
            case "tv_toggle" -> {
                this.powered = !this.powered;
                this.updateCells();
                this.setChanged();
                this.register();
                return this.powered ? "电视已开机。" : "电视已待机。";
            }
            case "tv_on" -> {
                this.powered = true;
                this.updateCells();
                this.setChanged();
                return "电视已开机。";
            }
            case "tv_off" -> {
                this.powered = false;
                this.updateCells();
                this.setChanged();
                this.register();
                return "电视已待机。";
            }
            case "tv_disconnect" -> {
                this.stopCast();
                return "已断开投屏。";
            }
            case "tv_refresh" -> {
                return "";
            }
            default -> {
                return "未知操作：" + action;
            }
        }
    }

    public JsonObject snapshot(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("tv", true);
        root.addProperty("size", this.kind.label());
        root.addProperty("name", this.name);
        root.addProperty("mac", this.mac());
        root.addProperty("powered", this.powered);
        root.addProperty("castName", this.castName);
        root.addProperty("castMac", this.castMac);
        root.addProperty("message", message == null ? "" : message);
        if (!this.castSource.isBlank()) {
            try {
                root.add("source", JsonParser.parseString(this.castSource));
            } catch (RuntimeException ignored) {
                // 快照损坏时只显示电视自己的界面，不影响开关机。
            }
        }
        if (!this.castMac.isBlank() && this.level instanceof ServerLevel server) {
            DeviceRegistry.Entry source = DeviceRegistry.get(server).byMac(this.castMac);
            if (source != null) {
                JsonObject castPos = new JsonObject();
                castPos.addProperty("x", source.x());
                castPos.addProperty("y", source.y());
                castPos.addProperty("z", source.z());
                root.add("castPos", castPos);
            }
        }
        return root;
    }

    public void sendState(ServerPlayer player, boolean open, String message) {
        this.ensureIdentity();
        ModNetworking.sendState(player, this.worldPosition, this.snapshot(message).toString(), open);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel) {
            this.register();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // 不要用 "id"：方块实体框架已经用它保存类型 ID。
        output.putString("deviceId", this.deviceId == null ? "" : this.deviceId.toString());
        output.putString("kind", this.kind.id());
        output.putString("name", this.name);
        output.putBoolean("powered", this.powered);
        output.putString("castName", this.castName);
        output.putString("castMac", this.castMac);
        output.putString("castSource", this.castSource);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String id = input.getStringOr("deviceId", "");
        this.deviceId = parseUuid(id);
        this.kind = TvKind.byId(input.getStringOr("kind", "tv_5x3"));
        this.name = input.getStringOr("name", "电视");
        this.powered = input.getBooleanOr("powered", false);
        this.castName = input.getStringOr("castName", "");
        this.castMac = input.getStringOr("castMac", "");
        this.castSource = input.getStringOr("castSource", "");
        if (this.level instanceof ServerLevel) {
            this.register();
        }
    }

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
}
