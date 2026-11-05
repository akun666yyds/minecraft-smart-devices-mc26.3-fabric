package com.akun.hajikun.registry;

import com.akun.hajikun.HajikunSmartMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import java.nio.ByteBuffer;

/** 自定义物品组件：让掉落物携带整台虚拟机。 */
public final class ModDataComponents {
    /**
     * 只在服务端存在的大数据组件在网络上的占位编码：写入零字节，读取时还原成空值。
     *
     * <p>26.3 的 {@code DataComponentType} 若没有显式声明 {@code networkSynchronized}，
     * 会自动退回持久化 codec 参与网络同步（{@code ByteBufCodecs.fromCodecWithRegistries}）。
     * 一台装满 P2V 文件的电脑可达数 MB，塞进背包同步包会直接超过协议上限，
     * 客户端解码失败被踢下线。这些数据客户端完全不需要（目录由专用包下发），
     * 因此网络上只传空值，真正的字节始终留在服务端的物品栈里。</p>
     *
     * <p>不能用 {@link StreamCodec#unit}：它会在编码时校验值必须等于占位值，
     * 服务端手里是大字节数组就会抛出「Can't encode」。</p>
     */
    private static final StreamCodec<Object, byte[]> EMPTY_BYTES = StreamCodec.of(
            (buffer, value) -> {
            },
            buffer -> new byte[0]);
    private static final StreamCodec<Object, String> EMPTY_STRING = StreamCodec.of(
            (buffer, value) -> {
            },
            buffer -> "");
    /**
     * 字节数组编解码器。1.21.5 之后 {@code Codec} 不再提供 BYTE_ARRAY，
     * 但 ByteBuffer 编解码器在 NBT 里就是 ByteArrayTag，同样没有 64KB 字符串上限。
     */
    public static final Codec<byte[]> BYTE_ARRAY = Codec.BYTE_BUFFER.xmap(
            buffer -> {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return bytes;
            },
            ByteBuffer::wrap);

    /**
     * 电脑掉落物上的虚拟机快照（JSON 字符串）。破坏电脑时写入，重新放置时还原，
     * 这样「破坏默认软关机」真正保住了机器里的文件与目录。
     */
    public static final DataComponentType<String> DEVICE_CARRY = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            HajikunSmartMod.id("device_carry"),
            DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(EMPTY_STRING)
                    .build());

    /**
     * 新版设备数据：整台虚拟机（含 P2V 导入的图片、音视频）压缩后的字节。
     * 字节数组在 NBT 里没有 64KB 字符串上限，且不在网络上同步，避免大文件刷包。
     */
    public static final DataComponentType<byte[]> DEVICE_DATA = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            HajikunSmartMod.id("device_data"),
            DataComponentType.<byte[]>builder()
                    .persistent(BYTE_ARRAY)
                    .networkSynchronized(EMPTY_BYTES)
                    .build());

    /**
     * 照相机胶卷：照片与录像的完整字节。只在服务端读写，不同步到客户端，
     * 避免大文件跟着背包刷新反复占带宽；传输面板按需下发目录，拍摄时按块上传。
     */
    public static final DataComponentType<byte[]> CAMERA_ROLL = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            HajikunSmartMod.id("camera_roll"),
            DataComponentType.<byte[]>builder()
                    .persistent(BYTE_ARRAY)
                    .networkSynchronized(EMPTY_BYTES)
                    .build());

    /** 物品信息里显示的小摘要：设备名、UUID、开机次数。 */
    public static final DataComponentType<String> DEVICE_SUMMARY = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            HajikunSmartMod.id("device_summary"),
            DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    private ModDataComponents() {
    }

    public static void initialize() {
        // 静态字段在类加载时完成注册。
    }
}
