package com.akun.hajikun.registry;

import com.akun.hajikun.HajikunSmartMod;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModTabs {
    public static final ResourceKey<CreativeModeTab> DEVICES =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, HajikunSmartMod.id("devices"));

    private ModTabs() {
    }

    public static void initialize() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, DEVICES, FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.hajikun_smart.devices"))
                .icon(() -> new ItemStack(ModItems.DESKTOP))
                .displayItems((parameters, output) -> {
                    output.accept(ModItems.DESKTOP);
                    output.accept(ModItems.LAPTOP);
                    output.accept(ModItems.TV_2X1);
                    output.accept(ModItems.TV_5X3);
                    output.accept(ModItems.TV_16X9);
                    output.accept(ModItems.CAMERA);
                    output.accept(ModItems.TRIPOD);
                })
                .build());
    }
}
