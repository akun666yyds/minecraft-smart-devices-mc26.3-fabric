package com.akun.hajikun.registry;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.camera.TripodBlockEntity;
import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.tv.TvBlockEntity;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class ModBlockEntities {
    public static final BlockEntityType<ComputerBlockEntity> DESKTOP = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            HajikunSmartMod.id("desktop_computer"),
            new BlockEntityType<>(ModBlockEntities::createDesktop, Set.of(ModBlocks.DESKTOP_COMPUTER)));

    public static final BlockEntityType<ComputerBlockEntity> LAPTOP = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            HajikunSmartMod.id("laptop"),
            new BlockEntityType<>(ModBlockEntities::createLaptop, Set.of(ModBlocks.LAPTOP)));

    public static final BlockEntityType<TvBlockEntity> TV = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            HajikunSmartMod.id("tv"),
            new BlockEntityType<>(ModBlockEntities::createTv, Set.of(ModBlocks.TV)));

    public static final BlockEntityType<TripodBlockEntity> TRIPOD = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            HajikunSmartMod.id("tripod"),
            new BlockEntityType<>(ModBlockEntities::createTripod, Set.of(ModBlocks.TRIPOD)));

    private ModBlockEntities() {
    }

    public static void initialize() {
    }

    // 静态方法而非内联 lambda：避免字段初始化期间直接引用自身触发 self-reference 编译错误。
    private static ComputerBlockEntity createDesktop(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(DESKTOP, pos, state, ComputerKind.DESKTOP);
    }

    private static ComputerBlockEntity createLaptop(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(LAPTOP, pos, state, ComputerKind.LAPTOP);
    }

    private static TvBlockEntity createTv(BlockPos pos, BlockState state) {
        return new TvBlockEntity(TV, pos, state);
    }

    private static TripodBlockEntity createTripod(BlockPos pos, BlockState state) {
        return new TripodBlockEntity(TRIPOD, pos, state);
    }
}
