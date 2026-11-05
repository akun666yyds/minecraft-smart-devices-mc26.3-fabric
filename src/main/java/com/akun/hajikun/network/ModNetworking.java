package com.akun.hajikun.network;

import com.akun.hajikun.camera.CameraFillLight;
import com.akun.hajikun.camera.CameraService;
import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.network.SmartPayloads.CameraFillC2S;
import com.akun.hajikun.network.SmartPayloads.CameraMediaS2C;
import com.akun.hajikun.network.SmartPayloads.CameraQueryC2S;
import com.akun.hajikun.network.SmartPayloads.CameraRollS2C;
import com.akun.hajikun.network.SmartPayloads.CameraShotC2S;
import com.akun.hajikun.network.SmartPayloads.CameraViewerS2C;
import com.akun.hajikun.network.SmartPayloads.DeviceRequestC2S;
import com.akun.hajikun.network.SmartPayloads.DeviceStateS2C;
import com.akun.hajikun.network.SmartPayloads.MediaChunkS2C;
import com.akun.hajikun.network.SmartPayloads.P2VChunkC2S;
import com.akun.hajikun.tv.TvBlockEntity;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 电脑与电视界面的网络接线：所有虚拟机动作都在服务端执行。 */
public final class ModNetworking {
    /** 允许操作的最大距离（方块），避免隔墙远程改别人的电脑。 */
    private static final double MAX_DISTANCE_SQR = 64.0D;
    private static final int CHUNK_BYTES = 24 * 1024;
    private static final int MAX_UPLOAD_BYTES = 96 * 1024 * 1024;
    private static final Map<String, Upload> UPLOADS = new ConcurrentHashMap<>();
    private static final Map<String, CameraUpload> CAMERA_UPLOADS = new ConcurrentHashMap<>();

