package com.akun.hajikun.client.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * 用物理机声卡播放虚拟机里的音频（P2V 导入时已统一转成 22.05kHz 单声道 WAV）。
 * 不注册游戏音效，避免服务器要给每台电脑下发声音事件。
 *
 * <p>支持音量、进度与 0.5–4 倍速：倍速通过线性重采样生成一份缩短/拉长的 PCM，
 * 因此进度对外始终按原始时间轴显示。</p>
 */
public final class MediaAudio {
    private static Clip clip;
    private static Thread watcher;
    private static float speed = 1.0F;
    private static float volume = 0.8F;

    private MediaAudio() {
    }

    public static synchronized void play(byte[] wav) {
        playAt(wav, 1.0F, 0L);
    }

    /** 从 startMillis（原始时间轴）开始播放；rate 为 0.5–4.0 倍速。 */
    public static synchronized void playAt(byte[] wav, float rate, long startMillis) {
        stop();
        if (wav == null || wav.length < 8) {
            return;
        }
        float nextSpeed = clamp(rate, 0.5F, 4.0F);
        byte[] data = nextSpeed == 1.0F ? wav : resample(wav, nextSpeed);
        if (data == wav) {
            // 不支持的采样格式无法重采样，退回原速，避免进度与声音脱节。
            nextSpeed = 1.0F;
        }
        try {
            AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
            Clip next = AudioSystem.getClip();
            next.open(stream);
            applyGain(next);
            long position = Math.max(0L,
                    Math.round(startMillis * 1000.0D / Math.max(1.0F, nextSpeed)));
            if (position > 0L) {
                next.setMicrosecondPosition(Math.min(position, next.getMicrosecondLength()));
            }
            next.start();
            clip = next;
            speed = nextSpeed;
            long remain = Math.max(50L,
                    (next.getMicrosecondLength() - position) / 1000L + 200L);
            Clip target = next;
            watcher = new Thread(() -> {
                try {
                    Thread.sleep(remain);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                synchronized (MediaAudio.class) {
                    if (clip == target) {
                        target.close();
                        clip = null;
                    }
                }
            }, "Hajikun media audio");
            watcher.setDaemon(true);
            watcher.start();
        } catch (RuntimeException | javax.sound.sampled.LineUnavailableException
                 | javax.sound.sampled.UnsupportedAudioFileException
                 | java.io.IOException exception) {
            clip = null;
        }
    }

    public static synchronized void stop() {
        if (watcher != null) {
            watcher.interrupt();
            watcher = null;
        }
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (RuntimeException ignored) {
            }
            clip = null;
        }
    }

    public static synchronized boolean playing() {
        return clip != null && clip.isRunning();
    }

    public static synchronized float speed() {
        return speed;
    }

    public static synchronized float volume() {
        return volume;
    }

    /** 0.0–1.0；对当前与之后的播放都生效。 */
    public static synchronized void setVolume(float value) {
        volume = clamp(value, 0.0F, 1.0F);
        if (clip != null) {
            applyGain(clip);
        }
    }

    /** 原始时间轴上的当前进度；没有播放时返回 -1。 */
    public static synchronized long positionMillis() {
        if (clip == null) {
            return -1L;
        }
        return Math.round(clip.getMicrosecondPosition() / 1000.0D * speed);
    }

    /** 原始时间轴上的总时长；没有播放时返回 -1。 */
    public static synchronized long durationMillis() {
        if (clip == null) {
            return -1L;
        }
        return Math.round(clip.getMicrosecondLength() / 1000.0D * speed);
    }

    /** 跳到原始时间轴的指定位置；播放中有效。 */
    public static synchronized void seekMillis(long millis) {
        if (clip == null) {
            return;
        }
        long micros = Math.round(Math.max(0L, millis) * 1000.0D / Math.max(0.5F, speed));
        try {
            clip.setMicrosecondPosition(Math.min(micros, clip.getMicrosecondLength()));
        } catch (RuntimeException ignored) {
        }
    }

    /** 读取 WAV 的原始时长（毫秒），用于未播放时画进度。 */
    public static long probeMillis(byte[] wav) {
        if (wav == null || wav.length < 8) {
            return 0L;
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            long frames = stream.getFrameLength();
            float rate = stream.getFormat().getFrameRate();
            if (frames <= 0L || rate <= 0.0F) {
                return 0L;
            }
            return Math.round(frames / (double) rate * 1000.0D);
        } catch (RuntimeException | java.io.IOException
                 | javax.sound.sampled.UnsupportedAudioFileException exception) {
            return 0L;
        }
    }

    private static void applyGain(Clip target) {
        try {
            FloatControl gain = (FloatControl) target.getControl(FloatControl.Type.MASTER_GAIN);
            float min = gain.getMinimum();
            float max = gain.getMaximum();
            gain.setValue(Math.max(min, Math.min(max, min + (max - min) * volume)));
        } catch (RuntimeException ignored) {
        }
    }

    /** 16 位小端 PCM 的线性重采样；其它格式原样返回。 */
    private static byte[] resample(byte[] wav, float rate) {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat format = input.getFormat();
            if (format.getSampleSizeInBits() != 16 || format.isBigEndian()) {
                return wav;
            }
            byte[] pcm = input.readAllBytes();
            int channels = Math.max(1, format.getChannels());
            int frameBytes = 2 * channels;
            int frames = pcm.length / frameBytes;
            if (frames <= 0) {
                return wav;
            }
            int outFrames = Math.max(1, (int) (frames / rate));
            byte[] out = new byte[outFrames * frameBytes];
            for (int i = 0; i < outFrames; i++) {
                double source = Math.min(frames - 1.0D, i * (double) rate);
                int first = (int) source;
                int second = Math.min(frames - 1, first + 1);
                double blend = source - first;
                for (int ch = 0; ch < channels; ch++) {
                    int a = sample(pcm, first * frameBytes + ch * 2);
                    int b = sample(pcm, second * frameBytes + ch * 2);
                    int value = (int) Math.round(a + (b - a) * blend);
                    out[i * frameBytes + ch * 2] = (byte) (value & 0xFF);
                    out[i * frameBytes + ch * 2 + 1] = (byte) ((value >> 8) & 0xFF);
                }
            }
            AudioFormat faster = new AudioFormat(format.getSampleRate() * rate, 16, channels, true, false);
            AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(out), faster, outFrames);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, bytes);
            return bytes.toByteArray();
        } catch (RuntimeException | java.io.IOException
                 | javax.sound.sampled.UnsupportedAudioFileException exception) {
            return wav;
        }
    }

    private static int sample(byte[] pcm, int offset) {
        return (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
