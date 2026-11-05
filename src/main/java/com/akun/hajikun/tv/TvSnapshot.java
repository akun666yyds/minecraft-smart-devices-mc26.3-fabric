package com.akun.hajikun.tv;

import com.akun.hajikun.computer.DeviceSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

/** 电视界面需要的只读快照；投屏时还会带上源电脑的完整画面与坐标。 */
public record TvSnapshot(String size, String name, String mac, boolean powered,
                         String castName, String castMac, String message, DeviceSnapshot source,
                         BlockPos castPos) {
    public static TvSnapshot parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            DeviceSnapshot source = null;
            if (root.has("source") && root.get("source").isJsonObject()) {
                source = DeviceSnapshot.parse(root.get("source").getAsJsonObject().toString());
            }
            BlockPos castPos = null;
            if (root.has("castPos") && root.get("castPos").isJsonObject()) {
                JsonObject pos = root.getAsJsonObject("castPos");
                castPos = new BlockPos(
                        pos.has("x") ? pos.get("x").getAsInt() : 0,
                        pos.has("y") ? pos.get("y").getAsInt() : 0,
                        pos.has("z") ? pos.get("z").getAsInt() : 0);
            }
            return new TvSnapshot(
                    root.has("size") ? root.get("size").getAsString() : "5×3",
                    root.has("name") ? root.get("name").getAsString() : "电视",
                    root.has("mac") ? root.get("mac").getAsString() : "",
                    root.has("powered") && root.get("powered").getAsBoolean(),
                    root.has("castName") ? root.get("castName").getAsString() : "",
                    root.has("castMac") ? root.get("castMac").getAsString() : "",
                    root.has("message") ? root.get("message").getAsString() : "",
                    source,
                    castPos);
        } catch (RuntimeException exception) {
            return new TvSnapshot("5×3", "电视", "", false, "", "", "读取设备状态失败。", null, null);
        }
    }

    public boolean casting() {
        return !this.castMac.isBlank();
    }

    /** 从快照里的尺寸文字还原电视规格，供客户端取宽高比。 */
    public TvKind kind() {
        for (TvKind kind : TvKind.values()) {
            if (kind.label().equals(this.size)) {
                return kind;
            }
        }
        return TvKind.MEDIUM;
    }
}
