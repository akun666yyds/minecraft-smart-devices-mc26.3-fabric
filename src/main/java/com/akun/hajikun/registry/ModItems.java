package com.akun.hajikun.registry;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.camera.CameraItem;
import com.akun.hajikun.computer.ComputerItem;
import com.akun.hajikun.tv.TvItem;
import com.akun.hajikun.tv.TvKind;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModItems {
    /** 台式机物品。掉落物上可能带着 device_carry 组件，重新放下会恢复虚拟机。 */
    public static final Item DESKTOP = computerItem("desktop_computer", ModBlocks.DESKTOP_COMPUTER);
    /** 笔记本物品。 */
    public static final Item LAPTOP = computerItem("laptop", ModBlocks.LAPTOP);
    /** 三种尺寸的多格电视。 */
    public static final Item TV_2X1 = tvItem("tv_2x1", TvKind.SMALL);
    public static final Item TV_5X3 = tvItem("tv_5x3", TvKind.MEDIUM);
    public static final Item TV_16X9 = tvItem("tv_16x9", TvKind.LARGE);
    /** 照相机：没有 MAC，只存照片与录像。 */
    public static final Item CAMERA = cameraItem("camera");
    /** 三脚架方块物品。 */
    public static final Item TRIPOD = blockItem("tripod", ModBlocks.TRIPOD);

    private ModItems() {
    }

    public static void initialize() {
    }

    /** 拆除电视时按尺寸取回对应物品。 */
    public static Item tvItem(TvKind kind) {
        return switch (kind) {
            case SMALL -> TV_2X1;
            case MEDIUM -> TV_5X3;
            case LARGE -> TV_16X9;
        };
    }

    private static Item computerItem(String name, Block block) {
        Identifier id = HajikunSmartMod.id(name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, key,
                new ComputerItem(block, new Item.Properties().setId(key)));
    }

    private static Item tvItem(String name, TvKind kind) {
        Identifier id = HajikunSmartMod.id(name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, key,
                new TvItem(ModBlocks.TV, new Item.Properties().setId(key), kind));
    }

    private static Item cameraItem(String name) {
        Identifier id = HajikunSmartMod.id(name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, key,
                new CameraItem(new Item.Properties().stacksTo(1).setId(key)));
    }

    private static Item blockItem(String name, Block block) {
        Identifier id = HajikunSmartMod.id(name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties().setId(key)));
    }
}
