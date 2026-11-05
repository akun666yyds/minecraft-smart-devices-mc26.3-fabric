package com.akun.hajikun.client.screen;

import com.akun.hajikun.client.HajikunSmartClient;
import com.akun.hajikun.client.media.HkvVideo;
import com.akun.hajikun.client.media.ImageDecode;
import com.akun.hajikun.client.media.VideoPlayer;
import com.akun.hajikun.network.SmartPayloads.CameraMediaS2C;
import com.akun.hajikun.network.SmartPayloads.CameraQueryC2S;
import com.akun.hajikun.network.SmartPayloads.CameraViewerS2C;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 拍摄模式里的胶卷列表：左侧项目，右侧预览。
 *
 * <p>这是唯一会打开的 Screen；它不暂停游戏，关闭后直接回到拍摄模式。
 * 列表只带文件名/类型/大小，点开某一项后才向服务端分块拉取文件本体，
 * 因此几十兆的录像不会在按 F 的瞬间全部推给客户端。</p>
 *
 * <p>录像控制与电脑媒体查看器一致：播放/暂停、进度跳转、快退快进、
 * 倒放、0.5–4 倍速、音量与上一个/下一个项目。图片只做等比预览。</p>
 */
public final class CameraRollScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_CHUNKS = 8192;
    private static final int KEY_EQUALS = 46;
    private static final AtomicInteger TEXTURE_SEQ = new AtomicInteger();
    private static CameraRollScreen current;

    private final boolean tripod;
    private final BlockPos pos;
    private final List<Item> items = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private String status = "";
    private int selected = -1;
    private int scroll;

    private String downloading = "";
    private byte[][] chunks;
    private int chunkTotal;
    private int chunkReceived;

    private String previewName = "";
    private String previewMime = "";
    private Identifier imageTexture;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private HkvVideo video;
    private VideoPlayer player;
    private float volume = 1.0F;

    private int progressX;
    private int progressY;
    private int progressW;
    private int progressH;

    public CameraRollScreen(boolean tripod, BlockPos pos) {
        super(Component.literal("照相机胶卷"));
        this.tripod = tripod;
        this.pos = pos == null ? BlockPos.ZERO : pos.immutable();
    }

    /** 服务端目录到达；只接受当前打开的这台相机。 */
    public static void acceptList(CameraViewerS2C packet) {
        CameraRollScreen screen = current;
        if (screen != null) {
            screen.onList(packet);
        }
    }

    /** 文件分块到达；按名字归到正在等待的那一项。 */
    public static void acceptMedia(CameraMediaS2C packet) {
        CameraRollScreen screen = current;
        if (screen != null) {
            screen.onMedia(packet);
        }
    }

    @Override
    protected void init() {
        super.init();
        current = this;
        this.requestList();
    }

    @Override
    public void removed() {
        if (current == this) {
            current = null;
        }
        this.closePreview();
        this.hits.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 不暂停、不变暗世界：胶卷列表只是叠在取景画面上的一层面板。
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hits.clear();
        if (this.player != null) {
            this.player.tick();
        }
        int panelW = Math.min(900, Math.max(300, this.width - 32));
        int panelH = Math.min(560, Math.max(220, this.height - 32));
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        int x1 = x0 + panelW;
        int y1 = y0 + panelH;

        graphics.fill(x0 + 4, y0 + 4, x1 + 4, y1 + 4, 0x66000000);
        graphics.fill(x0, y0, x1, y1, 0xF20B1017);
        graphics.outline(x0, y0, x1, y1, 0xFF4A6785);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 25, 0xFF152233);
        graphics.text(this.font, "照相机胶卷", x0 + 10, y0 + 8, 0xFFFFFFFF);
        String count = this.items.size() + " 个项目 · " + (this.tripod ? "三脚架相机" : "手持相机");
        graphics.text(this.font, count, x0 + 86, y0 + 8, 0xFF9FB4C8);

        int closeW = this.font.width("关闭") + 16;
        int closeX = x1 - closeW - 6;
        this.chip(graphics, closeX, y0 + 4, closeW, 18, "关闭", mouseX, mouseY, 0xFF2A3A4D,
                0xFF3E5876, this::onClose);

        int listX = x0 + 8;
        int listY = y0 + 32;
        int listW = Math.min(248, Math.max(150, panelW / 3));
        int footH = 40;
        int listH = Math.max(60, panelH - 32 - footH - 12);
        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFF070B10);
        graphics.outline(listX, listY, listX + listW, listY + listH, 0xFF2B3C50);

        int rows = Math.max(1, listH / ROW_HEIGHT);
        int maxScroll = Math.max(0, this.items.size() - rows);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
        if (this.items.isEmpty()) {
            graphics.text(this.font, "胶卷还是空的。", listX + 8, listY + 10, 0xFF7E93A8);
            graphics.text(this.font, "退出列表后按左键拍照，", listX + 8, listY + 26, 0xFF5E7186);
            graphics.text(this.font, "E 切到录像再按左键录制。", listX + 8, listY + 40, 0xFF5E7186);
        } else {
            for (int row = 0; row < rows; row++) {
                int index = this.scroll + row;
                if (index >= this.items.size()) {
                    break;
                }
                Item item = this.items.get(index);
                int ry = listY + row * ROW_HEIGHT;
                boolean hover = mouseX >= listX + 1 && mouseX < listX + listW - 1
                        && mouseY >= ry && mouseY < ry + ROW_HEIGHT;
                boolean active = index == this.selected;
                if (active) {
                    graphics.fill(listX + 1, ry, listX + listW - 1, ry + ROW_HEIGHT, 0xFF1D3A57);
                } else if (hover) {
                    graphics.fill(listX + 1, ry, listX + listW - 1, ry + ROW_HEIGHT, 0xFF132435);
                }
                boolean videoItem = item.video();
                int tagColor = videoItem ? 0xFF7A3F86 : 0xFF2E6DA4;
                graphics.fill(listX + 5, ry + 4, listX + 41, ry + 18, tagColor);
                graphics.text(this.font, videoItem ? "录像" : "照片", listX + 9, ry + 7, 0xFFFFFFFF);
                String sizeText = sizeText(item.size());
                int sizeW = this.font.width(sizeText);
                int nameX = listX + 46;
                int nameRight = listX + listW - 8 - sizeW - 6;
                graphics.enableScissor(nameX, ry, Math.max(nameX, nameRight), ry + ROW_HEIGHT);
                graphics.text(this.font, item.name(), nameX, ry + 7,
                        active ? 0xFFFFFFFF : 0xFFCBD9E6);
                graphics.disableScissor();
                graphics.text(this.font, sizeText, listX + listW - 8 - sizeW, ry + 7, 0xFF7890A6);
                int finalIndex = index;
                this.hits.add(new Hit(listX + 1, ry, listW - 2, ROW_HEIGHT, 1,
                        () -> this.select(finalIndex)));
            }
            if (maxScroll > 0) {
                int barH = Math.max(18, listH * rows / this.items.size());
                int barY = listY + (listH - barH) * this.scroll / maxScroll;
                graphics.fill(listX + listW - 5, barY, listX + listW - 2, barY + barH, 0xFF5B7A98);
            }
        }

        int px = listX + listW + 10;
        int py = listY;
        int pw = Math.max(80, x1 - 10 - px);
        int ph = listH;
        graphics.fill(px, py, px + pw, py + ph, 0xFF05080C);
        graphics.outline(px, py, px + pw, py + ph, 0xFF2B3C50);
        this.renderPreview(graphics, px, py, pw, ph);
        this.renderProgress(graphics, px, py + ph, pw);
        this.renderControls(graphics, px, py + ph + 12, pw, mouseX, mouseY);

        String hint = this.player != null
                ? "↑↓ 项目 · 空格 播放/暂停 · ←→ 快退/快进 5 秒 · R 倒放 · S 倍速 · -/= 音量 · F/Esc 关闭"
                : "↑↓ 项目 · [ ] 上一个/下一个 · F 或 Esc 关闭列表";
        graphics.text(this.font, hint, x0 + 10, y1 - 14, 0xFF6F8599);
        if (!this.status.isBlank()) {
            int sw = this.font.width(this.status);
            graphics.enableScissor(listX, y1 - 26, x1 - 10, y1 - 12);
            graphics.text(this.font, this.status, Math.min(listX + 10, x1 - 10 - sw), y1 - 26,
                    this.status.startsWith("无法") || this.status.startsWith("录像") ? 0xFFFF9A8A : 0xFF7CFF9B);
            graphics.disableScissor();
        }
    }

    private void renderPreview(GuiGraphicsExtractor graphics, int px, int py, int pw, int ph) {
        if (this.items.isEmpty()) {
            graphics.text(this.font, "按 F 退出列表，回到取景画面拍照。", px + 12, py + 12, 0xFF6F8599);
            return;
        }
        if (this.imageTexture != null) {
            int[] box = fit(px + 6, py + 6, pw - 12, ph - 12, this.imageWidth, this.imageHeight);
            graphics.blit(this.imageTexture, box[0], box[1], box[0] + box[2], box[1] + box[3],
                    0.0F, 1.0F, 0.0F, 1.0F);
            return;
        }
        if (this.player != null && this.video != null) {
            int[] box = fit(px + 6, py + 6, pw - 12, ph - 12, this.video.width, this.video.height);
            graphics.blit(this.player.texture(), box[0], box[1], box[0] + box[2], box[1] + box[3],
                    0.0F, 1.0F, 0.0F, 1.0F);
            String badge = this.player.reversed() ? "倒放" : "正放";
            String rate = String.format(Locale.ROOT, "%s · %.1f× · 音量 %d%%", badge,
                    this.player.speed(), Math.round(this.volume * 100.0F));
            int bw = this.font.width(rate);
            graphics.fill(px + pw - bw - 14, py + 6, px + pw - 6, py + 18, 0xB3000000);
            graphics.text(this.font, rate, px + pw - bw - 10, py + 8, 0xFFE6F0FA);
            if (!this.player.hasSound()) {
                graphics.text(this.font, "这段录像没有现场声", px + 8, py + 8, 0xFF7E93A8);
            }
            return;
        }
        if (!this.downloading.isBlank()) {
            graphics.text(this.font, "正在从胶卷读取 " + this.downloading + " …",
                    px + 12, py + 12, 0xFF9FB4C8);
            if (this.chunkTotal > 0) {
                int barW = Math.max(20, pw - 24);
                int filled = (int) (barW * (this.chunkReceived / (double) this.chunkTotal));
                graphics.fill(px + 12, py + 28, px + 12 + barW, py + 34, 0xFF1B2A3A);
                graphics.fill(px + 12, py + 28, px + 12 + filled, py + 34, 0xFF4C9EE8);
            }
            return;
        }
        graphics.text(this.font, "选择左侧项目预览", px + 12, py + 12, 0xFF6F8599);
    }

    private void renderProgress(GuiGraphicsExtractor graphics, int px, int py, int pw) {
        this.progressX = px;
        this.progressY = py;
        this.progressW = pw;
        this.progressH = 0;
        if (this.player == null) {
            return;
        }
        long duration = Math.max(1L, this.player.durationMillis());
        long position = Math.max(0L, Math.min(duration, this.player.positionMillis()));
        int barY = py + 3;
        this.progressY = barY;
        this.progressH = 7;
        graphics.fill(px, barY, px + pw, barY + 3, 0xFF1B2A3A);
        int filled = (int) (pw * (position / (double) duration));
        graphics.fill(px, barY, px + Math.max(0, Math.min(pw, filled)), barY + 3, 0xFF4C9EE8);
        int knobX = px + Math.max(0, Math.min(pw - 1, filled));
        graphics.fill(knobX - 1, barY - 2, knobX + 1, barY + 5, 0xFFE6F0FA);
        String clock = clock(position) + " / " + clock(this.player.durationMillis());
        graphics.text(this.font, clock, px + pw - this.font.width(clock), barY + 7, 0xFF9FB4C8);
    }

    private void renderControls(GuiGraphicsExtractor graphics, int x, int y, int width,
                                int mouseX, int mouseY) {
        if (this.player == null) {
            if (!this.items.isEmpty() && this.imageTexture != null) {
                this.chip(graphics, x, y, this.font.width("上一个") + 16, 20, "上一个",
                        mouseX, mouseY, 0xFF2A3A4D, 0xFF3E5876, this::previous);
                this.chip(graphics, x + this.font.width("上一个") + 20, y,
                        this.font.width("下一个") + 16, 20, "下一个", mouseX, mouseY,
                        0xFF2A3A4D, 0xFF3E5876, this::next);
                graphics.text(this.font, this.imageWidth + "×" + this.imageHeight,
                        x + width - this.font.width(this.imageWidth + "×" + this.imageHeight),
                        y + 6, 0xFF9FB4C8);
            }
            return;
        }
        int cx = x;
        String toggle = this.player.playing() ? "暂停" : "播放";
        cx = this.chip(graphics, cx, y, this.font.width(toggle) + 16, 20, toggle, mouseX, mouseY,
                0xFF1D3A57, 0xFF2C5B87, this::togglePlay);
        String reverse = this.player.reversed() ? "倒放中" : "倒放";
        cx = this.chip(graphics, cx, y, this.font.width(reverse) + 16, 20, reverse, mouseX, mouseY,
                0xFF2A3A4D, 0xFF3E5876, this::toggleReverse);
        String speed = String.format(Locale.ROOT, "%.1f×", this.player.speed());
        cx = this.chip(graphics, cx, y, this.font.width(speed) + 16, 20, speed, mouseX, mouseY,
                0xFF2A3A4D, 0xFF3E5876, this::cycleSpeed);
        cx = this.chip(graphics, cx, y, this.font.width("音量-") + 16, 20, "音量-", mouseX, mouseY,
                0xFF2A3A4D, 0xFF3E5876, () -> this.changeVolume(-0.1F));
        cx = this.chip(graphics, cx, y, this.font.width("音量+") + 16, 20, "音量+", mouseX, mouseY,
                0xFF2A3A4D, 0xFF3E5876, () -> this.changeVolume(0.1F));
        cx = this.chip(graphics, cx, y, this.font.width("上一个") + 16, 20, "上一个", mouseX, mouseY,
                0xFF2A3A4D, 0xFF3E5876, this::previous);
        cx = this.chip(graphics, cx, y, this.font.width("下一个") + 16, 20, "下一个", mouseX, mouseY,
                0xFF2A3A4D, 0xFF3E5876, this::next);
    }

    private int chip(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String label,
                     int mouseX, int mouseY, int fill, int hoverFill, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, hover ? hoverFill : fill);
        graphics.outline(x, y, x + w, y + h, hover ? 0xFF7FB0D8 : 0xFF3E5876);
        graphics.text(this.font, label, x + (w - this.font.width(label)) / 2,
                y + (h - 8) / 2, 0xFFE6F0FA);
        this.hits.add(new Hit(x, y, w, h, 1, action));
        return x + w + 4;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 1 && this.progressH > 0
                && event.x() >= this.progressX && event.x() < this.progressX + this.progressW
                && event.y() >= this.progressY - 3 && event.y() < this.progressY + this.progressH + 3) {
            this.seekTo((event.x() - this.progressX) / (double) Math.max(1, this.progressW));
            return true;
        }
        for (Hit hit : this.hits) {
            if (hit.button == event.button() && hit.contains(event.x(), event.y())) {
                hit.action.run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = Math.max(0, this.scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_F || key == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (this.items.isEmpty()) {
            return true;
        }
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_LBRACKET) {
            this.previous();
            return true;
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_RBRACKET) {
            this.next();
            return true;
        }
        if (this.player == null) {
            return true;
        }
        if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_RETURN) {
            this.togglePlay();
            return true;
        }
        if (key == InputConstants.KEY_LEFT) {
            this.player.seek(this.player.positionMillis() - 5000L);
            return true;
        }
        if (key == InputConstants.KEY_RIGHT) {
            this.player.seek(this.player.positionMillis() + 5000L);
            return true;
        }
        if (key == InputConstants.KEY_R) {
            this.toggleReverse();
            return true;
        }
        if (key == InputConstants.KEY_S) {
            this.cycleSpeed();
            return true;
        }
        if (key == InputConstants.KEY_MINUS) {
            this.changeVolume(-0.1F);
            return true;
        }
        if (key == KEY_EQUALS) {
            this.changeVolume(0.1F);
            return true;
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.level == null || this.minecraft.player == null) {
            this.onClose();
        }
    }

    private void requestList() {
        ClientPlayNetworking.send(new CameraQueryC2S(this.tripod, this.pos, "list", ""));
    }

    private void onList(CameraViewerS2C packet) {
        if (packet.tripod() != this.tripod || !packet.pos().equals(this.pos)) {
            return;
        }
        this.items.clear();
        for (int i = 0; i < packet.names().size(); i++) {
            this.items.add(new Item(packet.names().get(i),
                    i < packet.mimes().size() ? packet.mimes().get(i) : "",
                    i < packet.sizes().size() ? packet.sizes().get(i) : 0));
        }
        this.status = packet.status() == null ? "" : packet.status();
        if (this.selected >= 0) {
            String name = this.previewName;
            this.selected = -1;
            for (int i = 0; i < this.items.size(); i++) {
                if (this.items.get(i).name().equalsIgnoreCase(name)) {
                    this.selected = i;
                    break;
                }
            }
        }
        if (this.selected < 0 && !this.items.isEmpty()) {
            this.select(0);
        }
    }

    private void onMedia(CameraMediaS2C packet) {
        if (packet.total() <= 0 || packet.total() > MAX_CHUNKS
                || packet.index() < 0 || packet.index() >= packet.total()
                || !packet.name().equalsIgnoreCase(this.downloading)) {
            return;
        }
        if (this.chunks == null || this.chunkTotal != packet.total()) {
            this.chunks = new byte[packet.total()][];
            this.chunkTotal = packet.total();
            this.chunkReceived = 0;
        }
        if (this.chunks[packet.index()] == null) {
            this.chunks[packet.index()] = packet.data();
            this.chunkReceived++;
        }
        if (this.chunkReceived < this.chunkTotal) {
            return;
        }
        int size = 0;
        for (byte[] chunk : this.chunks) {
            if (chunk == null) {
                return;
            }
            size += chunk.length;
        }
        byte[] data = new byte[size];
        int offset = 0;
        for (byte[] chunk : this.chunks) {
            System.arraycopy(chunk, 0, data, offset, chunk.length);
            offset += chunk.length;
        }
        String name = this.downloading;
        String mime = packet.mime() == null ? "" : packet.mime();
        this.downloading = "";
        this.chunks = null;
        this.chunkTotal = 0;
        this.chunkReceived = 0;
        this.loadPreview(name, mime, data);
    }

    private void select(int index) {
        if (index < 0 || index >= this.items.size()) {
            return;
        }
        Item item = this.items.get(index);
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.items.size() - 1)));
        if (index == this.selected && item.name().equalsIgnoreCase(this.previewName)
                && (this.imageTexture != null || this.player != null)) {
            return;
        }
        this.selected = index;
        this.closePreview();
        this.previewName = item.name();
        this.previewMime = item.mime();
        this.downloading = item.name();
        this.chunks = null;
        this.chunkTotal = 0;
        this.chunkReceived = 0;
        this.status = "正在读取 " + item.name() + "…";
        ClientPlayNetworking.send(new CameraQueryC2S(this.tripod, this.pos, "media", item.name()));
    }

    private void loadPreview(String name, String mime, byte[] data) {
        if (!name.equalsIgnoreCase(this.previewName)) {
            return;
        }
        this.previewMime = mime;
        this.status = name + " · " + sizeText(data.length);
        if (mime.startsWith("video/")) {
            HkvVideo parsed = HkvVideo.parse(data);
            if (parsed == null) {
                this.status = "录像容器损坏，无法播放：" + name;
                return;
            }
            this.video = parsed;
            this.player = new VideoPlayer(parsed);
            this.player.setVolume(this.volume);
            return;
        }
        try {
            NativeImage image = ImageDecode.toNative(data);
            DynamicTexture texture = new DynamicTexture(() -> "Camera roll", image);
            texture.upload();
            Identifier id = Identifier.fromNamespaceAndPath("hajikun",
                    "camera_roll/" + TEXTURE_SEQ.incrementAndGet() + "_"
                            + Integer.toHexString(System.identityHashCode(this)));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            this.imageTexture = id;
            this.imageWidth = Math.max(1, image.getWidth());
            this.imageHeight = Math.max(1, image.getHeight());
        } catch (Exception exception) {
            this.status = "无法解码这张图片：" + name;
        }
    }

    private void closePreview() {
        if (this.player != null) {
            this.player.close();
            this.player = null;
        }
        this.video = null;
        if (this.imageTexture != null) {
            Minecraft.getInstance().getTextureManager().release(this.imageTexture);
            this.imageTexture = null;
        }
        this.imageWidth = 1;
        this.imageHeight = 1;
        this.previewName = "";
        this.previewMime = "";
        this.downloading = "";
        this.chunks = null;
        this.chunkTotal = 0;
        this.chunkReceived = 0;
    }

    private void previous() {
        if (this.items.isEmpty()) {
            return;
        }
        int index = this.selected <= 0 ? this.items.size() - 1 : this.selected - 1;
        this.select(index);
    }

    private void next() {
        if (this.items.isEmpty()) {
            return;
        }
        int index = this.selected < 0 || this.selected >= this.items.size() - 1 ? 0 : this.selected + 1;
        this.select(index);
    }

    private void togglePlay() {
        if (this.player != null) {
            this.player.toggle();
        }
    }

    private void toggleReverse() {
        if (this.player != null) {
            this.player.setReversed(!this.player.reversed());
        }
    }

    private void cycleSpeed() {
        if (this.player == null) {
            return;
        }
        float speed = this.player.speed();
        float next = speed < 0.75F ? 1.0F : speed < 1.5F ? 2.0F : speed < 3.0F ? 4.0F : 0.5F;
        this.player.setSpeed(next);
    }

    private void changeVolume(float delta) {
        this.volume = Math.max(0.0F, Math.min(1.0F, this.volume + delta));
        if (this.player != null) {
            this.player.setVolume(this.volume);
        }
    }

    private void seekTo(double ratio) {
        if (this.player == null) {
            return;
        }
        long millis = Math.round(Math.max(0.0D, Math.min(1.0D, ratio)) * this.player.durationMillis());
        this.player.seek(millis);
    }

    private static int[] fit(int x, int y, int width, int height, int sourceWidth, int sourceHeight) {
        double scale = Math.min(width / (double) Math.max(1, sourceWidth),
                height / (double) Math.max(1, sourceHeight));
        int w = Math.max(1, (int) Math.round(sourceWidth * scale));
        int h = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new int[]{x + (width - w) / 2, y + (height - h) / 2, w, h};
    }

    private static String clock(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String sizeText(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0D * 1024.0D));
    }

    private record Item(String name, String mime, int size) {
        private boolean video() {
            return this.mime != null && this.mime.startsWith("video/");
        }
    }

    private record Hit(int x, int y, int w, int h, int button, Runnable action) {
        private boolean contains(double px, double py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }
}
