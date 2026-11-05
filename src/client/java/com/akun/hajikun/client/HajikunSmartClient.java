package com.akun.hajikun.client;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.camera.CameraHooks;
import com.akun.hajikun.client.camera.CameraMode;
import com.akun.hajikun.client.camera.CameraState;
import com.akun.hajikun.client.browser.WebKitEngine;
import com.akun.hajikun.client.browser.ElectronEngine;
import com.akun.hajikun.client.media.MediaCache;
import com.akun.hajikun.client.net.BrowserClient;
import com.akun.hajikun.client.render.ComputerScreenRenderer;
import com.akun.hajikun.client.render.ScreenSurface;
import com.akun.hajikun.client.render.TvScreenRenderer;
import com.akun.hajikun.client.screen.CameraRollScreen;
import com.akun.hajikun.client.screen.CameraTransferScreen;
import com.akun.hajikun.client.screen.DeviceFocusScreen;
import com.akun.hajikun.client.ui.ComputerUi;
import com.akun.hajikun.client.ui.DeviceUis;
import com.akun.hajikun.client.ui.TvUi;
import com.akun.hajikun.computer.ComputerBlock;
import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.computer.DeviceSnapshot;
import com.akun.hajikun.network.SmartPayloads.CameraMediaS2C;
import com.akun.hajikun.network.SmartPayloads.CameraRollS2C;
import com.akun.hajikun.network.SmartPayloads.CameraShotC2S;
import com.akun.hajikun.network.SmartPayloads.CameraViewerS2C;
import com.akun.hajikun.network.SmartPayloads.DeviceRequestC2S;
import com.akun.hajikun.network.SmartPayloads.DeviceStateS2C;
import com.akun.hajikun.network.SmartPayloads.MediaChunkS2C;
import com.akun.hajikun.network.SmartPayloads.P2VChunkC2S;
import com.akun.hajikun.registry.ModBlockEntities;
import com.akun.hajikun.tv.TvBlock;
import com.akun.hajikun.tv.TvKind;
import com.akun.hajikun.tv.TvSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 客户端入口：接收设备快照，把界面画到方块屏幕上。
 *
 * <p>右键设备时只打开一个透明的输入层，玩家仍保持第一人称视角；
 * 真正的操作系统画面由方块渲染器贴到显示器/电视面板上。</p>
 */
