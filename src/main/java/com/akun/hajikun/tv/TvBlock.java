package com.akun.hajikun.tv;

import com.akun.hajikun.registry.ModBlockEntities;
import com.akun.hajikun.registry.ModBlocks;
import com.akun.hajikun.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 多格电视：一块主控格 + 若干拼接格，整块面板朝向点击的墙面。
 *
 * <p>电视只有主控格带方块实体；拼接格共享主控的电源与投屏状态。
 * 破坏任意一格都会整块拆除，并且只在触发破坏的那一格掉落对应尺寸的电视。</p>
 */
public class TvBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<TvKind> SIZE = EnumProperty.create("size", TvKind.class);
    public static final BooleanProperty MASTER = BooleanProperty.create("master");
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    public static final BooleanProperty CASTING = BooleanProperty.create("casting");

    public TvBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SIZE, TvKind.SMALL)
                .setValue(MASTER, false)
                .setValue(POWERED, false)
                .setValue(CASTING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIZE, MASTER, POWERED, CASTING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.16);
            case EAST -> Shapes.box(0.0, 0.0, 0.0, 0.16, 1.0, 1.0);
            case WEST -> Shapes.box(0.84, 0.0, 0.0, 1.0, 1.0, 1.0);
            default -> Shapes.box(0.0, 0.0, 0.84, 1.0, 1.0, 1.0);
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis().isVertical()) {
            if (context.getPlayer() != null) {
                context.getPlayer().sendOverlayMessage(Component.literal("电视需要贴在墙面上放置。"));
            }
            return null;
        }
        TvKind kind = context.getItemInHand().getItem() instanceof TvItem item ? item.kind() : TvKind.MEDIUM;
        Level level = context.getLevel();
        BlockPos anchor = context.getClickedPos();
        Direction right = face.getClockWise();
        for (int i = 0; i < kind.width(); i++) {
            for (int j = 0; j < kind.height(); j++) {
                BlockPos cell = anchor.relative(right, i).below(j);
                if (cell.equals(anchor)) {
                    continue;
                }
                if (level.isOutsideBuildHeight(cell) || !level.getBlockState(cell).canBeReplaced()) {
                    if (context.getPlayer() != null) {
                        context.getPlayer().sendOverlayMessage(Component.literal(
                                "空间不足：这块 " + kind.label() + " 电视需要 " + kind.width() + "×" + kind.height() + " 格的平整墙面。"));
                    }
                    return null;
                }
            }
        }
        return this.defaultBlockState()
                .setValue(FACING, face)
                .setValue(SIZE, kind)
                .setValue(MASTER, true)
                .setValue(POWERED, false)
                .setValue(CASTING, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        TvKind kind = stack.getItem() instanceof TvItem item ? item.kind() : TvKind.MEDIUM;
        Direction right = state.getValue(FACING).getClockWise();
        for (int i = 0; i < kind.width(); i++) {
            for (int j = 0; j < kind.height(); j++) {
                BlockPos cell = pos.relative(right, i).below(j);
                if (cell.equals(pos)) {
                    continue;
                }
                server.setBlock(cell, state.setValue(MASTER, false), 3);
            }
        }
        if (server.getBlockEntity(pos) instanceof TvBlockEntity tv) {
            tv.setKind(kind);
            tv.register();
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                              BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos masterPos = state.getValue(MASTER) ? pos : this.findMaster(level, pos, state);
        if (masterPos == null) {
            return InteractionResult.PASS;
        }
        if (level.getBlockEntity(masterPos) instanceof TvBlockEntity tv && player instanceof ServerPlayer serverPlayer) {
            faceScreen(serverPlayer, masterPos, state.getValue(FACING), tv.kind());
            tv.sendState(serverPlayer, true, "");
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** 把玩家视线转向整块面板中心，交互时就像站在电视正前方。 */
    private static void faceScreen(ServerPlayer player, BlockPos masterPos, Direction facing, TvKind kind) {
        Direction right = facing.getClockWise();
        double centerX = masterPos.getX() + 0.5 + right.getStepX() * ((kind.width() - 1) / 2.0)
                + facing.getStepX() * 0.44;
        double centerY = masterPos.getY() + 0.5 - (kind.height() - 1) / 2.0;
        double centerZ = masterPos.getZ() + 0.5 + right.getStepZ() * ((kind.width() - 1) / 2.0)
                + facing.getStepZ() * 0.44;
        double dx = centerX - player.getX();
        double dz = centerZ - player.getZ();
        double horizontal = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(centerY - player.getEyeY(), horizontal));
        pitch = Math.max(-35.0F, Math.min(35.0F, pitch));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockPos masterPos = state.getValue(MASTER) ? pos : this.findMaster(level, pos, state);
            if (masterPos != null) {
                TvBlockEntity tv = level.getBlockEntity(masterPos) instanceof TvBlockEntity entity ? entity : null;
                TvKind kind = tv == null ? TvKind.MEDIUM : tv.kind();
                Direction right = state.getValue(FACING).getClockWise();
                for (int i = 0; i < kind.width(); i++) {
                    for (int j = 0; j < kind.height(); j++) {
                        BlockPos cell = masterPos.relative(right, i).below(j);
                        if (level.getBlockState(cell).is(ModBlocks.TV)) {
                            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
                if (tv != null) {
                    tv.unregister();
                }
                if (!player.getAbilities().instabuild) {
                    popResource(level, pos, new ItemStack(ModItems.tvItem(kind)));
                }
            }
        }
        return state;
    }

    @Override
    public void playerDestroy(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
                              BlockEntity blockEntity, ItemStack tool) {
        // 掉落已在 playerWillDestroy 中按整块电视处理，避免拼接格重复掉落。
    }

    /** 从任意一格找回主控格。 */
    public BlockPos findMaster(Level level, BlockPos pos, BlockState state) {
        Direction right = state.getValue(FACING).getClockWise();
        for (int i = 0; i <= 15; i++) {
            for (int j = 0; j <= 8; j++) {
                BlockPos candidate = pos.relative(right, -i).above(j);
                BlockState candidateState = level.getBlockState(candidate);
                if (candidateState.is(ModBlocks.TV) && candidateState.getValue(MASTER)
                        && level.getBlockEntity(candidate) instanceof TvBlockEntity tv
                        && this.containsCell(tv, candidate, candidateState, pos)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean containsCell(TvBlockEntity tv, BlockPos master, BlockState masterState, BlockPos cell) {
        Direction right = masterState.getValue(FACING).getClockWise();
        TvKind kind = tv.kind();
        for (int i = 0; i < kind.width(); i++) {
            for (int j = 0; j < kind.height(); j++) {
                if (master.relative(right, i).below(j).equals(cell)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(MASTER) ? new TvBlockEntity(ModBlockEntities.TV, pos, state) : null;
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
