package com.akun.hajikun.device;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.tv.TvBlockEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 每个存档一份的设备目录：记录所有电脑与电视的 UUID、MAC、名字与坐标。
 *
 * <p>蓝牙扫描、按 MAC 配对、电脑之间传文件、电脑投屏到电视都依赖这份目录，
 * 因此即使设备所在的区块暂时没加载，也能凭 MAC 找到它的位置。</p>
 */
public class DeviceRegistry extends SavedData {
    public record Entry(String id, String name, String mac, String kind, String dimension,
                        int x, int y, int z, boolean powered) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Entry::id),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Entry::name),
                Codec.STRING.fieldOf("mac").forGetter(Entry::mac),
                Codec.STRING.optionalFieldOf("kind", "desktop").forGetter(Entry::kind),
                Codec.STRING.optionalFieldOf("dim", "minecraft:overworld").forGetter(Entry::dimension),
                Codec.INT.fieldOf("x").forGetter(Entry::x),
                Codec.INT.fieldOf("y").forGetter(Entry::y),
                Codec.INT.fieldOf("z").forGetter(Entry::z),
                Codec.BOOL.optionalFieldOf("powered", false).forGetter(Entry::powered)
        ).apply(instance, Entry::new));

        public BlockPos pos() {
            return new BlockPos(this.x, this.y, this.z);
        }

        public String label() {
            return this.name == null || this.name.isBlank() ? this.kind : this.name;
        }
    }

    public static final Codec<DeviceRegistry> CODEC = Entry.CODEC.listOf()
            .xmap(DeviceRegistry::new, DeviceRegistry::toList);
    public static final SavedDataType<DeviceRegistry> TYPE = new SavedDataType<>(
            HajikunSmartMod.id("devices"), DeviceRegistry::new, CODEC, DataFixTypes.LEVEL);

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public DeviceRegistry() {
    }

    private DeviceRegistry(List<Entry> list) {
        for (Entry entry : list) {
            this.entries.put(entry.id(), entry);
        }
    }

    public static DeviceRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private List<Entry> toList() {
        return new ArrayList<>(this.entries.values());
    }

    public void register(Entry entry) {
        // 一个坐标只能对应一台设备：放置新设备时顺手清掉同位置留下的旧条目，
        // 避免旧 QA/旧存档的目录项和新机器同时出现在蓝牙列表里。
        this.entries.values().removeIf(old -> !old.id().equals(entry.id())
                && old.dimension().equals(entry.dimension())
                && old.x() == entry.x() && old.y() == entry.y() && old.z() == entry.z());
        this.entries.put(entry.id(), entry);
        this.setDirty();
    }

    public void remove(String id) {
        if (id != null && this.entries.remove(id) != null) {
            this.setDirty();
        }
    }

    /** 删除某个坐标上的所有条目，返回被删掉的条目；用于爆破、活塞等非玩家破坏。 */
    public List<Entry> removeAt(String dimension, BlockPos pos) {
        List<Entry> removed = new ArrayList<>();
        this.entries.values().removeIf(entry -> {
            if (entry.dimension().equals(dimension)
                    && entry.x() == pos.getX() && entry.y() == pos.getY() && entry.z() == pos.getZ()) {
                removed.add(entry);
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            this.setDirty();
        }
        return removed;
    }

    public Entry at(String dimension, BlockPos pos) {
        for (Entry entry : this.entries.values()) {
            if (entry.dimension().equals(dimension)
                    && entry.x() == pos.getX() && entry.y() == pos.getY() && entry.z() == pos.getZ()) {
                return entry;
            }
        }
        return null;
    }

    public Entry byMac(String mac) {
        if (mac == null) {
            return null;
        }
        for (Entry entry : this.entries.values()) {
            if (entry.mac().equalsIgnoreCase(mac.trim())) {
                return entry;
            }
        }
        return null;
    }

    public Entry byId(String id) {
        return id == null ? null : this.entries.get(id);
    }

    public List<Entry> all() {
        return new ArrayList<>(this.entries.values());
    }

    /**
     * 校验目录里已加载的条目：方块实体必须仍然存在，且 UUID / MAC 与条目一致。
     * 不匹配的条目会被删除，并清掉其它电脑对它的配对与电视对它的投屏引用。
     *
     * <p>未加载的区块无法校验，先保留；等区块加载时方块实体会重新登记。</p>
     */
    public static void reconcile(ServerLevel level) {
        DeviceRegistry registry = get(level);
        String dimension = level.dimension().identifier().toString();
        List<Entry> stale = new ArrayList<>();
        for (Entry entry : registry.all()) {
            if (!dimension.equals(entry.dimension()) || !level.isLoaded(entry.pos())) {
                continue;
            }
            if (!matches(level.getBlockEntity(entry.pos()), entry)) {
                stale.add(entry);
            }
        }
        for (Entry entry : stale) {
            registry.remove(entry.id());
            purgePeer(level, entry.mac());
        }
        // 已注销的设备不应继续留在配对表里；仍有目录条目的离线设备保留。
        for (Entry entry : registry.all()) {
            if (!level.isLoaded(entry.pos())) {
                continue;
            }
            if (level.getBlockEntity(entry.pos()) instanceof ComputerBlockEntity computer) {
                boolean changed = false;
                for (String mac : computer.machine().pairedMacs()) {
                    if (registry.byMac(mac) == null) {
                        computer.machine().removePaired(mac);
                        changed = true;
                    }
                }
                if (changed) {
                    computer.setChanged();
                }
            }
        }
    }

    /** 某台设备注销后，清掉其它电脑的配对记录，并让正在投屏它的电视断开。 */
    public static void purgePeer(ServerLevel level, String mac) {
        if (mac == null || mac.isBlank()) {
            return;
        }
        String target = mac.trim().toUpperCase(Locale.ROOT);
        DeviceRegistry registry = get(level);
        for (Entry entry : registry.all()) {
            if (!level.isLoaded(entry.pos())) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(entry.pos());
            if (blockEntity instanceof ComputerBlockEntity computer) {
                if (computer.machine().isPaired(target)) {
                    computer.machine().removePaired(target);
                    computer.setChanged();
                }
            } else if (blockEntity instanceof TvBlockEntity tv && tv.castMac().equalsIgnoreCase(target)) {
                tv.stopCast();
            }
        }
    }

    private static boolean matches(BlockEntity blockEntity, Entry entry) {
        if (blockEntity instanceof ComputerBlockEntity computer) {
            String id = computer.deviceIdOrNull();
            return id != null && id.equals(entry.id()) && computer.mac().equalsIgnoreCase(entry.mac());
        }
        if (blockEntity instanceof TvBlockEntity tv) {
            String id = tv.deviceIdOrNull();
            return id != null && id.equals(entry.id()) && tv.mac().equalsIgnoreCase(entry.mac());
        }
        return false;
    }
}
