package com.akun.hajikun.client.ui;

import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.computer.DeviceSnapshot;
import com.akun.hajikun.tv.TvSnapshot;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * 客户端按方块坐标保存的屏幕界面。
 *
 * <p>方块渲染器每一帧从这里取界面画到物理屏幕上；服务端快照到达时
 * 更新同一份实例，因此窗口、终端输出和光标不会因为刷新而丢失。</p>
 */
public final class DeviceUis {
    private static final Map<BlockPos, ComputerUi> COMPUTERS = new HashMap<>();
    private static final Map<BlockPos, TvUi> TVS = new HashMap<>();

    private DeviceUis() {
    }

    public static ComputerUi computer(BlockPos pos) {
        return COMPUTERS.get(pos);
    }

    public static TvUi tv(BlockPos pos) {
        return TVS.get(pos);
    }

    /** 应用电脑快照；第一次收到状态时创建界面。 */
    public static ComputerUi applyComputer(BlockPos pos, DeviceSnapshot snapshot) {
        BlockPos key = pos.immutable();
        ComputerUi ui = COMPUTERS.get(key);
        if (ui == null) {
            ComputerKind kind = "laptop".equals(snapshot.kind) ? ComputerKind.LAPTOP : ComputerKind.DESKTOP;
            ui = new ComputerUi(key, kind, snapshot);
            COMPUTERS.put(key, ui);
        } else {
            ui.update(snapshot);
        }
        return ui;
    }

    /** 应用电视快照；第一次收到状态时创建界面。 */
    public static TvUi applyTv(BlockPos pos, TvSnapshot snapshot) {
        BlockPos key = pos.immutable();
        TvUi ui = TVS.get(key);
        if (ui == null) {
            ui = new TvUi(key, snapshot.kind(), snapshot);
            TVS.put(key, ui);
        } else {
            ui.update(snapshot);
        }
        return ui;
    }

    public static void clear(BlockPos pos) {
        COMPUTERS.remove(pos);
        TVS.remove(pos);
    }

    public static void clearAll() {
        COMPUTERS.clear();
        TVS.clear();
    }
}
