package com.akun.hajikun.camera;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 照相机补光灯：在取景位置放一盏原版 15 级光源，并让它跟着镜头移动。
 *
 * <p>补光是真实的方块光，不是给照片做后期提亮：光照由原版光照引擎计算，
 * 光影包、区块明暗和成片都看到同一份亮度，默认兼容一切光影。
 * 光源只放在空气里，关灯、退出取景、断开连接或服务器停止时都会撤掉，
 * 不会在原存档里留下多余的方块。</p>
 *
 * <p>灯始终落在镜头所在的那一格：转头不会让灯换位置，只有人走过方块边界
 * 时才会挪一格，所以取景时画面不会一格一格地闪。</p>
 */
public final class CameraFillLight {
    /** 原版光照上限，和火把/荧石最亮的一格一致。 */
    private static final int LEVEL = 15;
    /** 和三脚架取景的最大允许距离一致，玩家走远就自动收灯。 */
    private static final double MAX_DISTANCE_SQR = 64.0D * 64.0D;
    private static final Map<UUID, Light> LIGHTS = new HashMap<>();

    private static final class Light {
        ServerLevel level;
        BlockPos pos;
        boolean tripod;
        BlockPos anchor = BlockPos.ZERO;
        boolean placed;
    }

    private CameraFillLight() {
    }

    /** 客户端切换补光灯时调用；开灯后由服务端每 tick 跟随取景位置。 */
    public static void update(ServerPlayer player, boolean on, boolean tripod, BlockPos pos) {
        if (player == null) {
            return;
        }
        if (!on) {
            clear(player.getUUID());
            return;
        }
        Light light = LIGHTS.computeIfAbsent(player.getUUID(), ignored -> new Light());
        light.tripod = tripod;
        light.anchor = pos == null ? BlockPos.ZERO : pos.immutable();
        apply(player, light);
    }

    /** 服务端每 tick：手持时跟着眼睛，三脚架时跟着镜头前方那一点。 */
    public static void tick(MinecraftServer server) {
        if (LIGHTS.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Light light = LIGHTS.get(player.getUUID());
            if (light != null) {
                apply(player, light);
            }
        }
    }

    /** 玩家退出、相机消失或服务器停止时撤灯，避免光源留在存档里。 */
    public static void clear(UUID player) {
        Light light = LIGHTS.remove(player);
        if (light != null) {
            unlight(light);
        }
    }

    public static void clearAll() {
        for (Light light : LIGHTS.values()) {
            unlight(light);
        }
        LIGHTS.clear();
    }

    private static void apply(ServerPlayer player, Light light) {
        if (!(player.level() instanceof ServerLevel level)) {
            clear(player.getUUID());
            return;
        }
        if (light.level != null && light.level != level) {
            unlight(light);
        }
        light.level = level;

        BlockPos target;
        if (light.tripod) {
            BlockState state = level.getBlockState(light.anchor);
            if (!(state.getBlock() instanceof TripodBlock)
                    || !state.hasProperty(TripodBlock.MOUNTED)
                    || !state.getValue(TripodBlock.MOUNTED)
                    || player.distanceToSqr(light.anchor.getX() + 0.5D, light.anchor.getY() + 0.5D,
                    light.anchor.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
                clear(player.getUUID());
                return;
            }
            Direction facing = state.hasProperty(TripodBlock.FACING)
                    ? state.getValue(TripodBlock.FACING) : Direction.NORTH;
            Vec3 view = TripodBlock.viewPosition(light.anchor, facing);
            target = BlockPos.containing(view);
        } else {
            // 手持相机由玩家自己拿着；相机被换走就自动关灯。
            if (!(player.getMainHandItem().getItem() instanceof CameraItem)
                    && !(player.getOffhandItem().getItem() instanceof CameraItem)) {
                clear(player.getUUID());
                return;
            }
            target = BlockPos.containing(player.getEyePosition());
        }

        if (target.equals(light.pos)) {
            // 同一格：只有灯丢了（被其它逻辑清掉、区块重载）才补一次。
            if (!light.placed || !isLit(level, target)) {
                light.placed = place(level, target);
            }
            return;
        }
        // 先点新灯再撤旧灯：走过方块边界时不会出现一帧全黑，也就不会闪。
        BlockPos old = light.pos;
        boolean hadOld = light.placed;
        light.pos = target;
        light.placed = place(level, target);
        if (hadOld && old != null && !old.equals(target)) {
            unlight(level, old);
        }
    }

    /** 空气和补光灯本身都可以被新的灯占用，其它方块一律不碰。 */
    private static boolean passable(BlockState state) {
        return state.isAir() || state.is(Blocks.LIGHT);
    }

    private static boolean place(ServerLevel level, BlockPos target) {
        BlockState current = level.getBlockState(target);
        if (!passable(current)) {
            return false;
        }
        if (isLit(level, target)) {
            return true;
        }
        level.setBlock(target, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LEVEL),
                Block.UPDATE_ALL);
        return isLit(level, target);
    }

    private static void unlight(Light light) {
        if (light.level != null && light.pos != null && light.placed) {
            unlight(light.level, light.pos);
        }
        light.pos = null;
        light.placed = false;
    }

    private static void unlight(ServerLevel level, BlockPos pos) {
        if (isLit(level, pos)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static boolean isLit(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LIGHT) && state.hasProperty(LightBlock.LEVEL)
                && state.getValue(LightBlock.LEVEL) == LEVEL;
    }
}
