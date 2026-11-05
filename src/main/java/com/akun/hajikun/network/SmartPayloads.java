package com.akun.hajikun.network;

import com.akun.hajikun.HajikunSmartMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 电脑界面与服务端虚拟机之间的两个网络包。 */
public final class SmartPayloads {
    private SmartPayloads() {
    }

    /** 客户端 → 服务端：在一台电脑上执行动作（打开、开机、软关机、硬关机、改名、终端命令）。 */
    public record DeviceRequestC2S(BlockPos pos, String action, String arg) implements CustomPacketPayload {
        public static final Type<DeviceRequestC2S> TYPE = new Type<>(HajikunSmartMod.id("device_request"));
        public static final StreamCodec<FriendlyByteBuf, DeviceRequestC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceRequestC2S::pos,
                ByteBufCodecs.STRING_UTF8, DeviceRequestC2S::action,
                ByteBufCodecs.STRING_UTF8, DeviceRequestC2S::arg,
                DeviceRequestC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：整台虚拟机的快照；open 为 true 时客户端顺便打开界面。 */
    public record DeviceStateS2C(BlockPos pos, String json, boolean open) implements CustomPacketPayload {
        public static final Type<DeviceStateS2C> TYPE = new Type<>(HajikunSmartMod.id("device_state"));
        public static final StreamCodec<FriendlyByteBuf, DeviceStateS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DeviceStateS2C::pos,
                ByteBufCodecs.STRING_UTF8, DeviceStateS2C::json,
                ByteBufCodecs.BOOL, DeviceStateS2C::open,
                DeviceStateS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 客户端 → 服务端：P2V（physical to virtual）导入的一小块文件数据。
     *
     * <p>物理机上的文件先在客户端读完，再切成 24KB 的小块按顺序发到服务端，
     * 由服务端拼回完整字节写入虚拟机磁盘；这样不用把整个视频塞进一个网络包。</p>
     */
    public record P2VChunkC2S(BlockPos pos, String transfer, String dir, String name, String mime,
                              int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<P2VChunkC2S> TYPE = new Type<>(HajikunSmartMod.id("p2v_chunk"));
        public static final StreamCodec<FriendlyByteBuf, P2VChunkC2S> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, P2VChunkC2S::pos,
                ByteBufCodecs.STRING_UTF8, P2VChunkC2S::transfer,
                ByteBufCodecs.STRING_UTF8, P2VChunkC2S::dir,
                ByteBufCodecs.STRING_UTF8, P2VChunkC2S::name,
                ByteBufCodecs.STRING_UTF8, P2VChunkC2S::mime,
                ByteBufCodecs.VAR_INT, P2VChunkC2S::index,
                ByteBufCodecs.VAR_INT, P2VChunkC2S::total,
                ByteBufCodecs.BYTE_ARRAY, P2VChunkC2S::data,
                P2VChunkC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 客户端 → 服务端：照相机拍完的文件分块上传。
     *
     * <p>手持照相机时 {@code tripod} 为 false，文件写进玩家手上的相机；
     * 装在三脚架上时写进该坐标方块实体里的相机。照片是 JPEG，录像是 .hkv。</p>
     */
    public record CameraShotC2S(boolean tripod, BlockPos pos, String transfer, String name, String mime,
                                int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<CameraShotC2S> TYPE = new Type<>(HajikunSmartMod.id("camera_shot"));
        public static final StreamCodec<FriendlyByteBuf, CameraShotC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, CameraShotC2S::tripod,
                BlockPos.STREAM_CODEC, CameraShotC2S::pos,
                ByteBufCodecs.STRING_UTF8, CameraShotC2S::transfer,
                ByteBufCodecs.STRING_UTF8, CameraShotC2S::name,
                ByteBufCodecs.STRING_UTF8, CameraShotC2S::mime,
                ByteBufCodecs.VAR_INT, CameraShotC2S::index,
                ByteBufCodecs.VAR_INT, CameraShotC2S::total,
                ByteBufCodecs.BYTE_ARRAY, CameraShotC2S::data,
                CameraShotC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：照相机相册目录，右键电脑时打开传输面板。 */
    public record CameraRollS2C(BlockPos pos, List<String> names, List<String> mimes,
                                List<Integer> sizes, String status) implements CustomPacketPayload {
        public static final Type<CameraRollS2C> TYPE = new Type<>(HajikunSmartMod.id("camera_roll"));
        public static final StreamCodec<FriendlyByteBuf, CameraRollS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, CameraRollS2C::pos,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), CameraRollS2C::names,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), CameraRollS2C::mimes,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), CameraRollS2C::sizes,
                ByteBufCodecs.STRING_UTF8, CameraRollS2C::status,
                CameraRollS2C::new);

