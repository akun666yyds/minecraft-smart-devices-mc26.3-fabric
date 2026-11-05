package com.akun.hajikun.client.screen;

import com.akun.hajikun.client.render.FocusView;
import com.akun.hajikun.client.render.ScreenSurface;
import com.akun.hajikun.client.ui.ComputerUi;
import com.akun.hajikun.client.ui.TvUi;
import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.tv.TvBlock;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 聚焦到方块屏幕时打开的透明输入层。
 *
 * <p>它不画任何 GUI：世界照常以玩家第一人称渲染，显示器和键盘都留在视野里，
 * 只是把鼠标射线打到屏幕平面，再把点击、拖拽、滚轮与键盘交给屏幕里的界面。
 * 画面本身由方块渲染器实时绘制，Esc 或 Shift 离开。</p>
 *
 * <p>鼠标仍用系统光标：聚焦时只是把屏幕画布按透视映射到鼠标所在的像素，
 * 画面里的虚拟机箭头跟着系统光标走。之前尝试隐藏并抓取系统光标，
 * 但窗口抓取会让鼠标绝对坐标停止更新，虚拟光标因此卡死不动，现已放弃该做法。</p>
 */
public class DeviceFocusScreen extends Screen {
    private static final double MAX_DISTANCE_SQR = 36.0D;

    private final BlockPos pos;
    private final ScreenSurface surface;
    private final ComputerUi computer;
    private final TvUi tv;
    private boolean hidHud;
    private boolean textInput;
    /** 上一次成功投影到的画布坐标；射线与屏幕平面无交点时沿用它。 */
    private double lastPointerX = -1.0D;
    private double lastPointerY = -1.0D;

    public DeviceFocusScreen(BlockPos pos, ScreenSurface surface, ComputerUi ui) {
        super(Component.literal("电脑屏幕"));
        this.pos = pos;
        this.surface = surface;
        this.computer = ui;
        this.tv = null;
    }

    public DeviceFocusScreen(BlockPos pos, ScreenSurface surface, TvUi ui) {
        super(Component.literal("电视屏幕"));
        this.pos = pos;
        this.surface = surface;
        this.computer = null;
        this.tv = ui;
    }

    public BlockPos pos() {
        return this.pos;
    }

    @Override
    protected void init() {
        // 保留系统光标与窗口的正常状态：抓取窗口后部分平台上不再派发鼠标绝对坐标，
        // 虚拟机箭头会卡在原地。虚拟光标只跟随系统光标，不做任何独占。
        // 专注屏幕时收起快捷栏与准星，留出干净的第一人称视野看键盘和桌面。
        if (!this.minecraft.gui.hud.isHidden()) {
            this.minecraft.gui.hud.toggle();
            this.hidHud = true;
        }
        // 电脑和电视都固定到屏幕正前方，像坐在桌前：视线垂直屏幕，
        // 画面按窗口宽高比反算距离铺满视野，不再保留玩家原来的斜视角度。
        FocusView.focus(this.surface);
        this.ui().setLocalFocus(true);
        this.syncTextInput();
    }

    @Override
    public void removed() {
        if (this.textInput) {
            this.textInput = false;
            this.minecraft.textInputManager().stopTextInput(this);
        }
        if (this.hidHud && this.minecraft.gui.hud.isHidden()) {
            this.minecraft.gui.hud.toggle();
        }
        this.hidHud = false;
        FocusView.clear();
        this.ui().setLocalFocus(false);
    }

    @Override
    public void tick() {
        Minecraft client = this.minecraft;
        if (client == null || client.level == null || client.player == null) {
            return;
        }
        BlockState state = client.level.getBlockState(this.pos);
        boolean valid = this.tv != null
                ? state.getBlock() instanceof TvBlock && state.getValue(TvBlock.MASTER)
                : state.getBlock() instanceof ComputerBlock;
        if (!valid || client.player.distanceToSqr(Vec3.atCenterOf(this.pos)) > MAX_DISTANCE_SQR) {
            this.onClose();
            return;
        }
        this.syncTextInput();
    }

