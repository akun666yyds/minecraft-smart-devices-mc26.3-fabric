package com.akun.hajikun.camera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 照相机胶卷：一串照片或录像文件，整体压缩成一个字节数组存进物品组件。
 *
 * <p>照相机没有 MAC，也不属于虚拟机设备，只有这一个容器。照片是 JPEG，
 * 录像是模组自定义的 {@code .hkv} 容器，两者都能直接放进电脑的媒体查看器。
 * 容器自带魔数与条目长度，读到损坏数据时返回空列表而不是抛异常。</p>
 */
public final class CameraRoll {
    /** 单台照相机最多保存 96 MiB，和 P2V 单文件上限保持一致。 */
    public static final int MAX_BYTES = 96 * 1024 * 1024;
    private static final int MAGIC = 0x484B4341;
    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    private CameraRoll() {
    }

    /** 一条胶卷记录：文件名、MIME 与完整字节。 */
    public record Entry(String name, String mime, byte[] data) {
    }

    public static List<Entry> decode(byte[] bytes) {
        List<Entry> entries = new ArrayList<>();
        if (bytes == null || bytes.length < 4) {
            return entries;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                return entries;
            }
            int count = input.readUnsignedShort();
            for (int i = 0; i < count && i < MAX_ENTRIES; i++) {
                String name = input.readUTF();
                String mime = input.readUTF();
                int length = input.readInt();
                if (length < 0 || length > MAX_ENTRY_BYTES || length > input.available()) {
                    break;
                }
                byte[] data = new byte[length];
                input.readFully(data);
                entries.add(new Entry(name, mime, data));
            }
        } catch (IOException | RuntimeException ignored) {
            return new ArrayList<>();
        }
        return entries;
    }

    public static byte[] encode(List<Entry> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC);
            output.writeShort(Math.min(MAX_ENTRIES, entries.size()));
            int written = 0;
            for (Entry entry : entries) {
                if (written >= MAX_ENTRIES) {
                    break;
                }
                output.writeUTF(entry.name());
                output.writeUTF(entry.mime());
                output.writeInt(entry.data().length);
                output.write(entry.data());
                written++;
            }
        } catch (IOException exception) {
            return new byte[0];
        }
        return buffer.toByteArray();
    }

    public static long totalBytes(List<Entry> entries) {
        long total = 0L;
        for (Entry entry : entries) {
            total += entry.data().length;
        }
        return total;
    }

    /** 在胶卷里挑一个不重名的名字：IMG_1.jpg → IMG_1_2.jpg。 */
    public static String uniqueName(List<Entry> entries, String wanted) {
        String name = sanitize(wanted);
        if (name.isBlank()) {
            name = "IMG.bin";
        }
        if (find(entries, name) == null) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        while (find(entries, base + "_" + index + extension) != null) {
            index++;
        }
        return base + "_" + index + extension;
    }

    public static Entry find(List<Entry> entries, String name) {
        for (Entry entry : entries) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    public static Entry remove(List<Entry> entries, String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equalsIgnoreCase(name)) {
                return entries.remove(i);
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        String value = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        if (value.length() > 96) {
            String extension = value.contains(".") ? value.substring(value.lastIndexOf('.')) : "";
            value = value.substring(0, 96 - extension.length()) + extension;
        }
        return value;
    }

    /** 目录名按 MIME 分流；照片进「图片」，录像进「视频」。 */
    public static String directoryFor(String mime) {
        return mime != null && mime.startsWith("video/") ? "/home/user/视频" : "/home/user/图片";
    }

    public static String sizeText(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0D);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0D * 1024.0D));
    }

}
