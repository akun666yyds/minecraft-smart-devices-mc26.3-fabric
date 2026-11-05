package com.akun.hajikun.client.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hajikun 视频容器（.hkv）：一段由 JPEG 帧序列和一条 WAV 音轨组成的录像。
 *
 * <p>P2V 导入的视频先用 ffmpeg 转成 8 fps、最长边 320 像素的帧，再打包成这个
 * 容器。它不依赖任何平台编解码器，虚拟机里能存、能传，客户端查看器逐帧解码。</p>
 */
public final class HkvVideo {
    private static final int MAGIC = 0x484B5631;

    public final int width;
    public final int height;
    public final int fps;
    public final int frameCount;
    private final byte[][] frames;
    private final byte[] audio;

    private HkvVideo(int width, int height, int fps, byte[][] frames, byte[] audio) {
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.frames = frames;
        this.frameCount = frames.length;
        this.audio = audio == null ? new byte[0] : audio;
    }

    public byte[] frame(int index) {
        return this.frames[Math.max(0, Math.min(this.frameCount - 1, index))];
    }

    public byte[] audio() {
        return this.audio;
    }

    public long durationMs() {
        return this.frameCount * 1000L / this.fps;
    }

    /** 把已经编号的 JPEG 帧和可选 WAV 音轨打包成 .hkv 字节。 */
    public static byte[] write(int width, int height, int fps, List<byte[]> frames, byte[] wav) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(fps);
            out.writeInt(frames.size());
            out.writeInt(wav == null ? 0 : wav.length);
            for (byte[] frame : frames) {
                out.writeInt(frame.length);
                out.write(frame);
            }
            if (wav != null && wav.length > 0) {
                out.write(wav);
            }
            out.flush();
            return buffer.toByteArray();
        } catch (IOException exception) {
            return null;
        }
    }

    public static HkvVideo parse(byte[] data) {
        if (data == null || data.length < 24) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            int width = in.readInt();
            int height = in.readInt();
            int fps = in.readInt();
            int count = in.readInt();
            int audioLength = in.readInt();
            if (width <= 0 || height <= 0 || count <= 0 || count > 20000
                    || fps <= 0 || fps > 120 || audioLength < 0) {
                return null;
            }
            List<byte[]> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length <= 0 || length > 4 * 1024 * 1024) {
                    return null;
                }
                byte[] frame = new byte[length];
                in.readFully(frame);
                frames.add(frame);
            }
            byte[] audio = new byte[0];
            if (audioLength > 0) {
                if (audioLength > 64 * 1024 * 1024) {
                    return null;
                }
                audio = new byte[audioLength];
                in.readFully(audio);
            }
            return new HkvVideo(width, height, fps, frames.toArray(new byte[0][]), audio);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }
}
