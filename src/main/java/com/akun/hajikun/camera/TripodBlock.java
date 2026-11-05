package com.akun.hajikun.camera;

import com.akun.hajikun.registry.ModBlockEntities;
import com.akun.hajikun.registry.ModItems;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 三脚架：放在地面上，手持照相机右键安装，空手右键进入固定点位取景。
 *
 * <p>支架只占用一格，但模型会向上伸出相机；拆掉支架时同时回收支架与相机，
 * 相机胶卷随物品一起保存。正在进行的录像由客户端取景器负责中断。</p>
 */
public class TripodBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty MOUNTED = BooleanProperty.create("mounted");
    private static final VoxelShape SHAPE = Shapes.box(0.2, 0.0, 0.2, 0.8, 0.95, 0.8);

    public TripodBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MOUNTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MOUNTED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getClickedFace() != Direction.UP) {
            return null;
        }
        BlockPos pos = context.getClickedPos();
        if (!context.getLevel().getBlockState(pos).canBeReplaced(context)) {
            return null;
        }
        // 镜头朝玩家看的方向：装好后就能直接拍前面的景色。
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(MOUNTED, false);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return !below.isAir() && below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TripodBlockEntity(ModBlockEntities.TRIPOD, pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            if (state.getValue(MOUNTED)) {
                CameraHooks.openTripodAt(pos);
            }
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof TripodBlockEntity tripod)) {
            return InteractionResult.CONSUME;
        }
        if (!tripod.mounted()) {
            // 方块状态和方块实体偶尔会因旧存档不同步，顺手修正。
            if (state.getValue(MOUNTED)) {
                level.setBlock(pos, state.setValue(MOUNTED, false), Block.UPDATE_ALL);
            }
            serverPlayer.sendOverlayMessage(Component.literal("手持照相机右键三脚架安装。"));
            return InteractionResult.CONSUME;
        }
        if (serverPlayer.isShiftKeyDown()) {
            dismount(serverPlayer, pos);
        }
        return InteractionResult.CONSUME;
    }

    /** 把手持的照相机装到空支架上。 */
    public static void mount(ServerPlayer player, ItemStack held, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof TripodBlockEntity tripod)) {
            return;
        }
        if (tripod.mounted()) {
            player.sendOverlayMessage(Component.literal("三脚架上已经有一台照相机了。"));
            return;
        }
        if (!(held.getItem() instanceof CameraItem)) {
            return;
        }
        tripod.setCamera(held);
        // 三脚架安装是「直接放置」：创造模式同样消耗手里的照相机，避免复制设备。
        held.shrink(1);
        BlockState state = player.level().getBlockState(pos);
        if (state.getBlock() instanceof TripodBlock) {
            player.level().setBlock(pos, state.setValue(MOUNTED, true), Block.UPDATE_ALL);
        }
        player.containerMenu.broadcastChanges();
        player.sendOverlayMessage(Component.literal("照相机已装上三脚架：再右键支架进入拍摄模式，潜行右键取下。")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 潜行右键取下相机，放回背包或掉在支架上方。 */
    public static void dismount(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof TripodBlockEntity tripod)) {
            return;
        }
        ItemStack camera = tripod.takeCamera();
        BlockState state = player.level().getBlockState(pos);
        if (state.getBlock() instanceof TripodBlock) {
            player.level().setBlock(pos, state.setValue(MOUNTED, false), Block.UPDATE_ALL);
        }
        if (!camera.isEmpty() && !player.addItem(camera)) {
            Block.popResource(player.level(), pos.above(), camera);
        }
        player.containerMenu.broadcastChanges();
        player.sendOverlayMessage(Component.literal("已取下照相机，当前拍摄任务中断。"));
    }

    /** 镜头在世界里的位置：支架顶部略靠前，和模型上的相机镜头对齐。 */
    public static Vec3 viewPosition(BlockPos pos, Direction facing) {
        return new Vec3(
                pos.getX() + 0.5D + facing.getStepX() * 0.22D,
                pos.getY() + 1.12D,
                pos.getZ() + 0.5D + facing.getStepZ() * 0.22D);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof TripodBlockEntity tripod
                && !tripod.looted()) {
            tripod.markLooted();
            ItemStack camera = tripod.takeCamera();
            if (!camera.isEmpty()) {
                popResource(level, pos, camera);
            }
            popResource(level, pos, new ItemStack(ModItems.TRIPOD));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void playerDestroy(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
                              BlockEntity blockEntity, ItemStack tool) {
        // 掉落已在 playerWillDestroy 中处理。
    }

    @Override
    public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
        super.destroy(level, pos, state);
        if (level instanceof ServerLevel server
                && server.getBlockEntity(pos) instanceof TripodBlockEntity tripod && !tripod.looted()) {
            tripod.markLooted();
            ItemStack camera = tripod.takeCamera();
            if (!camera.isEmpty()) {
                popResource(server, pos, camera);
            }
            popResource(server, pos, new ItemStack(ModItems.TRIPOD));
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** 创造栏预览：带相机的造型。 */
    public static BlockBehaviour.Properties tripodProperties() {
        return BlockBehaviour.Properties.of()
                .strength(1.8F, 8.0F)
                .sound(SoundType.METAL)
                .noOcclusion();
    }
}