    /**
     * 26.3 改用 SDL 文字输入：只有界面显式开启后，系统才会把输入法/键盘的字符事件送来。
     * 屏幕内的输入框有焦点时开启，离开时关闭，这样中文输入法也能正常上屏。
     */
    private void syncTextInput() {
        boolean want = this.computer != null && this.ui().typing();
        if (want == this.textInput) {
            return;
        }
        this.textInput = want;
        if (want) {
            this.minecraft.textInputManager().startTextInput(this);
            int w = this.width;
            int h = this.height;
            this.minecraft.textInputManager().setTextInputArea(w / 2 - 64, h / 2 - 12, 128, 20);
        } else {
            this.minecraft.textInputManager().stopTextInput(this);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 保持世界原样：不模糊、不变暗，玩家能看到桌面和键盘。
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.updatePointer(mouseX, mouseY);
    }

    /**
     * 把界面坐标换算成屏幕画布像素。
     *
     * <p>射线直接用相机朝向和本帧视野角构造：{@code forward + left * x + up * y}。
     * 26.x 的投影矩阵由图形后端决定（深度可能是 -1..1 或 0..1、Y 轴方向也不同），
     * 反投影矩阵在 OpenGL / Vulkan 下不能共用，之前正是它让交点永远为 null、
     * 虚拟光标钉在画布中央。相机基向量与 {@code getFov()} 没有这些差异。</p>
     *
     * <p>交点落在屏幕轮廓之外时夹进画布边缘，射线与平面无交点时沿用上一次的坐标：
     * 虚拟机箭头因此始终留在画面里，点击也始终有明确落点。</p>
     */
    private double[] pointer(double mouseX, double mouseY) {
        double maxX = Math.max(0.0D, this.ui().width() - 1.0D);
        double maxY = Math.max(0.0D, this.ui().height() - 1.0D);
        double[] hit = pointerFor(this.minecraft, this.surface, this.width, this.height,
                this.ui().width(), this.ui().height(), mouseX, mouseY);
        // 画布像素的合法范围是 [0, width-1]×[0, height-1]：贴边时箭头仍然画得出来。
        if (hit != null) {
            this.lastPointerX = Mth.clamp(hit[0], 0.0D, maxX);
            this.lastPointerY = Mth.clamp(hit[1], 0.0D, maxY);
        }
        return this.holdPointer(maxX, maxY);
    }

    /** 把界面坐标射线投到屏幕平面，返回画布像素坐标；无交点返回 null。 */
    public static double[] pointerFor(Minecraft client, ScreenSurface surface, int guiWidth, int guiHeight,
                                      int canvasWidth, int canvasHeight, double mouseX, double mouseY) {
        Camera camera = client == null || client.gameRenderer == null
                ? null : client.gameRenderer.mainCamera();
        if (camera == null || guiWidth <= 0 || guiHeight <= 0) {
            return null;
        }
        double tanHalf = Math.tan(Math.toRadians(Math.max(1.0F, camera.getFov())) * 0.5D);
        double aspect = guiWidth / (double) guiHeight;
        // 界面坐标 → 视锥里的归一化坐标：右为 +1，上为 +1。
        double ndcX = mouseX / guiWidth * 2.0D - 1.0D;
        double ndcY = 1.0D - mouseY / guiHeight * 2.0D;
        Vec3 direction = new Vec3(camera.forwardVector())
                .add(new Vec3(camera.leftVector()).scale(-ndcX * tanHalf * aspect))
                .add(new Vec3(camera.upVector()).scale(ndcY * tanHalf))
                .normalize();
        return surface.raycast(camera.position(), direction, canvasWidth, canvasHeight);
    }

    /** 本帧没有有效交点时沿用上一次的位置；首次进入聚焦则放在画布中央。 */
    private double[] holdPointer(double maxX, double maxY) {
        if (this.lastPointerX < 0.0D) {
            this.lastPointerX = maxX * 0.5D;
            this.lastPointerY = maxY * 0.5D;
        }
        return new double[]{this.lastPointerX, this.lastPointerY};
    }

    private void updatePointer(double mouseX, double mouseY) {
        double[] hit = this.pointer(mouseX, mouseY);
        this.ui().mouseMove(hit[0], hit[1]);
    }

    private void movePointer(double mouseX, double mouseY) {
        double[] hit = this.pointer(mouseX, mouseY);
        if (this.computer != null) {
            this.computer.mouseDrag(hit[0], hit[1]);
        } else {
            this.tv.mouseMove(hit[0], hit[1]);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double[] hit = this.pointer(event.x(), event.y());
        boolean shift = event.hasShiftDown();
        // 26.3 用 SDL 的键号：1=左键、2=中键、3=右键；界面内部仍按 0 起算。
        int button = event.button() - 1;
        if (this.computer != null) {
            this.computer.mouseDown(hit[0], hit[1], button, shift);
        } else {
            this.tv.mouseDown(hit[0], hit[1], button, shift);
        }
        this.syncTextInput();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        this.movePointer(event.x(), event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.computer != null) {
            this.computer.mouseUp();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double[] hit = this.pointer(mouseX, mouseY);
        if (this.computer != null) {
            this.computer.scroll(hit[0], hit[1], scrollY);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean shift = event.hasShiftDown();
        // 先交给屏幕里的界面：IDE 会用 Esc 先退出它自己的输入焦点。
        boolean handled = this.ui().keyPressed(key, shift, event.hasControlDown());
        this.syncTextInput();
        if (handled) {
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if ((key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT) && !this.ui().typing()) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.ui().charTyped(event.codepoint());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Display ui() {
        return this.computer != null ? new ComputerDisplay(this.computer) : new TvDisplay(this.tv);
    }

    /** 两种设备共用的输入面；电视没有拖拽与打字。 */
    private interface Display {
        int width();

        int height();

        void setLocalFocus(boolean focused);

        void mouseMove(double x, double y);

        boolean typing();

        boolean keyPressed(int key, boolean shift, boolean ctrl);

        boolean charTyped(int codePoint);
    }

    private record ComputerDisplay(ComputerUi ui) implements Display {
        @Override
        public int width() {
            return this.ui.width();
        }

        @Override
        public int height() {
            return this.ui.height();
        }

        @Override
        public void setLocalFocus(boolean focused) {
            this.ui.setLocalFocus(focused);
        }

        @Override
        public void mouseMove(double x, double y) {
            this.ui.mouseMove(x, y);
        }

        @Override
        public boolean typing() {
            return this.ui.typing();
        }

        @Override
        public boolean keyPressed(int key, boolean shift, boolean ctrl) {
            return this.ui.keyPressed(key, shift, ctrl);
        }

        @Override
        public boolean charTyped(int codePoint) {
            return this.ui.charTyped(codePoint);
        }
    }

    private record TvDisplay(TvUi ui) implements Display {
        @Override
        public int width() {
            return this.ui.width();
        }

        @Override
        public int height() {
            return this.ui.height();
        }

        @Override
        public void setLocalFocus(boolean focused) {
            this.ui.setLocalFocus(focused);
        }

        @Override
        public void mouseMove(double x, double y) {
            this.ui.mouseMove(x, y);
        }

        @Override
        public boolean typing() {
            return false;
        }

        @Override
        public boolean keyPressed(int key, boolean shift, boolean ctrl) {
            return false;
        }

        @Override
        public boolean charTyped(int codePoint) {
            return false;
        }
    }
}
