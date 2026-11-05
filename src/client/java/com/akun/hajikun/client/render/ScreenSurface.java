package com.akun.hajikun.client.render;

import com.akun.hajikun.client.ui.ComputerUi;
import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.tv.TvKind;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 一块屏幕在世界里的位置：左上角原点、向右/向下两个单位方向以及法线。
 *
 * <p>渲染和射线拾取共用同一份几何，鼠标才能准确落在方块屏幕上。</p>
 */
public record ScreenSurface(Vec3 origin, Vec3 right, Vec3 down, Vec3 normal,
                            double width, double height) {
    /** 屏幕表面向观察者方向微移，避免与模型表面 z-fighting。 */
    private static final float SURFACE_EPSILON = 0.0025F;

    public static ScreenSurface computer(BlockPos pos, Direction facing, ComputerKind kind) {
        float[] origin = canvasOrigin(kind);
        float[] size = canvasSize(kind);
        return build(base(pos, localComputer(facing, kind)), origin[0], origin[1], origin[2], size[0], size[1]);
    }

    public static ScreenSurface tv(BlockPos masterPos, Direction facing, TvKind kind) {
        float[] origin = canvasOrigin(kind);
        float[] size = canvasSize(kind);
        return build(base(masterPos, localTv(facing)), origin[0], origin[1], origin[2], size[0], size[1]);
    }

    /** 画布左上角在方块局部坐标里的位置（未乘任何缩放）。 */
    public static float[] canvasOrigin(ComputerKind kind) {
        boolean laptop = kind == ComputerKind.LAPTOP;
        return new float[]{
                (laptop ? 14.4F : 13.7F) / 16.0F,
                (laptop ? 9.1F : 13.3F) / 16.0F,
                (laptop ? 10.6F : 8.6F) / 16.0F - SURFACE_EPSILON
        };
    }

    public static float[] canvasOrigin(TvKind kind) {
        float[] view = tvView(kind);
        return new float[]{
                kind.width() - view[0],
                kind.height() - view[1],
                13.55F / 16.0F - SURFACE_EPSILON
        };
    }

    public static float[] canvasSize(ComputerKind kind) {
        boolean laptop = kind == ComputerKind.LAPTOP;
        return new float[]{
                (laptop ? 12.8F : 11.4F) / 16.0F,
                (laptop ? 8.3F : 8.8F) / 16.0F
        };
    }

    public static float[] canvasSize(TvKind kind) {
        float[] view = tvView(kind);
        return new float[]{view[2], view[3]};
    }

    /** 电视面板的整块发光面尺寸（多格拼接后是一整块，不再留内部边框），单位是格。 */
    public static float[] tvFaceSize(TvKind kind) {
        return new float[]{kind.width(), kind.height()};
    }

    /**
     * 画面在电视面板上的矩形：强制采用电脑屏幕的宽高比并居中，
     * 面板比画面更宽/更高时，差额留给渲染器补黑边。
     */
    private static float[] tvView(TvKind kind) {
        float[] face = tvFaceSize(kind);
        float viewW = Math.min(face[0], face[1] * ComputerUi.ASPECT);
        float viewH = viewW / ComputerUi.ASPECT;
        return new float[]{(face[0] - viewW) / 2.0F, (face[1] - viewH) / 2.0F, viewW, viewH};
    }

    /** 把方块渲染器的姿态推到屏幕坐标系：调用后仍是方块局部坐标。 */
    public static void applyPose(PoseStack poseStack, Direction facing, ComputerKind kind) {
        poseStack.mulPose(localComputer(facing, kind));
    }

    public static void applyPose(PoseStack poseStack, Direction facing) {
        poseStack.mulPose(localTv(facing));
    }

    private static Matrix4f localComputer(Direction facing, ComputerKind kind) {
        Matrix4f matrix = localTv(facing);
        if (kind == ComputerKind.LAPTOP) {
            tilt(matrix);
        }
        return matrix;
    }

    private static Matrix4f localTv(Direction facing) {
        float angle = switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
        Matrix4f matrix = new Matrix4f();
        matrix.translate(0.5F, 0.5F, 0.5F);
        matrix.rotateY((float) Math.toRadians(-angle));
        matrix.translate(-0.5F, -0.5F, -0.5F);
        return matrix;
    }

    private static Matrix4f base(BlockPos pos, Matrix4f local) {
        Matrix4f matrix = new Matrix4f();
        matrix.translate(pos.getX(), pos.getY(), pos.getZ());
        matrix.mul(local);
        return matrix;
    }

    /** 笔记本屏幕绕转轴后仰 15°，和方块模型里的旋转保持一致。 */
    private static void tilt(Matrix4f matrix) {
        matrix.translate(0.5F, 0.05F, 0.6875F);
        matrix.rotateX((float) Math.toRadians(15.0F));
        matrix.translate(-0.5F, -0.05F, -0.6875F);
    }

    private static ScreenSurface build(Matrix4f matrix, float xTopLeft, float yTopLeft, float z,
                                       float width, float height) {
        Vector3f origin = new Vector3f(xTopLeft, yTopLeft, z);
        Vector3f right = new Vector3f(-1.0F, 0.0F, 0.0F);
        Vector3f down = new Vector3f(0.0F, -1.0F, 0.0F);
        matrix.transformPosition(origin);
        matrix.transformDirection(right);
        matrix.transformDirection(down);
        right.mul((float) width);
        down.mul((float) height);
        Vec3 o = new Vec3(origin);
        Vec3 r = new Vec3(right);
        Vec3 d = new Vec3(down);
        Vec3 normal = d.cross(r).normalize();
        return new ScreenSurface(o, r, d, normal, width, height);
    }

    /** 屏幕坐标（0..1）对应的世界点。 */
    public Vec3 at(double u, double v) {
        return this.origin.add(this.right.scale(u)).add(this.down.scale(v));
    }

    /** 射线与屏幕平面求交，返回 canvas 像素坐标；未命中返回 null。 */
    public double[] raycast(Vec3 from, Vec3 direction, int canvasWidth, int canvasHeight) {
        double denominator = direction.dot(this.normal);
        if (Math.abs(denominator) < 1.0E-6D) {
            return null;
        }
        double distance = this.origin.subtract(from).dot(this.normal) / denominator;
        if (distance <= 0.0D) {
            return null;
        }
        Vec3 hit = from.add(direction.scale(distance));
        Vec3 offset = hit.subtract(this.origin);
        double u = offset.dot(this.right) / this.right.lengthSqr();
        double v = offset.dot(this.down) / this.down.lengthSqr();
        return new double[]{u * canvasWidth, v * canvasHeight};
    }
}
