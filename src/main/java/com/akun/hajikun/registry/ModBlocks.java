package com.akun.hajikun.registry;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.camera.TripodBlock;
import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.tv.TvBlock;
import com.akun.hajikun.tv.TvKind;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    /** 台式机：黑曜石机箱 + 显示器 + 键盘的一体造型，只摆在方块上表面。 */
    public static final Block DESKTOP_COMPUTER = register("desktop_computer",
            props -> new ComputerBlock(ComputerKind.DESKTOP, props), computerProperties());

    /** 笔记本：掀开的屏幕与键盘，只摆在方块上表面。 */
    public static final Block LAPTOP = register("laptop",
            props -> new ComputerBlock(ComputerKind.LAPTOP, props), computerProperties());

    /** 多格电视：2×1、5×3、16×9 三种尺寸共用同一方块，尺寸记在物品与主控方块实体里。 */
    public static final Block TV = register("tv",
            TvBlock::new, BlockBehaviour.Properties.of()
                    .strength(2.0F, 12.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(TvBlock.POWERED) ? 10 : 0));

    /** 三脚架：可以安装照相机，拆掉时连同相机一起回收。 */
    public static final Block TRIPOD = register("tripod",
            TripodBlock::new, TripodBlock.tripodProperties());

    /** 三种尺寸的电视名称，供创造栏与提示使用。 */
    public static final TvKind[] TV_SIZES = TvKind.values();

    private ModBlocks() {
    }

    public static void initialize() {
    }

    public static int count() {
        return 4;
    }

    /** 与末影箱相同的硬度与抗爆。不加 requiresCorrectToolForDrops：设备自带虚拟机，
     * 任何工具破坏都应当完整掉落，避免手滑把存档丢了。 */
    private static BlockBehaviour.Properties computerProperties() {
        return BlockBehaviour.Properties.of()
                .strength(22.5F, 600.0F)
                .sound(SoundType.STONE)
                .noOcclusion()
                .lightLevel(state -> state.getValue(ComputerBlock.POWERED) ? 7 : 0);
    }

    private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
                                  BlockBehaviour.Properties properties) {
        Identifier id = HajikunSmartMod.id(name);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        Block block = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }
}
