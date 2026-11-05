package com.akun.hajikun.client.ui;

import com.akun.hajikun.client.HajikunSmartClient;
import com.akun.hajikun.client.render.Canvas;
import com.akun.hajikun.client.render.ScaledCanvas;
import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.computer.DeviceSnapshot;
import com.akun.hajikun.tv.TvKind;
import com.akun.hajikun.tv.TvSnapshot;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.core.BlockPos;

/**
 * 电视屏幕里的界面：待机、主界面与投屏画面都直接画在电视面板上。
 *
 * <p>电视没有鼠标指针以外的输入设备，所以界面只保留电源与断开投屏两个
 * 可点区域；其余内容都是这台电视自己的状态，不占用全屏 GUI。</p>
 */
public final class TvUi {
    private static final int CHIP_H = 15;

    private static final int COLOR_TEXT = 0xFFE8F0F8;
    private static final int COLOR_MUTED = 0xFF9FB0C4;
    private static final int COLOR_DIM = 0xFF6D7C8C;
    private static final int COLOR_ACCENT = 0xFF6FD6F0;
    private static final int COLOR_VIOLET = 0xFF9B7BF0;
    private static final int COLOR_GREEN = 0xFF5CF08A;
    private static final int COLOR_RED = 0xFFE85C5C;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日");

    private final BlockPos pos;
    private final TvKind kind;
    private final int width;
    private final int height;
    private TvSnapshot device;

    private double mouseX = -1.0D;
    private double mouseY = -1.0D;
    private boolean localFocus;
    private String toast = "";
    private long toastUntil;
    private long castSince;
    private boolean wasCasting;
    /** 投屏镜像：优先用源电脑正在使用的界面，否则按快照建一份只读副本。 */
    private ComputerUi mirror;
    private DeviceSnapshot mirrorSource;

    public TvUi(BlockPos pos, TvKind kind, TvSnapshot device) {
        this.pos = pos;
        this.kind = kind == null ? TvKind.MEDIUM : kind;
        // 电视画面固定采用电脑桌面的宽高比（320:247），三种尺寸只改分辨率；
        // 面板比例和这个不一致时由渲染器在两侧补黑边，画面本身不会被拉扁拉宽。
        this.width = switch (this.kind) {
            case SMALL -> 256;
            case MEDIUM -> 384;
            case LARGE -> 512;
        };
        this.height = Math.max(96, Math.round(this.width / ComputerUi.ASPECT));
        this.device = device;
        this.wasCasting = device != null && device.casting();
        if (this.wasCasting) {
            this.castSince = System.currentTimeMillis();
        }
    }

    public BlockPos pos() {
        return this.pos;
    }

