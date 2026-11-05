package com.akun.hajikun.camera;

import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 手持照相机：没有 MAC，也不占设备目录，只把照片和录像存在物品自己的胶卷里。
 *
 * <p>右键打开取景器；右键电脑打开「相机传输」面板，把胶卷里的文件复制或移动到
 * 虚拟机；右键三脚架则把照相机装到支架上，之后空手右键支架进入定点取景。
 * 胶卷组件不做网络同步，避免动辄几十兆的照片跟着背包刷新反复发包。</p>
 */
public final class CameraItem extends Item {
    public CameraItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            CameraHooks.openHeld(hand);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockState state = level.getBlockState(context.getClickedPos());
        if (state.getBlock() instanceof ComputerBlock) {
            if (!level.isClientSide() && context.getPlayer() instanceof ServerPlayer serverPlayer) {
                CameraService.openTransfer(serverPlayer, context.getClickedPos());
            }
            return InteractionResult.SUCCESS;
        }
        if (state.getBlock() instanceof TripodBlock) {
            boolean mounted = state.hasProperty(TripodBlock.MOUNTED)
                    && state.getValue(TripodBlock.MOUNTED);
            boolean sneak = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
            if (mounted && sneak) {
                // 潜行右键优先取下相机；否则会变成“打开取景器”，没法拆支架。
                if (!level.isClientSide() && context.getPlayer() instanceof ServerPlayer serverPlayer) {
                    TripodBlock.dismount(serverPlayer, context.getClickedPos());
                }
                return InteractionResult.SUCCESS;
            }
            if (level.isClientSide()) {
                // 已经装好相机的支架：右键直接进入定点拍摄；空支架由服务端安装。
                if (mounted) {
                    CameraHooks.openTripodAt(context.getClickedPos());
                }
                return InteractionResult.SUCCESS;
            }
            if (!mounted && context.getPlayer() instanceof ServerPlayer serverPlayer) {
                TripodBlock.mount(serverPlayer, context.getItemInHand(), context.getClickedPos());
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) {
            CameraHooks.openHeld(context.getHand());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> consumer, TooltipFlag flag) {
        byte[] roll = stack.get(ModDataComponents.CAMERA_ROLL);
        consumer.accept(Component.literal("照相机 · 无 MAC 地址").withStyle(ChatFormatting.GRAY));
        consumer.accept(Component.literal("只保存照片与录像，不注册为电脑设备")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (roll != null && roll.length > 0) {
            int count = CameraRoll.decode(roll).size();
            consumer.accept(Component.literal("胶卷：" + count + " 个文件")
                    .withStyle(ChatFormatting.AQUA));
        }
        consumer.accept(Component.literal("右键进入拍摄 · 右键开机电脑传输 · 右键三脚架安装")
                .withStyle(ChatFormatting.DARK_GRAY));
        consumer.accept(Component.literal("拍摄中：左键快门 · Q 前后 · E 拍照/录像 · 滚轮变焦 · F 胶卷")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
