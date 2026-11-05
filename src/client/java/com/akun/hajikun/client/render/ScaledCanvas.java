package com.akun.hajikun.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * 把一份界面按比例缩放到另一块画布上，给电视投屏用。
 *
 * <p>电脑界面按 320 像素宽排布，电视面板的像素尺寸各不相同；这里只在坐标与
 * 字号上乘一个统一比例，界面代码完全不需要知道自己被投到了多大的屏上。
 * 缩小时方块会合并到同一像素，文字仍由游戏字体重新排版，不会糊成马赛克。</p>
 */
public final class ScaledCanvas implements Canvas {
    private final Canvas target;
    private final float scale;
    private final int offsetX;
    private final int offsetY;
    private final float depthOffset;
    private final int width;
    private final int height;
    private final List<int[]> clips = new ArrayList<>();

    /**
     * @param scale     每个逻辑像素占屏幕像素的倍数
     * @param offsetX   左上角在目标画布上的位置
     * @param offsetY   同上
     * @param width     缩放前的逻辑宽度
     * @param height    缩放前的逻辑高度
     */
    public ScaledCanvas(Canvas target, float scale, int offsetX, int offsetY, int width, int height) {
        this.target = target;
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.depthOffset = 0.0F;
        this.width = width;
        this.height = height;
    }

    public int offsetX() {
        return this.offsetX;
    }

    public int offsetY() {
        return this.offsetY;
    }

    public int scaledWidth() {
        return Math.round(this.width * this.scale);
    }

    public int scaledHeight() {
        return Math.round(this.height * this.scale);
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return this.height;
    }

    @Override
    public int lineHeight() {
        return Math.max(1, Math.round(this.target.lineHeight() / this.scale));
    }

    @Override
    public int textWidth(String text) {
        return Math.round(this.target.textWidth(text) / this.scale);
    }

    @Override
    public int textWidth(FormattedCharSequence text) {
        return Math.round(this.target.textWidth(text) / this.scale);
    }

    @Override
    public int textWidth(String text, float extra) {
        return Math.round(this.target.textWidth(text, this.scale * extra) / this.scale);
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        int left = this.offsetX + Math.round(Math.min(x0, x1) * this.scale);
        int right = this.offsetX + Math.round(Math.max(x0, x1) * this.scale);
        int top = this.offsetY + Math.round(Math.min(y0, y1) * this.scale);
        int bottom = this.offsetY + Math.round(Math.max(y0, y1) * this.scale);
        // 缩小渲染时 1 像素的边框会被取整成 0 像素而消失；保证有面积就至少留 1 像素，
        // 否则 IDE 的分隔线、按钮描边会在 0.5 倍分辨率下时隐时现。
        if (right <= left && x0 != x1) {
            right = left + 1;
        }
        if (bottom <= top && y0 != y1) {
            bottom = top + 1;
        }
        if (right <= left || bottom <= top) {
            return;
        }
        this.target.fill(left, top, right, bottom, argb);
    }

    @Override
    public void text(int x, int y, String text, int argb) {
        this.target.text(this.offsetX + Math.round(x * this.scale),
                this.offsetY + Math.round(y * this.scale), text, argb, this.scale);
    }

    @Override
    public void text(int x, int y, String text, int argb, float extra) {
        this.target.text(this.offsetX + Math.round(x * this.scale),
                this.offsetY + Math.round(y * this.scale), text, argb, this.scale * extra);
    }

    @Override
    public void text(int x, int y, FormattedCharSequence text, int argb) {
        this.target.text(this.offsetX + Math.round(x * this.scale),
                this.offsetY + Math.round(y * this.scale), text, argb, this.scale);
    }

    @Override
    public void text(int x, int y, FormattedCharSequence text, int argb, float extra) {
        this.target.text(this.offsetX + Math.round(x * this.scale),
                this.offsetY + Math.round(y * this.scale), text, argb, this.scale * extra);
    }

    @Override
    public void image(int x0, int y0, int x1, int y1, Identifier texture) {
        this.target.image(
                this.offsetX + Math.round(Math.min(x0, x1) * this.scale),
                this.offsetY + Math.round(Math.min(y0, y1) * this.scale),
                this.offsetX + Math.round(Math.max(x0, x1) * this.scale),
                this.offsetY + Math.round(Math.max(y0, y1) * this.scale),
                texture);
    }

    @Override
    public List<FormattedCharSequence> split(String text, int maxWidth) {
        return this.target.split(text, Math.max(4, Math.round(maxWidth * this.scale)));
    }

    @Override
    public void pushClip(int x0, int y0, int x1, int y1) {
        int left = this.offsetX + Math.round(Math.min(x0, x1) * this.scale);
        int right = this.offsetX + Math.round(Math.max(x0, x1) * this.scale);
        int top = this.offsetY + Math.round(Math.min(y0, y1) * this.scale);
        int bottom = this.offsetY + Math.round(Math.max(y0, y1) * this.scale);
        this.clips.add(new int[]{left, top, right, bottom});
        this.target.pushClip(left, top, right, bottom);
    }

    @Override
    public void popClip() {
        if (!this.clips.isEmpty()) {
            this.clips.remove(this.clips.size() - 1);
        }
        this.target.popClip();
    }
}
