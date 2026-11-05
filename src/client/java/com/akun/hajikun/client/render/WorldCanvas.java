package com.akun.hajikun.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * 把二维界面画到方块屏幕上的画布。
 *
 * <p>方块和文字都提交到 26.3 的文本渲染阶段，提交顺序就是绘制顺序，
 * 因此窗口、按钮和文字之间的遮挡关系与普通 GUI 完全一致，
 * 也不需要在世界坐标里手动叠 z 层。</p>
 */
public final class WorldCanvas implements Canvas {
    private static final int FULL_BRIGHT = 0xF000F0;
    /**
     * 每次提交向观察者方向推一点，深度测试就会按提交顺序叠放，不受批次重排影响。
     *
     * <p>步长必须远小于相机的观察距离（聚焦时约 0.5 格），否则同一层的相邻小方块
     * 会因为离相机远近不同而被透视放大成竖直缝隙。26.3 的深度缓冲精度足够分辨
     * 微米级的差值，所以这里取 0.001 像素级。</p>
     */
    private static final float DEPTH_STEP = 0.0012F;

    private final Font font;
    private final PoseStack pose;
    private final OrderedSubmitNodeCollector collector;
    private final int width;
    private final int height;
    private final List<int[]> clips = new ArrayList<>();
    private float depth;

    public WorldCanvas(Font font, PoseStack pose, OrderedSubmitNodeCollector collector, int width, int height) {
        this.font = font;
        this.pose = pose;
        this.collector = collector;
        this.width = width;
        this.height = height;
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
        return this.font.lineHeight;
    }

    @Override
    public int textWidth(String text) {
        return this.font.width(text);
    }

    @Override
    public int textWidth(FormattedCharSequence text) {
        return this.font.width(text);
    }

    @Override
    public int textWidth(String text, float scale) {
        return Math.round(this.font.width(text) * scale);
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        int left = Math.min(x0, x1);
        int right = Math.max(x0, x1);
        int top = Math.min(y0, y1);
        int bottom = Math.max(y0, y1);
        int[] clip = this.currentClip();
        if (clip != null) {
            left = Math.max(left, clip[0]);
            top = Math.max(top, clip[1]);
            right = Math.min(right, clip[2]);
            bottom = Math.min(bottom, clip[3]);
        }
        if (right <= left || bottom <= top) {
            return;
        }
        this.pose.pushPose();
        this.pose.translate(0.0F, 0.0F, this.depth += DEPTH_STEP);
        this.collector.submitTextBackground(this.pose, left, top, right, bottom, argb,
                Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT);
        this.pose.popPose();
    }

    @Override
    public void text(int x, int y, String text, int argb) {
        if (text == null || text.isEmpty()) {
            return;
        }
        this.text(x, y, Component.literal(text).getVisualOrderText(), argb);
    }

    @Override
    public void text(int x, int y, String text, int argb, float scale) {
        if (text == null || text.isEmpty() || scale <= 0.0F) {
            return;
        }
        int[] clip = this.currentClip();
        if (clip != null) {
            int height = Math.round(this.font.lineHeight * scale);
            if (y + height <= clip[1] || y >= clip[3]) {
                return;
            }
            int right = x + Math.round(this.font.width(text) * scale);
            if (x >= clip[2] || right <= clip[0]) {
                return;
            }
            if (x < clip[0] || right > clip[2]) {
                int[] window = this.textWindow(x, clip, scale);
                int lead = this.clipLead(text, window[0]);
                text = this.clipText(text, window[0], window[1]);
                if (text.isEmpty()) {
                    return;
                }
                x += Math.round(lead * scale);
            }
        }
        FormattedCharSequence sequence = Component.literal(text).getVisualOrderText();
        this.pose.pushPose();
        this.pose.translate(x, y, this.depth += DEPTH_STEP);
        this.pose.scale(scale, scale, 1.0F);
        this.collector.submitText(this.pose, 0, 0, sequence, false, Font.DisplayMode.POLYGON_OFFSET,
                FULL_BRIGHT, argb, 0, 0);
        this.pose.popPose();
    }

    @Override
    public void text(int x, int y, FormattedCharSequence text, int argb) {
        this.text(x, y, text, argb, 1.0F);
    }

    @Override
    public void text(int x, int y, FormattedCharSequence text, int argb, float scale) {
        if (text == null || scale <= 0.0F) {
            return;
        }
        int[] clip = this.currentClip();
        if (clip != null) {
            if (y + this.font.lineHeight * scale <= clip[1] || y >= clip[3]) {
                return;
            }
            int right = x + Math.round(this.font.width(text) * scale);
            if (x >= clip[2] || right <= clip[0]) {
                return;
            }
            if (x < clip[0] || right > clip[2]) {
                int[] window = this.textWindow(x, clip, scale);
                int lead = this.clipLead(text, window[0]);
                text = this.clipText(text, window[0], window[1]);
                if (text == FormattedCharSequence.EMPTY) {
                    return;
                }
                x += Math.round(lead * scale);
            }
        }
        this.pose.pushPose();
        this.pose.translate(x, y, this.depth += DEPTH_STEP);
        if (scale != 1.0F) {
            this.pose.scale(scale, scale, 1.0F);
        }
        this.collector.submitText(this.pose, 0, 0, text, false, Font.DisplayMode.POLYGON_OFFSET,
                FULL_BRIGHT, argb, 0, 0);
        this.pose.popPose();
    }

