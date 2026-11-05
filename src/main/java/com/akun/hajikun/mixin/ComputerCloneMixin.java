package com.akun.hajikun.mixin;

import com.akun.hajikun.computer.ComputerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 创造模式背包里中键克隆电脑物品时取消（与方块中键拾取同一条规则）。
 *
 * <p>只拦截 {@link ContainerInput#CLONE}，普通拿取/移动不受影响；
 * 生存模式没有无限物品，也不会走到这里。</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ComputerCloneMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void hajikun$blockComputerClone(int slotId, int button, ContainerInput input, Player player,
                                            CallbackInfo ci) {
        if (input != ContainerInput.CLONE || player == null || !player.hasInfiniteMaterials()) {
            return;
        }
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (slotId < 0 || slotId >= menu.slots.size()) {
            return;
        }
        if (menu.slots.get(slotId).getItem().getItem() instanceof ComputerItem) {
            player.sendOverlayMessage(Component.literal("创造模式不能中键复制电脑设备（MAC 会冲突）")
                    .withStyle(ChatFormatting.RED));
            ci.cancel();
        }
    }
}
