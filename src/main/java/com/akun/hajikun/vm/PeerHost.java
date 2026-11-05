package com.akun.hajikun.vm;

/**
 * 虚拟机与物理设备世界之间的桥：蓝牙扫描/配对、电脑间传输、投屏到电视。
 *
 * <p>内核本身只认识文件与命令，所有需要“世界里的其它设备”的操作都交给宿主的
 * 方块实体实现，保证虚拟机内核保持独立。</p>
 */
public interface PeerHost {
    String scan();

    /** 按名称或 MAC 片段查找附近设备。 */
    String find(String query);

    String pair(String mac);

    String send(String mac, String path);

    String cast(String mac);

    /** 广播投屏：把本机画面送到附近所有电视。 */
    String castAll();

    String paired();
}
