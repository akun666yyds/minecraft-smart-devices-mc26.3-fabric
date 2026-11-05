package com.akun.hajikun.mixin;

import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.computer.ComputerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 创造模式中键拾取电脑方块/电脑掉落物时直接取消。
 *
 * <p>电脑的身份（UUID 与派生 MAC）跟着方块实体数据走。原版中键会复制一份
 * 完全相同的方块物品，两台机器就会抢同一个 MAC；因此创造模式不允许把
 * 电脑当作普通方块复制。需要新机器请从创造分栏拿全新物品。</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ComputerPickMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void hajikun$blockComputerPick(ServerboundPickItemFromBlockPacket packet, CallbackInfo ci) {
        if (this.player == null || !this.player.hasInfiniteMaterials()) {
            return;
        }
        if (this.player.level().getBlockState(packet.pos()).getBlock() instanceof ComputerBlock) {
            this.player.sendOverlayMessage(Component.literal("创造模式不能中键复制电脑设备（MAC 会冲突）")
                    .withStyle(ChatFormatting.RED));
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
    private void hajikun$entityComputerPick(ServerboundPickItemFromEntityPacket packet, CallbackInfo ci) {
        if (this.player == null || !this.player.hasInfiniteMaterials()) {
            return;
        }
        Entity entity = this.player.level().getEntity(packet.id());
        if (entity instanceof ItemEntity item && item.getItem().getItem() instanceof ComputerItem) {
            this.player.sendOverlayMessage(Component.literal("创造模式不能中键复制电脑设备（MAC 会冲突）")
                    .withStyle(ChatFormatting.RED));
            ci.cancel();
        }
    }
}
