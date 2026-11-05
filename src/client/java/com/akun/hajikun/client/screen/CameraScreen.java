package com.akun.hajikun.client.screen;

import com.akun.hajikun.camera.CameraItem;
import com.akun.hajikun.camera.TripodBlock;
import com.akun.hajikun.client.camera.CameraFrames;
import com.akun.hajikun.client.camera.CameraState;
import com.akun.hajikun.client.media.HkvVideo;
import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.sdl.SDLMouse;

/**
 * 照相机取景器：不暂停游戏的世界层叠界面。
 *
 * <p>滚轮在 0.5×–5.0× 之间变焦；左键/空格拍照；R 开始或结束录像；F 开关闪光灯；
 * C 切换前置自拍；方向键做固定机位微调。屏幕本身不画任何面板，只画取景框与状态，
 * 截图时抓取的是尚未绘制 GUI 的主渲染目标，所以照片里不会出现这些提示。</p>
 */
public final class CameraScreen extends Screen {
    private static final int PHOTO_WIDTH = 720;
    private static final int VIDEO_WIDTH = 320;
    private static final int VIDEO_FPS = 8;
    private static final long FRAME_INTERVAL_MS = 1000L / VIDEO_FPS;
    private static final int MAX_FRAMES = 2400;
    private static final long MAX_VIDEO_BYTES = 80L * 1024L * 1024L;
    private static final double MAX_DISTANCE_SQR = 64.0D * 64.0D;

    private final InteractionHand hand;
    private BlockPos tripod;
    private final Set<Integer> heldKeys = new HashSet<>();

    private boolean hidHud;
    private boolean flash;
    private boolean recording;
    private volatile boolean autoStop;
    private boolean photoInFlight;
    private boolean frameInFlight;
    private long flashUntil;
    private long recordStartedAt;
    private long lastFrameAt;
    private int photos;
    private int videoWidth;
    private int videoHeight;
    private final List<byte[]> frames = new ArrayList<>();
    private long frameBytes;
    private String toastText = "";
    private long toastUntil;

    public CameraScreen(InteractionHand hand, BlockPos tripod) {
        super(Component.literal("照相机取景器"));
        this.hand = hand;
        this.tripod = tripod;
    }

    @Override
    protected void init() {
        SDLMouse.SDL_HideCursor();
        if (!this.minecraft.gui.hud.isHidden()) {
            this.minecraft.gui.hud.toggle();
            this.hidHud = true;
        }
        if (this.tripod != null) {
            BlockState state = this.minecraft.level == null
                    ? null : this.minecraft.level.getBlockState(this.tripod);
            net.minecraft.core.Direction facing = state != null && state.hasProperty(TripodBlock.FACING)
                    ? state.getValue(TripodBlock.FACING) : net.minecraft.core.Direction.NORTH;
            CameraState.activateTripod(this.tripod, facing);
            this.toast("三脚架固定机位 · 方向键微调");
        } else {
            CameraState.activateHeld();
        }
    }

    @Override
    public void removed() {
        if (this.recording) {
            this.stopRecording(false);
        }
        CameraState.clear();
        this.heldKeys.clear();
        if (this.hidHud && this.minecraft.gui.hud.isHidden()) {
            this.minecraft.gui.hud.toggle();
        }
        this.hidHud = false;
        SDLMouse.SDL_ShowCursor();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 取景时保持世界原样，不压暗、不模糊。
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 必须在 GUI 正式绘制前抓帧：主渲染目标里只有世界，没有取景框。
        if (this.recording && !this.frameInFlight && !this.autoStop
                && System.currentTimeMillis() - this.lastFrameAt >= FRAME_INTERVAL_MS) {
            this.lastFrameAt = System.currentTimeMillis();
            this.requestFrame();
        }
        this.renderHud(graphics);
    }

