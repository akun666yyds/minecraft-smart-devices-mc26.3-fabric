package com.akun.hajikun.mixin;

import com.akun.hajikun.client.camera.CameraMode;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拍摄模式下截获鼠标按键与滚轮：左键快门/录像，滚轮变焦，同时屏蔽挖方块与攻击。
 */
@Mixin(MouseHandler.class)
public abstract class CameraMouseMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void hajikun$cameraButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (CameraMode.handleMouseButton(info.button(), action)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void hajikun$cameraScroll(long window, double scrollX, double scrollY, CallbackInfo ci) {
        if (CameraMode.handleScroll(scrollY)) {
            ci.cancel();
        }
    }
}