    @Override
    public void image(int x0, int y0, int x1, int y1, Identifier texture) {
        if (texture == null) {
            return;
        }
        int left = Math.min(x0, x1);
        int right = Math.max(x0, x1);
        int top = Math.min(y0, y1);
        int bottom = Math.max(y0, y1);
        int[] clip = this.currentClip();
        if (clip != null) {
            left = Math.max(left, clip[0]);
            top = Math.max(top, clip[1]);
            right = Math.min(right, clip[2]);
            bottom = Math.min(bottom, clip[3]);
        }
        if (right <= left || bottom <= top) {
            return;
        }
        final int l = left;
        final int t = top;
        final int r = right;
        final int b = bottom;
        this.pose.pushPose();
        this.pose.translate(0.0F, 0.0F, this.depth += DEPTH_STEP);
        this.collector.submitCustomGeometry(this.pose, RenderTypes.textPolygonOffset(texture),
                (pose, vertices) -> {
                    vertices.addVertex(pose, l, t, 0.0F).setColor(255, 255, 255, 255)
                            .setUv(0.0F, 0.0F).setLight(FULL_BRIGHT);
                    vertices.addVertex(pose, l, b, 0.0F).setColor(255, 255, 255, 255)
                            .setUv(0.0F, 1.0F).setLight(FULL_BRIGHT);
                    vertices.addVertex(pose, r, b, 0.0F).setColor(255, 255, 255, 255)
                            .setUv(1.0F, 1.0F).setLight(FULL_BRIGHT);
                    vertices.addVertex(pose, r, t, 0.0F).setColor(255, 255, 255, 255)
                            .setUv(1.0F, 0.0F).setLight(FULL_BRIGHT);
                });
        this.pose.popPose();
    }

    @Override
    public List<FormattedCharSequence> split(String text, int maxWidth) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), maxWidth);
        if (lines.isEmpty()) {
            lines = new ArrayList<>();
            lines.add(FormattedCharSequence.EMPTY);
        }
        return lines;
    }

    @Override
    public void pushClip(int x0, int y0, int x1, int y1) {
        this.clips.add(new int[]{Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)});
    }

    @Override
    public void popClip() {
        if (!this.clips.isEmpty()) {
            this.clips.remove(this.clips.size() - 1);
        }
    }

    private int[] currentClip() {
        if (this.clips.isEmpty()) {
            return null;
        }
        int[] clip = this.clips.get(this.clips.size() - 1);
        if (this.clips.size() == 1) {
            return clip;
        }
        int[] parent = this.clips.get(this.clips.size() - 2);
        return new int[]{Math.max(clip[0], parent[0]), Math.max(clip[1], parent[1]),
                Math.min(clip[2], parent[2]), Math.min(clip[3], parent[3])};
    }

    /**
     * 裁剪框换算成以文字原点为基准的像素区间。
     *
     * <p>文字提交接口没有裁剪栈，只能在提交前把越界的字形剔掉；
     * 这里给出窗口的左右边界，边界上的半个字形直接不画，避免压出容器。</p>
     */
    private int[] textWindow(int x, int[] clip, float scale) {
        return new int[]{(int) Math.ceil((clip[0] - x) / (double) scale),
                (int) Math.floor((clip[2] - x) / (double) scale)};
    }

    /** 左侧被裁掉的字形总宽度：剩下的字要整体右移这么多，间距才不会塌。 */
    private int clipLead(String text, int left) {
        int cursor = 0;
        for (int i = 0; i < text.length(); ) {
            if (cursor >= left) {
                return cursor;
            }
            int codePoint = text.codePointAt(i);
            int step = Character.charCount(codePoint);
            cursor += this.font.width(text.substring(i, i + step));
            i += step;
        }
        return cursor;
    }

    private int clipLead(FormattedCharSequence text, int left) {
        int[] cursor = {0};
        text.accept((index, style, codePoint) -> {
            if (cursor[0] >= left) {
                return false;
            }
            cursor[0] += this.font.width(new String(Character.toChars(codePoint)));
            return true;
        });
        return cursor[0];
    }

    private String clipText(String text, int left, int right) {        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int step = Character.charCount(codePoint);
            String glyph = text.substring(i, i + step);
            int start = cursor;
            cursor += this.font.width(glyph);
            i += step;
            if (cursor <= left) {
                continue;
            }
            if (start >= right) {
                break;
            }
            if (start >= left && cursor <= right) {
                out.append(glyph);
            }
        }
        return out.toString();
    }

    private FormattedCharSequence clipText(FormattedCharSequence text, int left, int right) {
        List<FormattedCharSequence> parts = new ArrayList<>();
        int[] cursor = {0};
        text.accept((index, style, codePoint) -> {
            String glyph = new String(Character.toChars(codePoint));
            int start = cursor[0];
            cursor[0] = start + this.font.width(glyph);
            if (cursor[0] <= left) {
                return true;
            }
            if (start >= right) {
                return false;
            }
            if (start >= left && cursor[0] <= right) {
                parts.add(FormattedCharSequence.codepoint(codePoint, style));
            }
            return true;
        });
        if (parts.isEmpty()) {
            return FormattedCharSequence.EMPTY;
        }
        return parts.size() == 1 ? parts.get(0) : FormattedCharSequence.composite(parts);
    }
}
