package com.akun.hajikun.client.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 照相机录像的现场声轨：记录录制期间身边实际播放过的游戏音效事件。
 *
 * <p>OpenAL 的混音结果无法直接抓取，所以这里在 {@code SoundEngine.play} 处
 * 记下音效 ID、来源、音量、音高和相对取景器的位置。回放时用
 * {@link net.minecraft.client.multiplayer.ClientLevel#playLocalSound} 重新触发这些音效，
 * 因此听起来就是当时玩家身边真实发生的脚步声、方块声、怪物声与角色语音。</p>
 *
 * <p>这个字节块塞在 .hkv 的 audio 字段里，用独立魔数和 P2V 的 WAV 区分；
 * 老录像仍是 WAV，继续走物理机声卡播放。</p>
 */
public final class CameraSoundTrack {
    private static final int MAGIC = 0x484B5331;
    private static final int MAX_EVENTS = 4096;

    private CameraSoundTrack() {
    }

    /**
     * 一条被记录的音效。位置是相对当时取景器的偏移，回放时叠加到观看者当前位置，
     * 这样即使录制时玩家一路走动，声音也仍然围绕画面里的方向。
     */
    public record Event(int timeMs, String id, String source, float volume, float pitch,
                        float dx, float dy, float dz) {
    }

    public static boolean isTrack(byte[] data) {
        return data != null && data.length >= 8
                && ((data[0] & 0xFF) << 24 | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8
                | (data[3] & 0xFF)) == MAGIC;
    }

    public static byte[] encode(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            int count = Math.min(MAX_EVENTS, events.size());
            out.writeInt(count);
            for (int i = 0; i < count; i++) {
                Event event = events.get(i);
                out.writeInt(Math.max(0, event.timeMs()));
                out.writeUTF(event.id() == null ? "" : event.id());
                out.writeUTF(event.source() == null ? "" : event.source());
                out.writeFloat(event.volume());
                out.writeFloat(event.pitch());
                out.writeFloat(event.dx());
                out.writeFloat(event.dy());
                out.writeFloat(event.dz());
            }
            out.flush();
            return buffer.toByteArray();
        } catch (IOException exception) {
            return null;
        }
    }

    public static List<Event> decode(byte[] data) {
        List<Event> events = new ArrayList<>();
        if (!isTrack(data)) {
            return events;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            in.readInt();
            int count = in.readInt();
            if (count <= 0 || count > MAX_EVENTS) {
                return events;
            }
            for (int i = 0; i < count; i++) {
                int time = Math.max(0, in.readInt());
                String id = in.readUTF();
                String source = in.readUTF();
                events.add(new Event(time, id, source, in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(), in.readFloat()));
            }
            return events;
        } catch (IOException | RuntimeException exception) {
            return new ArrayList<>();
        }
    }

    /** 在观看者周围重放一条现场声；volumeScale 来自播放器音量滑杆。 */
    public static void play(Event event, double x, double y, double z, float volumeScale) {
        if (event == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        float volume = event.volume() * Math.max(0.0F, volumeScale);
        if (volume <= 0.001F) {
            return;
        }
        Identifier id = Identifier.tryParse(event.id());
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (sound == null) {
            return;
        }
        client.level.playLocalSound(x + event.dx(), y + event.dy(), z + event.dz(),
                sound, source(event.source()), volume, Math.max(0.05F, event.pitch()), false);
    }

    /** 只接受已知来源名；旧录像/未来新增来源回退到 PLAYERS，避免整条音轨失效。 */
    private static SoundSource source(String name) {
        if (name != null) {
            for (SoundSource value : SoundSource.values()) {
                if (value.getName().equalsIgnoreCase(name)) {
                    return value;
                }
            }
        }
        return SoundSource.PLAYERS;
    }
}