    @Override
    public void tick() {
        Minecraft client = this.minecraft;
        if (client == null || client.player == null || client.level == null) {
            this.onClose();
            return;
        }
        if (this.tripod != null) {
            BlockState state = client.level.getBlockState(this.tripod);
            if (!(state.getBlock() instanceof TripodBlock)
                    || !state.getValue(TripodBlock.MOUNTED)
                    || client.player.distanceToSqr(this.tripod.getX() + 0.5D, this.tripod.getY() + 0.5D,
                    this.tripod.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
                this.toast("三脚架或照相机已被移除，拍摄任务中断");
                this.onClose();
                return;
            }
        } else if (!(client.player.getItemInHand(this.hand).getItem() instanceof CameraItem)) {
            this.onClose();
            return;
        }
        if (!this.heldKeys.isEmpty()) {
            float yaw = 0.0F;
            float pitch = 0.0F;
            if (this.heldKeys.contains(InputConstants.KEY_LEFT)) {
                yaw += 1.4F;
            }
            if (this.heldKeys.contains(InputConstants.KEY_RIGHT)) {
                yaw -= 1.4F;
            }
            if (this.heldKeys.contains(InputConstants.KEY_UP)) {
                pitch -= 1.0F;
            }
            if (this.heldKeys.contains(InputConstants.KEY_DOWN)) {
                pitch += 1.0F;
            }
            CameraState.turn(yaw, pitch);
        }
        if (this.recording && this.autoStop) {
            this.stopRecording(true);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT
                || key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            this.heldKeys.add(key);
            return true;
        }
        if (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_RETURN) {
            this.takePhoto();
            return true;
        }
        if (key == InputConstants.KEY_R) {
            this.toggleRecording();
            return true;
        }
        if (key == InputConstants.KEY_F) {
            this.flash = !this.flash;
            this.toast(this.flash ? "闪光灯：开" : "闪光灯：关");
            return true;
        }
        if (key == InputConstants.KEY_C) {
            CameraState.toggleFront();
            this.toast(CameraState.front() ? "切换到前置镜头（自拍）" : "切换到后置镜头");
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        this.heldKeys.remove(event.key());
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 26.3 的 SDL 键号：1 = 左键。
        if (event.button() == 1) {
            this.takePhoto();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        CameraState.zoomBy(scrollY);
        return true;
    }

    private void toggleRecording() {
        if (this.recording) {
            this.stopRecording(true);
            return;
        }
        synchronized (this.frames) {
            this.frames.clear();
        }
        this.frameBytes = 0L;
        this.recording = true;
        this.autoStop = false;
        this.recordStartedAt = System.currentTimeMillis();
        this.lastFrameAt = this.recordStartedAt;
        this.videoWidth = VIDEO_WIDTH;
        this.videoHeight = 0;
        this.toast("开始录像");
    }

    /** save 为 false 时丢弃所有缓冲帧（拆支架、退出取景器都算中断）。 */
    private void stopRecording(boolean save) {
        if (!this.recording) {
            return;
        }
        this.recording = false;
        this.autoStop = false;
        List<byte[]> copy;
        synchronized (this.frames) {
            copy = new ArrayList<>(this.frames);
            this.frames.clear();
        }
        this.frameBytes = 0L;
        if (!save) {
            this.toast("拍摄任务中断，未完成的录像已丢弃");
            return;
        }
        if (copy.size() < 2) {
            this.toast("录像太短，未保存");
            return;
        }
        int width = Math.max(2, this.videoWidth);
        int height = Math.max(2, this.videoHeight);
        BlockPos pos = this.tripod == null ? BlockPos.ZERO : this.tripod;
        boolean onTripod = this.tripod != null;
        Thread worker = new Thread(() -> {
            byte[] video = HkvVideo.write(width, height, VIDEO_FPS, copy, null);
            if (video != null) {
                CameraFrames.upload(onTripod, pos, CameraFrames.name("VID", ".hkv"), "video/hkv", video);
            }
        }, "hajikun-camera-video");
        worker.setDaemon(true);
        worker.start();
        this.toast("录像已保存：" + copy.size() + " 帧");
    }

    private void takePhoto() {
        if (this.recording) {
            this.toast("录像中不能拍照，先按 R 结束录像");
            return;
        }
        if (this.photoInFlight) {
            return;
        }
        this.photoInFlight = true;
        boolean flashOn = this.flash;
        if (flashOn) {
            this.flashUntil = System.currentTimeMillis() + 160L;
        }
        BlockPos pos = this.tripod == null ? BlockPos.ZERO : this.tripod;
        boolean onTripod = this.tripod != null;
        this.photos++;
        Screenshot.takeScreenshot(this.minecraft.gameRenderer.mainRenderTarget(), image -> {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getPixelsABGR();
            image.close();
            this.photoInFlight = false;
            if (this.minecraft.gui.screen() != this) {
                return;
            }
            Thread worker = new Thread(() -> {
                BufferedImage photo = CameraFrames.sample(pixels, width, height, PHOTO_WIDTH, flashOn ? 34 : 0);
                byte[] jpeg = CameraFrames.jpeg(photo, 0.9F);
                if (jpeg != null) {
                    CameraFrames.upload(onTripod, pos, CameraFrames.name("IMG", ".jpg"), "image/jpeg", jpeg);
                }
            }, "hajikun-camera-photo");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void requestFrame() {
        if (this.frameInFlight || !this.recording) {
            return;
        }
        this.frameInFlight = true;
        Screenshot.takeScreenshot(this.minecraft.gameRenderer.mainRenderTarget(), image -> {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getPixelsABGR();
            image.close();
            this.frameInFlight = false;
            if (this.minecraft.gui.screen() != this) {
                return;
            }
            Thread worker = new Thread(() -> {
                BufferedImage frame = CameraFrames.sample(pixels, width, height, VIDEO_WIDTH, 0);
                byte[] jpeg = CameraFrames.jpeg(frame, 0.72F);
                if (jpeg == null) {
                    return;
                }
                synchronized (this.frames) {
                    if (!this.recording) {
                        return;
                    }
                    if (this.videoHeight == 0) {
                        this.videoWidth = frame.getWidth();
                        this.videoHeight = frame.getHeight();
                    }
                    this.frames.add(jpeg);
                    this.frameBytes += jpeg.length;
                    if (this.frames.size() >= MAX_FRAMES || this.frameBytes >= MAX_VIDEO_BYTES) {
                        this.autoStop = true;
                    }
                }
            }, "hajikun-camera-frame");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void renderHud(GuiGraphicsExtractor graphics) {
        Minecraft client = this.minecraft;
        if (client == null) {
            return;
        }
        int width = this.width;
        int height = this.height;
        int white = 0xFFFFFFFF;
        int gray = 0xFFBFD3E6;
        int green = 0xFF7CFF9B;
        int red = 0xFFFF5A5A;
        graphics.fill(0, 0, width, 28, 0x8F05080D);
        graphics.fill(0, height - 34, width, height, 0x8F05080D);

        String mode = CameraState.front() ? "前置 · 自拍" : "后置";
        String source = this.tripod != null ? "三脚架固定机位" : "手持";
        String line = String.format(Locale.ROOT, "照相机  %s  %s  %.1f×  闪光 %s  %s",
                mode, source, CameraState.zoom(), this.flash ? "开" : "关", "本次 " + this.photos + " 张");
        graphics.text(this.font, line, 12, 10, white);

        if (this.recording) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - this.recordStartedAt);
            String clock = String.format(Locale.ROOT, "%02d:%02d", elapsed / 60000L, (elapsed / 1000L) % 60L);
            int rx = width - 100;
            graphics.fill(rx, 9, rx + 10, 19, red);
            graphics.text(this.font, "REC " + clock, rx + 16, 10, red);
            graphics.outline(3, 3, width - 3, height - 3, 0x99FF3B3B);
        } else {
            String zoom = String.format(Locale.ROOT, "%.1f×", CameraState.zoom());
            graphics.text(this.font, zoom, width - 12 - this.font.width(zoom), 10, gray);
        }

        // 取景框四角，提示这是相机画面而不是普通 HUD。
        int m = 12;
        int arm = 22;
        graphics.fill(m, m, m + arm, m + 2, 0xCCFFFFFF);
        graphics.fill(m, m, m + 2, m + arm, 0xCCFFFFFF);
        graphics.fill(width - m - arm, m, width - m, m + 2, 0xCCFFFFFF);
        graphics.fill(width - m - 2, m, width - m, m + arm, 0xCCFFFFFF);
        graphics.fill(m, height - m - 2, m + arm, height - m, 0xCCFFFFFF);
        graphics.fill(m, height - m - arm, m + 2, height - m, 0xCCFFFFFF);
        graphics.fill(width - m - arm, height - m - 2, width - m, height - m, 0xCCFFFFFF);
        graphics.fill(width - m - 2, height - m - arm, width - m, height - m, 0xCCFFFFFF);

        // 中央准星。
        int cx = width / 2;
        int cy = height / 2;
        graphics.fill(cx - 9, cy, cx - 3, cy + 1, 0xCCFFFFFF);
        graphics.fill(cx + 3, cy, cx + 9, cy + 1, 0xCCFFFFFF);
        graphics.fill(cx, cy - 9, cx + 1, cy - 3, 0xCCFFFFFF);
        graphics.fill(cx, cy + 3, cx + 1, cy + 9, 0xCCFFFFFF);

        String hints = this.tripod != null
                ? "滚轮 变焦 · 左键/空格 拍照 · R 录像 · F 闪光 · C 前后 · 方向键 转动 · Esc 退出"
                : "滚轮 变焦 · 左键/空格 拍照 · R 录像 · F 闪光 · C 自拍 · 方向键 微调 · Esc 退出";
        graphics.text(this.font, hints, 12, height - 24, gray);

        if (System.currentTimeMillis() < this.toastUntil && !this.toastText.isBlank()) {
            int tw = this.font.width(this.toastText);
            int x = (width - tw) / 2;
            graphics.fill(x - 8, height - 58, x + tw + 8, height - 42, 0xB005080D);
            graphics.text(this.font, this.toastText, x, height - 54, green);
        }

        long now = System.currentTimeMillis();
        if (now < this.flashUntil) {
            int remain = (int) Math.max(0L, this.flashUntil - now);
            int alpha = (int) (200L * remain / 160L);
            graphics.fill(0, 0, width, height, (alpha << 24) | 0x00FFFFFF);
        }
    }

    private void toast(String message) {
        this.toastText = message == null ? "" : message;
        this.toastUntil = System.currentTimeMillis() + 2200L;
    }
}
