package com.akun.hajikun.mixin;

import com.akun.hajikun.client.camera.CameraMode;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在世界画完、GUI 开始绘制的那一帧抓屏。
 *
 * <p>26.3 把界面拆成「提取 + 延迟提交」两部分：以前在
 * {@code Gui.extractRenderState} 里抓，GPU 拷贝到的其实是上一帧已经画过
 * HUD 的画面，照片和录像里会带着取景框与快捷栏。改在
 * {@link GuiRenderer#render()} 的入口抓，主渲染目标里只有刚刚画好的世界，
 * 既没有取景框，也没有快捷栏和准星。</p>
 */
@Mixin(GuiRenderer.class)
public abstract class CameraGuiMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void hajikun$captureBeforeGui(CallbackInfo ci) {
        CameraMode.beforeGui();
    }
}
