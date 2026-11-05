package com.akun.hajikun.client.camera;

import com.akun.hajikun.camera.CameraItem;
import com.akun.hajikun.camera.TripodBlock;
import com.akun.hajikun.client.media.CameraSoundTrack;
import com.akun.hajikun.client.media.HkvVideo;
import com.akun.hajikun.client.screen.CameraRollScreen;
import com.akun.hajikun.network.SmartPayloads.CameraFillC2S;
import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 照相机拍摄模式：不打开任何 Screen，因此玩家仍然能自由走动、自由转视角。
 *
 * <p>取景框和状态由 Fabric HUD 画在最后一层；键盘与鼠标按键由客户端 Mixin
 * 在进入游戏逻辑前截获：左键快门、Q 前后镜头、E 拍照/录像、滚轮变焦、
 * F 打开胶卷列表、Esc 退出。照片与录像帧在主画面绘制 GUI 之前抓取，
 * 因此成品里不会出现 HUD、准星或取景框。</p>
 *
 * <p>录像的现场声在 {@code SoundEngine.play} 处记录成事件轨，随 .hkv 保存，
 * 回放时重新触发，见 {@link CameraSoundTrack}。</p>
 */
public final class CameraMode {
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_FPS = 15;
    private static final float VIDEO_QUALITY = 0.94F;
    private static final long FRAME_INTERVAL_MS = 1000L / VIDEO_FPS;
    private static final int MAX_FRAMES = 2400;
    private static final long MAX_VIDEO_BYTES = 80L * 1024L * 1024L;
    private static final double MAX_DISTANCE_SQR = 64.0D * 64.0D;
    private static final double SOUND_RANGE = 48.0D;
    private static final int MAX_SOUND_EVENTS = 2048;

    /**
     * 跨线程状态：抓帧线程读写这些标记，渲染线程每 tick 读取，
     * 必须 volatile 才能及时看到「上一帧已经处理完」。
     */
    private static volatile boolean active;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static BlockPos tripodPos;
    private static boolean videoMode;
    private static volatile boolean recording;
    private static volatile boolean autoStop;

    private static boolean photoPending;
    private static boolean framePending;
    private static volatile boolean photoInFlight;
    private static volatile boolean frameInFlight;
    private static int photosTaken;
    private static long flashUntil;
    private static long recordStartedAt;
    private static long lastFrameAt;
    private static int videoWidth;
    private static int videoHeight;
    private static final List<byte[]> FRAMES = new ArrayList<>();
    private static long frameBytes;
    private static final List<CameraSoundTrack.Event> SOUNDS = new ArrayList<>();

    private static String toastText = "";
    private static long toastUntil;

    private CameraMode() {
    }

    public static boolean active() {
        return active;
    }

