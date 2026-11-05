package com.akun.hajikun.client.net;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * 浏览器里的图片与动图缓存。
 *
 * <p>网页图片从真实网络下载后先解码成 BufferedImage，再转成 Minecraft 纹理；
 * GIF 会拆成多帧按时间播放，因此「视频加载」至少覆盖了网页上最常见的动态图片。
 * 所有纹理解码都在客户端线程执行，网络请求留在后台线程。</p>
 */
public final class WebMedia {
    private static final int MAX_WIDTH = 256;
    private static final int MAX_HEIGHT = 168;
    private static final int MAX_FRAMES = 32;
    private static final long RETRY_MS = 5000L;

    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private WebMedia() {
    }

    /** 一张已解码的网页图片：多帧时按时间轮播。 */
    public static final class Image {
        public final Identifier[] frames;
        public final int[] delays;
        public final int width;
        public final int height;

        private Image(Identifier[] frames, int[] delays, int width, int height) {
            this.frames = frames;
            this.delays = delays;
            this.width = width;
            this.height = height;
        }

        public boolean animated() {
            return this.frames.length > 1;
        }

        public Identifier frame(long now) {
            if (this.frames.length == 1) {
                return this.frames[0];
            }
            long total = 0L;
            for (int delay : this.delays) {
                total += Math.max(40, delay);
            }
            if (total <= 0L) {
                return this.frames[0];
            }
            long position = now % total;
            for (int i = 0; i < this.frames.length; i++) {
                position -= Math.max(40, this.delays[i]);
                if (position < 0L) {
                    return this.frames[i];
                }
            }
            return this.frames[this.frames.length - 1];
        }
    }

    private static final class Entry {
        private byte[] data;
        private Image image;
        private boolean loading;
        private long requestedAt;
        private String error = "";
    }

    private static String key(BlockPos pos, String url) {
        return pos.asLong() + "|" + url;
    }

    /** 已经加载好的图片；没加载时自动发起请求并返回 null。 */
    public static Image image(BlockPos pos, String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        String url = absolute(src);
        if (url.isEmpty()) {
            return null;
        }
        String key = key(pos, url);
        Entry entry = ENTRIES.computeIfAbsent(key, ignored -> new Entry());
        if (entry.image != null || entry.loading) {
            return entry.image;
        }
        long now = System.currentTimeMillis();
        if (now - entry.requestedAt < RETRY_MS) {
            return null;
        }
        entry.requestedAt = now;
        entry.loading = true;
        entry.error = "";
        BrowserClient.bytes(pos, url, (data, mime, error) -> Minecraft.getInstance().execute(() -> {
            entry.loading = false;
            if (error != null || data == null || data.length == 0) {
                entry.error = error == null ? "空图片" : error;
                return;
            }
            entry.data = data;
            try {
                entry.image = decode(key, data);
            } catch (RuntimeException exception) {
                entry.error = "图片解码失败";
            }
        }));
        return null;
    }

    /** 供浏览器“保存到电脑”使用：返回已下载的原始字节。 */
    public static byte[] bytes(BlockPos pos, String src) {
        Entry entry = ENTRIES.get(key(pos, absolute(src)));
        return entry == null ? null : entry.data;
    }

    public static String error(BlockPos pos, String src) {
        Entry entry = ENTRIES.get(key(pos, absolute(src)));
        return entry == null ? "" : entry.error;
    }

    private static String absolute(String src) {
        String value = src.trim();
        if (value.startsWith("data:")) {
            return value;
        }
        int colon = value.indexOf(':');
        if (colon > 0) {
            String scheme = value.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "";
            }
            return value;
        }
        return value.startsWith("//") ? "https:" + value : value;
    }

    private static Image decode(String key, byte[] data) {
        if (data.length < 16) {
            return null;
        }
        try {
            if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
                Image gif = decodeGif(key, data);
                if (gif != null) {
                    return gif;
                }
            }
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(data));
            if (source == null) {
                return null;
            }
            BufferedImage scaled = scale(source);
            Identifier texture = register(key + "#0", scaled);
            return new Image(new Identifier[]{texture}, new int[]{100}, scaled.getWidth(), scaled.getHeight());
        } catch (Exception exception) {
            return null;
        }
    }

    /** 拆 GIF：每帧合成到同一张画布上，最多保留 32 帧。 */
    private static Image decodeGif(String key, byte[] data) {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(input, false);
            int count = Math.min(MAX_FRAMES, reader.getNumImages(true));
            BufferedImage canvas = new BufferedImage(reader.getWidth(0), reader.getHeight(0),
                    BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < count; i++) {
                BufferedImage frame = reader.read(i);
                Graphics2D graphics = canvas.createGraphics();
                graphics.drawImage(frame, 0, 0, null);
                graphics.dispose();
                BufferedImage copy = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D copyGraphics = copy.createGraphics();
                copyGraphics.drawImage(canvas, 0, 0, null);
                copyGraphics.dispose();
                frames.add(scale(copy));
                int delay = 100;
                try {
                    var meta = reader.getImageMetadata(i);
                    var tree = (org.w3c.dom.Element) meta.getAsTree("javax_imageio_gif_image_1.0");
                    var children = tree.getElementsByTagName("GraphicControlExtension");
                    if (children.getLength() > 0) {
                        String value = ((org.w3c.dom.Element) children.item(0))
                                .getAttribute("delayTime");
                        delay = Math.max(40, Integer.parseInt(value) * 10);
                    }
                } catch (RuntimeException ignored) {
                }
                delays.add(delay);
            }
            reader.dispose();
        } catch (Exception exception) {
            return null;
        }
        if (frames.isEmpty()) {
            return null;
        }
        Identifier[] textures = new Identifier[frames.size()];
        int[] times = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            textures[i] = register(key + "#" + i, frames.get(i));
            times[i] = delays.get(i);
        }
        return new Image(textures, times, frames.get(0).getWidth(), frames.get(0).getHeight());
    }

    private static BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min(1.0D, Math.min(MAX_WIDTH / (double) width, MAX_HEIGHT / (double) height));
        int targetW = Math.max(1, (int) Math.round(width * ratio));
        int targetH = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetW, targetH, null);
        graphics.dispose();
        return target;
    }

    private static Identifier register(String key, BufferedImage image) {
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), true);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                nativeImage.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        DynamicTexture texture = new DynamicTexture(() -> "hajikun web", nativeImage);
        texture.upload();
        Identifier id = Identifier.fromNamespaceAndPath("hajikun",
                "web/" + Integer.toHexString(key.hashCode()) + "_" + image.getWidth() + "x" + image.getHeight());
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    public static void clearAll() {
        ENTRIES.clear();
    }

    /** 把 data URI 还原成字节，供“保存图片”使用。 */
    public static byte[] dataUri(String src) {
        if (src == null || !src.startsWith("data:")) {
            return null;
        }
        int comma = src.indexOf(',');
        if (comma < 0) {
            return null;
        }
        try {
            String header = src.substring(5, comma);
            if (!header.contains("base64")) {
                return null;
            }
            return Base64.getDecoder().decode(src.substring(comma + 1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
