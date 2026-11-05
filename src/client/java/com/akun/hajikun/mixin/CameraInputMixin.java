package com.akun.hajikun.mixin;

import com.akun.hajikun.client.camera.CameraMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拍摄模式下在键盘事件进入原版处理前截获快捷键。
 *
 * <p>拍摄模式没有 Screen，所以 Q/E/F 原本会触发丢弃物品、打开背包等行为。
 * 这里只消费照相机自己的按键；WASD 等移动键照常放行，玩家可以继续自由走动。</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class CameraInputMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void hajikun$cameraKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (CameraMode.handleKey(action, event.key())) {
            ci.cancel();
        }
    }
}
