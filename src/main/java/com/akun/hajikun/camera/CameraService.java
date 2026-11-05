package com.akun.hajikun.camera;

import com.akun.hajikun.computer.ComputerBlockEntity;
import com.akun.hajikun.network.SmartPayloads.CameraMediaS2C;
import com.akun.hajikun.network.SmartPayloads.CameraRollS2C;
import com.akun.hajikun.network.SmartPayloads.CameraViewerS2C;
import com.akun.hajikun.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 照相机在服务端的全部动作：保存照片/录像、列相册、传输到电脑。
 *
 * <p>胶卷只存在于服务端的物品组件里，客户端界面看到的是按需发送的目录。
 * 这样一张 640px 照片不会跟着背包同步包来回传输，也不会被客户端篡改。</p>
 */
public final class CameraService {
    private static final int CHUNK_BYTES = 24 * 1024;
    private static final double MAX_DISTANCE_SQR = 64.0D * 64.0D;

    private CameraService() {
    }

    public static ItemStack heldCamera(ServerPlayer player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof CameraItem) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (off.getItem() instanceof CameraItem) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    /** 右键电脑：只有已经开机的电脑才接收胶卷传输。 */
    public static void openTransfer(ServerPlayer player, BlockPos computerPos) {
        ItemStack camera = heldCamera(player);
        if (camera.isEmpty()) {
            player.sendOverlayMessage(Component.literal("手里没有照相机。").withStyle(ChatFormatting.RED));
            return;
        }
        if (!(player.level().getBlockEntity(computerPos) instanceof ComputerBlockEntity computer)
                || !computer.powered()) {
            player.sendOverlayMessage(Component.literal("电脑还没有开机：先空手右键电脑开机，再传输胶卷。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        sendRoll(player, computerPos, camera, "相册已载入（电脑已开机）");
    }

    public static void sendRoll(ServerPlayer player, BlockPos computerPos, ItemStack camera, String status) {
        List<CameraRoll.Entry> entries = CameraRoll.decode(camera.get(ModDataComponents.CAMERA_ROLL));
        List<String> names = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (CameraRoll.Entry entry : entries) {
            names.add(entry.name());
            mimes.add(entry.mime());
            sizes.add(Math.min(Integer.MAX_VALUE, entry.data().length));
        }
        CameraRollS2C packet = new CameraRollS2C(computerPos, names, mimes, sizes, status);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, packet);
    }

    /**
     * 取景器胶卷列表：查询目录或分块拉取某个文件。
     *
     * <p>列表本身只带文件名、MIME 与大小，图片/录像字节只在玩家点开某项时才传，
     * 避免每按一次 F 都把几十兆胶卷整个推到客户端。</p>
     */
    public static void query(ServerPlayer player, boolean tripod, BlockPos pos, String action, String name) {
        if (player == null || player.level() == null) {
            return;
        }
        ItemStack camera = tripod ? tripodCamera(player, pos) : heldCamera(player);
        if (camera.isEmpty()) {
            sendViewer(player, tripod, pos, List.of(), List.of(), List.of(),
                    tripod ? "三脚架上的照相机已经不在了" : "照相机已不在手上");
            return;
        }
        List<CameraRoll.Entry> entries = CameraRoll.decode(camera.get(ModDataComponents.CAMERA_ROLL));
        if ("media".equals(action)) {
            CameraRoll.Entry entry = CameraRoll.find(entries, name);
            if (entry == null) {
                sendViewer(player, tripod, pos, List.of(), List.of(), List.of(), "文件已不在胶卷里");
                return;
            }
            byte[] data = entry.data();
            int total = Math.max(1, (data.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
            for (int i = 0; i < total; i++) {
                int start = i * CHUNK_BYTES;
                int end = Math.min(data.length, start + CHUNK_BYTES);
                ServerPlayNetworking.send(player, new CameraMediaS2C(entry.name(), entry.mime(), i, total,
                        Arrays.copyOfRange(data, start, end)));
            }
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (CameraRoll.Entry entry : entries) {
            names.add(entry.name());
            mimes.add(entry.mime());
            sizes.add(Math.min(Integer.MAX_VALUE, entry.data().length));
        }
        sendViewer(player, tripod, pos, names, mimes, sizes,
                entries.isEmpty() ? "胶卷里还没有照片或录像" : "共 " + entries.size() + " 个文件 · 点击列表预览");
    }

    private static ItemStack tripodCamera(ServerPlayer player, BlockPos pos) {
        if (pos == null || player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D) > MAX_DISTANCE_SQR) {
            return ItemStack.EMPTY;
        }
        if (player.level().getBlockEntity(pos) instanceof TripodBlockEntity tripod && tripod.mounted()) {
            return tripod.camera();
        }
        return ItemStack.EMPTY;
    }

    private static void sendViewer(ServerPlayer player, boolean tripod, BlockPos pos, List<String> names,
                                   List<String> mimes, List<Integer> sizes, String status) {
        ServerPlayNetworking.send(player,
                new CameraViewerS2C(tripod, pos == null ? BlockPos.ZERO : pos, names, mimes, sizes, status));
    }

    /**
     * 客户端拍完一张照片/一段录像后，把完整字节追加进相机胶卷。
     * 三脚架上的照相机写在方块实体里，手持的写在当前手持物品上。
     */
    public static void appendShot(ServerPlayer player, boolean tripod, BlockPos tripodPos,
                                  String name, String mime, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        ItemStack camera;
        TripodBlockEntity holder = null;
        if (tripod) {
            if (!(player.level().getBlockEntity(tripodPos) instanceof TripodBlockEntity entity)
                    || entity.camera().isEmpty()) {
                player.sendOverlayMessage(Component.literal("三脚架上的照相机已经不在了。")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            camera = entity.camera();
            holder = entity;
        } else {
            camera = heldCamera(player);
            if (camera.isEmpty()) {
                player.sendOverlayMessage(Component.literal("照相机已不在手上，本次保存取消。")
                        .withStyle(ChatFormatting.RED));
                return;
            }
        }
        List<CameraRoll.Entry> entries = new ArrayList<>(
                CameraRoll.decode(camera.get(ModDataComponents.CAMERA_ROLL)));
        if (CameraRoll.totalBytes(entries) + data.length > CameraRoll.MAX_BYTES) {
            player.sendOverlayMessage(Component.literal("照相机已满（上限 96 MiB），请先传输到电脑。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        String unique = CameraRoll.uniqueName(entries, name);
        entries.add(new CameraRoll.Entry(unique, mime, data));
        camera.set(ModDataComponents.CAMERA_ROLL, CameraRoll.encode(entries));
        if (holder != null) {
            holder.setChanged();
        } else {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        player.sendOverlayMessage(Component.literal("已保存 " + unique + "（"
                + CameraRoll.sizeText(data.length) + "）").withStyle(ChatFormatting.GREEN));
    }

    /** 传输面板上的按钮：复制/移动单个或全部。 */
    public static void transfer(ServerPlayer player, ComputerBlockEntity computer, String action, String arg) {
        ItemStack camera = heldCamera(player);
        if (camera.isEmpty()) {
            sendRoll(player, computer.getBlockPos(), camera, "照相机已不在手上");
            return;
        }
        if (!computer.powered()) {
            sendRoll(player, computer.getBlockPos(), camera, "电脑已关机，无法写入胶卷文件");
            return;
        }
        List<CameraRoll.Entry> entries = new ArrayList<>(
                CameraRoll.decode(camera.get(ModDataComponents.CAMERA_ROLL)));
        if ("camera_refresh".equals(action)) {
            sendRoll(player, computer.getBlockPos(), camera, "相册已刷新");
            return;
        }
        boolean all = action.endsWith("_all");
        boolean move = action.startsWith("camera_move");
        List<CameraRoll.Entry> chosen = new ArrayList<>();
        if (all) {
            chosen.addAll(entries);
        } else {
            CameraRoll.Entry entry = CameraRoll.find(entries, arg);
            if (entry != null) {
                chosen.add(entry);
            }
        }
        if (chosen.isEmpty()) {
            sendRoll(player, computer.getBlockPos(), camera, "没有可传输的文件");
            return;
        }
        int copied = 0;
        int failed = 0;
        for (CameraRoll.Entry entry : chosen) {
            String directory = CameraRoll.directoryFor(entry.mime());
            String target = uniqueTarget(computer, directory, entry.name());
            String written = computer.receiveMedia(directory, target, entry.mime(), entry.data());
            if (written == null) {
                failed++;
                continue;
            }
            copied++;
            if (move) {
                CameraRoll.remove(entries, entry.name());
            }
        }
        if (move && copied > 0) {
            camera.set(ModDataComponents.CAMERA_ROLL, CameraRoll.encode(entries));
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        String status = (move ? "已移动 " : "已复制 ") + copied + " 个文件到电脑"
                + (failed > 0 ? "，失败 " + failed + " 个" : "");
        computer.syncCast();
        computer.sendState(player, false, status);
        sendRoll(player, computer.getBlockPos(), camera, status);
    }

    /** 目标目录里已有同名文件时自动加序号，绝不覆盖玩家已有资料。 */
    private static String uniqueTarget(ComputerBlockEntity computer, String directory, String name) {
        String clean = name == null || name.isBlank() ? "IMG.bin" : name.replace('/', '_').replace('\\', '_');
        String candidate = clean;
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String extension = dot > 0 ? clean.substring(dot) : "";
        int index = 2;
        while (computer.fs().find(directory + "/" + candidate) != null) {
            candidate = base + "_" + index++ + extension;
        }
        return candidate;
    }
}
