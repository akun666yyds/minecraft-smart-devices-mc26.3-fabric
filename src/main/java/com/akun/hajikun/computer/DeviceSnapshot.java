package com.akun.hajikun.computer;

import com.akun.hajikun.vm.VirtualFileSystem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备状态快照：服务端把整台虚拟机打包成 JSON 发给客户端，界面只读这份快照。
 *
 * <p>快照不包含任何客户端专属数据，换客户端、多人联机时同一台设备看到的
 * 内容始终一致；所有写操作都要回到服务端执行后再发一份新快照。</p>
 */
public final class DeviceSnapshot {
    /** 蓝牙列表里的一台设备：MAC 是唯一标识，名字与类型只用于显示。 */
    public record Peer(String mac, String name, String kind, boolean online, boolean paired) {
        /** 电脑显示「台式机 / 笔记本」，电视显示「电视 5×3」等。 */
        public String kindLabel() {
            if (this.kind == null) {
                return "设备";
            }
            return switch (this.kind) {
                case "desktop" -> "台式机";
                case "laptop" -> "笔记本";
                case "tv_2x1" -> "电视 2×1";
                case "tv_5x3" -> "电视 5×3";
                case "tv_16x9" -> "电视 16×9";
                default -> "设备";
            };
        }

        public boolean tv() {
            return this.kind != null && this.kind.startsWith("tv_");
        }

        /** 列表里显示的名字：没命名时退回类型标签。 */
        public String label() {
            return this.name == null || this.name.isBlank() ? this.kindLabel() : this.name;
        }
    }

    public final String kind;
    public final String name;
    public final String mac;
    public final boolean powered;
    public final boolean suspended;
    public final int boots;
    public final long uptime;
    public final String cwd;
    public final VirtualFileSystem fs;
    public final String message;
    public final boolean bluetooth;
    public final List<Peer> paired;
    public final List<Peer> nearby;

    private DeviceSnapshot(String kind, String name, String mac, boolean powered, boolean suspended,
                           int boots, long uptime, String cwd, VirtualFileSystem fs, String message,
                           boolean bluetooth, List<Peer> paired, List<Peer> nearby) {
        this.kind = kind;
        this.name = name;
        this.mac = mac;
        this.powered = powered;
        this.suspended = suspended;
        this.boots = boots;
        this.uptime = uptime;
        this.cwd = cwd;
        this.fs = fs;
        this.message = message;
        this.bluetooth = bluetooth;
        this.paired = paired;
        this.nearby = nearby;
    }

    /** 设备是否已经完成首次命名。 */
    public boolean named() {
        return this.name != null && !this.name.isBlank();
    }

    public static DeviceSnapshot parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        VirtualFileSystem fs = root.has("fs")
                ? VirtualFileSystem.fromJson(root.getAsJsonObject("fs"))
                : new VirtualFileSystem();
        return new DeviceSnapshot(
                string(root, "kind", "desktop"),
                string(root, "name", ""),
                string(root, "mac", ""),
                root.has("powered") && root.get("powered").getAsBoolean(),
                root.has("suspended") && root.get("suspended").getAsBoolean(),
                root.has("boots") ? root.get("boots").getAsInt() : 0,
                root.has("uptime") ? root.get("uptime").getAsLong() : 0L,
                string(root, "cwd", "/home/user"),
                fs,
                string(root, "message", ""),
                !root.has("bt") || root.get("bt").getAsBoolean(),
                peers(root, "paired"),
                peers(root, "nearby"));
    }

    private static List<Peer> peers(JsonObject root, String key) {
        List<Peer> list = new ArrayList<>();
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return list;
        }
        JsonArray array = root.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject peer = element.getAsJsonObject();
            list.add(new Peer(
                    string(peer, "mac", ""),
                    string(peer, "name", ""),
                    string(peer, "kind", ""),
                    peer.has("online") && peer.get("online").getAsBoolean(),
                    peer.has("paired") && peer.get("paired").getAsBoolean()));
        }
        return list;
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }
}
