package com.akun.hajikun.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 聚焦电脑时把相机固定到屏幕正前方，像坐在桌前看显示器。
 *
 * <p>屏幕本身仍然由方块渲染器画在显示器表面，这里只移动相机：
 * 眼位放在屏幕中心沿法线前移，视线垂直屏幕。距离按当前视野角与窗口宽高比
 * 反算，保证整块显示器都落在视野里；玩家的 FOV 设置很小（窄视野）时会自动退远，
 * 否则 320 像素宽的 IDE 右侧智能体面板会被屏幕边缘裁掉。</p>
 */
public final class FocusView {
    private static final long EASE_MS = 220L;
    /**
     * 屏幕占视野较小一边的比例。早先铺到 0.86 会让 320 像素宽的界面被拉成
     * 满屏巨字，既糊又费性能；0.62 更像一台摆在桌上的显示器，文字密度正常。
     */
    private static final double FILL = 0.62D;
    private static final double MIN_DISTANCE = 0.42D;

    private static ScreenSurface surface;
    private static Vec3 targetPos;
    private static float targetYaw;
    private static float targetPitch;

    private static long startedAt;
    private static Vec3 fromPos;
    private static float fromYaw;
    private static float fromPitch;

    private FocusView() {
    }

    /** 记录目标屏幕；同一块屏幕重复调用不会重新播放推近动画。 */
    public static void focus(ScreenSurface target) {
        if (target == null) {
            return;
        }
        if (surface != null && same(surface, target)) {
            return;
        }
        surface = target;
        startedAt = 0L;
        updateTarget();
    }

    /** 每帧刷新机位：窗口大小或 FOV 变化后，屏幕仍然完整落在视野里。 */
    private static void updateTarget() {
        if (surface == null) {
            return;
        }
        Vec3 normal = surface.normal();
        Vec3 up = surface.down().scale(-1.0D).normalize();
        Vec3 center = surface.at(0.5D, 0.5D);
        double distance = fitDistance();
        targetPos = center.add(normal.scale(distance)).add(up.scale(0.012D));
        Vec3 forward = normal.scale(-1.0D);
        targetYaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        targetPitch = (float) -Math.toDegrees(Math.asin(clamp(forward.y)));
    }

    /** 让屏幕的宽和高都不超过视野的 {@link #FILL}，返回相机到屏幕的距离。 */
    private static double fitDistance() {
        double fov = 70.0D;
        double aspect = 1.6D;
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            fov = client.gameRenderer.mainCamera().getFov();
            int w = client.getWindow().getScreenWidth();
            int h = client.getWindow().getScreenHeight();
            if (w > 0 && h > 0) {
                aspect = w / (double) h;
            }
        }
        double half = Math.tan(Math.toRadians(Math.max(10.0D, fov) * 0.5D));
        double byHeight = surface.height() / FILL / (2.0D * half);
        double byWidth = surface.width() / FILL / (2.0D * half * aspect);
        return Math.max(MIN_DISTANCE, Math.max(byHeight, byWidth));
    }

    public static void clear() {
        surface = null;
        startedAt = 0L;
    }

    /** 相机每帧更新末尾调用；返回本帧要覆盖的位置与角度，未聚焦时为 null。 */
    public static Pose current(Camera camera) {
        if (surface == null) {
            return null;
        }
        updateTarget();
        if (startedAt == 0L) {
            fromPos = camera.position();
            fromYaw = camera.yRot();
            fromPitch = camera.xRot();
            startedAt = System.currentTimeMillis();
            if (!camera.isInitialized() || fromPos.lengthSqr() < 1.0E-6D) {
                // 首帧相机还没就位时直接给目标姿态，避免从原点飞过来。
                fromPos = targetPos;
                fromYaw = targetYaw;
                fromPitch = targetPitch;
            }
        }
        float t = Math.min(1.0F, (System.currentTimeMillis() - startedAt) / (float) EASE_MS);
        t = t * t * (3.0F - 2.0F * t);
        Vec3 position = fromPos.add(targetPos.subtract(fromPos).scale(t));
        float yaw = fromYaw + wrap(targetYaw - fromYaw) * t;
        float pitch = fromPitch + (targetPitch - fromPitch) * t;
        return new Pose(position, yaw, pitch);
    }

    private static boolean same(ScreenSurface a, ScreenSurface b) {
        return a.origin().distanceToSqr(b.origin()) < 1.0E-6D
                && a.normal().dot(b.normal()) > 0.999D
                && Math.abs(a.height() - b.height()) < 1.0E-4D;
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

    private static double clamp(double value) {
        return Math.max(-1.0D, Math.min(1.0D, value));
    }

    /** 一帧的固定机位姿态。 */
    public record Pose(Vec3 position, float yaw, float pitch) {
    }
}
