package com.akun.hajikun.client.ide;

import com.akun.hajikun.client.render.Canvas;
import net.minecraft.client.gui.Font;

/**
 * 原来 IDE 直接调用游戏 GUI 的绘制接口；嵌进电脑屏幕后改走二维画布。
 *
 * <p>这个薄适配层保留了 {@code fill / outline / text / enableScissor} 的调用形状，
 * 让 IDE 的绘制代码不用关心自己画在全屏 GUI 还是显示器表面。</p>
 */
final class Gfx {
    private final Canvas canvas;

    Gfx(Canvas canvas) {
        this.canvas = canvas;
    }

    void fill(int x0, int y0, int x1, int y1, int argb) {
        this.canvas.fill(x0, y0, x1, y1, argb);
    }

    void outline(int x0, int y0, int x1, int y1, int argb) {
        this.canvas.outline(x0, y0, x1, y1, argb);
    }

    /** 屏幕上的阴影字会变成两倍提交量，物理屏幕上也不明显，这里统一不画阴影。 */
    void text(Font font, String text, int x, int y, int argb, boolean shadow) {
        if (text == null || text.isEmpty() || font == null) {
            return;
        }
        this.canvas.text(x, y, text, argb);
    }

    void enableScissor(int x0, int y0, int x1, int y1) {
        this.canvas.pushClip(x0, y0, x1, y1);
    }

    void disableScissor() {
        this.canvas.popClip();
    }
}
