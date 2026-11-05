package com.akun.hajikun.client.render;

import com.akun.hajikun.client.ui.ComputerUi;
import com.akun.hajikun.client.ui.DeviceUis;
import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.computer.ComputerKind;
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
 * 把操作系统画面画在电脑方块的显示器表面。
 *
 * <p>画面和方块模型共用同一个坐标系：先按朝向旋转，再按笔记本的 15° 转轴后仰，
 * 最后把画布左上角对齐到模型里的屏幕区域。这样屏幕、键盘和周围的方块在
 * 玩家视角里是同一套透视，不会出现浮空的 GUI 面板。</p>
 */
public final class ComputerScreenRenderer
        implements BlockEntityRenderer<ComputerBlockEntity, ComputerScreenRenderer.State> {
    private final Font font;

    public ComputerScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    /** 渲染状态只需要朝向与机型；界面内容从客户端设备表里取。 */
    public static final class State extends BlockEntityRenderState {
        public ComputerKind kind = ComputerKind.DESKTOP;
        public Direction facing = Direction.NORTH;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ComputerBlockEntity blockEntity, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay);
        state.kind = blockEntity.kind();
        BlockState blockState = blockEntity.getBlockState();
        state.facing = blockState.hasProperty(ComputerBlock.FACING)
                ? blockState.getValue(ComputerBlock.FACING) : Direction.NORTH;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        ComputerUi ui = DeviceUis.computer(state.blockPos);
        if (ui == null) {
            // 没有客户端界面时保留模型自带的静态屏幕贴图（关机/亮屏）。
            return;
        }
        ComputerKind kind = state.kind == null ? ComputerKind.DESKTOP : state.kind;
        float[] origin = ScreenSurface.canvasOrigin(kind);
        float[] size = ScreenSurface.canvasSize(kind);
        float scale = size[0] / (float) ui.width();

        poseStack.pushPose();
        ScreenSurface.applyPose(poseStack, state.facing, kind);
        poseStack.translate(origin[0], origin[1], origin[2]);
        // 画布 x 向右、y 向下，屏幕朝观察者，因此三轴都取负把二维坐标投到模型表面。
        poseStack.scale(-scale, -scale, -scale);
        ui.render(new WorldCanvas(this.font, poseStack, collector, ui.width(), ui.height()));
        poseStack.popPose();
    }
}