        public CameraRollS2C {
            names = List.copyOf(names);
            mimes = List.copyOf(mimes);
            sizes = List.copyOf(sizes);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 客户端 → 服务端：取景器里按 F 打开胶卷列表时查询目录或某个文件的完整字节。
     *
     * <p>手持相机时 {@code tripod} 为 false；三脚架相机需要带上支架坐标。
     * action 为 {@code list} 时只列目录，为 {@code media} 时按 name 分块回传文件。</p>
     */
    public record CameraQueryC2S(boolean tripod, BlockPos pos, String action, String name)
            implements CustomPacketPayload {
        public static final Type<CameraQueryC2S> TYPE = new Type<>(HajikunSmartMod.id("camera_query"));
        public static final StreamCodec<FriendlyByteBuf, CameraQueryC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, CameraQueryC2S::tripod,
                BlockPos.STREAM_CODEC, CameraQueryC2S::pos,
                ByteBufCodecs.STRING_UTF8, CameraQueryC2S::action,
                ByteBufCodecs.STRING_UTF8, CameraQueryC2S::name,
                CameraQueryC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：胶卷目录，供取景器里的胶卷列表预览。 */
    public record CameraViewerS2C(boolean tripod, BlockPos pos, List<String> names, List<String> mimes,
                                  List<Integer> sizes, String status) implements CustomPacketPayload {
        public static final Type<CameraViewerS2C> TYPE = new Type<>(HajikunSmartMod.id("camera_viewer"));
        public static final StreamCodec<FriendlyByteBuf, CameraViewerS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, CameraViewerS2C::tripod,
                BlockPos.STREAM_CODEC, CameraViewerS2C::pos,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), CameraViewerS2C::names,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), CameraViewerS2C::mimes,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), CameraViewerS2C::sizes,
                ByteBufCodecs.STRING_UTF8, CameraViewerS2C::status,
                CameraViewerS2C::new);

        public CameraViewerS2C {
            names = List.copyOf(names);
            mimes = List.copyOf(mimes);
            sizes = List.copyOf(sizes);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：胶卷列表预览所需的文件分块（24KB 一片）。 */
    public record CameraMediaS2C(String name, String mime, int index, int total, byte[] data)
            implements CustomPacketPayload {
        public static final Type<CameraMediaS2C> TYPE = new Type<>(HajikunSmartMod.id("camera_media"));
        public static final StreamCodec<FriendlyByteBuf, CameraMediaS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CameraMediaS2C::name,
                ByteBufCodecs.STRING_UTF8, CameraMediaS2C::mime,
                ByteBufCodecs.VAR_INT, CameraMediaS2C::index,
                ByteBufCodecs.VAR_INT, CameraMediaS2C::total,
                ByteBufCodecs.BYTE_ARRAY, CameraMediaS2C::data,
                CameraMediaS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：媒体查看器拉取的一张图片/一段录音/一段录像。 */
    public record MediaChunkS2C(BlockPos pos, String path, String mime, int index, int total,
                                byte[] data) implements CustomPacketPayload {
        public static final Type<MediaChunkS2C> TYPE = new Type<>(HajikunSmartMod.id("media_chunk"));
        public static final StreamCodec<FriendlyByteBuf, MediaChunkS2C> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, MediaChunkS2C::pos,
                ByteBufCodecs.STRING_UTF8, MediaChunkS2C::path,
                ByteBufCodecs.STRING_UTF8, MediaChunkS2C::mime,
                ByteBufCodecs.VAR_INT, MediaChunkS2C::index,
                ByteBufCodecs.VAR_INT, MediaChunkS2C::total,
                ByteBufCodecs.BYTE_ARRAY, MediaChunkS2C::data,
                MediaChunkS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 客户端 → 服务端：补光灯开或关。
     *
     * <p>补光不是后期提亮，而是在取景位置放一盏原版 15 级光源；
     * 服务端每 tick 让它跟着玩家或三脚架镜头走，关灯时再撤掉。</p>
     */
    public record CameraFillC2S(boolean on, boolean tripod, BlockPos pos) implements CustomPacketPayload {
        public static final Type<CameraFillC2S> TYPE = new Type<>(HajikunSmartMod.id("camera_fill"));
        public static final StreamCodec<FriendlyByteBuf, CameraFillC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, CameraFillC2S::on,
                ByteBufCodecs.BOOL, CameraFillC2S::tripod,
                BlockPos.STREAM_CODEC, CameraFillC2S::pos,
                CameraFillC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
