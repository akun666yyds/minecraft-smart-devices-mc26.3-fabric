package com.akun.hajikun;

import com.akun.hajikun.camera.CameraFillLight;
import com.akun.hajikun.device.DeviceRegistry;
import com.akun.hajikun.registry.ModBlockEntities;
import com.akun.hajikun.registry.ModBlocks;
import com.akun.hajikun.registry.ModDataComponents;
import com.akun.hajikun.registry.ModItems;
import com.akun.hajikun.registry.ModTabs;
import com.akun.hajikun.network.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 哈基坤的智能设备：可放置的电脑、笔记本与多尺寸智能电视。
 *
 * <p>电脑不是贴图，而是一台保存在世界存档里的虚拟机：每台设备拥有独立的
 * 设备名、世界唯一且不可修改的 MAC 地址、持久化文件系统与运行状态。关机分软、硬两种，
 * 挖掉设备等同于硬关机：整台虚拟机随掉落物保存，设备目录条目立即注销。</p>
 */
public final class HajikunSmartMod implements ModInitializer {
    public static final String MOD_ID = "hajikun_smart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModDataComponents.initialize();
        ModBlocks.initialize();
        ModBlockEntities.initialize();
        ModItems.initialize();
        ModTabs.initialize();
        ModNetworking.initialize();
        // 每 5 秒核对一次设备目录：被爆破、活塞或旧存档遗留下来的幽灵条目自动清掉，
        // 其它电脑的蓝牙列表与电视投屏引用也会同步移除。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 补光灯每 tick 跟随一次；先撤灯再核对设备，退出时也不会留下光源。
            CameraFillLight.tick(server);
            if (server.getTickCount() % 100 != 0) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                DeviceRegistry.reconcile(level);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CameraFillLight.clear(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CameraFillLight.clearAll());
        LOGGER.info("哈基坤的智能设备已载入：{} 种设备方块", ModBlocks.count());
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