    public TvKind kind() {
        return this.kind;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public void setLocalFocus(boolean focused) {
        this.localFocus = focused;
        if (focused) {
            this.toast("光标已释放：单击电视上的按钮，Esc 或 Shift 离开");
        } else {
            this.mouseX = -1.0D;
            this.mouseY = -1.0D;
        }
    }

    public void update(TvSnapshot snapshot) {
        boolean casting = snapshot != null && snapshot.casting();
        if (casting && !this.wasCasting) {
            this.castSince = System.currentTimeMillis();
            this.toast("已连接投屏：" + snapshot.castName());
        }
        if (!casting && this.wasCasting) {
            this.toast("投屏已断开。");
        }
        this.wasCasting = casting;
        this.device = snapshot;
        if (snapshot != null && snapshot.message() != null && !snapshot.message().isBlank()) {
            this.toast(snapshot.message());
        }
    }

    private void toast(String message) {
        this.toast = message;
        this.toastUntil = System.currentTimeMillis() + 4200L;
    }

    // ------------------------------------------------------------------ 绘制

    public void render(Canvas c) {
        if (!this.device.powered()) {
            this.renderStandby(c);
        } else if (this.device.casting()) {
            this.renderCast(c);
        } else {
            this.renderHome(c);
        }
        this.renderPower(c);
        this.renderToast(c);
        this.renderCursor(c);
    }

    private void renderHome(Canvas c) {
        c.gradient(0, 0, c.width(), c.height(), 0xFF07131F, 0xFF151034);
        for (int i = 0; i < 4; i++) {
            int y = c.height() / 5 + i * c.height() / 7;
            c.fill(0, y, c.width(), y + 1, 0x1FFFFFFF);
        }
        int bar = this.barHeight();
        this.renderTopBar(c, "已开机", COLOR_GREEN);
        boolean compact = this.height < 160;
        int x = Math.max(12, c.width() / 16);
        int titleY = bar + (compact ? 10 : 22);
        float scale = compact ? 1.25F : (this.height >= 260 ? 2.2F : 1.8F);
        c.text(x, titleY, "等待投屏", COLOR_ACCENT, scale);
        int infoY = titleY + Math.round(c.lineHeight() * scale) + (compact ? 2 : 8);
        c.text(x, infoY, this.device.name() + "  ·  " + this.device.size(), COLOR_TEXT);
        if (compact) {
            c.text(x, infoY + 12, "MAC " + this.device.mac(), COLOR_DIM);
        } else {
            c.text(x, infoY + 14, "虚拟 MAC  " + this.device.mac(), COLOR_MUTED);
            c.text(x, infoY + 28, "1. 在电脑终端输入  cast " + this.device.mac(), COLOR_DIM);
            c.text(x, infoY + 42, "2. 或在电脑设置里按 MAC 配对后点「投屏到电视」", COLOR_DIM);
        }
        if (!compact) {
            String clock = LocalTime.now().format(CLOCK);
            int clockX = c.width() - 22 - c.textWidth(clock, 2.6F);
            c.text(clockX, bar + 20, clock, 0xFFC9EDF8, 2.6F);
            String date = LocalDate.now().format(DATE);
            c.text(c.width() - 22 - c.textWidth(date), bar + 20 + Math.round(c.lineHeight() * 2.6F) + 2,
                    date, COLOR_MUTED);
        }

        int cardX = x;
        int cardY = c.height() - this.bottomHeight() - (compact ? 24 : 56);
        int cardW = c.width() - x * 2;
        int cardH = compact ? 20 : 40;
        c.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xCC101C2B);
        c.outline(cardX, cardY, cardX + cardW, cardY + cardH, 0xFF2C4057);
        c.fill(cardX, cardY, cardX + 2, cardY + cardH, COLOR_ACCENT);
        int dotAlpha = (System.currentTimeMillis() / 500L) % 2L == 0L ? 0xFF : 0x88;
        c.fill(cardX + 10, cardY + cardH / 2 - 2, cardX + 14, cardY + cardH / 2 + 2,
                (dotAlpha << 24) | (COLOR_ACCENT & 0xFFFFFF));
        c.text(cardX + 20, cardY + (cardH - 8) / 2, compact ? "等待电脑投屏…" : "投屏接收就绪",
                COLOR_TEXT);
        if (!compact) {
            String hint = "把电脑的画面送到这块 " + this.device.size() + " 大屏上";
            c.text(cardX + cardW - 10 - c.textWidth(hint), cardY + (cardH - 8) / 2, hint, COLOR_MUTED);
        }
        this.renderBottomBar(c, "右键屏幕 · 单击右下角电源键");
    }

    /** 投屏就是源电脑桌面的实时镜像：按 320 宽的逻辑画布等比缩放铺进电视面板。 */
    private void renderCast(Canvas c) {
        int top = this.barHeight();
        int bottom = this.bottomHeight();
        c.fill(0, 0, c.width(), c.height(), 0xFF04070C);
        this.renderTopBar(c, "投屏中", COLOR_RED);

        ComputerUi source = this.mirrorUi();
        int areaW = c.width();
        int areaH = Math.max(1, c.height() - top - bottom);
        if (source == null) {
            c.centered("投屏源暂时不可用", c.width() / 2, top + areaH / 2 - 6, COLOR_MUTED);
            c.centered("等源电脑回到加载范围后会自动恢复", c.width() / 2, top + areaH / 2 + 10, COLOR_DIM);
        } else {
            float scale = Math.min(areaW / (float) source.width(), areaH / (float) source.height());
            int drawW = Math.round(source.width() * scale);
            int drawH = Math.round(source.height() * scale);
            int offX = (areaW - drawW) / 2;
            int offY = top + (areaH - drawH) / 2;
            c.fill(offX - 1, offY - 1, offX + drawW + 1, offY + drawH + 1, 0xFF000000);
            source.renderMirror(new ScaledCanvas(c, scale, offX, offY, source.width(), source.height()));
        }

        int by = c.height() - bottom;
        c.fill(0, by, c.width(), c.height(), 0xD90A1018);
        c.fill(0, by, c.width(), by + 1, 0xFF2E3A4A);
        int textW = c.width() - 74;
        String name = this.device.castName().isBlank() ? "电脑" : this.device.castName();
        String label = name + "  ·  " + this.elapsed();
        c.pushClip(10, by, 10 + textW, by + bottom);
        c.text(10, by + (bottom - 8) / 2, label, COLOR_MUTED);
        c.popClip();
        int[] box = this.disconnectBox();
        this.renderDisconnectChip(c, box[0], box[1]);
    }

