package com.akun.hajikun.mixin;

import com.akun.hajikun.client.camera.CameraMode;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 录像时在音效真正进入混音器前记录一条现场声事件。
 *
 * <p>OpenAL 的混音结果抓不到，但每条游戏音效都经过这里；记录 ID、来源、
 * 音量、音高与相对取景器的位置，回放时重新触发，就是玩家录制时身边真实的声音。</p>
 */
@Mixin(SoundEngine.class)
public abstract class CameraSoundMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void hajikun$captureSound(SoundInstance instance,
                                     CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        CameraMode.onSound(instance);
    }
}