    public static void startHeld(InteractionHand targetHand) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !(client.player.getItemInHand(targetHand).getItem() instanceof CameraItem)) {
            return;
        }
        if (active && tripodPos == null && hand == targetHand) {
            // 按住右键时原版每 4 tick 会再调一次 use；已经在取景就只清键位，不重开。
            releaseUseKey();
            return;
        }
        begin();
        hand = targetHand;
        tripodPos = null;
        CameraState.activateHeld();
        toast("拍摄模式 · 左键快门 · 右键补光 · E 拍照/录像 · F 胶卷 · Esc 退出");
    }

    public static void startTripod(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || pos == null) {
            return;
        }
        BlockState state = client.level.getBlockState(pos);
        if (!(state.getBlock() instanceof TripodBlock)
                || !state.hasProperty(TripodBlock.MOUNTED)
                || !state.getValue(TripodBlock.MOUNTED)) {
            return;
        }
        if (active && pos.equals(tripodPos)) {
            releaseUseKey();
            return;
        }
        begin();
        tripodPos = pos.immutable();
        Direction facing = state.hasProperty(TripodBlock.FACING)
                ? state.getValue(TripodBlock.FACING) : Direction.NORTH;
        CameraState.activateTripod(tripodPos, facing);
        toast("三脚架定点拍摄 · 方向键微调视角 · Esc 退出");
    }

    private static void begin() {
        if (active) {
            exit();
        }
        active = true;
        recording = false;
        autoStop = false;
        videoMode = false;
        photoPending = false;
        framePending = false;
        photoInFlight = false;
        frameInFlight = false;
        photosTaken = 0;
        videoWidth = VIDEO_WIDTH;
        videoHeight = 0;
        frameBytes = 0L;
        synchronized (FRAMES) {
            FRAMES.clear();
        }
        synchronized (SOUNDS) {
            SOUNDS.clear();
        }
        // 进入取景的那次右键不会被拦截（当时还没激活），原版已经把 Use 记成按住；
        // 这里立刻放开，否则每 4 tick 会再 use 一次，把取景反复重开。
        releaseUseKey();
    }

    /**
     * 把右鼠标键的「按下」状态放回抬起。
     *
     * <p>原版在 {@code MouseHandler.onButton} 里用这次事件维护
     * {@code isRightPressed} 与按键绑定；取景模式只吞按下、放行松开，
     * 但触发进入的按下本身已经落到原版手里，所以激活后要主动清一次。</p>
     */
    private static void releaseUseKey() {
        KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(3), false);
    }

    /** Esc、断开连接、相机被移走时调用；正在录制的内容会先落盘。 */
    public static void exit() {
        if (!active) {
            return;
        }
        if (recording) {
            stopRecording(true);
        }
        CameraState.clear();
        sendFillOff();
        active = false;
        tripodPos = null;
        photoPending = false;
        framePending = false;
        photoInFlight = false;
        frameInFlight = false;
        synchronized (FRAMES) {
            FRAMES.clear();
        }
        synchronized (SOUNDS) {
            SOUNDS.clear();
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof CameraRollScreen) {
            client.setScreenAndShow(null);
        }
    }

    /** 把补光灯状态同步给服务端；服务端按取景位置放一盏跟随的原版光源。 */
    private static void syncFill() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new CameraFillC2S(CameraState.fill(), tripodPos != null,
                tripodPos == null ? BlockPos.ZERO : tripodPos));
    }

    private static void sendFillOff() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new CameraFillC2S(false, false, BlockPos.ZERO));
    }

    /** 客户端每 tick 调一次：校验相机仍在，推进录像采帧。 */
    public static void tick() {
        if (!active) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            exit();
            return;
        }
        if (client.gui.screen() != null && !(client.gui.screen() instanceof CameraRollScreen)) {
            exit();
            return;
        }
        if (tripodPos != null) {
            BlockState state = client.level.getBlockState(tripodPos);
            if (!(state.getBlock() instanceof TripodBlock)
                    || !state.hasProperty(TripodBlock.MOUNTED)
                    || !state.getValue(TripodBlock.MOUNTED)
                    || client.player.distanceToSqr(tripodPos.getX() + 0.5D, tripodPos.getY() + 0.5D,
                    tripodPos.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
                toast("三脚架或照相机已被移除，拍摄任务中断");
                exit();
                return;
            }
        } else if (!(client.player.getItemInHand(hand).getItem() instanceof CameraItem)) {
            toast("照相机已不在手上，拍摄任务中断");
            exit();
            return;
        }
        if (!recording) {
            return;
        }
        if (autoStop) {
            stopRecording(true);
            return;
        }
        long now = System.currentTimeMillis();
        if (!frameInFlight && now - lastFrameAt >= FRAME_INTERVAL_MS) {
            lastFrameAt = now;
            framePending = true;
        }
    }

    /**
     * 键盘按下/重复。返回 true 表示已经消费，Mixin 会取消原版处理，
     * 从而不会打开背包、丢物品、切副手或弹出暂停菜单。
     */
    public static boolean handleKey(int action, int key) {
        if (!active || action < 1 || Minecraft.getInstance().gui.screen() != null) {
            return false;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            exit();
            return true;
        }
        if (action > 1) {
            // 方向键允许长按连续微调；其它按键只在首次按下触发。
            if (tripodPos != null) {
                return turnByKey(key);
            }
            return false;
        }
        if (key == InputConstants.KEY_Q) {
            CameraState.toggleFront();
            toast(CameraState.front() ? "前置镜头（自拍）" : "后置镜头");
            return true;
        }
        if (key == InputConstants.KEY_E) {
            toggleVideoMode();
            return true;
        }
        if (key == InputConstants.KEY_F) {
            openRoll();
            return true;
        }
        if (tripodPos != null) {
            return turnByKey(key);
        }
        return false;
    }

    /** 鼠标按键；SDLB 左键为 1、右键为 3。返回 true 时取消原版的攻击/使用。 */
    public static boolean handleMouseButton(int button, int action) {
        if (!active || Minecraft.getInstance().gui.screen() != null) {
            return false;
        }
        if (action != 1) {
            // 松开必须放行：原版靠它把 isRightPressed 与 Use/Attack 键位置回抬起。
            // 以前按下和松开一起吞掉，右键永远停在“按住”，每 4 tick 再 use 一次，
            // Esc 退出后立刻又被重新拉回取景，看起来就是死循环。
            return false;
        }
        if (button == 3) {
            // 右（键位编号 3）= 补光灯开关；目标只是被拍对象，不碰物品使用。
            CameraState.toggleFill();
            syncFill();
            toast(CameraState.fill() ? "补光灯 已打开 · 镜头位置亮起原生光源" : "补光灯 已关闭");
        } else if (button == 1) {
            if (videoMode) {
                toggleRecording();
            } else {
                takePhoto();
            }
        }
        // 按下时左右键都不应该挖方块、攻击或使用物品。
        return true;
    }

    public static boolean handleScroll(double amount) {
        if (!active || Minecraft.getInstance().gui.screen() != null) {
            return false;
        }
        CameraState.zoomBy(amount);
        return true;
    }

    /** 在主画面开始绘制 GUI 前抓帧，保证照片/录像里只有世界。 */
    public static void beforeGui() {
        if (!active || (photoInFlight || frameInFlight)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null) {
            return;
        }
        if (photoPending) {
            photoPending = false;
            photoInFlight = true;
            flashUntil = System.currentTimeMillis() + 170L;
            capture(client, true);
        } else if (framePending) {
            framePending = false;
            frameInFlight = true;
            capture(client, false);
        }
    }

    private static void capture(Minecraft client, boolean photo) {
        var target = client.gameRenderer.mainRenderTarget();
        try {
            Screenshot.takeScreenshot(target, photo ? 1 : videoDownscale(target), image -> {
                boolean onTripod = tripodPos != null;
                BlockPos pos = onTripod ? tripodPos : BlockPos.ZERO;
                Thread worker = new Thread(() -> {
                    int width;
                    int height;
                    int[] pixels;
                    try {
                        // 整屏像素的读取、缩放和 JPEG 编码都留在后台线程：
                        // 2560×1600 的一帧有 16 MB，在渲染线程上拷贝会让走动、
                        // 转视角和滚轮变焦一起顿一下，录像时会每隔 125 ms 顿一次。
                        width = image.getWidth();
                        height = image.getHeight();
                        pixels = image.getPixelsABGR();
                    } finally {
                        image.close();
                    }
                    if (!active) {
                        if (photo) {
                            photoInFlight = false;
                        } else {
                            frameInFlight = false;
                        }
                        return;
                    }
                    if (photo) {
                        photoInFlight = false;
                    } else {
                        frameInFlight = false;
                    }
                    if (photo) {
                        // 照片保留整屏原始像素，无损 PNG：不缩放、不丢细节。
                        // 补光是世界里的真实方块光，抓帧时已经照亮场景，不再做后期提亮。
                        BufferedImage result = CameraFrames.fromAbgr(pixels, width, height);
                        byte[] png = CameraFrames.png(result);
                        if (png != null && active) {
                            CameraFrames.upload(onTripod, pos, CameraFrames.name("IMG", ".png"), "image/png", png);
                        }
                        return;
                    }
                    BufferedImage result = CameraFrames.sample(pixels, width, height, VIDEO_WIDTH, 0);
                    byte[] jpeg = CameraFrames.jpeg(result, VIDEO_QUALITY);
                    if (jpeg == null) {
                        return;
                    }
                    synchronized (FRAMES) {
                        if (!recording) {
                            return;
                        }
                        if (videoHeight == 0) {
                            videoWidth = result.getWidth();
                            videoHeight = result.getHeight();
                        }
                        FRAMES.add(jpeg);
                        frameBytes += jpeg.length;
                        if (FRAMES.size() >= MAX_FRAMES || frameBytes >= MAX_VIDEO_BYTES) {
                            autoStop = true;
                        }
                    }
                }, photo ? "hajikun-camera-photo" : "hajikun-camera-frame");
                worker.setDaemon(true);
                worker.start();
            });
        } catch (RuntimeException unavailable) {
            // 窗口正在缩放、主帧缓冲还没成形时截图会直接抛异常；
            // 取景器不能因此结束录像或崩溃，下一 tick 再试。
            if (photo) {
                photoInFlight = false;
                photoPending = false;
            } else {
                frameInFlight = false;
                framePending = false;
            }
        }
    }

    /**
     * 录像帧的抓屏降采样倍数。
     *
     * <p>26.3 的截图接口要求宽高都能被倍数整除，否则直接抛
     * {@link IllegalArgumentException}。这里挑同时整除宽高、且读回宽度仍不小于
     * 录像宽度（640）的最大整数倍：2560×1600 读回 640×400，再区域平均到 640 宽，
     * 画质不降，拷贝量只剩 1/16；1920×1080 读回 640×360。</p>
     */
    private static int videoDownscale(com.mojang.blaze3d.pipeline.RenderTarget target) {
        if (target == null || target.width <= 0 || target.height <= 0) {
            return 1;
        }
        int factor = 1;
        for (int each = 2; each <= 8; each++) {
            if (target.width % each == 0 && target.height % each == 0
                    && target.width / each >= VIDEO_WIDTH) {
                factor = each;
            }
        }
        return factor;
    }

    private static boolean turnByKey(int key) {
        float yaw = 0.0F;
        float pitch = 0.0F;
        if (key == InputConstants.KEY_LEFT) {
            yaw += 1.2F;
        } else if (key == InputConstants.KEY_RIGHT) {
            yaw -= 1.2F;
        } else if (key == InputConstants.KEY_UP) {
            pitch -= 0.9F;
        } else if (key == InputConstants.KEY_DOWN) {
            pitch += 0.9F;
        } else {
            return false;
        }
        CameraState.turn(yaw, pitch);
        return true;
    }

    private static void toggleVideoMode() {
        if (recording) {
            stopRecording(true);
        }
        videoMode = !videoMode;
        toast(videoMode ? "录像模式 · 左键开始/停止 · 录像含现场声" : "拍照模式 · 左键快门");
    }

    private static void takePhoto() {
        if (recording) {
            toast("录像中不能拍照，先左键停止录像");
            return;
        }
        if (photoPending || photoInFlight) {
            return;
        }
        photoPending = true;
        photosTaken++;
        toast("快门 · 第 " + photosTaken + " 张");
    }

    private static void toggleRecording() {
        if (recording) {
            stopRecording(true);
            return;
        }
        synchronized (FRAMES) {
            FRAMES.clear();
        }
        synchronized (SOUNDS) {
            SOUNDS.clear();
        }
        frameBytes = 0L;
        recording = true;
        autoStop = false;
        recordStartedAt = System.currentTimeMillis();
        lastFrameAt = recordStartedAt;
        videoWidth = VIDEO_WIDTH;
        videoHeight = 0;
        toast("开始录像 · 现场声已开启");
    }

    /** save 为 false 时丢弃缓冲帧；拆支架或断开连接会走中断路径。 */
    private static void stopRecording(boolean save) {
        if (!recording) {
            return;
        }
        recording = false;
        autoStop = false;
        List<byte[]> copy;
        synchronized (FRAMES) {
            copy = new ArrayList<>(FRAMES);
            FRAMES.clear();
        }
        List<CameraSoundTrack.Event> sounds;
        synchronized (SOUNDS) {
            sounds = new ArrayList<>(SOUNDS);
            SOUNDS.clear();
        }
        frameBytes = 0L;
        if (!save) {
            toast("拍摄任务中断，未完成的录像已丢弃");
            return;
        }
        if (copy.size() < 2) {
            toast("录像太短，未保存");
            return;
        }
        int width = Math.max(2, videoWidth);
        int height = Math.max(2, videoHeight);
        boolean onTripod = tripodPos != null;
        BlockPos pos = onTripod ? tripodPos : BlockPos.ZERO;
        byte[] soundTrack = CameraSoundTrack.encode(sounds);
        Thread worker = new Thread(() -> {
            byte[] video = HkvVideo.write(width, height, VIDEO_FPS, copy, soundTrack);
            if (video != null) {
                CameraFrames.upload(onTripod, pos, CameraFrames.name("VID", ".hkv"), "video/hkv", video);
            }
        }, "hajikun-camera-video");
        worker.setDaemon(true);
        worker.start();
        toast("录像已保存：" + copy.size() + " 帧 · " + VIDEO_FPS + " fps · " + sounds.size() + " 个现场声");
    }

    private static void openRoll() {
        if (recording) {
            toast("录像中不能查看胶卷，先左键停止录像");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        boolean onTripod = tripodPos != null;
        client.setScreenAndShow(new CameraRollScreen(onTripod, onTripod ? tripodPos : BlockPos.ZERO));
    }

    /**
     * 由 {@code SoundEngine.play} 的 Mixin 调用：只记录取景器 48 格内、
     * 正在录制时的真实游戏音效。UI、音乐、唱片和循环环境声不进入音轨，
     * 避免回放时出现按钮声或永不停止的雨声。
     *
     * <p>录像只是附加功能，任何音效异常都不允许打断游戏：资源包缺音效时
     * {@code getVolume()} 会在尚未解析到音频实例时抛空指针（蜜滴粒子就会），
     * 这里整段兜住，遇到就跳过这一条。</p>
     */
    public static void onSound(SoundInstance instance) {
        try {
            recordSound(instance);
        } catch (RuntimeException ignored) {
        }
    }

    private static void recordSound(SoundInstance instance) {
        if (!active || !recording || instance == null) {
            return;
        }
        SoundSource source = instance.getSource();
        if (source == SoundSource.MASTER || source == SoundSource.MUSIC
                || source == SoundSource.RECORDS || source == SoundSource.UI
                || instance.isRelative() || instance.isLooping()) {
            return;
        }
        float volume = instance.getVolume();
        float pitch = instance.getPitch();
        if (!(volume > 0.01F) || !(pitch > 0.01F) || pitch > 8.0F) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        Vec3 anchor = tripodPos != null
                ? client.gameRenderer.mainCamera().position() : client.player.getEyePosition();
        double dx = instance.getX() - anchor.x;
        double dy = instance.getY() - anchor.y;
        double dz = instance.getZ() - anchor.z;
        if (dx * dx + dy * dy + dz * dz > SOUND_RANGE * SOUND_RANGE) {
            return;
        }
        synchronized (SOUNDS) {
            if (SOUNDS.size() >= MAX_SOUND_EVENTS) {
                return;
            }
            SOUNDS.add(new CameraSoundTrack.Event(
                    (int) Math.max(0L, System.currentTimeMillis() - recordStartedAt),
                    instance.getIdentifier().toString(), source.getName(), volume, pitch,
                    (float) dx, (float) dy, (float) dz));
        }
    }

    /** Fabric HUD 最后一层：取景框、状态、REC、提示与闪光。 */
    public static void extractHud(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
        if (!active) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.gui.screen() != null) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int white = 0xFFFFFFFF;
        int gray = 0xFFBFD3E6;
        int green = 0xFF7CFF9B;
        int red = 0xFFFF5A5A;
        graphics.fill(0, 0, width, 28, 0x8F05080D);
        graphics.fill(0, height - 34, width, height, 0x8F05080D);

        String lens = CameraState.front() ? "前置 · 自拍" : "后置";
        String source = tripodPos != null ? "三脚架" : "手持";
        String mode = videoMode ? (recording ? "录像中" : "录像待机") : "拍照";
        String fillState = CameraState.fill() ? "补光 开" : "补光 关";
        String line = String.format(Locale.ROOT, "照相机  %s  %s  %s  %s  %.1f×  已拍 %d",
                lens, source, mode, fillState, CameraState.zoom(), photosTaken);
        graphics.text(client.font, line, 12, 10, white);

        if (recording) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - recordStartedAt);
            String clock = String.format(Locale.ROOT, "%02d:%02d", elapsed / 60000L, (elapsed / 1000L) % 60L);
            int rx = width - 112;
            graphics.fill(rx, 9, rx + 10, 19, red);
            graphics.text(client.font, "REC " + clock, rx + 16, 10, red);
            graphics.outline(3, 3, width - 3, height - 3, 0x99FF3B3B);
        } else {
            String zoom = String.format(Locale.ROOT, "%.1f×", CameraState.zoom());
            graphics.text(client.font, zoom, width - 12 - client.font.width(zoom), 10, gray);
        }

        // 取景框四角与中央准星。
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
        int cx = width / 2;
        int cy = height / 2;
        graphics.fill(cx - 9, cy, cx - 3, cy + 1, 0xCCFFFFFF);
        graphics.fill(cx + 3, cy, cx + 9, cy + 1, 0xCCFFFFFF);
        graphics.fill(cx, cy - 9, cx + 1, cy - 3, 0xCCFFFFFF);
        graphics.fill(cx, cy + 3, cx + 1, cy + 9, 0xCCFFFFFF);

        String hints = (tripodPos != null ? "方向键 微调 · " : "")
                + "左键 " + (videoMode ? "开始/停止录像" : "快门")
                + " · 右键 补光 · Q 前后 · E 拍照/录像 · 滚轮 变焦 · F 胶卷 · Esc 退出";
        graphics.text(client.font, hints, 12, height - 24, gray);

        if (System.currentTimeMillis() < toastUntil && !toastText.isBlank()) {
            int tw = client.font.width(toastText);
            int x = (width - tw) / 2;
            graphics.fill(x - 8, height - 58, x + tw + 8, height - 42, 0xB005080D);
            graphics.text(client.font, toastText, x, height - 54, green);
        }

        long now = System.currentTimeMillis();
        if (now < flashUntil) {
            int remain = (int) Math.max(0L, flashUntil - now);
            int alpha = (int) (200L * remain / 170L);
            graphics.fill(0, 0, width, height, (alpha << 24) | 0x00FFFFFF);
        }
    }

    private static void toast(String message) {
        toastText = message == null ? "" : message;
        toastUntil = System.currentTimeMillis() + 2400L;
    }
}