    /**
     * 取投屏源界面：源电脑正在客户端打开时直接用那一份（窗口、终端输出完全同步）；
     * 否则按电视快照携带的电脑快照建一份镜像副本，快照换了就重建。
     */
    private ComputerUi mirrorUi() {
        BlockPos sourcePos = this.device.castPos();
        if (sourcePos != null) {
            ComputerUi live = DeviceUis.computer(sourcePos);
            if (live != null) {
                return live;
            }
        }
        DeviceSnapshot source = this.device.source();
        if (source == null) {
            return null;
        }
        if (this.mirror == null || this.mirrorSource != source) {
            BlockPos origin = sourcePos == null ? this.pos : sourcePos;
            ComputerKind kind = "laptop".equals(source.kind) ? ComputerKind.LAPTOP : ComputerKind.DESKTOP;
            this.mirror = new ComputerUi(origin, kind, source);
            this.mirrorSource = source;
        }
        return this.mirror;
    }

    private void renderStandby(Canvas c) {
        c.fill(0, 0, c.width(), c.height(), 0xFF020305);
        c.gradient(0, c.height() / 3, c.width(), c.height() * 2 / 3, 0x12112433, 0x00000000);
        boolean compact = this.height < 160;
        float scale = compact ? 1.6F : 2.2F;
        int centerY = c.height() / 2 - Math.round(c.lineHeight() * scale);
        c.centered("待机", c.width() / 2, centerY, 0xFF46566A, scale);
        c.centered("单击右下角电源键开机", c.width() / 2, centerY + Math.round(c.lineHeight() * scale) + 12,
                0xFF33414F);
        c.text(10, c.height() - 14, "HAJIKUN TV  ·  " + this.device.size(), 0xFF2C3947);
        String mac = this.device.mac();
        c.text(c.width() - 10 - c.textWidth(mac), c.height() - 14, mac, 0xFF2C3947);
    }

    private void renderTopBar(Canvas c, String state, int stateColor) {
        int bar = this.barHeight();
        c.fill(0, 0, c.width(), bar, 0xCC080E16);
        c.fill(0, bar - 1, c.width(), bar, 0x66526B86);
        c.text(10, (bar - 8) / 2, "HAJIKUN TV", COLOR_ACCENT);
        String clock = LocalTime.now().format(CLOCK);
        int right = c.width() - 10;
        int clockW = c.textWidth(clock);
        c.text(right - clockW, (bar - 8) / 2, clock, COLOR_TEXT);
        int stateW = c.textWidth(state);
        int stateX = right - clockW - 12 - stateW;
        c.text(stateX, (bar - 8) / 2, state, stateColor);
        if (!"已开机".equals(state)) {
            c.fill(stateX - 8, bar / 2 - 2, stateX - 4, bar / 2 + 2, stateColor);
        }
    }

    private void renderBottomBar(Canvas c, String hint) {
        int bottom = this.bottomHeight();
        int by = c.height() - bottom;
        c.fill(0, by, c.width(), c.height(), 0xD90A1018);
        c.fill(0, by, c.width(), by + 1, 0xFF2E3A4A);
        c.text(10, by + (bottom - 8) / 2, hint, COLOR_DIM);
    }

    private void renderDisconnectChip(Canvas c, int x, int y) {
        String label = "断开投屏";
        int w = c.textWidth(label) + 12;
        boolean hover = this.inside(x, y, w, CHIP_H);
        c.fill(x, y, x + w, y + CHIP_H, hover ? 0xFF7A3A44 : 0xFF3A2630);
        c.outline(x, y, x + w, y + CHIP_H, 0xFF8A4A58);
        c.text(x + 6, y + 4, label, 0xFFF2DCE0);
    }

    private void renderPower(Canvas c) {
        int[] box = this.powerBox();
        int cx = (box[0] + box[2]) / 2;
        int cy = (box[1] + box[3]) / 2;
        boolean hover = this.inside(box[0], box[1], box[2] - box[0], box[3] - box[1]);
        c.fill(box[0], box[1], box[2], box[3], 0xFF10151B);
        c.outline(box[0], box[1], box[2], box[3], hover ? 0xFF4A5C72 : 0xFF2C3742);
        int color = this.device.powered() ? COLOR_GREEN : COLOR_RED;
        c.fill(cx - 4, cy - 4, cx + 4, cy + 4, this.device.powered() ? 0xFF12301E : 0xFF2A1113);
        c.fill(cx, cy - 4, cx + 1, cy, color);
        c.fill(cx - 3, cy - 2, cx - 2, cy + 1, color);
        c.fill(cx + 2, cy - 2, cx + 3, cy + 1, color);
        c.fill(cx - 2, cy + 1, cx - 1, cy + 2, color);
        c.fill(cx + 1, cy + 1, cx + 2, cy + 2, color);
        c.fill(cx - 1, cy + 2, cx + 2, cy + 3, color);
        if (hover) {
            String hint = this.device.powered() ? "单击待机" : "单击开机";
            int w = c.textWidth(hint);
            int hx = box[0] - w - 10;
            int hy = cy - 8;
            c.fill(hx, hy, hx + w + 8, hy + 15, 0xE0141A22);
            c.text(hx + 4, hy + 4, hint, COLOR_TEXT);
        }
    }

