package com.akun.hajikun.client.render;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * 一块“屏幕”的绘制接口：坐标以屏幕像素为单位，原点在左上角，y 轴向下。
 *
 * <p>同一个界面既能画到方块表面的屏幕（{@link WorldCanvas}），也能在需要时
 * 画到别的地方；界面代码只依赖这套二维坐标，不关心 3D 朝向。</p>
 */
public interface Canvas {
    int width();

    int height();

    int lineHeight();

    int textWidth(String text);

    int textWidth(FormattedCharSequence text);

    /** 放大后的文字宽度，用于居中大字（电视标题、时钟）。 */
    int textWidth(String text, float scale);

    void fill(int x0, int y0, int x1, int y1, int argb);

    void text(int x, int y, String text, int argb);

    /** 按倍数放大的文字；原点仍在左上角，不改变画布坐标。 */
    void text(int x, int y, String text, int argb, float scale);

    void text(int x, int y, FormattedCharSequence text, int argb);

    /** 已经按比例折好行的文字；{@code scale} 与 {@link #split} 使用的倍数保持一致。 */
    void text(int x, int y, FormattedCharSequence text, int argb, float scale);

    /**
     * 把一张已注册的纹理铺到指定矩形里，用于图片查看器和录像帧。
     * 默认实现什么都不画，方便不支持的画布保持兼容。
     */
    default void image(int x0, int y0, int x1, int y1, Identifier texture) {
    }

    List<FormattedCharSequence> split(String text, int maxWidth);

    void pushClip(int x0, int y0, int x1, int y1);

    void popClip();

    default void outline(int x0, int y0, int x1, int y1, int argb) {
        this.fill(x0, y0, x1, y0 + 1, argb);
        this.fill(x0, y1 - 1, x1, y1, argb);
        this.fill(x0, y0, x0 + 1, y1, argb);
        this.fill(x1 - 1, y0, x1, y1, argb);
    }

    default void centered(String text, int centerX, int y, int argb) {
        this.text(centerX - this.textWidth(text) / 2, y, text, argb);
    }

    default void centered(String text, int centerX, int y, int argb, float scale) {
        this.text(centerX - this.textWidth(text, scale) / 2, y, text, argb, scale);
    }

    /** 从上到下的双色渐变，用若干横条近似，避免额外的着色器。 */
    default void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        int steps = Math.max(1, Math.min(24, (y1 - y0) / 4));
        int height = Math.max(1, (y1 - y0) / steps);
        for (int i = 0; i < steps; i++) {
            int y = y0 + i * height;
            int end = i == steps - 1 ? y1 : y + height;
            this.fill(x0, y, x1, end, lerpArgb(top, bottom, i / (float) (steps - 1)));
        }
    }

    private static int lerpArgb(int from, int to, float t) {
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t);
        int g = (int) (((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
