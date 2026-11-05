package com.akun.hajikun.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * 一个正在查看的录像：按 fps 逐帧解码到一张动态纹理上。
 *
 * <p>支持正放/倒放、0.5–4 倍速、音量、任意进度跳转与上一条/下一条。
 * 音轨有两种：P2V 导入的 WAV 走物理机声卡；照相机录像的现场声事件轨
 * 按时间轴重新触发游戏音效，倒放时静音（倒放人声没有可听性）。</p>
 */
public final class VideoPlayer implements AutoCloseable {
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final HkvVideo video;
    private final DynamicTexture texture;
    private final Identifier id;
    private final byte[] audio;
    private final boolean wavAudio;
    private final boolean eventAudio;
    private final List<CameraSoundTrack.Event> events;
    private int nextEvent;
    private int frame = -1;
    private boolean playing;
    private long frameStart;
    private boolean failed;
    private float speed = 1.0F;
    private float volume = 1.0F;
    private int direction = 1;

    public VideoPlayer(HkvVideo video) {
        this.video = video;
        this.audio = video.audio();
        this.eventAudio = CameraSoundTrack.isTrack(this.audio);
        this.wavAudio = !this.eventAudio && this.audio.length > 0;
        this.events = this.eventAudio ? CameraSoundTrack.decode(this.audio)
                : java.util.List.of();
        this.texture = new DynamicTexture("P2V video", Math.max(1, video.width), Math.max(1, video.height), true);
        this.id = Identifier.fromNamespaceAndPath("hajikun",
                "video/" + Integer.toHexString(SEQ.incrementAndGet()) + "_" + System.identityHashCode(this));
        Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
        this.show(0);
        this.resetEventCursor();
    }

    public Identifier texture() {
        return this.id;
    }

    public HkvVideo video() {
        return this.video;
    }

    public int frame() {
        return Math.max(0, this.frame);
    }

    public boolean playing() {
        return this.playing;
    }

    public boolean failed() {
        return this.failed;
    }

    public float speed() {
        return this.speed;
    }

    public float volume() {
        return this.volume;
    }

    public boolean reversed() {
        return this.direction < 0;
    }

    public boolean hasSound() {
        return this.wavAudio || this.eventAudio;
    }

    public long positionMillis() {
        return Math.round(this.frame() * 1000.0D / Math.max(1, this.video.fps));
    }

    public long durationMillis() {
        return this.video.durationMs();
    }

    public void toggle() {
        if (this.playing) {
            this.pause();
            return;
        }
        if (this.direction > 0 && this.frame >= this.video.frameCount - 1) {
            this.show(0);
        } else if (this.direction < 0 && this.frame <= 0) {
            this.show(this.video.frameCount - 1);
        }
        this.play();
    }

    public void play() {
        if (this.direction > 0 && (this.frame < 0 || this.frame >= this.video.frameCount - 1)) {
            this.show(0);
        } else if (this.direction < 0 && (this.frame < 0 || this.frame <= 0)) {
            this.show(this.video.frameCount - 1);
        }
        this.playing = true;
        this.frameStart = System.currentTimeMillis();
        this.resetEventCursor();
        this.startAudio();
    }

    public void pause() {
        this.playing = false;
        MediaAudio.stop();
    }

    public void rewind() {
        this.pause();
        this.show(this.direction > 0 ? 0 : this.video.frameCount - 1);
        this.resetEventCursor();
    }

    /** 倍速只在录像帧上生效；WAV 音轨按同一倍速重采样，事件轨按时间轴自然压缩。 */
    public void setSpeed(float value) {
        float next = Math.max(0.5F, Math.min(4.0F, value));
        if (Math.abs(next - this.speed) < 0.001F) {
            return;
        }
        boolean wasPlaying = this.playing;
        this.speed = next;
        if (wasPlaying) {
            this.playing = false;
            MediaAudio.stop();
            this.playing = true;
            this.frameStart = System.currentTimeMillis();
            this.resetEventCursor();
            this.startAudio();
        }
    }