public final class HajikunSmartClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 清理上次可能遗留的 P2V 转换临时文件，宿主机不留虚拟机残留。
        com.akun.hajikun.client.media.P2VImport.cleanupScratch();
        BlockEntityRendererRegistry.register(ModBlockEntities.TV, TvScreenRenderer::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.DESKTOP, ComputerScreenRenderer::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.LAPTOP, ComputerScreenRenderer::new);

        // 拍摄模式不打开 Screen：取景框走 Fabric HUD，按键走客户端 Mixin，
        // 玩家因此可以继续自由走动、自由转视角。
        CameraHooks.openViewfinder = CameraMode::startHeld;
        CameraHooks.openTripod = CameraMode::startTripod;
        HudElementRegistry.addLast(HajikunSmartMod.id("camera_hud"), CameraMode::extractHud);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CameraMode.tick());

        ClientPlayNetworking.registerGlobalReceiver(DeviceStateS2C.TYPE, (payload, context) -> {
            Minecraft client = context.client();
            client.execute(() -> handle(client, payload.pos(), payload.json(), payload.open()));
        });
        ClientPlayNetworking.registerGlobalReceiver(MediaChunkS2C.TYPE, (payload, context) ->
                context.client().execute(() -> MediaCache.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(CameraRollS2C.TYPE, (payload, context) ->
                context.client().execute(() -> openCameraRoll(context.client(), payload)));
        ClientPlayNetworking.registerGlobalReceiver(CameraViewerS2C.TYPE, (payload, context) ->
                context.client().execute(() -> CameraRollScreen.acceptList(payload)));
        ClientPlayNetworking.registerGlobalReceiver(CameraMediaS2C.TYPE, (payload, context) ->
                context.client().execute(() -> CameraRollScreen.acceptMedia(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(CameraMode::exit);
            client.execute(DeviceUis::clearAll);
            client.execute(MediaCache::clearAll);
            client.execute(CameraState::clear);
            client.execute(ElectronEngine::shutdown);
            client.execute(WebKitEngine::shutdown);
        });
    }

    private static void openCameraRoll(Minecraft client, CameraRollS2C packet) {
        Screen current = client.gui.screen();
        if (current instanceof CameraTransferScreen screen && screen.pos().equals(packet.pos())) {
            screen.update(packet);
            return;
        }
        client.setScreenAndShow(new CameraTransferScreen(packet));
    }

    private void handle(Minecraft client, BlockPos pos, String json, boolean open) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("tv") && root.get("tv").getAsBoolean()) {
                TvSnapshot snapshot = TvSnapshot.parse(json);
                TvUi ui = DeviceUis.applyTv(pos, snapshot);
                if (open) {
                    this.focus(client, new DeviceFocusScreen(pos, tvSurface(client, pos, ui.kind()), ui));
                }
                return;
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON 时退回电脑解析，由它给出错误提示。
        }
        DeviceSnapshot snapshot = DeviceSnapshot.parse(json);
        ComputerUi ui = DeviceUis.applyComputer(pos, snapshot);
        if (open) {
            this.focus(client, new DeviceFocusScreen(pos, computerSurface(client, pos), ui));
        }
    }

    private void focus(Minecraft client, DeviceFocusScreen screen) {
        Screen current = client.gui.screen();
        if (current instanceof DeviceFocusScreen focus && focus.pos().equals(screen.pos())) {
            return;
        }
        client.setScreenAndShow(screen);
    }

    private static ScreenSurface computerSurface(Minecraft client, BlockPos pos) {
        if (client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            if (state.getBlock() instanceof ComputerBlock computer && state.hasProperty(ComputerBlock.FACING)) {
                return ScreenSurface.computer(pos, state.getValue(ComputerBlock.FACING), computer.kind());
            }
            if (client.level.getBlockEntity(pos) instanceof ComputerBlockEntity entity) {
                return ScreenSurface.computer(pos, Direction.NORTH, entity.kind());
            }
        }
        return ScreenSurface.computer(pos, Direction.NORTH, ComputerKind.DESKTOP);
    }

    private static ScreenSurface tvSurface(Minecraft client, BlockPos pos, TvKind fallback) {
        if (client.level != null) {
            BlockState state = client.level.getBlockState(pos);
            if (state.getBlock() instanceof TvBlock
                    && state.hasProperty(TvBlock.FACING) && state.hasProperty(TvBlock.SIZE)) {
                return ScreenSurface.tv(pos, state.getValue(TvBlock.FACING), state.getValue(TvBlock.SIZE));
            }
        }
        return ScreenSurface.tv(pos, Direction.NORTH, fallback);
    }

    /** 供界面发送动作。 */
    public static void request(BlockPos pos, String action, String arg) {
        ClientPlayNetworking.send(new DeviceRequestC2S(pos, action, arg == null ? "" : arg));
    }

    /** P2V 上传：把物理机文件切成 24KB 的小包按顺序送到服务端。 */
    public static void upload(BlockPos pos, String transfer, String dir, String name, String mime,
                              int index, int total, byte[] data) {
        // 转码线程只负责切块，发包统一排到客户端主线程，保证分片按序写出。
        Minecraft.getInstance().execute(() ->
                ClientPlayNetworking.send(new P2VChunkC2S(pos, transfer, dir, name, mime, index, total, data)));
    }

    /** P2V 上传（整份数据）：调用方已拿到完整字节，这里负责切片。 */
    public static void upload(BlockPos pos, String dir, String name, String mime, byte[] data) {
        int chunk = 24 * 1024;
        int total = Math.max(1, (data.length + chunk - 1) / chunk);
        String transfer = java.util.UUID.randomUUID().toString();
        for (int i = 0; i < total; i++) {
            int start = i * chunk;
            int end = Math.min(data.length, start + chunk);
            byte[] slice = java.util.Arrays.copyOfRange(data, start, end);
            upload(pos, transfer, dir, name, mime, i, total, slice);
        }
    }

    /** 照相机上传：照片/录像同样分块，落在手持相机或三脚架相机的胶卷里。 */
    public static void capture(boolean tripod, BlockPos pos, String transfer, String name, String mime,
                               int index, int total, byte[] data) {
        Minecraft.getInstance().execute(() ->
                ClientPlayNetworking.send(new CameraShotC2S(tripod, pos, transfer, name, mime, index, total, data)));
    }
}