    private void renderToast(Canvas c) {
        if (this.toast.isBlank() || System.currentTimeMillis() >= this.toastUntil) {
            return;
        }
        int w = Math.min(c.width() - 20, c.textWidth(this.toast) + 16);
        int x = (c.width() - w) / 2;
        int y = this.barHeight() + 5;
        c.fill(x, y, x + w, y + 17, 0xE0141A22);
        c.outline(x, y, x + w, y + 17, 0xFF3A4859);
        c.pushClip(x + 4, y, x + w - 4, y + 17);
        c.text(x + 8, y + 5, this.toast, COLOR_TEXT);
        c.popClip();
    }

    private void renderCursor(Canvas c) {
        if (!this.localFocus || this.mouseX < 0.0D) {
            return;
        }
        int mx = (int) Math.round(this.mouseX);
        int my = (int) Math.round(this.mouseY);
        for (int i = 0; i < 10; i++) {
            int w = Math.min(i + 1, 7);
            c.fill(mx, my + i, mx + w, my + i + 1, 0xFF0A0D11);
        }
        for (int i = 1; i < 9; i++) {
            int w = Math.min(i + 1, 7);
            if (w > 2) {
                c.fill(mx + 1, my + i, mx + w - 1, my + i + 1, 0xFFFFFFFF);
            }
        }
        c.fill(mx + 2, my + 7, mx + 5, my + 9, 0xFF0A0D11);
        c.fill(mx + 3, my + 7, mx + 4, my + 8, 0xFFFFFFFF);
    }

    private String elapsed() {
        long seconds = Math.max(0L, (System.currentTimeMillis() - this.castSince) / 1000L);
        return String.format("%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private int barHeight() {
        return this.height < 160 ? 20 : 26;
    }

    private int bottomHeight() {
        return this.height < 160 ? 22 : 28;
    }

    private int[] powerBox() {
        int size = this.height < 160 ? 15 : 19;
        int x1 = this.width - 8;
        int y1 = this.height - 5;
        return new int[]{x1 - size, y1 - size, x1, y1};
    }

    private int[] disconnectBox() {
        // 中文字宽固定 9px，「断开投屏」四个字加内边距约 50，贴在底栏右侧。
        int w = 50;
        int bottom = this.bottomHeight();
        int x1 = this.width - 10;
        int y = this.height - bottom + (bottom - CHIP_H) / 2;
        return new int[]{x1 - w, y, x1, y + CHIP_H};
    }

    // ------------------------------------------------------------------ 输入

    private boolean inside(double x, double y, int w, int h) {
        return this.mouseX >= x && this.mouseX < x + w && this.mouseY >= y && this.mouseY < y + h;
    }

    public void mouseMove(double x, double y) {
        this.mouseX = Math.max(0.0D, Math.min(this.width, x));
        this.mouseY = Math.max(0.0D, Math.min(this.height, y));
    }

    public void mouseLeave() {
        this.mouseX = -1.0D;
        this.mouseY = -1.0D;
    }

    public boolean mouseDown(double x, double y, int button, boolean shift) {
        this.mouseMove(x, y);
        if (button != 0) {
            return false;
        }
        int[] power = this.powerBox();
        if (this.mouseX >= power[0] && this.mouseX < power[2]
                && this.mouseY >= power[1] && this.mouseY < power[3]) {
            HajikunSmartClient.request(this.pos, this.device.powered() ? "tv_off" : "tv_on", "");
            return true;
        }
        if (this.device.casting()) {
            int[] box = this.disconnectBox();
            if (this.mouseX >= box[0] && this.mouseX < box[2]
                    && this.mouseY >= box[1] && this.mouseY < box[3]) {
                HajikunSmartClient.request(this.pos, "tv_disconnect", "");
                return true;
            }
        }
        if (!this.device.powered()) {
            this.toast("单击右下角电源键开机。");
        } else if (!this.device.casting()) {
            this.toast("在电脑终端输入 cast " + this.device.mac() + " 即可投屏");
        }
        return true;
    }

    public boolean typing() {
        return false;
    }

    public boolean keyPressed(int key, boolean shift) {
        return false;
    }

    public boolean charTyped(int codePoint) {
        return false;
    }
}