    /** 0.0–1.0；只影响本播放器，事件轨按比例缩放音量。 */
    public void setVolume(float value) {
        this.volume = Math.max(0.0F, Math.min(1.0F, value));
        if (this.wavAudio && MediaAudio.playing()) {
            MediaAudio.setVolume(this.volume);
        }
    }

    /** 切换正放/倒放；倒放时停止声音，避免出现无法理解的倒放人声。 */
    public void setReversed(boolean reversed) {
        int next = reversed ? -1 : 1;
        if (next == this.direction) {
            return;
        }
        boolean wasPlaying = this.playing;
        MediaAudio.stop();
        this.direction = next;
        if (this.direction < 0 && this.frame <= 0) {
            this.show(this.video.frameCount - 1);
        } else if (this.direction > 0 && this.frame >= this.video.frameCount - 1) {
            this.show(0);
        }
        this.resetEventCursor();
        if (wasPlaying) {
            this.playing = true;
            this.frameStart = System.currentTimeMillis();
            this.startAudio();
        }
    }

    /** 跳到指定毫秒（原始时间轴）。 */
    public void seek(long millis) {
        long clamped = Math.max(0L, Math.min(this.durationMillis(), millis));
        int index = (int) Math.round(clamped / 1000.0D * this.video.fps);
        this.show(index);
        this.resetEventCursor();
        if (this.playing) {
            this.frameStart = System.currentTimeMillis();
            this.startAudio();
        }
    }

    /** 每帧调用：到时间就推进/后退一帧，播到边界停住。 */
    public void tick() {
        if (!this.playing) {
            return;
        }
        long step = this.stepMillis();
        long elapsed = System.currentTimeMillis() - this.frameStart;
        int steps = (int) (elapsed / step);
        if (steps > 0) {
            int target = this.frame + this.direction * steps;
            if (target < 0 || target >= this.video.frameCount) {
                this.show(this.direction > 0 ? this.video.frameCount - 1 : 0);
                this.pause();
                return;
            }
            this.show(target);
            this.frameStart += steps * step;
        }
        this.playDueEvents();
    }

    private void startAudio() {
        if (this.direction < 0) {
            MediaAudio.stop();
            return;
        }
        if (this.wavAudio) {
            MediaAudio.setVolume(this.volume);
            MediaAudio.playAt(this.audio, this.speed, this.positionMillis());
        }
    }

    private void resetEventCursor() {
        this.nextEvent = 0;
        if (this.events.isEmpty()) {
            return;
        }
        long pos = this.positionMillis();
        while (this.nextEvent < this.events.size()
                && this.events.get(this.nextEvent).timeMs() < pos - 30L) {
            this.nextEvent++;
        }
    }

    /** 事件轨按当前进度触发；倒放时不播放。 */
    private void playDueEvents() {
        if (!this.eventAudio || this.direction < 0 || this.events.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Vec3 at = client.player != null
                ? client.player.getEyePosition()
                : client.gameRenderer.mainCamera().position();
        long pos = this.positionMillis() + 40L;
        while (this.nextEvent < this.events.size()
                && this.events.get(this.nextEvent).timeMs() <= pos) {
            CameraSoundTrack.play(this.events.get(this.nextEvent), at.x, at.y, at.z, this.volume);
            this.nextEvent++;
        }
    }

    private long stepMillis() {
        return Math.max(1L, Math.round(1000.0D / Math.max(0.5F, this.video.fps * this.speed)));
    }

    private void show(int index) {
        this.frame = Math.max(0, Math.min(this.video.frameCount - 1, index));
        byte[] jpeg = this.video.frame(this.frame);
        try {
            // 26.x 的 NativeImage.read 只认 PNG，录像帧是 JPEG，改用 ImageIO 解码后写进同一张纹理。
            BufferedImage image = ImageDecode.read(jpeg);
            NativeImage pixels = this.texture.getPixels();
            ImageDecode.fill(pixels, image, this.video.width, this.video.height);
            this.texture.upload();
        } catch (IOException | RuntimeException exception) {
            // 单帧解码失败不打断整段录像：保留上一张纹理继续往下播。
            this.failed = true;
        }
    }

    @Override
    public void close() {
        this.pause();
        Minecraft.getInstance().getTextureManager().release(this.id);
    }
}
