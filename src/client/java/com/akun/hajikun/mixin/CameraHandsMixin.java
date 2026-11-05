package com.akun.hajikun.mixin;

import com.akun.hajikun.client.camera.CameraMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取景时隐藏第一人称手臂和手里的相机。
 *
 * <p>相机模型贴在屏幕右下角，正好压住取景框；进入拍照/录像模式后整条手臂都不画，
 * 画面里只剩真实世界。退出取景后原版渲染立即恢复，不需要改物品或装备状态。</p>
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class CameraHandsMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void hajikun$hideHandsWhileFraming(float partialTick, PoseStack poseStack,
                                               SubmitNodeCollector collector,
                                               PlayerRenderState playerState,
                                               FirstPersonHandsAndItemsRenderState handsState,
                                               CallbackInfo ci) {
        if (CameraMode.active()) {
            ci.cancel();
        }
    }
}
