package com.akun.hajikun.computer;

import com.akun.hajikun.device.DeviceIdentity;
import com.akun.hajikun.device.DeviceRegistry;
import com.akun.hajikun.registry.ModDataComponents;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * 电脑方块物品：掉落物可能带着整台虚拟机。
 *
 * <p>物品信息里显示设备名、MAC 与硬关机状态，让玩家在背包里就能确认
 * 手里的是哪一台机器；全新物品或鼠标中键拾取的物品没有数据，显示为未注册。</p>
 */
public final class ComputerItem extends BlockItem {
    public ComputerItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    /**
     * 物品是否承载着一台已经注册过的电脑。
     *
     * <p>这类物品代表一个唯一的 MAC/UUID：创造模式中键复制、放置后不消耗
     * 都会造成两台电脑抢同一个设备身份，因此所有防复制逻辑都以这个判断为准。</p>
     */
    public static boolean carriesDevice(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ComputerItem)) {
            return false;
        }
        byte[] data = stack.get(ModDataComponents.DEVICE_DATA);
        if (data != null && data.length > 0) {
            return true;
        }
        String summary = stack.get(ModDataComponents.DEVICE_SUMMARY);
        if (summary != null && !summary.isBlank()) {
            return true;
        }
        String carry = stack.get(ModDataComponents.DEVICE_CARRY);
        return carry != null && !carry.isBlank();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> consumer, TooltipFlag flag) {
        String summary = stack.get(ModDataComponents.DEVICE_SUMMARY);
        String carry = stack.get(ModDataComponents.DEVICE_CARRY);
        if ((summary == null || summary.isBlank()) && (carry == null || carry.isBlank())
                && stack.get(ModDataComponents.DEVICE_DATA) == null) {
            consumer.accept(Component.literal("全新设备 · 未注册").withStyle(ChatFormatting.GRAY));
            consumer.accept(Component.literal("放下后首次开机自动分配 MAC")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    summary != null && !summary.isBlank() ? summary : carry).getAsJsonObject();
            String name = root.has("name") ? root.get("name").getAsString() : "";
            String id = root.has("id") ? root.get("id").getAsString() : "";
            int boots = root.has("boots") ? root.get("boots").getAsInt() : 0;
            int files = root.has("files") ? root.get("files").getAsInt() : 0;
            consumer.accept(Component.literal("设备名：" + (name.isBlank() ? "未命名" : name))
                    .withStyle(ChatFormatting.GRAY));
            consumer.accept(Component.literal("MAC：" + mac(id)).withStyle(ChatFormatting.AQUA));
            consumer.accept(Component.literal("状态：已硬关机 · 数据已保存").withStyle(ChatFormatting.GRAY));
            consumer.accept(Component.literal("已开机 " + boots + " 次 · 主目录 " + files + " 项")
                    .withStyle(ChatFormatting.DARK_GRAY));
            consumer.accept(Component.literal("物品被销毁或丢入虚空后，设备数据将彻底释放")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } catch (RuntimeException exception) {
            consumer.accept(Component.literal("设备数据损坏").withStyle(ChatFormatting.RED));
        }
    }

    /**
     * 掉落物被烧毁、被岩浆/仙人掌/爆炸摧毁时触发：这台电脑在现实世界意义上已经报废。
     *
     * <p>设备目录里的条目会被真正删除，其它电脑的配对记录与电视投屏引用一并清理，
     * 不会留下可以按 MAC 找回的幽灵设备。物品本身随后由原版丢弃。</p>
     */
    @Override
    public void onDestroyed(ItemEntity entity) {
        super.onDestroyed(entity);
        if (!(entity.level() instanceof ServerLevel server)) {
            return;
        }
        ItemStack stack = entity.getItem();
        String id = deviceId(stack);
        if (id.isBlank()) {
            return;
        }
        DeviceRegistry registry = DeviceRegistry.get(server);
        DeviceRegistry.Entry entry = registry.byId(id);
        String mac = entry != null ? entry.mac() : derivedMac(id);
        registry.remove(id);
        if (mac != null && !mac.isBlank()) {
            DeviceRegistry.purgePeer(server, mac);
        }
        // 数据已经注销；清掉组件，防止同一份物品被指令或模组复制回来时复活旧设备。
        stack.remove(ModDataComponents.DEVICE_DATA);
        stack.remove(ModDataComponents.DEVICE_SUMMARY);
        stack.remove(ModDataComponents.DEVICE_CARRY);
    }

    /** 从掉落物的摘要或旧版携带数据里取出设备 UUID。 */
    private static String deviceId(ItemStack stack) {
        for (String json : new String[]{stack.get(ModDataComponents.DEVICE_SUMMARY),
                stack.get(ModDataComponents.DEVICE_CARRY)}) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("id")) {
                    return root.get("id").getAsString();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    private static String derivedMac(String id) {
        try {
            return DeviceIdentity.mac(UUID.fromString(id));
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /** 与方块实体完全一致：MAC 始终由设备 UUID 派生。 */
    private static String mac(String id) {
        try {
            return DeviceIdentity.mac(UUID.fromString(id));
        } catch (RuntimeException exception) {
            return "未知";
        }
    }
}
