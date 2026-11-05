package com.akun.hajikun.client.screen;

import com.akun.hajikun.client.HajikunSmartClient;
import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.network.SmartPayloads.CameraRollS2C;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 相机传输面板：照相机右键电脑时打开，把照片/录像复制或移动到虚拟机。
 *
 * <p>面板只显示文件名、类型与大小，文件本体始终留在服务端；点一下由服务端
 * 直接写进电脑的「图片」或「视频」目录，因此几十兆录像也不会经过客户端。</p>
 */
public final class CameraTransferScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final double MAX_DISTANCE_SQR = 64.0D * 64.0D;

    private final BlockPos pos;
    private final List<String> names = new ArrayList<>();
    private final List<String> mimes = new ArrayList<>();
    private final List<Integer> sizes = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private String status = "";
    private int scroll;

    public CameraTransferScreen(CameraRollS2C packet) {
        super(Component.literal("照相机传输"));
        this.pos = packet.pos();
        this.update(packet);
    }

    public void update(CameraRollS2C packet) {
        this.names.clear();
        this.names.addAll(packet.names());
        this.mimes.clear();
        this.mimes.addAll(packet.mimes());
        this.sizes.clear();
        this.sizes.addAll(packet.sizes());
        this.status = packet.status() == null ? "" : packet.status();
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.names.size() - 1)));
    }

    public BlockPos pos() {
        return this.pos;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hits.clear();
        int panelW = Math.min(560, this.width - 40);
        int panelH = Math.min(390, this.height - 40);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        int x1 = x0 + panelW;
        int y1 = y0 + panelH;

        graphics.fill(x0 + 3, y0 + 3, x1 + 3, y1 + 3, 0x66000000);
        graphics.fill(x0, y0, x1, y1, 0xFFECE9D8);
        graphics.outline(x0, y0, x1, y1, 0xFF003C74);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 24, 0xFF0A246A);
        graphics.text(this.font, "照相机 → 电脑", x0 + 10, y0 + 8, 0xFFFFFFFF);
        String close = "关闭";
        int closeW = this.font.width(close) + 14;
        int closeX = x1 - closeW - 5;
        this.button(graphics, closeX, y0 + 4, closeW, 17, close, mouseX, mouseY,
                () -> this.onClose());

        graphics.text(this.font, "目标：/home/user/图片（照片）  /home/user/视频（录像）",
                x0 + 10, y0 + 32, 0xFF333333);

        int bx = x0 + 10;
        int by = y0 + 50;
        bx = this.chip(graphics, bx, by, "全部复制", mouseX, mouseY,
                () -> HajikunSmartClient.request(this.pos, "camera_copy_all", ""));
        bx = this.chip(graphics, bx, by, "全部移动", mouseX, mouseY,
                () -> HajikunSmartClient.request(this.pos, "camera_move_all", ""));
        bx = this.chip(graphics, bx, by, "刷新", mouseX, mouseY,
                () -> HajikunSmartClient.request(this.pos, "camera_refresh", ""));
        graphics.text(this.font, this.names.size() + " 个文件", x1 - 74, by + 4, 0xFF35506B);

        int listX = x0 + 10;
        int listY = by + 24;
        int listW = panelW - 20;
        int listH = panelH - (listY - y0) - 52;
        graphics.fill(listX, listY, listX + listW, listY + listH, 0xFFFFFFFF);
        graphics.outline(listX, listY, listX + listW, listY + listH, 0xFF7B8FA6);
        int rows = Math.max(1, listH / ROW_HEIGHT);
        int maxScroll = Math.max(0, this.names.size() - rows);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));

        if (this.names.isEmpty()) {
            graphics.text(this.font, "相册里还没有照片或录像。回到拍摄模式按左键拍照，按 E 切到录像后再按左键录制。",
                    listX + 10, listY + 12, 0xFF666666);
        } else {
            for (int row = 0; row < rows; row++) {
                int index = this.scroll + row;
                if (index >= this.names.size()) {
                    break;
                }
                int ry = listY + row * ROW_HEIGHT;
                boolean hover = mouseX >= listX && mouseX < listX + listW
                        && mouseY >= ry && mouseY < ry + ROW_HEIGHT;
                if (hover) {
                    graphics.fill(listX + 1, ry, listX + listW - 1, ry + ROW_HEIGHT, 0xFFDCE8F5);
                }
                String mime = this.mimes.get(index);
                boolean video = mime != null && mime.startsWith("video/");
                int tag = video ? 0xFF8A3B8F : 0xFF2E6DA4;
                graphics.fill(listX + 6, ry + 5, listX + 42, ry + 17, tag);
                graphics.text(this.font, video ? "录像" : "照片", listX + 10, ry + 7, 0xFFFFFFFF);
                String name = this.names.get(index);
                graphics.text(this.font, name, listX + 50, ry + 7, 0xFF1F1F1F);
                int size = index < this.sizes.size() ? this.sizes.get(index) : 0;
                String sizeText = sizeText(size);
                graphics.text(this.font, sizeText, listX + listW - 10 - this.font.width(sizeText),
                        ry + 7, 0xFF5E6B78);
                int finalIndex = index;
                this.hits.add(new Hit(listX + 1, ry, listW - 2, ROW_HEIGHT, 1,
                        () -> HajikunSmartClient.request(this.pos, "camera_copy", this.names.get(finalIndex))));
                this.hits.add(new Hit(listX + 1, ry, listW - 2, ROW_HEIGHT, 3,
                        () -> HajikunSmartClient.request(this.pos, "camera_move", this.names.get(finalIndex))));
            }
            if (maxScroll > 0) {
                int barH = Math.max(18, listH * rows / this.names.size());
                int barY = listY + (listH - barH) * this.scroll / maxScroll;
                graphics.fill(listX + listW - 5, barY, listX + listW - 2, barY + barH, 0xFF8EA6BD);
            }
        }

        String hint = "左键复制到电脑 · 右键移动（从相机删除） · 滚轮翻页 · Esc 关闭";
        graphics.text(this.font, hint, x0 + 10, y1 - 42, 0xFF4A5560);
        if (!this.status.isBlank()) {
            graphics.text(this.font, this.status, x0 + 10, y1 - 25, 0xFF1E6B2F);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int button = event.button();
        for (Hit hit : this.hits) {
            if (hit.button == button && hit.contains(event.x(), event.y())) {
                hit.action.run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = Math.max(0, this.scroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        if (this.minecraft == null || this.minecraft.level == null || this.minecraft.player == null) {
            this.onClose();
            return;
        }
        BlockState state = this.minecraft.level.getBlockState(this.pos);
        if (!(state.getBlock() instanceof ComputerBlock)
                || this.minecraft.player.distanceToSqr(Vec3.atCenterOf(this.pos)) > MAX_DISTANCE_SQR) {
            this.onClose();
        }
    }

    private int chip(GuiGraphicsExtractor graphics, int x, int y, String label, int mouseX, int mouseY,
                     Runnable action) {
        int w = this.font.width(label) + 16;
        this.button(graphics, x, y, w, 18, label, mouseX, mouseY, action);
        return x + w + 6;
    }

    private void button(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String label,
                        int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, hover ? 0xFFFFF6D5 : 0xFFF6F4EA);
        graphics.outline(x, y, x + w, y + h, 0xFF7B8FA6);
        graphics.text(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2, 0xFF1F1F1F);
        this.hits.add(new Hit(x, y, w, h, 1, action));
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

    private record Hit(int x, int y, int w, int h, int button, Runnable action) {
        private boolean contains(double px, double py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }
}
