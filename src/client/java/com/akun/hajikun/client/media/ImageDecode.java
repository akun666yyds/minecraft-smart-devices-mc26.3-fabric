package com.akun.hajikun.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 把图片字节转成游戏可上传的 RGBA 像素图。
 *
 * <p>26.x 的 {@link NativeImage#read(byte[])} 只认 PNG，P2V 导入的 JPEG 和录像帧
 * 都会报 “Bad PNG Signature”。这里改用 JDK 自带的 ImageIO 解码（PNG/JPEG/GIF/BMP
 * 都支持），再逐像素写进 NativeImage。</p>
 */
public final class ImageDecode {
    private ImageDecode() {
    }

    /** 解码成 BufferedImage；格式不认识时抛 IOException。 */
    public static BufferedImage read(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("空图片");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("不支持的图片格式");
        }
        return image;
    }

    /** 按图片自身尺寸生成新的 NativeImage。 */
    public static NativeImage toNative(byte[] data) throws IOException {
        BufferedImage image = read(data);
        return toNative(image, image.getWidth(), image.getHeight());
    }

    /** 生成指定尺寸的 NativeImage；尺寸不一致时等比拉伸填满。 */
    public static NativeImage toNative(BufferedImage image, int width, int height) {
        NativeImage nativeImage = new NativeImage(Math.max(1, width), Math.max(1, height), false);
        fill(nativeImage, image, width, height);
        return nativeImage;
    }

    /** 把 BufferedImage 写进已有的 NativeImage（视频逐帧复用它，避免反复申请显存）。 */
    public static void fill(NativeImage target, BufferedImage image, int width, int height) {
        BufferedImage source = image;
        if (source.getWidth() != width || source.getHeight() != height) {
            BufferedImage scaled = new BufferedImage(Math.max(1, width), Math.max(1, height),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            source = scaled;
        }
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                // setPixel 内部会把 ARGB 转成 NativeImage 的 RGBA 存储顺序。
                target.setPixel(x, y, row[x]);
            }
        }
    }
}
