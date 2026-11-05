package com.akun.hajikun.mixin;

import com.akun.hajikun.client.camera.CameraState;
import com.akun.hajikun.client.render.FocusView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 聚焦电脑时在原版相机完成全部计算后覆盖眼位和朝向。
 *
 * <p>位置与角度都用原版 {@code setPosition} / {@code setRotation} 写入，
 * 视图矩阵、前向右上向量和射线拾取因此和画面保持一致；
 * 投影矩阵只依赖视场角，不需要重建。</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void hajikun$applyFocusView(DeltaTracker deltaTracker, CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        // 照相机取景优先于电脑聚焦：两者不会同时打开，但顺序写死更安全。
        FocusView.Pose pose = CameraState.pose(camera);
        if (pose == null) {
            pose = FocusView.current(camera);
        }
        if (pose == null) {
            return;
        }
        this.setPosition(pose.position().x, pose.position().y, pose.position().z);
        this.setRotation(pose.yaw(), pose.pitch());
    }

    /**
     * 变焦：改每帧算出来的视场角。
     *
     * <p>26.3 的透视矩阵在 {@code Camera.update} 里直接拿私有字段 {@code fov} 建立，
     * 只覆盖 {@code getFov()} 的返回值影响不到画面——HUD 上的倍率会变、视角却不动。
     * 这里改 {@code calculateFov} 的返回值，字段与投影矩阵都会跟着缩放。</p>
     */
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void hajikun$cameraZoom(float partialTick, CallbackInfoReturnable<Float> cir) {
        float zoom = CameraState.zoom();
        if (zoom != 1.0F) {
            cir.setReturnValue(cir.getReturnValueF() / zoom);
        }
    }

    /** 手持物品用 hudFov 单独建投影，变焦时一起缩放，手臂大小才不会和画面脱节。 */
    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true)
    private void hajikun$cameraZoomHud(float partialTick, CallbackInfoReturnable<Float> cir) {
        float zoom = CameraState.zoom();
        if (zoom != 1.0F) {
            cir.setReturnValue(cir.getReturnValueF() / zoom);
        }
    }

    /** 自拍时把原版第三人称前视的距离压到一臂之内，脸能占住画面。 */
    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
    private void hajikun$selfieDistance(float distance, CallbackInfoReturnable<Float> cir) {
        if (CameraState.selfie()) {
            cir.setReturnValue(Math.min(cir.getReturnValueF(), 1.45F));
        }
    }
}