    private ModNetworking() {
    }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(DeviceRequestC2S.TYPE, DeviceRequestC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(P2VChunkC2S.TYPE, P2VChunkC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CameraShotC2S.TYPE, CameraShotC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CameraQueryC2S.TYPE, CameraQueryC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CameraFillC2S.TYPE, CameraFillC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DeviceStateS2C.TYPE, DeviceStateS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MediaChunkS2C.TYPE, MediaChunkS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CameraRollS2C.TYPE, CameraRollS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CameraViewerS2C.TYPE, CameraViewerS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CameraMediaS2C.TYPE, CameraMediaS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(DeviceRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> handle(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(P2VChunkC2S.TYPE, (payload, context) ->
                context.server().execute(() -> handleUpload(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(CameraShotC2S.TYPE, (payload, context) ->
                context.server().execute(() -> handleCameraShot(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(CameraQueryC2S.TYPE, (payload, context) ->
                context.server().execute(() -> CameraService.query(context.player(),
                        payload.tripod(), payload.pos(), payload.action(), payload.name())));
        ServerPlayNetworking.registerGlobalReceiver(CameraFillC2S.TYPE, (payload, context) ->
                context.server().execute(() -> CameraFillLight.update(context.player(),
                        payload.on(), payload.tripod(), payload.pos())));
    }

    /** P2V 上传拼装：同一玩家同一 transfer 的块按顺序堆进内存，最后一次性落盘。 */
    private static final class Upload {
        private final String dir;
        private final String name;
        private final String mime;
        private final int total;
        /** 按分片序号落位：即使网络包乱序到达，拼出来的文件也保持原字节顺序。 */
        private final byte[][] chunks;
        private int received;
        private int bytes;

        private Upload(String dir, String name, String mime, int total) {
            this.dir = dir;
            this.name = name;
            this.mime = mime;
            this.total = total;
            this.chunks = new byte[total][];
        }

        private boolean accept(int index, byte[] data) {
            if (index < 0 || index >= this.total || data == null) {
                return false;
            }
            if (this.chunks[index] == null) {
                this.chunks[index] = data;
                this.received++;
                this.bytes += data.length;
            }
            return true;
        }

        private byte[] assemble() {
            if (this.received < this.total) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(32, this.bytes));
            for (byte[] chunk : this.chunks) {
                if (chunk == null) {
                    return null;
                }
                buffer.writeBytes(chunk);
            }
            return buffer.toByteArray();
        }
    }

    private static void handleUpload(ServerPlayer player, P2VChunkC2S payload) {
        if (player == null || payload.data() == null) {
            return;
        }
        BlockPos pos = payload.pos();
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
            return;
        }
        if (!(player.level().getBlockEntity(pos) instanceof ComputerBlockEntity computer)) {
            return;
        }
        if (payload.total() <= 0 || payload.total() > 8192) {
            return;
        }
        String key = player.getUUID() + ":" + payload.transfer();
        Upload upload = UPLOADS.computeIfAbsent(key, ignored ->
                new Upload(payload.dir(), payload.name(), payload.mime(), payload.total()));
        if (!upload.accept(payload.index(), payload.data())) {
            UPLOADS.remove(key);
            return;
        }
        if (upload.bytes > MAX_UPLOAD_BYTES) {
            UPLOADS.remove(key);
            sendState(player, pos, computer.snapshot("P2V 导入失败：文件超过 96 MiB").toString(), false);
            return;
        }
        if (upload.received < upload.total) {
            return;
        }
        UPLOADS.remove(key);
        byte[] assembled = upload.assemble();
        if (assembled == null) {
            sendState(player, pos, computer.snapshot("P2V 导入失败：传输不完整").toString(), false);
            return;
        }
        String path = computer.receiveMedia(upload.dir, upload.name, upload.mime, assembled);
        computer.syncCast();
        String message = path == null ? "P2V 导入失败：虚拟机写入失败"
                : "P2V 已导入 " + path;
        sendState(player, pos, computer.snapshot(message).toString(), false);
    }

    /** 照相机上传拼装：照片与录像按 transfer 归组，凑齐后写进相机胶卷。 */
    private static final class CameraUpload {
        private final boolean tripod;
        private final BlockPos pos;
        private final String name;
        private final String mime;
        private final int total;
        private final byte[][] chunks;
        private int received;
        private int bytes;

        private CameraUpload(boolean tripod, BlockPos pos, String name, String mime, int total) {
            this.tripod = tripod;
            this.pos = pos;
            this.name = name;
            this.mime = mime;
            this.total = total;
            this.chunks = new byte[total][];
        }

        private boolean accept(int index, byte[] data) {
            if (index < 0 || index >= this.total || data == null) {
                return false;
            }
            if (this.chunks[index] == null) {
                this.chunks[index] = data;
                this.received++;
                this.bytes += data.length;
            }
            return true;
        }

        private byte[] assemble() {
            if (this.received < this.total) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(32, this.bytes));
            for (byte[] chunk : this.chunks) {
                if (chunk == null) {
                    return null;
                }
                buffer.writeBytes(chunk);
            }
            return buffer.toByteArray();
        }
    }

    private static void handleCameraShot(ServerPlayer player, CameraShotC2S payload) {
        if (player == null || payload.data() == null) {
            return;
        }
        if (payload.total() <= 0 || payload.total() > 8192) {
            return;
        }
        if (payload.tripod()
                && player.distanceToSqr(payload.pos().getX() + 0.5D, payload.pos().getY() + 0.5D,
                payload.pos().getZ() + 0.5D) > MAX_DISTANCE_SQR) {
            return;
        }
        String key = player.getUUID() + ":cam:" + payload.transfer();
        CameraUpload upload = CAMERA_UPLOADS.computeIfAbsent(key, ignored -> new CameraUpload(
                payload.tripod(), payload.pos(), payload.name(), payload.mime(), payload.total()));
        if (!upload.accept(payload.index(), payload.data())) {
            CAMERA_UPLOADS.remove(key);
            return;
        }
        if (upload.bytes > MAX_UPLOAD_BYTES) {
            CAMERA_UPLOADS.remove(key);
            return;
        }
        if (upload.received < upload.total) {
            return;
        }
        CAMERA_UPLOADS.remove(key);
        byte[] shots = upload.assemble();
        if (shots == null) {
            return;
        }
        CameraService.appendShot(player, upload.tripod, upload.pos, upload.name, upload.mime, shots);
    }

    /** 媒体查看器拉取：把虚拟机里的一段二进制切成小包发回客户端。 */
    private static void handleMediaGet(ServerPlayer player, ComputerBlockEntity computer, String path) {
        byte[] data = computer.readMedia(path);
        if (data == null) {
            sendState(player, computer.getBlockPos(),
                    computer.snapshot("无法读取媒体：" + path).toString(), false);
            return;
        }
        String mime = computer.mimeOf(path);
        int total = Math.max(1, (data.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_BYTES;
            int end = Math.min(data.length, start + CHUNK_BYTES);
            byte[] chunk = java.util.Arrays.copyOfRange(data, start, end);
            ServerPlayNetworking.send(player, new MediaChunkS2C(
                    computer.getBlockPos(), path, mime, i, total, chunk));
        }
        computer.sendState(player, false, "");
    }

    private static void handle(ServerPlayer player, DeviceRequestC2S payload) {
        if (player == null || player.level() == null) {
            return;
        }
        BlockPos pos = payload.pos();
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
            return;
        }
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        boolean open = "open".equals(payload.action());
        if (blockEntity instanceof ComputerBlockEntity computer) {
            if (!open && "media_get".equals(payload.action())) {
                handleMediaGet(player, computer, payload.arg());
                return;
            }
            if (!open && payload.action().startsWith("camera_")) {
                CameraService.transfer(player, computer, payload.action(), payload.arg());
                return;
            }
            String message = open ? "" : computer.act(payload.action(), payload.arg());
            computer.sendState(player, open, message);
            if (!open) {
                // 投屏是实时镜像：电脑一有动作就把新画面推给附近的电视。
                computer.syncCast();
            }
            return;
        }
        if (blockEntity instanceof TvBlockEntity tv) {
            String message = open ? "" : tv.act(payload.action());
            tv.sendState(player, open, message);
        }
    }

    public static void sendState(ServerPlayer player, BlockPos pos, String json, boolean open) {
        ServerPlayNetworking.send(player, new DeviceStateS2C(pos, json, open));
    }
}
