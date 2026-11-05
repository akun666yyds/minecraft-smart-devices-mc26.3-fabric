package com.akun.hajikun.client.media;

import com.akun.hajikun.client.HajikunSmartClient;
import com.akun.hajikun.network.SmartPayloads.MediaChunkS2C;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * 客户端媒体缓存：虚拟机里的图片、音频、视频只在查看时才通过 P2V 通道拉取，
 * 拉到的原始字节按「电脑坐标 + 文件路径」缓存，快照本身不携带这些大块数据。
 */
public final class MediaCache {
    /** 一次拉取的完整文件。 */
    public record Blob(byte[] data, String mime) {
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private static final Map<String, HkvVideo> VIDEOS = new HashMap<>();
    private static final Map<String, int[]> SIZES = new HashMap<>();

    private MediaCache() {
    }

    public static final class Entry {
        private byte[][] chunks;
        private int total;
        private int received;
        private byte[] data;
        private String mime = "";
        private String hash = "";
        private long requestedAt;
    }

    private static String key(BlockPos pos, String path) {
        return pos.asLong() + "|" + path;
    }

    /** 保证这份文件已经在拉取；hash 变化（重新导入同名文件）会自动作废旧内容。 */
    public static void ensure(BlockPos pos, String path, String hash) {
        if (path == null || path.isBlank()) {
            return;
        }
        String key = key(pos, path);
        Entry entry = ENTRIES.get(key);
        if (entry != null && !hash.equals(entry.hash)) {
            release(key);
            entry = null;
        }
        if (entry == null) {
            entry = new Entry();
            entry.hash = hash == null ? "" : hash;
            ENTRIES.put(key, entry);
        }
        if (entry.data != null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 分片正在到达：给它多一点时间，不要中途清空重来。
        long wait = entry.chunks == null ? 1500L : 8000L;
        if (now - entry.requestedAt < wait) {
            return;
        }
        entry.requestedAt = now;
        entry.chunks = null;
        entry.received = 0;
        entry.total = 0;
        HajikunSmartClient.request(pos, "media_get", path);
    }

    /** 网络分块到达；全部到齐后拼成完整文件。 */
    public static void accept(MediaChunkS2C payload) {
        if (payload.total() <= 0 || payload.total() > 8192 || payload.index() < 0
                || payload.index() >= payload.total()) {
            return;
        }
        String key = key(payload.pos(), payload.path());
        Entry entry = ENTRIES.computeIfAbsent(key, ignored -> new Entry());
        if (entry.total != payload.total() || entry.chunks == null) {
            entry.total = payload.total();
            entry.chunks = new byte[payload.total()][];
            entry.received = 0;
            entry.data = null;
        }
        if (entry.chunks[payload.index()] == null) {
            entry.chunks[payload.index()] = payload.data();
            entry.received++;
        }
        entry.mime = payload.mime();
        entry.requestedAt = System.currentTimeMillis();
        if (entry.received < entry.total) {
            return;
        }
        int size = 0;
        for (byte[] chunk : entry.chunks) {
            if (chunk == null) {
                return;
            }
            size += chunk.length;
        }
        byte[] data = new byte[size];
        int offset = 0;
        for (byte[] chunk : entry.chunks) {
            System.arraycopy(chunk, 0, data, offset, chunk.length);
            offset += chunk.length;
        }
        entry.data = data;
        entry.chunks = null;
    }

    public static Blob blob(BlockPos pos, String path) {
        Entry entry = ENTRIES.get(key(pos, path));
        return entry == null || entry.data == null ? null : new Blob(entry.data, entry.mime);
    }

    /** 图片纹理；解码失败返回 null（查看器会显示错误文字）。 */
    public static Identifier image(BlockPos pos, String path, String hash) {
        ensure(pos, path, hash);
        String key = key(pos, path);
        Identifier cached = TEXTURES.get(key);
        if (cached != null) {
            return cached;
        }
        Blob blob = blob(pos, path);
        if (blob == null) {
            return null;
        }
        try {
            NativeImage image = ImageDecode.toNative(blob.data());
            DynamicTexture texture = new DynamicTexture(() -> "P2V image", image);
            texture.upload();
            String id = "media/" + Integer.toHexString(key.hashCode()) + "_"
                    + Integer.toHexString(image.getWidth() * 31 + image.getHeight());
            Identifier identifier = Identifier.fromNamespaceAndPath("hajikun", id);
            Minecraft.getInstance().getTextureManager().register(identifier, texture);
            TEXTURES.put(key, identifier);
            SIZES.put(key, new int[]{image.getWidth(), image.getHeight()});
            return identifier;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** 视频容器；解析失败返回 null。 */
    public static HkvVideo video(BlockPos pos, String path) {
        String key = key(pos, path);
        HkvVideo cached = VIDEOS.get(key);
        if (cached != null) {
            return cached;
        }
        Blob blob = blob(pos, path);
        if (blob == null) {
            return null;
        }
        HkvVideo video = HkvVideo.parse(blob.data());
        if (video != null) {
            VIDEOS.put(key, video);
        }
        return video;
    }

    /** 图片像素尺寸；尚未解码时返回 {1,1}。 */
    public static int[] imageSize(BlockPos pos, String path) {
        int[] size = SIZES.get(key(pos, path));
        return size == null ? new int[]{1, 1} : size;
    }

    /** 音频/视频里的音轨直接走物理机声卡播放。 */
    public static void playAudio(byte[] wav) {
        MediaAudio.play(wav);
    }

    public static void stopAudio() {
        MediaAudio.stop();
    }

    private static void release(String key) {
        Identifier texture = TEXTURES.remove(key);
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(texture);
        }
        VIDEOS.remove(key);
        SIZES.remove(key);
        MediaAudio.stop();
    }

    /** 文件被删除或改名时清掉对应缓存。 */
    public static void forget(BlockPos pos, String path) {
        if (path != null) {
            release(key(pos, path));
            ENTRIES.remove(key(pos, path));
        }
    }

    public static void clearAll() {
        for (String key : new java.util.ArrayList<>(TEXTURES.keySet())) {
            release(key);
        }
        ENTRIES.clear();
        VIDEOS.clear();
        MediaAudio.stop();
    }
}
