package com.akun.hajikun.client.render;

import com.akun.hajikun.client.ui.DeviceUis;
import com.akun.hajikun.client.ui.TvUi;
import com.akun.hajikun.tv.TvBlock;
import com.akun.hajikun.tv.TvBlockEntity;
import com.akun.hajikun.tv.TvKind;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 多格电视的整屏画面。
 *
 * <p>拼接格共用主控格的界面，由主控格一次性铺满整块面板，因此 5×3、16×9
 * 看起来是一整块大屏；待机、主界面和投屏画面都直接画在面板像素上。</p>
 */
public final class TvScreenRenderer implements BlockEntityRenderer<TvBlockEntity, TvScreenRenderer.State> {
    private final Font font;

    public TvScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    /** 渲染状态只需要知道屏幕尺寸、朝向与是否为拼接格。 */
    public static final class State extends BlockEntityRenderState {
        public TvKind size = TvKind.MEDIUM;
        public Direction facing = Direction.NORTH;
        public boolean masked;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(TvBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay);
        BlockState blockState = blockEntity.getBlockState();
        state.size = blockState.hasProperty(TvBlock.SIZE) ? blockState.getValue(TvBlock.SIZE) : blockEntity.kind();
        state.facing = blockState.hasProperty(TvBlock.FACING) ? blockState.getValue(TvBlock.FACING) : Direction.NORTH;
        state.masked = blockState.hasProperty(TvBlock.MASTER) && !blockState.getValue(TvBlock.MASTER);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.masked) {
            return;
        }
        TvUi ui = DeviceUis.tv(state.blockPos);
        if (ui == null) {
            return;
        }
        TvKind kind = state.size == null ? TvKind.MEDIUM : state.size;
        float[] origin = ScreenSurface.canvasOrigin(kind);
        float[] size = ScreenSurface.canvasSize(kind);
        float[] face = ScreenSurface.tvFaceSize(kind);
        float scale = size[0] / (float) ui.width();

        poseStack.pushPose();
        ScreenSurface.applyPose(poseStack, state.facing);
        poseStack.translate(origin[0], origin[1], origin[2]);
        poseStack.scale(-scale, -scale, -scale);
        // 画面按电脑屏幕比例居中，面板比画面更宽/更高时把两侧刷成电视黑边。
        float padX = (face[0] - size[0]) / 2.0F / scale;
        float padY = (face[1] - size[1]) / 2.0F / scale;
        WorldCanvas canvas = new WorldCanvas(this.font, poseStack, collector, ui.width(), ui.height());
        canvas.fill(Math.round(-padX), Math.round(-padY), Math.round(face[0] / scale - padX),
                Math.round(face[1] / scale - padY), 0xFF020305);
        ui.render(canvas);
        poseStack.popPose();
    }
}
