package com.akun.hajikun.client.camera;

import com.akun.hajikun.client.HajikunSmartClient;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * 把游戏截图转成照片/录像帧，并分块上传到服务端胶卷。
 *
 * <p>照片直接使用整屏原始像素编码为无损 PNG，不再降采样、不再做有损压缩；
 * 录像帧按区域平均缩放到 640 像素宽，再用高质量 JPEG 编码。
 * 所有耗时工作都放在后台线程，只有真正发包时回到客户端线程。</p>
 */
public final class CameraFrames {
    private static final int CHUNK_BYTES = 24 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private CameraFrames() {
    }

    /**
     * 从整屏 ABGR 像素里按区域平均缩放出目标图；{@code boost} 是闪光灯补光量。
     */
    public static BufferedImage sample(int[] abgr, int sourceWidth, int sourceHeight,
                                       int targetWidth, int boost) {
        int width = Math.max(1, Math.min(targetWidth, sourceWidth));
        int height = Math.max(1, (int) Math.round(sourceHeight * (width / (double) sourceWidth)));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int y0 = y * sourceHeight / height;
            int y1 = Math.max(y0 + 1, (y + 1) * sourceHeight / height);
            for (int x = 0; x < width; x++) {
                int x0 = x * sourceWidth / width;
                int x1 = Math.max(x0 + 1, (x + 1) * sourceWidth / width);
                long red = 0L;
                long green = 0L;
                long blue = 0L;
                int count = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int offset = sy * sourceWidth;
                    for (int sx = x0; sx < x1; sx++) {
                        int value = abgr[offset + sx];
                        red += value & 0xFF;
                        green += (value >> 8) & 0xFF;
                        blue += (value >> 16) & 0xFF;
                        count++;
                    }
                }
                int r = clamp((int) (red / Math.max(1, count)) + boost);
                int g = clamp((int) (green / Math.max(1, count)) + boost);
                int b = clamp((int) (blue / Math.max(1, count)) + boost);
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /**
     * 原尺寸 ABGR 像素直接转成图片，不缩放：照片要保留屏幕的每一个像素。
     */
    public static BufferedImage fromAbgr(int[] abgr, int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_RGB);
        int[] row = new int[Math.max(1, width)];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int value = abgr[offset + x];
                // 截图是 ABGR：低 8 位红、8–15 绿、16–23 蓝。
                row[x] = ((value & 0xFF) << 16) | (value & 0xFF00) | ((value >> 16) & 0xFF);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /** 无损 PNG；照片画质优先，不引入任何有损压缩。 */
    public static byte[] png(BufferedImage image) {
        if (image == null) {
            return null;
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            return ImageIO.write(image, "png", buffer) ? buffer.toByteArray() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /** 高质量 JPEG；失败时退回 ImageIO 默认编码，返回 null 表示彻底失败。 */
    public static byte[] jpeg(BufferedImage image, float quality) {
        if (image == null) {
            return null;
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").hasNext()
                    ? ImageIO.getImageWritersByFormatName("jpeg").next() : null;
            if (writer == null) {
                return ImageIO.write(image, "jpg", buffer) ? buffer.toByteArray() : null;
            }
            try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.1F, Math.min(1.0F, quality)));
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return buffer.toByteArray();
        } catch (Exception exception) {
            return null;
        }
    }

    public static BufferedImage scale(BufferedImage source, int width) {
        if (source == null || source.getWidth() <= width) {
            return source;
        }
        int height = Math.max(1, (int) Math.round(source.getHeight() * (width / (double) source.getWidth())));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    public static String name(String prefix, String extension) {
        return prefix + "_" + LocalDateTime.now().format(STAMP) + extension;
    }

    /**
     * 把一段完整文件切成 24KB 小包发给服务端。
     *
     * <p>无损照片和 15 fps 录像动辄几 MB，如果一次任务里把几百个包全塞给
     * 渲染线程，就会卡一下。这里由后台线程分批投递：每批 12 个包，批与批之间
     * 留 4 ms 空隙，渲染线程每次只处理很小的一段序列化工作。</p>
     */
    public static void upload(boolean tripod, BlockPos pos, String name, String mime, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        String transfer = UUID.randomUUID().toString();
        Minecraft client = Minecraft.getInstance();
        int total = Math.max(1, (data.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        Thread worker = new Thread(() -> {
            for (int first = 0; first < total; first += 12) {
                if (client.getConnection() == null) {
                    return;
                }
                int start = first;
                int end = Math.min(total, first + 12);
                byte[][] slices = new byte[end - start][];
                for (int index = start; index < end; index++) {
                    int from = index * CHUNK_BYTES;
                    int to = Math.min(data.length, from + CHUNK_BYTES);
                    slices[index - start] = Arrays.copyOfRange(data, from, to);
                }
                client.execute(() -> {
                    try {
                        for (int index = start; index < end; index++) {
                            HajikunSmartClient.capture(tripod, pos, transfer, name, mime, index, total,
                                    slices[index - start]);
                        }
                    } catch (RuntimeException disconnected) {
                        // 上传途中退出世界时连接已释放，剩下的包直接作废。
                    }
                });
                try {
                    Thread.sleep(4L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "hajikun-camera-upload");
        worker.setDaemon(true);
        worker.start();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
