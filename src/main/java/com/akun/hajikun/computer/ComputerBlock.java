package com.akun.hajikun.computer;

import com.akun.hajikun.device.DeviceRegistry;
import com.akun.hajikun.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import java.util.List;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 可放置的电脑方块。只允许摆在方块上表面，默认关机。
 *
 * <p>右击在服务端生成快照并发给客户端，由客户端打开全屏操作系统界面；
 * 破坏时先把虚拟机关机并存进掉落物，保证「破坏默认软关机」不会丢数据。</p>
 */
public class ComputerBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    // 碰撞箱与模型外轮廓一致：台式 bbox(1.5,0,0.8)-(15.8,14.1,14.3)，笔记本 bbox(1,0,0.9)-(15,9.6,12)。
    private static final VoxelShape DESKTOP_SHAPE = Shapes.box(0.09, 0.0, 0.05, 0.99, 0.88, 0.9);
    private static final VoxelShape LAPTOP_SHAPE = Shapes.box(0.06, 0.0, 0.055, 0.94, 0.6, 0.76);
    private final ComputerKind kind;

    public ComputerBlock(ComputerKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false));
    }

    public ComputerKind kind() {
        return this.kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    /** 只接受点击方块上表面时的放置；先判断上表面，再判断是否踩得稳。 */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getClickedFace() != Direction.UP) {
            return null;
        }
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (!level.getBlockState(pos).canBeReplaced(context)) {
            return null;
        }
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(POWERED, false);
    }

    @Override
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return !below.isAir() && below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.kind == ComputerKind.LAPTOP ? LAPTOP_SHAPE : DESKTOP_SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(
                this.kind == ComputerKind.LAPTOP ? com.akun.hajikun.registry.ModBlockEntities.LAPTOP
                        : com.akun.hajikun.registry.ModBlockEntities.DESKTOP,
                pos, state, this.kind);
    }

    /** 放置时把掉落物里的虚拟机数据还原到方块实体。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        byte[] data = stack.get(ModDataComponents.DEVICE_DATA);
        String carry = stack.get(ModDataComponents.DEVICE_CARRY);
        if (level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            if (data != null && data.length > 0) {
                computer.applyCarryBytes(data);
            } else if (carry != null) {
                computer.applyCarry(carry);
            } else {
                // 全新物品或创造模式中键拾取的方块：无论物品里是否夹带方块实体数据，
                // 都从空白机器开始，保证中键拿到的是一台未注册的新电脑。
                computer.resetToBlank();
            }
            computer.claimIdentity();
            computer.ensureIdentity();
            computer.register();
            // 带设备数据的电脑物品代表一台唯一的 MAC/UUID：创造模式原版不消耗物品，
            // 这里手动扣掉一份，保证「放下后该物品被消耗」，无法靠放置复制设备。
            if (ComputerItem.carriesDevice(stack)
                    && placer instanceof Player player && player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            // 界面直接画在方块屏幕上，放下后只同步首次开机画面，不弹出全屏 GUI。
            if (placer instanceof ServerPlayer serverPlayer) {
                computer.sendState(serverPlayer, false, "");
            }
        }
    }

    /** 右键：视角转向屏幕，服务端生成快照并发给客户端打开界面。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            faceScreen(serverPlayer, pos);
            computer.sendState(serverPlayer, true, "");
        }
        return InteractionResult.CONSUME;
    }

    /** 把玩家视线转向屏幕中心，打开界面后就像坐在屏幕前。 */
    private static void faceScreen(ServerPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double horizontal = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        double targetY = pos.getY() + 0.9;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(targetY - player.getEyeY(), horizontal));
        pitch = Math.max(-35.0F, Math.min(35.0F, pitch));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }

    /**
     * 玩家挖掉电脑（生存与创造都一样）：
     *
     * <p>先在这一步硬关机、把整台虚拟机装进掉落物并注销设备目录。这里方块还没被移除，
     * 因此绝不能调用会 {@code setBlock} 的关机动作，否则原地会重新长出一个无数据的方块，
     * 反复挖就能无限刷物品。</p>
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            ItemStack drop = computer.pickUpItem();
            computer.retire();
            dropGuaranteed(level, pos, drop);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 掉落必须发生：不经过 {@code doBlockDrops} 游戏规则，创造模式也照常掉。
     *
     * <p>掉落的电脑永不自然消失（{@code unlimited lifetime}），避免玩家把整台机器
     * 放在地上转身就丢数据；真正被烧毁/炸毁时由 {@link ComputerItem#onDestroyed}
     * 注销设备并清空宿主机侧残留。</p>
     */
    private static void dropGuaranteed(Level level, BlockPos pos, ItemStack drop) {
        if (drop.isEmpty()) {
            return;
        }
        ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, drop);
        entity.setDefaultPickUpDelay();
        entity.setUnlimitedLifetime();
        level.addFreshEntity(entity);
    }

    @Override
    public void playerDestroy(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        // 掉落已在 playerWillDestroy 中处理，这里保持为空，避免重复掉落。
    }

    /**
     * 爆炸、{@code /setblock ... destroy} 等非玩家破坏也带着整台虚拟机掉落，
     * 不会变成一台空白电脑。方块实体此时还活着，取完数据立即硬关机注销。
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                instanceof ComputerBlockEntity computer) {
            ItemStack drop = computer.pickUpItem();
            computer.retire();
            return drop.isEmpty() ? List.of() : List.of(drop);
        }
        return super.getDrops(state, params);
    }

    /** 非玩家破坏（爆破、活塞等）也要注销设备，防止目录里留下幽灵条目。 */
    @Override
    public void destroy(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state) {
        super.destroy(level, pos, state);
        if (level instanceof ServerLevel server) {
            String dimension = server.dimension().identifier().toString();
            for (DeviceRegistry.Entry entry : DeviceRegistry.get(server).removeAt(dimension, pos)) {
                DeviceRegistry.purgePeer(server, entry.mac());
            }
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
}
