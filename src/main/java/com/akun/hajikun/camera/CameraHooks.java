package com.akun.hajikun.camera;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * 服务端与客户端之间的照相机界面入口。
 *
 * <p>公共源码集不能直接引用客户端界面类，因此由客户端初始化时把「打开取景器」
 * 与「打开三脚架取景器」的回调注册进来；服务端运行时这两个字段保持为 null。</p>
 */
public final class CameraHooks {
    public static Consumer<InteractionHand> openViewfinder;
    public static Consumer<BlockPos> openTripod;

    private CameraHooks() {
    }

    public static void openHeld(InteractionHand hand) {
        if (openViewfinder != null) {
            openViewfinder.accept(hand);
        }
    }

    public static void openTripodAt(BlockPos pos) {
        if (openTripod != null) {
            openTripod.accept(pos);
        }
    }
}
