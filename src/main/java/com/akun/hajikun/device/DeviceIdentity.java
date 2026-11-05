package com.akun.hajikun.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** 设备身份工具：由 UUID 派生本地管理地址段的虚拟 MAC。 */
public final class DeviceIdentity {
    private DeviceIdentity() {
    }

    /**
     * 用 SHA-256 把设备 UUID 映射成 48 位 MAC，并把第一个字节设为本地管理、单播。
     * UUID 在存档里唯一，因此 MAC 在世界存档内唯一且不可修改。
     */
    public static String mac(UUID id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(id.toString().getBytes(StandardCharsets.UTF_8));
            hash[0] = (byte) ((hash[0] & 0xFC) | 0x02);
            return String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                    hash[0], hash[1], hash[2], hash[3], hash[4], hash[5]);
        } catch (NoSuchAlgorithmException exception) {
            return "02:00:00:00:00:00";
        }
    }
}
