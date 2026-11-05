package com.akun.hajikun.client.camera;

import com.akun.hajikun.camera.TripodBlock;
import com.akun.hajikun.client.render.FocusView;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 取景器的相机状态：变焦、前后镜头、三脚架固定机位与方向微调。
 *
 * <p>手持相机默认沿用玩家第一人称视角，只通过 {@code getFov} 改动视场角；
 * 前置镜头临时切到原版第三人称前视，这样能真正看到自己并保留照相动作。
 * 装在三脚架上时完全接管眼位与朝向，玩家走动也不会改变机位。</p>
 */
public final class CameraState {
    private static boolean active;
    private static BlockPos tripodPos;
    private static Direction facing = Direction.NORTH;
    /** 滚轮给出的目标倍率；画面实际倍率由 zoomShown 平滑追过去。 */
    private static float zoom = 1.0F;
    private static float zoomShown = 1.0F;
    private static long zoomStamp = System.nanoTime();
    private static volatile boolean fill;
    private static boolean front;
    private static float yawOffset;
    private static float pitchOffset;
    private static CameraType previousType = CameraType.FIRST_PERSON;
    private static boolean typeChanged;

    private CameraState() {
    }

    public static void activateHeld() {
        reset();
        active = true;
        tripodPos = null;
        previousType = Minecraft.getInstance().options.getCameraType();
        typeChanged = false;
    }

    public static void activateTripod(BlockPos pos, Direction direction) {
        reset();
        active = true;
        tripodPos = pos.immutable();
        facing = direction == null ? Direction.NORTH : direction;
        previousType = Minecraft.getInstance().options.getCameraType();
        typeChanged = false;
    }

    public static void clear() {
        restoreCameraType();
        reset();
    }

    private static void reset() {
        active = false;
        tripodPos = null;
        facing = Direction.NORTH;
        zoom = 1.0F;
        zoomShown = 1.0F;
        zoomStamp = System.nanoTime();
        fill = false;
        front = false;
        yawOffset = 0.0F;
        pitchOffset = 0.0F;
        typeChanged = false;
    }

    public static boolean active() {
        return active;
    }

    public static boolean tripod() {
        return active && tripodPos != null;
    }

    public static BlockPos tripodPos() {
        return tripodPos;
    }

    /**
     * 本帧实际使用的倍率。
     *
     * <p>滚轮只改目标值，这里按时间做指数逼近（约 0.2 秒收拢），
     * 投影矩阵因此每帧都在变，画面是连续拉近而不是一格一格跳。
     * 渲染线程每帧都会调用本方法两到三次，用真实时间差推进，
     * 所以帧率高时步长更细，帧率低时步长自动变大。</p>
     */
    public static float zoom() {
        if (!active) {
            return 1.0F;
        }
        long now = System.nanoTime();
        float dt = (now - zoomStamp) / 1_000_000_000.0F;
        zoomStamp = now;
        if (dt <= 0.0F) {
            return zoomShown;
        }
        dt = Math.min(dt, 0.1F);
        float alpha = 1.0F - (float) Math.exp(-dt / 0.075F);
        zoomShown += (zoom - zoomShown) * alpha;
        if (Math.abs(zoom - zoomShown) < 0.002F) {
            zoomShown = zoom;
        }
        return zoomShown;
    }

    /** 补光灯是否打开；拍照与录像按这个状态提亮画面。 */
    public static boolean fill() {
        return active && fill;
    }

    public static void toggleFill() {
        if (active) {
            fill = !fill;
        }
    }

    public static boolean front() {
        return front;
    }

    public static float yawOffset() {
        return yawOffset;
    }

    public static float pitchOffset() {
        return pitchOffset;
    }

    /** 前置镜头由原版第三人称前视负责，只有三脚架和手持微调需要覆盖姿态。 */
    public static boolean selfie() {
        return active && tripodPos == null && front;
    }

    public static void zoomBy(double notches) {
        if (!active) {
            return;
        }
        float next = (float) (zoom * Math.pow(1.18D, notches));
        zoom = Math.max(0.5F, Math.min(5.0F, next));
    }

    public static void setZoom(float value) {
        zoom = Math.max(0.5F, Math.min(5.0F, value));
        zoomShown = zoom;
        zoomStamp = System.nanoTime();
    }

    public static void toggleFront() {
        if (!active) {
            return;
        }
        front = !front;
        if (tripodPos == null) {
            applyCameraType();
        }
    }

    public static void turn(float yaw, float pitch) {
        if (!active) {
            return;
        }
        yawOffset = wrap(yawOffset + yaw);
        pitchOffset = Math.max(-85.0F, Math.min(85.0F, pitchOffset + pitch));
    }

    private static void applyCameraType() {
        Minecraft client = Minecraft.getInstance();
        if (front) {
            if (!typeChanged) {
                previousType = client.options.getCameraType();
                typeChanged = true;
            }
            client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        } else {
            client.options.setCameraType(previousType == CameraType.THIRD_PERSON_FRONT
                    ? CameraType.FIRST_PERSON : previousType);
        }
    }

    private static void restoreCameraType() {
        if (typeChanged) {
            typeChanged = false;
            Minecraft client = Minecraft.getInstance();
            client.options.setCameraType(previousType == CameraType.THIRD_PERSON_FRONT
                    ? CameraType.FIRST_PERSON : previousType);
        }
    }

    /** 相机每帧更新末尾调用；返回本帧要覆盖的位姿，未取景时为 null。 */
    public static FocusView.Pose pose(Camera camera) {
        if (!active) {
            return null;
        }
        if (tripodPos != null) {
            Vec3 position = TripodBlock.viewPosition(tripodPos, facing);
            float yaw = yawOf(facing) + yawOffset + (front ? 180.0F : 0.0F);
            return new FocusView.Pose(position, wrap(yaw), pitchOffset);
        }
        if (front) {
            // 原版第三人称前视已经把相机放到脸前并朝后，不需要再覆盖。
            return null;
        }
        if (Math.abs(yawOffset) < 0.01F && Math.abs(pitchOffset) < 0.01F) {
            return null;
        }
        return new FocusView.Pose(camera.position(),
                wrap(camera.yRot() + yawOffset),
                Math.max(-89.0F, Math.min(89.0F, camera.xRot() + pitchOffset)));
    }

    /** 与方块朝向一致的水平角：北=180°，东=-90°，南=0°，西=90°。 */
    public static float yawOf(Direction direction) {
        return switch (direction) {
            case EAST -> -90.0F;
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            default -> 180.0F;
        };
    }

    private static float wrap(float degrees) {
        float value = degrees;
        while (value > 180.0F) {
            value -= 360.0F;
        }
        while (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }
}
