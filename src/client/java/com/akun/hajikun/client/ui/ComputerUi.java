package com.akun.hajikun.client.ui;

import com.akun.hajikun.client.HajikunSmartClient;
import com.akun.hajikun.client.browser.WebKitEngine;
import com.akun.hajikun.client.browser.ElectronEngine;
import com.akun.hajikun.client.ide.IdeApp;
import com.akun.hajikun.client.media.HkvVideo;
import com.akun.hajikun.client.media.MediaAudio;
import com.akun.hajikun.client.media.MediaCache;
import com.akun.hajikun.client.media.P2VImport;
import com.akun.hajikun.client.media.VideoPlayer;
import com.akun.hajikun.client.net.BrowserClient;
import com.akun.hajikun.client.net.WebMedia;
import com.akun.hajikun.client.render.Canvas;
import com.akun.hajikun.client.render.ScaledCanvas;
import com.akun.hajikun.computer.ComputerKind;
import com.akun.hajikun.computer.DeviceSnapshot;
import com.akun.hajikun.vm.VirtualFileSystem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.net.URLEncoder;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * 电脑屏幕里的操作系统界面。
 *
 * <p>这个类不做 3D 或全屏 GUI 绘制，只在一张二维画布上排布桌面、窗口和文字。
 * 方块渲染器把整张画布贴到显示器表面，鼠标射线负责把光标落到屏幕上，
 * 因此聚焦时仍然是玩家自己的第一人称视野，显示器、键盘和桌面上的东西都看得见。</p>
 *
 * <p>外观按 Windows XP 的风格排布：蓝天绿丘桌面、左列图标、蓝色任务栏与开始菜单、
 * Luna 标题栏窗口；任务栏托盘里始终保留电源键，任何状态下都能关机或开机。</p>
 */
public final class ComputerUi {
    public static final int WIDTH = 320;
    public static final int DESKTOP_HEIGHT = 247;
    public static final int LAPTOP_HEIGHT = 208;
    /** 屏幕逻辑画布的宽高比；电视等外接屏幕统一按这个比例显示。 */
    public static final float ASPECT = WIDTH / (float) DESKTOP_HEIGHT;
    private static final int TASKBAR = 22;
    private static final int TITLEBAR = 18;
    private static final int ROW = 12;
    private static final int CHIP_H = 15;
    private static final int LINE = 10;

    /** IDE 的逻辑分辨率倍数：排版坐标是屏幕像素的两倍，绘制时缩回一半，字更细。 */
    private static final int IDE_SCALE = 2;

    private static final int ICON_X = 3;
    private static final int ICON_TOP = 4;
    private static final int ICON_W = 62;
    private static final int ICON_H = 34;

    private static final int START_X = 2;
    private static final int START_W = 58;
    private static final int MENU_X = 2;
    private static final int MENU_W = 150;
    private static final int MENU_HEADER = 26;
    private static final int MENU_ROW = 21;
    private static final int MENU_FOOTER = 26;

    private static final int DIALOG_W = 214;
    private static final int DIALOG_H = 116;
    private static final int POWER_SIZE = 16;

    private static final int COLOR_TEXT = 0xFF1A1A1A;
    private static final int COLOR_MUTED = 0xFF5E5E5E;
    private static final int COLOR_DIM = 0xFF8A8A8A;
    private static final int COLOR_FACE = 0xFFECE9D8;
    private static final int COLOR_FACE_EDGE = 0xFF9C9A8E;
    private static final int COLOR_ACCENT = 0xFF00309E;
    private static final int COLOR_GREEN = 0xFF157A1C;
    private static final int COLOR_WARN = 0xFF9A5A00;
    private static final int COLOR_BLUE_DARK = 0xFF0A4AA8;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmmss");
    private static final String[] APP_NAMES = {
            "我的电脑", "命令提示符", "P2V协议", "记事本", "控制面板", "蓝牙", "JavaIDE", "浏览器"};
    /** 桌面与开始菜单的图标顺序；不直接用 App.values()，避免新增枚举后错位。 */
    private static final App[] DESKTOP_APPS = {
            App.FILES, App.TERMINAL, App.P2V, App.EDITOR, App.SETTINGS, App.BLUETOOTH, App.JAVAIDE,
            App.BROWSER};
    private static final String[] FILE_CHIPS = {"上级", "新建文件夹", "新建文件", "刷新"};
    /** P2V 固定把物理机文件传到这个虚拟机目录，文件管理器里能直接看到。 */
    private static final String P2V_DIR = "/home/user/导入";
    private static final long DOUBLE_CLICK_MS = 420L;
    /** 媒体播放器支持的倍速档位。 */
    private static final float[] MEDIA_SPEEDS = {0.5F, 1.0F, 1.5F, 2.0F};
    /** 图片缩放下限与上限。 */
    private static final double MIN_ZOOM = 0.25D;
    private static final double MAX_ZOOM = 8.0D;

    private enum App { FILES, TERMINAL, P2V, EDITOR, SETTINGS, BLUETOOTH, VIEWER, JAVAIDE, BROWSER }

    private enum Focus {
        NONE, SETUP, TERMINAL, EDITOR, RENAME, FILE_NAME, IDE_CHAT, ADDRESS_FILES, ADDRESS_P2V,
        /** 浏览器地址栏或网页表单字段；具体目标记在窗口的 browserField 上。 */
        ADDRESS_BROWSER,
        /** 页内查找条。 */
        FIND
    }

    private static final String BROWSER_HOME = "electron://home";
    private static final String BROWSER_ABOUT = "electron://about";
    private static final String BROWSER_WIKI = "electron://wiki";
    private static final String BROWSER_BOOKMARKS = "electron://bookmarks";
    private static final String BROWSER_HISTORY = "electron://history";
    private static final String BROWSER_DOWNLOADS = "electron://downloads";
    private static final String BROWSER_SETTINGS = "electron://settings";
    private static final String BROWSER_COOKIE_FILE = "/home/user/.electron/cookies.json";
    private static final String BROWSER_BOOKMARK_FILE = "/home/user/.electron/bookmarks.json";
    private static final String BROWSER_HISTORY_FILE = "/home/user/.electron/history.json";
    private static final String BROWSER_SETTINGS_FILE = "/home/user/.electron/settings.json";
    private static final String BROWSER_DOWNLOAD_FILE = "/home/user/.electron/downloads.json";
    private static final String BROWSER_DOWNLOAD_DIR = "/home/user/下载";
    private static final int BROWSER_HISTORY_MAX = 120;
    private static final DateTimeFormatter BROWSER_STAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** 内置 electron:// 页面共用的样式：全部流式宽度，窄视口也不出横向滚动条。 */
    private static final String BROWSER_PAGE_CSS =
            "*{box-sizing:border-box;}"
            + "html,body{width:100%;margin:0;padding:0;background:#FFFFFF;color:#1A1A1A;"
            + "font-family:'Segoe UI','Microsoft YaHei',sans-serif;font-size:15px;"
            + "line-height:1.55;overflow-wrap:anywhere;word-break:break-word;}"
            // 网页按桌面宽度（虚拟机屏幕约 1200 CSS 像素）排版，正文收在 860 像素内，
            // 行太长不好读；窄窗口时仍然是原来的整宽排版。
            + ".wrap{padding:18px 22px;max-width:860px;margin:0 auto;}"
            + "h1{font-size:26px;line-height:1.25;margin:0 0 8px 0;}"
            + "h2{font-size:17px;margin:20px 0 8px 0;}"
            + "p{margin:0 0 10px 0;}"
            + "a{color:#1A4FB4;}"
            + ".hero{background:#F2F6FF;padding:16px 18px;margin:0 0 18px 0;border-radius:4px;}"
            + ".card{background:#F6F7F9;padding:10px 12px;margin:0 0 10px 0;border-radius:4px;}"
            + ".muted{color:#6B7280;}"
            + ".small{font-size:12px;}"
            + "form{display:flex;gap:8px;align-items:center;margin:0 0 10px 0;}"
            + ".field{flex:1 1 auto;min-width:0;width:auto;padding:6px 9px;font-size:15px;}"
            + "button{flex:0 0 auto;padding:6px 14px;font-size:15px;}"
            + "img,video,canvas{max-width:100%;height:auto;}"
            + "pre{white-space:pre-wrap;}"
            + ".actions{margin:12px 0 0 0;}";

    private final BlockPos pos;
    private final ComputerKind kind;
    private final int height;
    private DeviceSnapshot device;
    private final List<Win> windows = new ArrayList<>();
    /** IDE 会话独立于窗口：关窗或最小化后仍保留当前对话，再次打开直接续上。 */
    private IdeApp ideSession;

    private double mouseX = -1.0D;
    private double mouseY = -1.0D;
    private boolean localFocus;
    private Win dragging;
    private int dragOffX;
    private int dragOffY;
    private Focus focus = Focus.NONE;
    private Win focusWin;
    /** 当前聚焦文本框的光标与选区；切换字段时重新绑定文本。 */
    private final TextEdit edit = new TextEdit();
    private boolean editDragging;
    /** 媒体查看器里的拖动：图片平移或进度条跳转。 */
    private Win viewerDragWin;
    private int viewerDragMode;
    private double viewerDragX;
    private double viewerDragY;
    private Win pendingShell;
    private String setupText = "";
    private String toast = "";
    private long toastUntil;
    private int appSeq;
    private boolean startMenu;
    private boolean shutdownDialog;
    /** 当前打开的层叠右键菜单；每个菜单项可以再挂一个子菜单。 */
    private Menu menu;
    private double menuAnchorX;
    private double menuAnchorY;
    /** 每帧重建的悬浮气泡候选；鼠标停在同一处 480ms 后显示完整文字。 */
    private final List<Tip> tips = new ArrayList<>();
    private String tipText = "";
    private long tipSince;
    private double tipX;
    private double tipY;
    /** 浏览器书签、历史与设置从虚拟机文件懒加载，修改后立即写回。 */
    private boolean browserStoreLoaded;
    private final List<BrowserBookmark> bookmarks = new ArrayList<>();
    private final List<BrowserVisit> visits = new ArrayList<>();
    private final List<BrowserDownload> downloads = new ArrayList<>();
    /** 与 Node 引擎共用的 Cookie 表；按「名字|域名」去重，写回虚拟机磁盘。 */
    private final Map<String, ElectronEngine.Cookie> browserCookies = new HashMap<>();
    private final BrowserSettings browserSettings = new BrowserSettings();
    /** 第一次打开浏览器时拉起 Node；失败后不再反复尝试。 */
    private boolean browserEngineTried;
    /** Chromium 每帧画面贴成的动态纹理，按标签页编号缓存。 */
    private final Map<Integer, ChromiumTexture> chromiumTextures = new HashMap<>();
    private long chromiumMoveAt;
    private long chromiumGuardUntil;

    public ComputerUi(BlockPos pos, ComputerKind kind, DeviceSnapshot device) {
        this.pos = pos;
        this.kind = kind;
        this.height = kind == ComputerKind.LAPTOP ? LAPTOP_HEIGHT : DESKTOP_HEIGHT;
        this.device = device;
        this.setupText = device.name == null ? "" : device.name;
        this.edit.set(this.setupText);
        if (!device.named()) {
            this.focus = Focus.SETUP;
        }
    }

    public BlockPos pos() {
        return this.pos;
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return this.height;
    }

    public void setLocalFocus(boolean focused) {
        this.localFocus = focused;
        if (focused) {
            this.toast("点击屏幕操作，Esc 或 Shift 离开");
        } else {
            this.mouseX = -1.0D;
            this.mouseY = -1.0D;
            this.dragging = null;
            this.startMenu = false;
            this.shutdownDialog = false;
            this.menu = null;
        }
    }

    /** 服务端返回新快照：刷新内容，终端命令的输出追加到发起命令的窗口。 */
    public void update(DeviceSnapshot snapshot) {
        boolean wasPowered = this.device != null && this.device.powered;
        boolean wasNamed = this.device != null && this.device.named();
        this.device = snapshot;
        if (snapshot.message != null && !snapshot.message.isBlank()) {
            if (this.pendingShell != null) {
                Win shell = this.pendingShell;
                this.pendingShell = null;
                if (snapshot.message.equals("__CLEAR__")) {
                    shell.log.clear();
                } else {
                    for (String line : snapshot.message.split("\n")) {
                        shell.log.add(line);
                    }
                }
                this.trimLog();
            } else {
                // Cookie 配置、会话与 IDE 内部文件是静默写入，不弹提示。
                boolean internal = snapshot.message.startsWith("已保存 /home/user/.config/")
                        || snapshot.message.contains("/.ide/")
                        || snapshot.message.contains("/.electron/");
                if (!internal) {
                    this.toast(snapshot.message);
                }
                // 文件与蓝牙窗口的底部状态行跟随服务端结果，避免停在“正在…”。
                Win files = this.windowOf(App.FILES);
                if (files != null) {
                    files.status = snapshot.message;
                }
                Win bluetooth = this.windowOf(App.BLUETOOTH);
                if (bluetooth != null) {
                    bluetooth.status = snapshot.message;
                }
            }
        }
        if (!wasNamed && snapshot.named()) {
            this.focus = Focus.NONE;
            this.focusWin = null;
            this.setupText = snapshot.name;
        }
        if (!wasPowered && snapshot.powered) {
            this.toast("欢迎使用 哈基坤 OS");
        }
        if (wasPowered && !snapshot.powered) {
            this.focus = Focus.NONE;
            this.focusWin = null;
            this.dragging = null;
            this.startMenu = false;
            this.shutdownDialog = false;
            // 软关机保留会话，硬关机（或被别人硬关机）则整段会话丢弃。
            if (!snapshot.suspended) {
                this.dropSession();
            }
        }
        // IDE 是电脑里的普通窗口，文件与命令结果同样通过快照回到它手里；
        // 窗口关掉时由 ideSession 在后台继续接收，智能体不会因为关窗而掉线。
        boolean ideOpen = false;
        for (Win win : this.windows) {
            if (win.ide != null) {
                win.ide.updateSnapshot(snapshot);
                if (win.app == App.JAVAIDE) {
                    ideOpen = true;
                }
            }
        }
        if (!ideOpen && this.ideSession != null) {
            this.ideSession.updateSnapshot(snapshot);
        }
    }

    /** 丢弃当前运行会话：关掉所有窗口并清掉待回填的终端输出。 */
    private void dropSession() {
        for (Win win : this.windows) {
            this.releaseMedia(win);
            for (BrowserTab tab : win.tabs) {
                this.releaseChromiumTab(tab);
            }
            win.ide = null;
        }
        if (this.ideSession != null) {
            this.ideSession.dispose();
            this.ideSession = null;
        }
        this.windows.clear();
        this.pendingShell = null;
        this.focusWin = null;
        this.focus = Focus.NONE;
        this.dragging = null;
        this.startMenu = false;
        this.menu = null;
    }

    private void trimLog() {
        for (Win win : this.windows) {
            while (win.log.size() > 300) {
                win.log.remove(0);
            }
        }
    }

    private void toast(String message) {
        this.toast = message;
        this.toastUntil = System.currentTimeMillis() + 4200L;
    }

    // ------------------------------------------------------------------ 绘制

    public void render(Canvas c) {
        this.tips.clear();
        if (!this.device.named()) {
            this.renderSetup(c);
        } else if (!this.device.powered) {
            this.renderOff(c);
        } else {
            this.renderDesktop(c);
            this.renderTaskbar(c);
            if (this.startMenu) {
                this.renderStartMenu(c);
            }
        }
        if (this.shutdownDialog && this.device.named() && this.device.powered) {
            this.renderShutdown(c);
        }
        this.renderPower(c);
        this.renderToast(c);
        this.renderMenus(c);
        this.renderTips(c);
        this.renderCursor(c);
    }

    /** 投屏到电视时的画面：只要桌面与窗口，不画光标、提示条和电源键。 */
    public void renderMirror(Canvas c) {
        if (!this.device.named() || !this.device.powered) {
            this.renderOff(c);
            return;
        }
        this.renderDesktop(c);
        this.renderTaskbar(c);
        if (this.startMenu) {
            this.renderStartMenu(c);
        }
    }

    /** 蓝天、白云与绿丘的 XP 桌面，图标竖排在左侧。 */
    private void renderDesktop(Canvas c) {
        int h = c.height();
        int horizon = (int) (h * 0.60D);
        c.gradient(0, 0, c.width(), horizon + 10, 0xFF1B5FC0, 0xFF9BCFF2);
        this.cloud(c, 34, 20, 1.0F);
        this.cloud(c, 186, 14, 1.25F);
        this.cloud(c, 244, 52, 0.8F);

        int hillBase = (int) (h * 0.56D);
        int amp = (int) (h * 0.11D);
        int step = 2;
        for (int x = 0; x < c.width(); x += step) {
            double t = x / (double) c.width();
            int top = (int) (hillBase - Math.sin(t * Math.PI) * amp - Math.sin(t * Math.PI * 2.0D) * 5.0D);
            c.fill(x, top, x + step, h, 0xFF57A03A);
            c.fill(x, top, x + step, top + 2, 0xFF93CE63);
            c.fill(x, top + (h - top) / 2, x + step, h, 0xFF3F812C);
        }
        for (int i = 0; i < 5; i++) {
            int sx = i * 64 + 10;
            c.fill(sx, hillBase, sx + 24, h, 0x0DFFFFFF);
        }
        this.centeredShadowed(c, "HAJIKUN OS", c.width() - 46, h - TASKBAR - 13, 0xBBFFFFFF);

        for (int i = 0; i < APP_NAMES.length; i++) {
            int ix = this.iconX(i);
            int iy = this.iconY(i);
            boolean hover = !this.startMenu && !this.shutdownDialog
                    && this.topWinAt(this.mouseX, this.mouseY) == null
                    && this.inside(ix, iy, ICON_W, ICON_H);
            if (hover) {
                c.fill(ix, iy, ix + ICON_W, iy + ICON_H, 0x55316AC5);
                c.outline(ix, iy, ix + ICON_W, iy + ICON_H, 0x99A8C8F0);
            }
            this.appIcon(c, i, ix + (ICON_W - 16) / 2, iy + 2);
            this.centeredShadowed(c, this.fitText(APP_NAMES[i], ICON_W), ix + ICON_W / 2, iy + 20,
                    0xFFFFFFFF);
            // 窗口盖住桌面时，被遮住的图标不能再抢悬浮气泡，否则气泡会从窗口上冒出来。
            boolean exposed = !this.startMenu && !this.shutdownDialog
                    && this.topWinAt(this.mouseX, this.mouseY) == null;
            if (exposed) {
                this.tip(ix, iy, ICON_W, ICON_H, APP_NAMES[i]);
            }
        }

        for (Win win : this.windows) {
            if (!win.minimized) {
                this.renderWindow(c, win);
            }
        }
    }

    /** 一列能放几个图标；放不下就换到下一列，图标不会越过任务栏。 */
    private int iconRows() {
        return Math.max(1, (this.height - TASKBAR - ICON_TOP - 2) / ICON_H);
    }

    private int iconColumn(int index) {
        return index / this.iconRows();
    }

    private int iconX(int index) {
        return ICON_X + this.iconColumn(index) * (ICON_W + 2);
    }

    private int iconY(int index) {
        return ICON_TOP + (index % this.iconRows()) * ICON_H;
    }

    private void cloud(Canvas c, int x, int y, float scale) {
        int w = (int) (28 * scale);
        int body = (int) (5 * scale);
        int white = 0x77FFFFFF;
        int soft = 0x33FFFFFF;
        c.fill(x, y + body, x + w, y + body + 4, white);
        c.fill(x + 5, y + 2, x + w - 5, y + body + 3, white);
        c.fill(x + 9, y, x + w - 10, y + body, white);
        c.fill(x + 2, y + body + 4, x + w - 2, y + body + 6, soft);
    }

    private void renderTaskbar(Canvas c) {
        int ty = c.height() - TASKBAR;
        c.gradient(0, ty, c.width(), c.height(), 0xFF3F8CF3, 0xFF1E5FC9);
        c.fill(0, ty, c.width(), ty + 1, 0xFF9CC8FF);
        c.fill(0, c.height() - 1, c.width(), c.height(), 0xFF103E8E);

        boolean startHover = this.inside(START_X, ty + 2, START_W, 18);
        int startTop = this.startMenu || startHover ? 0xFFA8E87C : 0xFF8FDD64;
        c.gradient(START_X, ty + 2, START_X + START_W, ty + 20, startTop, 0xFF3E9B1F);
        c.outline(START_X, ty + 2, START_X + START_W, ty + 20, 0xFF2A6E14);
        int fx = START_X + 5;
        int fy = ty + 5;
        c.fill(fx, fy, fx + 5, fy + 4, 0xFFE24B3E);
        c.fill(fx + 6, fy + 1, fx + 11, fy + 4, 0xFF7BC043);
        c.fill(fx, fy + 6, fx + 5, fy + 9, 0xFF3E8FE0);
        c.fill(fx + 6, fy + 7, fx + 11, fy + 10, 0xFFF2C230);
        this.shadowed(c, "开始", START_X + 21, ty + 7, 0xFFFFFFFF);

        String clock = LocalTime.now().format(CLOCK);
        int clockX = c.width() - 24 - c.textWidth(clock);
        String state = this.device.suspended ? "已恢复" : "运行中";
        int stateX = clockX - 8 - c.textWidth(state);
        c.fill(stateX - 8, ty + 9, stateX - 4, ty + 13,
                this.device.suspended ? 0xFFFFD24A : 0xFF6BE06B);
        this.shadowed(c, state, stateX, ty + 7, 0xFFFFFFFF);
        this.shadowed(c, clock, clockX, ty + 7, 0xFFFFFFFF);

        int x = START_X + START_W + 6;
        int end = stateX - 16;
        Win top = null;
        for (Win win : this.windows) {
            if (!win.minimized) {
                top = win;
            }
        }
        for (Win win : this.windows) {
            if (x + 30 > end) {
                break;
            }
            int w = Math.min(76, c.textWidth(win.title) + 16);
            boolean hover = this.inside(x, ty + 2, w, TASKBAR - 4);
            int bg = win.minimized ? (hover ? 0xFF4E86D8 : 0xFF2E66C2)
                    : win == top ? 0xFF1B52B4 : hover ? 0xFF5B95EA : 0xFF3F80E0;
            c.fill(x, ty + 2, x + w, ty + TASKBAR - 2, bg);
            c.outline(x, ty + 2, x + w, ty + TASKBAR - 2, 0xFF2A63C8);
            int labelWidth = w - 10;
            this.shadowed(c, this.fitText(win.title, labelWidth), x + 6, ty + 7,
                    win.minimized ? 0xFFC8D6EA : 0xFFFFFFFF);
            this.tip(x, ty + 2, w, TASKBAR - 4, win.title);
            if (win.minimized) {
                // 最小化的窗口在任务栏右侧留一个小方块标记，和「还原」状态区分开。
                c.fill(x + w - 6, ty + TASKBAR - 7, x + w - 3, ty + TASKBAR - 4, 0xFF9FC0F0);
            }
            x += w + 3;
        }
    }

    private void renderWindow(Canvas c, Win win) {
        if (win.app == App.JAVAIDE) {
            // IDE 也使用系统标题栏：深色底 + 同一口乂三个按钮，和其他窗口一致。
            c.fill(win.x + 3, win.y + 3, win.x + win.w + 3, win.y + win.h + 3, 0x55000000);
            c.fill(win.x, win.y, win.x + win.w, win.y + win.h, 0xFF1E1E1E);
            c.outline(win.x, win.y, win.x + win.w, win.y + win.h, 0xFF08316B);
            this.renderTitleBar(c, win, true);
            if (win.ide != null) {
                c.pushClip(win.x, win.y + TITLEBAR, win.x + win.w, win.y + win.h);
                this.renderIde(c, win, win.x, win.y + TITLEBAR, win.w, win.h - TITLEBAR);
                c.popClip();
            }
            return;
        }
        c.fill(win.x + 3, win.y + 3, win.x + win.w + 3, win.y + win.h + 3, 0x55000000);
        c.fill(win.x, win.y, win.x + win.w, win.y + win.h, COLOR_FACE);
        c.outline(win.x, win.y, win.x + win.w, win.y + win.h, 0xFF08316B);
        this.renderTitleBar(c, win, false);

        int cx = win.x + 6;
        int cy = win.y + TITLEBAR + 4;
        int cw = win.w - 12;
        int ch = win.h - TITLEBAR - 7;
        c.fill(cx - 3, cy - 3, cx + cw + 3, cy + ch + 3, 0xFFFFFFFF);
        c.outline(cx - 3, cy - 3, cx + cw + 3, cy + ch + 3, 0xFF7F9DB9);
        c.pushClip(cx, cy, cx + cw, cy + ch);
        switch (win.app) {
            case FILES -> this.renderFiles(c, win, cx, cy, cw, ch);
            case TERMINAL -> this.renderTerminal(c, win, cx, cy, cw, ch);
            case P2V -> this.renderP2v(c, win, cx, cy, cw, ch);
            case EDITOR -> this.renderEditor(c, win, cx, cy, cw, ch);
            case SETTINGS -> this.renderSettings(c, win, cx, cy, cw, ch);
            case BLUETOOTH -> this.renderBluetooth(c, win, cx, cy, cw, ch);
            case VIEWER -> this.renderViewer(c, win, cx, cy, cw, ch);
            case BROWSER -> this.renderBrowser(c, win, cx, cy, cw, ch);
            default -> {
            }
        }
        c.popClip();
    }

    /**
     * 所有窗口共用的标题栏与「一口乂」按钮：最右是关闭，往左依次是最大化/还原、最小化。
     * 图标、标题和按钮走同一套坐标，IDE 只把底色换成深灰。
     */
    private void renderTitleBar(Canvas c, Win win, boolean dark) {
        int bar = win.y + TITLEBAR;
        if (dark) {
            c.gradient(win.x + 2, win.y + 2, win.x + win.w - 2, bar + 1, 0xFF33373D, 0xFF202124);
            c.fill(win.x + 2, win.y + 2, win.x + win.w - 2, win.y + 3, 0xFF5A616B);
            c.fill(win.x + 4, win.y + 5, win.x + 14, win.y + 14, 0xFF2B3A4A);
            c.text(win.x + 4, win.y + 5, "</>", 0xFF7FD1FF);
        } else {
            c.gradient(win.x + 2, win.y + 2, win.x + win.w - 2, bar + 1, 0xFF0A5BD8, 0xFF4A9BF5);
            c.fill(win.x + 2, win.y + 2, win.x + win.w - 2, win.y + 3, 0xFF7EB8FF);
            c.fill(win.x + 5, win.y + 5, win.x + 13, win.y + 13, 0xFF2F6FCC);
            c.fill(win.x + 6, win.y + 6, win.x + 12, win.y + 9, 0xFFBFE0FF);
        }
        int closeX = win.x + win.w - 17;
        int maxX = closeX - 15;
        int minX = maxX - 15;
        int titleX = win.x + (dark ? 18 : 16);
        int titleW = Math.max(6, minX - 6 - titleX);
        this.shadowed(c, this.fitText(win.title, titleW), titleX, win.y + 5, 0xFFFFFFFF);
        this.tip(titleX, win.y + 2, titleW, TITLEBAR - 2, win.title);
        this.windowButton(c, minX, win.y + 3, "—", 0);
        this.windowButton(c, maxX, win.y + 3, win.maximized ? "❐" : "□", 1);
        this.windowButton(c, closeX, win.y + 3, "✕", 2);
    }

    /** 标题栏按钮：0 最小化、1 最大化/还原、2 关闭；关闭用红色，其余保持蓝色。 */
    private void windowButton(Canvas c, int x, int y, String glyph, int kind) {
        boolean hover = this.inside(x, y, 13, 13);
        int top;
        int bottom;
        int border;
        if (kind == 2) {
            top = hover ? 0xFFF08A7A : 0xFFE05B4A;
            bottom = hover ? 0xFFC9483A : 0xFFB23427;
            border = 0xFF7A1E16;
        } else {
            top = hover ? 0xFF7CB6F8 : 0xFF4C90E8;
            bottom = hover ? 0xFF2E6FC8 : 0xFF2663B8;
            border = 0xFF1B4A86;
        }
        c.gradient(x, y, x + 13, y + 13, top, bottom);
        c.outline(x, y, x + 13, y + 13, border);
        c.centered(glyph, x + 6, y + 6, 0xFFFFFFFF);
    }

    /** IDE 在标题栏以下的深色内容区里排版；逻辑坐标仍是屏幕像素的两倍。 */
    private void renderIde(Canvas c, Win win, int x, int y, int w, int h) {
        IdeApp ide = win.ide;
        if (ide == null) {
            return;
        }
        int lw = w * IDE_SCALE;
        int lh = h * IDE_SCALE;
        int mx = this.mouseX < 0.0D ? -1000 : ((int) Math.round(this.mouseX) - x) * IDE_SCALE;
        int my = this.mouseY < 0.0D ? -1000 : ((int) Math.round(this.mouseY) - y) * IDE_SCALE;
        // IDE 用两倍逻辑坐标排版，气泡候选换算回屏幕像素后再登记。
        ide.setTips((lx, ly, tw, th, text) -> this.tip(
                x + Math.round(lx / (float) IDE_SCALE),
                y + Math.round(ly / (float) IDE_SCALE),
                Math.max(4, Math.round(tw / (float) IDE_SCALE)),
                Math.max(8, Math.round(th / (float) IDE_SCALE)), text));
        ide.render(new ScaledCanvas(c, 1.0F / IDE_SCALE, x, y, lw, lh), lw, lh, mx, my);
    }

    private void renderFiles(Canvas c, Win win, int x, int y, int w, int h) {
        boolean address = this.focus == Focus.ADDRESS_FILES && this.focusWin == win;
        int addrY = y - 1;
        c.fill(x - 1, addrY, x + w + 1, addrY + 12, 0xFFFFFFFF);
        c.outline(x - 1, addrY, x + w + 1, addrY + 12, address ? 0xFF2A62D8 : 0xFF7F9DB9);
        this.fieldText(c, x + 3, addrY + 3, w - 6, address ? win.addressText : win.path,
                address, COLOR_ACCENT);
        if (address && this.blink()) {
            int caret = this.caretX(x + 3, win.addressText, this.edit.caret, x + w - 2);
            c.fill(caret, addrY + 2, caret + 1, addrY + 10, 0xFF202020);
        }
        int barY = y + 14;
        int cx = x;
        for (String label : FILE_CHIPS) {
            this.chip(c, label, cx, barY);
            cx += this.labelWidth(label) + 4;
        }
        List<VirtualFileSystem.Node> nodes = this.device.fs.list(win.path);
        int listY = barY + 17;
        int rows = this.fileRows(h, listY - y);
        if (nodes.isEmpty()) {
            c.text(x, listY, "（空目录）", COLOR_MUTED);
        }
        int start = Math.min(win.scroll, Math.max(0, nodes.size() - rows));
        for (int i = 0; i < rows && start + i < nodes.size(); i++) {
            VirtualFileSystem.Node node = nodes.get(start + i);
            int ry = listY + i * ROW;
            boolean selected = node.name.equals(win.selected);
            boolean hover = !selected && win.menuName.isBlank() && this.inside(x, ry - 2, w, ROW - 1);
            if (selected) {
                c.fill(x - 2, ry - 2, x + w + 2, ry + ROW - 3, 0xFF316AC5);
            } else if (hover) {
                c.fill(x - 2, ry - 2, x + w + 2, ry + ROW - 3, 0xFFD9E8FF);
            }
            if (node.isDir()) {
                this.folderIcon(c, x, ry);
            } else {
                this.fileIcon(c, x, ry);
            }
            int nameColor = selected ? 0xFFFFFFFF
                    : node.isDir() ? 0xFF1A3C88 : COLOR_TEXT;
            String tag = !node.isDir() && node.isBinary() && w > 96
                    ? this.shortMime(node.mime) : "";
            int tagW = tag.isBlank() ? 0 : c.textWidth(tag);
            int nameW = Math.max(10, x + w - 12 - (tagW > 0 ? tagW + 4 : 0) - (x + 11));
            c.text(x + 11, ry, this.fitText(node.name, nameW), nameColor);
            if (tagW > 0) {
                c.text(x + w - tagW - 2, ry, tag, selected ? 0xFFE2ECF8 : COLOR_DIM);
            }
            this.tip(x + 9, ry - 2, w - 9, ROW, node.name);
        }
        String footer = win.status.isBlank()
                ? "双击打开 · 右键更多操作" : win.status;
        c.fill(x - 3, y + h - 12, x + w + 3, y + h + 3, 0xFFF4F2E8);
        c.text(x, y + h - 11, this.fitText(footer, w),
                win.status.isBlank() ? COLOR_DIM : COLOR_ACCENT);
        this.tip(x, y + h - 13, w, 13, footer);

        if (!win.menuName.isBlank()) {
            this.renderFileMenu(c, win, x, y, w, h);
        }
        if (!win.confirmDelete.isBlank()) {
            this.renderFileConfirm(c, win, x, y, w, h);
        }
        if (win.naming) {
            this.renderFileRename(c, win, x, y, w, h);
        }
    }

    /** 媒体查看器：图片可缩放平移，音视频带进度、音量与倍速。 */
    private void renderViewer(Canvas c, Win win, int x, int y, int w, int h) {
        c.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF0C1018);
        if (win.mediaPath.isBlank()) {
            c.text(x + 6, y + 8, "没有打开的文件。", COLOR_MUTED);
            return;
        }
        MediaCache.ensure(this.pos, win.mediaPath, win.mediaHash);
        MediaCache.Blob blob = MediaCache.blob(this.pos, win.mediaPath);
        boolean video = win.mediaMime.startsWith("video/");
        boolean audio = win.mediaMime.startsWith("audio/");
        int barH = video || audio ? 30 : 14;
        int areaY = y + 2;
        int areaH = Math.max(10, h - barH - 4);
        if (win.mediaMime.startsWith("image/")) {
            Identifier texture = MediaCache.image(this.pos, win.mediaPath, win.mediaHash);
            if (texture != null) {
                int[] size = MediaCache.imageSize(this.pos, win.mediaPath);
                int dw = Math.max(1, size[0]);
                int dh = Math.max(1, size[1]);
                int[] box = this.imageLayout(x, y, w, h, win, dw, dh);
                int ix = box[0];
                int iy = box[1];
                int iw = box[2];
                int ih = box[3];
                c.pushClip(x, areaY, x + w, areaY + areaH);
                c.fill(ix - 1, iy - 1, ix + iw + 1, iy + ih + 1, 0xFF2A2F38);
                c.image(ix, iy, ix + iw, iy + ih, texture);
                c.popClip();
                int fy = y + h - 13;
                int plusW = this.labelWidth("＋");
                int minusW = this.labelWidth("－");
                int fitW = this.labelWidth("适应");
                int fitX = x + w - fitW;
                int plusX = fitX - 4 - plusW;
                int minusX = plusX - 4 - minusW;
                this.chip(c, "－", minusX, fy);
                this.chip(c, "＋", plusX, fy);
                this.chip(c, "适应", fitX, fy);
                String info = dw + "×" + dh + " · " + Math.round(win.zoom * 100.0D) + "% · "
                        + this.mediaSize(blob);
                c.pushClip(x, fy + 2, minusX - 4, fy + 14);
                c.text(x, fy + 3, this.fitText(info, Math.max(8, minusX - 4 - x)), 0xFF9FB4C8);
                c.popClip();
                this.tip(x, fy + 1, Math.max(8, minusX - 4 - x), 12, info);
                if (win.zoom > 1.01D) {
                    c.text(x + 4, y + 4, "拖动画面平移", 0x889FB4C8);
                }
            } else if (blob == null) {
                this.pendingMedia(c, x, y, w, areaH);
            } else {
                c.text(x + 4, y + 8, "无法解码这张图片。", 0xFFFFB0A0);
                c.text(x + 4, y + 20, "建议用 P2V 重新导入为 PNG / JPG。", 0xFF9FB4C8);
            }
            return;
        }
        if (video) {
            HkvVideo hkv = MediaCache.video(this.pos, win.mediaPath);
            if (hkv == null) {
                if (blob == null) {
                    this.pendingMedia(c, x, y, w, areaH);
                } else {
                    c.text(x + 4, y + 8, "录像容器损坏，无法播放。", 0xFFFFB0A0);
                }
                return;
            }
            if (win.player == null || win.player.video() != hkv) {
                if (win.player != null) {
                    win.player.close();
                }
                win.player = new VideoPlayer(hkv);
                win.player.setSpeed(win.speed);
            }
            win.player.tick();
            float scale = Math.min((w - 4) / (float) hkv.width, areaH / (float) hkv.height);
            int iw = Math.max(1, Math.round(hkv.width * scale));
            int ih = Math.max(1, Math.round(hkv.height * scale));
            int ix = x + (w - iw) / 2;
            int iy = areaY + (areaH - ih) / 2;
            c.fill(ix - 1, iy - 1, ix + iw + 1, iy + ih + 1, 0xFF000000);
            c.image(ix, iy, ix + iw, iy + ih, win.player.texture());
            int barY = y + h - 30;
            String toggle = win.player.playing() ? "暂停" : "播放";
            int cx = x;
            this.chip(c, toggle, cx, barY);
            cx += this.labelWidth(toggle) + 4;
            String speedLabel = this.mediaSpeedLabel(win.speed);
            this.chip(c, speedLabel, cx, barY);
            cx += this.labelWidth(speedLabel) + 4;
            this.chip(c, "－", cx, barY);
            cx += this.labelWidth("－") + 3;
            this.chip(c, "＋", cx, barY);
            cx += this.labelWidth("＋") + 5;
            c.text(cx, barY + 3, "音量 " + Math.round(MediaAudio.volume() * 100.0F) + "%",
                    0xFF9FB4C8);
            String clock = this.clock(win.player.positionMillis()) + " / "
                    + this.clock(hkv.durationMs());
            c.text(x + w - this.textWidth(clock), barY + 3, clock, 0xFF9FB4C8);
            this.progressBar(c, x, y + h - 11, w, win.player.positionMillis(), hkv.durationMs());
            if (win.player.failed()) {
                c.text(x + 4, y + 4, "部分帧解码失败", 0xFFFFB0A0);
            }
            return;
        }
        if (audio) {
            if (blob != null && win.audioDurationMs < 0L) {
                win.audioDurationMs = MediaAudio.probeMillis(blob.data());
            }
            int boxW = Math.min(w - 12, 150);
            int bx = x + (w - boxW) / 2;
            int by = areaY + 8;
            c.fill(bx, by, bx + boxW, by + 38, 0xFF1B2430);
            c.outline(bx, by, bx + boxW, by + 38, 0xFF3D5168);
            for (int i = 0; i < 10; i++) {
                int bh = 3 + (i * 7 + win.mediaName.length()) % 9;
                int lx = bx + 10 + i * 6;
                c.fill(lx, by + 30 - bh, lx + 3, by + 30, 0xFF6FA8D8);
            }
            c.text(bx + 82, by + 8, "音频", 0xFFE6F0FA);
            c.text(bx + 82, by + 20, this.mediaSize(blob), 0xFF9FB4C8);
            c.centered(this.fitText(win.mediaName, Math.max(20, w - 16)), x + w / 2, by + 46,
                    0xFFE6F0FA);
            this.tip(x + 8, by + 44, Math.max(20, w - 16), 11, win.mediaName);
            c.centered(MediaAudio.playing() ? "正在通过物理机声卡播放…" : "点击播放试听",
                    x + w / 2, by + 58, 0xFF9FB4C8);
            long pos = MediaAudio.playing() ? Math.max(0L, MediaAudio.positionMillis()) : win.audioPosMs;
            long dur = MediaAudio.playing() ? Math.max(0L, MediaAudio.durationMillis())
                    : Math.max(0L, win.audioDurationMs);
            int barY = y + h - 30;
            String toggle = MediaAudio.playing() ? "停止" : "播放";
            int cx = x;
            this.chip(c, toggle, cx, barY);
            cx += this.labelWidth(toggle) + 4;
            String speedLabel = this.mediaSpeedLabel(win.speed);
            this.chip(c, speedLabel, cx, barY);
            cx += this.labelWidth(speedLabel) + 4;
            this.chip(c, "－", cx, barY);
            cx += this.labelWidth("－") + 3;
            this.chip(c, "＋", cx, barY);
            cx += this.labelWidth("＋") + 5;
            c.text(cx, barY + 3, "音量 " + Math.round(MediaAudio.volume() * 100.0F) + "%",
                    0xFF9FB4C8);
            String clock = this.clock(pos) + " / " + this.clock(dur);
            c.text(x + w - this.textWidth(clock), barY + 3, clock, 0xFF9FB4C8);
            this.progressBar(c, x, y + h - 11, w, pos, dur);
            return;
        }
        if (blob == null) {
            this.pendingMedia(c, x, y, w, areaH);
        } else {
            c.text(x + 4, y + 8, "这个文件没有可用的预览方式。", 0xFFFFD08A);
            c.text(x + 4, y + 20, win.mediaMime + " · " + this.mediaSize(blob), 0xFF9FB4C8);
        }
    }

    /** 图片在可用区域里的实际位置与尺寸；zoom 与 pan 都作用在这里。 */
    private int[] imageLayout(int x, int y, int w, int h, Win win, int dw, int dh) {
        int areaY = y + 2;
        int areaH = Math.max(10, h - 14 - 4);
        float fit = Math.min((w - 4) / (float) dw, areaH / (float) dh);
        int iw = Math.max(1, Math.round(dw * fit * (float) win.zoom));
        int ih = Math.max(1, Math.round(dh * fit * (float) win.zoom));
        int ix = x + (w - iw) / 2 + win.panX;
        int iy = areaY + (areaH - ih) / 2 + win.panY;
        return new int[]{ix, iy, iw, ih, areaY, areaH};
    }

    private void progressBar(Canvas c, int x, int y, int w, long position, long duration) {
        c.fill(x, y, x + w, y + 3, 0xFF2A2F38);
        int knob = duration <= 0L ? x
                : x + (int) Math.round(w * Math.max(0.0D, Math.min(1.0D,
                        position / (double) duration)));
        if (knob > x) {
            c.fill(x, y, knob, y + 3, 0xFF4A9BF5);
        }
        c.fill(Math.max(x, knob - 1), y - 2, Math.min(x + w, knob + 2), y + 5, 0xFFBBD8FF);
    }

    private String mediaSpeedLabel(float speed) {
        return String.format(Locale.ROOT, "%.1f×", speed);
    }

    private static float nextMediaSpeed(float speed) {
        for (float option : MEDIA_SPEEDS) {
            if (option > speed + 0.01F) {
                return option;
            }
        }
        return MEDIA_SPEEDS[0];
    }

    private void pendingMedia(Canvas c, int x, int y, int w, int h) {
        c.text(x + 4, y + 8, "正在通过 P2V 通道读取…", 0xFF9FB4C8);
        int dots = (int) ((System.currentTimeMillis() / 250L) % 4);
        c.text(x + 4, y + 22, ".".repeat(dots), 0xFF6FA8D8);
    }

    private String mediaSize(MediaCache.Blob blob) {
        if (blob == null) {
            return "等待数据";
        }
        long bytes = blob.data().length;
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
    }

    private String clock(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", total / 60L, total % 60L);
    }

    private int fileRows(int contentHeight, int headHeight) {
        return Math.max(1, (contentHeight - headHeight - 16) / ROW);
    }

    private int[] fileMenuRect(Win win) {
        int menuW = 66;
        int menuH = 3 * 13 + 4;
        int mx = Math.max(2, Math.min(win.x + win.w - menuW - 6, win.menuX));
        int my = Math.max(2, Math.min(win.y + win.h - menuH - 6, win.menuY));
        return new int[]{mx, my, menuW, menuH};
    }

    private void renderFileMenu(Canvas c, Win win, int x, int y, int w, int h) {
        int[] rect = this.fileMenuRect(win);
        int mx = rect[0];
        int my = rect[1];
        int menuW = rect[2];
        int menuH = rect[3];
        c.fill(mx + 2, my + 2, mx + menuW + 2, my + menuH + 2, 0x44000000);
        c.fill(mx, my, mx + menuW, my + menuH, 0xFFF7F5EA);
        c.outline(mx, my, mx + menuW, my + menuH, 0xFF7F9DB9);
        boolean locked = VirtualFileSystem.isProtected(this.filesChild(win, win.menuName));
        String[] items = {"打开", "重命名", "删除"};
        for (int i = 0; i < items.length; i++) {
            int iy = my + 2 + i * 13;
            boolean disabled = locked && i > 0;
            boolean hover = !disabled && this.inside(mx + 2, iy, menuW - 4, 13);
            if (hover) {
                c.fill(mx + 2, iy, mx + menuW - 2, iy + 13, 0xFF316AC5);
            }
            c.text(mx + 9, iy + 3, items[i],
                    disabled ? 0xFFA8A8A8 : hover ? 0xFFFFFFFF : COLOR_TEXT);
        }
        if (locked) {
            c.fill(mx, my + menuH, mx + menuW, my + menuH + 12, 0xFFFFFBC8);
            c.outline(mx, my + menuH, mx + menuW, my + menuH + 12, 0xFFA08A3C);
            c.text(mx + 4, my + menuH + 2, "系统文件只读", COLOR_WARN);
        }
    }

    private void renderFileConfirm(Canvas c, Win win, int x, int y, int w, int h) {
        int boxW = Math.min(w - 8, 176);
        int boxH = 64;
        int bx = x + (w - boxW) / 2;
        int by = y + (h - boxH) / 2;
        c.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0x55000000);
        c.fill(bx, by, bx + boxW, by + boxH, COLOR_FACE);
        c.outline(bx, by, bx + boxW, by + boxH, 0xFF08316B);
        c.gradient(bx + 2, by + 2, bx + boxW - 2, by + 16, 0xFF0A5BD8, 0xFF4A9BF5);
        this.shadowed(c, "删除", bx + 8, by + 4, 0xFFFFFFFF);
        String ask = "确定删除「" + win.confirmDelete + "」吗？";
        c.text(bx + 10, by + 23, this.fitText(ask, boxW - 20), COLOR_TEXT);
        this.tip(bx + 8, by + 20, boxW - 16, 12, ask);
        c.text(bx + 10, by + 34, "删除后无法恢复。", COLOR_MUTED);
        int bw = (boxW - 30) / 2;
        this.fileButton(c, bx + 10, by + boxH - 22, bw, "删除");
        this.fileButton(c, bx + 20 + bw, by + boxH - 22, bw, "取消");
    }

    private void renderFileRename(Canvas c, Win win, int x, int y, int w, int h) {
        int boxW = Math.min(w - 8, 186);
        int boxH = 70;
        int bx = x + (w - boxW) / 2;
        int by = y + (h - boxH) / 2;
        c.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0x55000000);
        c.fill(bx, by, bx + boxW, by + boxH, COLOR_FACE);
        c.outline(bx, by, bx + boxW, by + boxH, 0xFF08316B);
        c.gradient(bx + 2, by + 2, bx + boxW - 2, by + 16, 0xFF0A5BD8, 0xFF4A9BF5);
        this.shadowed(c, "重命名", bx + 8, by + 4, 0xFFFFFFFF);
        c.text(bx + 10, by + 22, "新名称：", COLOR_TEXT);
        boolean focused = this.focus == Focus.FILE_NAME && this.focusWin == win;
        int fieldY = by + 33;
        c.fill(bx + 10, fieldY, bx + boxW - 10, fieldY + 15, 0xFFFFFFFF);
        c.outline(bx + 10, fieldY, bx + boxW - 10, fieldY + 15, focused ? 0xFF2A62D8 : 0xFF7F9DB9);
        this.fieldText(c, bx + 13, fieldY + 3, boxW - 25, win.renameText, focused, COLOR_TEXT);
        if (focused && this.blink()) {
            int caret = this.caretX(bx + 13, win.renameText, this.edit.caret, bx + boxW - 12);
            c.fill(caret, fieldY + 2, caret + 1, fieldY + 12, COLOR_TEXT);
        }
        int bw = (boxW - 30) / 2;
        this.fileButton(c, bx + 10, by + boxH - 20, bw, "确定");
        this.fileButton(c, bx + 20 + bw, by + boxH - 20, bw, "取消");
    }

    private void fileButton(Canvas c, int x, int y, int w, String label) {
        boolean hover = this.inside(x, y, w, 15);
        c.fill(x, y, x + w, y + 15, hover ? 0xFFFFF5C0 : 0xFFFFFFFF);
        c.outline(x, y, x + w, y + 15, hover ? 0xFFC8A23C : COLOR_FACE_EDGE);
        c.centered(label, x + w / 2, y + 3, COLOR_TEXT);
    }

    /** 当前目录下某个子项的绝对路径；空名字返回 null。 */
    private String filesChild(Win win, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return (win.path.equals("/") ? "" : win.path) + "/" + name;
    }


    private void renderTerminal(Canvas c, Win win, int x, int y, int w, int h) {
        c.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF0C0C0C);
        int rows = Math.max(1, (h - 15) / LINE);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : win.log) {
            lines.addAll(c.split(line, w - 4));
        }
        int first = Math.max(0, lines.size() - rows);
        for (int i = first; i < lines.size(); i++) {
            c.text(x, y + (i - first) * LINE, lines.get(i), 0xFFB8F0B8);
        }
        String prompt = this.prompt();
        int promptY = y + h - LINE;
        boolean active = this.focus == Focus.TERMINAL && this.focusWin == win;
        int promptMax = Math.max(16, w / 2);
        String shownPrompt = this.fitText(prompt, promptMax);
        c.text(x, promptY, shownPrompt, 0xFFF0F0F0);
        this.tip(x, promptY - 1, Math.max(8, c.textWidth(shownPrompt)), LINE, prompt);
        int inputX = x + c.textWidth(shownPrompt);
        this.fieldText(c, inputX, promptY, Math.max(1, w - (inputX - x) - 2), win.input,
                active, 0xFFF0F0F0);
        if (active && this.blink()) {
            int caret = this.caretX(inputX, win.input, this.edit.caret, x + w - 1);
            c.fill(caret, promptY - 1, caret + 1, promptY + 9, 0xFFF0F0F0);
        }
    }

    /** P2V 协议：浏览物理机目录树，把图片/文本/音频/视频传进虚拟机。 */
    private void renderP2v(Canvas c, Win win, int x, int y, int w, int h) {
        if (!win.hostLoaded) {
            this.reloadHost(win);
        }
        boolean address = this.focus == Focus.ADDRESS_P2V && this.focusWin == win;
        c.text(x, y + 2, "宿主", COLOR_MUTED);
        int fieldX = x + c.textWidth("宿主") + 4;
        int addrY = y - 1;
        c.fill(fieldX, addrY, x + w + 1, addrY + 12, 0xFFFFFFFF);
        c.outline(fieldX, addrY, x + w + 1, addrY + 12, address ? 0xFF2A62D8 : 0xFF7F9DB9);
        String where = win.hostPath.isEmpty() ? "此电脑 · 物理机磁盘（输入盘符或目录）" : win.hostPath;
        String shown = address ? win.addressText : where;
        this.fieldText(c, fieldX + 3, addrY + 3, Math.max(1, x + w - fieldX - 5), shown,
                address, COLOR_ACCENT);
        if (!address) {
            this.tip(fieldX, addrY, x + w - fieldX, 12, where);
        }
        if (address && this.blink()) {
            int caret = this.caretX(fieldX + 3, win.addressText, this.edit.caret, x + w - 1);
            c.fill(caret, addrY + 2, caret + 1, addrY + 10, 0xFF202020);
        }

        int barY = y + 15;
        int cx = x;
        this.chip(c, "上级", cx, barY, win.hostPath.isEmpty() ? 0xFFB8B4A8 : COLOR_TEXT);
        cx += this.labelWidth("上级") + 4;
        this.chip(c, "主目录", cx, barY);
        cx += this.labelWidth("主目录") + 4;
        this.chip(c, "磁盘", cx, barY);
        cx += this.labelWidth("磁盘") + 4;
        this.chip(c, "刷新", cx, barY);
        cx += this.labelWidth("刷新") + 4;
        int picked = win.hostPicked.size();
        String send = picked == 0 ? "传入" : "传入 " + picked;
        this.chip(c, send, cx, barY, picked == 0 ? 0xFFB8B4A8 : COLOR_TEXT);
        cx += this.labelWidth(send) + 4;
        this.chip(c, "目标目录", cx, barY);

        int listY = barY + CHIP_H + 3;
        int rows = this.p2vRows(h, listY - y);
        List<P2VImport.HostEntry> entries = win.hostEntries;
        win.hostMaxScroll = Math.max(0, entries.size() - rows);
        win.scroll = Math.max(0, Math.min(win.scroll, win.hostMaxScroll));
        int start = win.scroll;
        if (entries.isEmpty()) {
            c.text(x, listY, "（这里没有可显示的文件）", COLOR_MUTED);
        }
        for (int i = 0; i < rows && start + i < entries.size(); i++) {
            P2VImport.HostEntry entry = entries.get(start + i);
            int ry = listY + i * ROW;
            boolean file = !entry.directory();
            boolean pickedRow = file && win.hostPicked.contains(entry.path());
            boolean hover = this.inside(x, ry - 2, w, ROW - 1);
            if (pickedRow) {
                c.fill(x - 2, ry - 2, x + w + 2, ry + ROW - 3, 0xFFDCEBFF);
            } else if (hover) {
                c.fill(x - 2, ry - 2, x + w + 2, ry + ROW - 3, 0xFFEDF4FF);
            }
            if (file) {
                c.fill(x + 1, ry + 2, x + 8, ry + 9, pickedRow ? 0xFF2E7BD6 : 0xFFFFFFFF);
                c.outline(x + 1, ry + 2, x + 9, ry + 10,
                        pickedRow ? 0xFF2E7BD6 : 0xFF7F9DB9);
                if (pickedRow) {
                    c.fill(x + 3, ry + 5, x + 4, ry + 7, 0xFFFFFFFF);
                    c.fill(x + 4, ry + 6, x + 7, ry + 8, 0xFFFFFFFF);
                }
            } else {
                this.folderIcon(c, x + 1, ry + 1);
            }
            boolean supported = entry.supported();
            int nameColor = file && !supported ? 0xFFA0A0A0
                    : entry.directory() ? 0xFF1A3C88 : COLOR_TEXT;
            int kindW = entry.kind().isBlank() ? 0 : c.textWidth(entry.kind());
            int nameW = Math.max(8, x + w - 34 - kindW - (x + 11));
            c.text(x + 11, ry, this.fitText(entry.name(), nameW), nameColor);
            this.tip(x + 9, ry - 2, x + w - 9 - x, ROW - 1, entry.name());
            if (!entry.kind().isBlank()) {
                c.text(x + w - kindW - 1, ry, entry.kind(),
                        file && !supported ? 0xFFB0A090 : pickedRow ? 0xFF1A4DA8 : COLOR_DIM);
            }
        }

        c.fill(x - 3, y + h - 12, x + w + 3, y + h + 3, 0xFFF4F2E8);
        String footer = win.p2vStatus.isBlank()
                ? "物理机 → 虚拟机 · 只传图片/文本/音频/视频" : win.p2vStatus;
        c.text(x, y + h - 11, this.fitText(footer, w), win.p2vStatus.isBlank()
                ? COLOR_DIM : COLOR_ACCENT);
        this.tip(x, y + h - 13, w, 13, footer);
    }

    private int p2vRows(int contentHeight, int headHeight) {
        return Math.max(1, (contentHeight - headHeight - 13) / ROW);
    }

    /** 重新读取物理机目录；读盘失败时保留空列表并给出提示。 */
    private void reloadHost(Win win) {
        try {
            win.hostEntries = P2VImport.listHost(win.hostPath);
        } catch (RuntimeException exception) {
            win.hostEntries = new ArrayList<>();
            win.p2vStatus = "无法读取物理机目录：" + exception.getMessage();
        }
        win.hostLoaded = true;
        win.scroll = 0;
        win.hostLastClick = "";
    }

    private static String hostParent(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        File parent = new File(path).getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    /** 勾选状态只在支持的文件上生效；返回实际要传入的物理机文件。 */
    private List<File> p2vPickedFiles(Win win) {
        List<File> files = new ArrayList<>();
        for (String path : win.hostPicked) {
            File file = new File(path);
            if (P2VImport.supported(file)) {
                files.add(file);
            }
        }
        return files;
    }

    private void p2vSend(Win win) {
        List<File> files = this.p2vPickedFiles(win);
        if (files.isEmpty()) {
            win.p2vStatus = "先勾选图片/文本/音频/视频文件。";
            this.toast(win.p2vStatus);
            return;
        }
        String target = win.p2vDir == null || win.p2vDir.isBlank() ? P2V_DIR : win.p2vDir;
        Set<String> existing = new HashSet<>();
        for (VirtualFileSystem.Node node : this.device.fs.list(target)) {
            existing.add(node.name);
        }
        win.p2vStatus = "P2V：准备传入 " + files.size() + " 个文件…";
        P2VImport.importFiles(this.pos, target, existing,
                message -> this.p2vReport(win, message),
                imported -> win.hostPicked.remove(imported.source()),
                files);
    }

    /** 后台线程的回调已经切回游戏线程，这里只更新仍然打开的窗口。 */
    private void p2vReport(Win win, String message) {
        if (!this.windows.contains(win)) {
            return;
        }
        win.p2vStatus = message;
        if (message.startsWith("P2V 传输完成") || message.startsWith("P2V 导入失败")
                || message.startsWith("P2V 不可用")) {
            this.toast(message);
        }
        if (message.startsWith("P2V 传输完成")) {
            HajikunSmartClient.request(this.pos, "refresh", "");
        }
    }

    private Menu p2vMenu(Win win) {
        Menu menu = new Menu("P2V 协议");
        menu.item("上级目录", () -> {
            win.hostPath = hostParent(win.hostPath);
            this.reloadHost(win);
        }, !win.hostPath.isEmpty());
        menu.item("主目录", () -> {
            win.hostPath = System.getProperty("user.home", "");
            this.reloadHost(win);
        });
        menu.item("磁盘列表", () -> {
            win.hostPath = "";
            this.reloadHost(win);
        });
        menu.sep();
        menu.item("全选本目录支持的文件", () -> {
            for (P2VImport.HostEntry entry : win.hostEntries) {
                if (!entry.directory() && entry.supported()) {
                    win.hostPicked.add(entry.path());
                }
            }
            win.p2vStatus = "已选 " + win.hostPicked.size() + " 个文件。";
        });
        menu.item("清空选择", () -> {
            win.hostPicked.clear();
            win.p2vStatus = "已清空选择。";
        }, !win.hostPicked.isEmpty());
        menu.item("传入选中文件", () -> this.p2vSend(win), !win.hostPicked.isEmpty());
        menu.sep();
        menu.item("打开虚拟机目标目录", () -> {
            HajikunSmartClient.request(this.pos, "refresh", "");
            this.openFilesAt(win.p2vDir == null || win.p2vDir.isBlank() ? P2V_DIR : win.p2vDir);
        });
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private boolean clickP2v(Win win, double mx, double my) {
        int x = win.x + 6;
        int y = win.y + TITLEBAR + 4;
        int w = win.w - 12;
        int h = win.h - TITLEBAR - 7;
        if (!win.hostLoaded) {
            this.reloadHost(win);
        }
        int fieldX = x + this.textWidth("宿主") + 4;
        if (this.inside(fieldX, y - 1, x + w + 1 - fieldX, 12)) {
            this.beginEditAt(Focus.ADDRESS_P2V, win, win.hostPath,
                    this.textIndexAt(fieldX + 3, win.hostPath, mx), true);
            return true;
        }
        int barY = y + 15;
        int cx = x;
        if (this.inside(cx, barY, this.labelWidth("上级"), CHIP_H)) {
            if (!win.hostPath.isEmpty()) {
                win.hostPath = hostParent(win.hostPath);
                this.reloadHost(win);
                win.p2vStatus = "已返回上级目录。";
            }
            return true;
        }
        cx += this.labelWidth("上级") + 4;
        if (this.inside(cx, barY, this.labelWidth("主目录"), CHIP_H)) {
            win.hostPath = System.getProperty("user.home", "");
            this.reloadHost(win);
            win.p2vStatus = "已打开物理机主目录。";
            return true;
        }
        cx += this.labelWidth("主目录") + 4;
        if (this.inside(cx, barY, this.labelWidth("磁盘"), CHIP_H)) {
            win.hostPath = "";
            this.reloadHost(win);
            win.p2vStatus = "已列出物理机磁盘。";
            return true;
        }
        cx += this.labelWidth("磁盘") + 4;
        if (this.inside(cx, barY, this.labelWidth("刷新"), CHIP_H)) {
            this.reloadHost(win);
            win.p2vStatus = "已刷新物理机目录。";
            return true;
        }
        cx += this.labelWidth("刷新") + 4;
        String send = win.hostPicked.isEmpty() ? "传入" : "传入 " + win.hostPicked.size();
        if (this.inside(cx, barY, this.labelWidth(send), CHIP_H)) {
            this.p2vSend(win);
            return true;
        }
        cx += this.labelWidth(send) + 4;
        if (this.inside(cx, barY, this.labelWidth("目标目录"), CHIP_H)) {
            String target = win.p2vDir == null || win.p2vDir.isBlank() ? P2V_DIR : win.p2vDir;
            HajikunSmartClient.request(this.pos, "refresh", "");
            this.openFilesAt(target);
            win.p2vStatus = "已在文件管理器打开 " + target;
            return true;
        }

        int listY = barY + CHIP_H + 3;
        int rows = this.p2vRows(h, listY - y);
        int start = Math.min(win.scroll, Math.max(0, win.hostEntries.size() - rows));
        for (int i = 0; i < rows && start + i < win.hostEntries.size(); i++) {
            P2VImport.HostEntry entry = win.hostEntries.get(start + i);
            int ry = listY + i * ROW;
            if (!this.inside(x - 2, ry - 2, w + 4, ROW - 1)) {
                continue;
            }
            if (entry.directory()) {
                win.hostPath = entry.path();
                this.reloadHost(win);
                win.p2vStatus = "进入 " + entry.name();
                return true;
            }
            if (!entry.supported()) {
                win.p2vStatus = "不支持 " + entry.name() + "：只传图片/文本/音频/视频。";
                this.toast(win.p2vStatus);
                return true;
            }
            boolean doubleClick = entry.path().equals(win.hostLastClick)
                    && System.currentTimeMillis() - win.hostLastAt <= DOUBLE_CLICK_MS;
            win.hostLastClick = doubleClick ? "" : entry.path();
            win.hostLastAt = System.currentTimeMillis();
            if (doubleClick) {
                win.hostPicked.add(entry.path());
                this.p2vSend(win);
            } else if (win.hostPicked.remove(entry.path())) {
                win.p2vStatus = "已取消选择 " + entry.name();
            } else {
                win.hostPicked.add(entry.path());
                win.p2vStatus = "已选 " + win.hostPicked.size() + " 个文件，点「传入」开始传输。";
            }
            return true;
        }
        win.p2vStatus = "";
        return true;
    }

    private void renderEditor(Canvas c, Win win, int x, int y, int w, int h) {
        c.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFFFFFFFF);
        int saveW = this.labelWidth("保存");
        c.text(x, y + 2, this.fitText(win.savedPath, w - saveW - 6), COLOR_ACCENT);
        this.tip(x, y, w - saveW - 4, 11, win.savedPath);
        this.chip(c, "保存", x + w - saveW, y - 4);
        List<EditorLine> lines = this.editorLayout(win.content, w - 4);
        int maxLines = Math.max(1, (h - 16) / LINE);
        boolean active = this.focus == Focus.EDITOR && this.focusWin == win;
        int caretLine = -1;
        int caretColumn = 0;
        if (active) {
            for (int i = 0; i < lines.size(); i++) {
                EditorLine line = lines.get(i);
                if (this.edit.caret >= line.start() && this.edit.caret <= line.end()) {
                    caretLine = i;
                    caretColumn = this.edit.caret - line.start();
                    if (this.edit.caret < line.end()) {
                        break;
                    }
                }
            }
            if (caretLine < win.scroll) {
                win.scroll = caretLine;
            } else if (caretLine >= win.scroll + maxLines) {
                win.scroll = caretLine - maxLines + 1;
            }
        }
        win.scroll = Math.max(0, Math.min(win.scroll, Math.max(0, lines.size() - maxLines)));
        int start = win.scroll;
        for (int i = 0; i < maxLines && start + i < lines.size(); i++) {
            EditorLine line = lines.get(start + i);
            int rowY = y + 17 + i * LINE;
            if (active && this.edit.hasSelection()) {
                int a = Math.max(line.start(), this.edit.selStart());
                int b = Math.min(line.end(), this.edit.selEnd());
                if (b > a) {
                    int sx = x + c.textWidth(line.text().substring(0, a - line.start()));
                    int ex = x + c.textWidth(line.text().substring(0, b - line.start()));
                    c.fill(sx, rowY - 1, ex, rowY + 9, 0xFFB5D5FF);
                }
            }
            c.text(x, rowY, line.text(), 0xFF202020);
        }
        if (active && caretLine >= 0 && this.blink()) {
            int row = caretLine - start;
            if (row >= 0 && row < maxLines) {
                EditorLine line = lines.get(caretLine);
                int caret = x + c.textWidth(line.text().substring(0, Math.min(caretColumn, line.text().length())));
                c.fill(caret, y + 16 + row * LINE, caret + 1, y + 26 + row * LINE, 0xFF202020);
            }
        }
    }

    private void renderSettings(Canvas c, Win win, int x, int y, int w, int h) {
        int row = 14;
        String nameLine = "设备名称：" + this.device.name;
        String macLine = "MAC 地址：" + this.device.mac + "（不可修改）";
        String bootLine = "开机次数：" + this.device.boots + "    运行："
                + (this.device.uptime / 20) + " 秒";
        String stateLine = "状态：" + (this.device.powered
                ? (this.device.suspended ? "已恢复（软关机保留）" : "运行中") : "已关机");
        c.text(x, y, this.fitText(nameLine, w), COLOR_TEXT);
        this.tip(x, y - 1, w, 11, nameLine);
        c.text(x, y + row, this.fitText(macLine, w), COLOR_MUTED);
        this.tip(x, y + row - 1, w, 11, macLine);
        c.text(x, y + row * 2, this.fitText(bootLine, w), COLOR_MUTED);
        this.tip(x, y + row * 2 - 1, w, 11, bootLine);
        c.text(x, y + row * 3, this.fitText(stateLine, w),
                this.device.powered ? COLOR_GREEN : COLOR_WARN);
        this.tip(x, y + row * 3 - 1, w, 11, stateLine);

        boolean focused = this.focus == Focus.RENAME && this.focusWin == win;
        int fieldY = y + row * 4 + 3;
        c.fill(x, fieldY, x + w - 50, fieldY + CHIP_H, 0xFFFFFFFF);
        c.outline(x, fieldY, x + w - 50, fieldY + CHIP_H, focused ? 0xFF2A62D8 : 0xFF7F9DB9);
        this.fieldText(c, x + 4, fieldY + 3, w - 55, win.input, focused, COLOR_TEXT);
        if (focused && this.blink()) {
            int caret = this.caretX(x + 4, win.input, this.edit.caret, x + w - 51);
            c.fill(caret, fieldY + 3, caret + 1, fieldY + 12, COLOR_TEXT);
        }
        this.chip(c, "改名", x + w - 44, fieldY);
        int cx = x;
        for (String label : new String[]{"软关机", "硬关机", "刷新"}) {
            this.chip(c, label, cx, fieldY + 24);
            cx += this.labelWidth(label) + 5;
        }
        String hint = "软关机保留虚拟机状态；硬关机清空内存与当前目录。";
        c.text(x, fieldY + 46, this.fitText(hint, w), COLOR_DIM);
        this.tip(x, fieldY + 44, w, 12, hint);
    }

    // ------------------------------------------------------------------ 蓝牙

    private void renderBluetooth(Canvas c, Win win, int x, int y, int w, int h) {
        if (win.picking) {
            this.renderBtPicker(c, win, x, y, w, h);
            return;
        }
        boolean on = this.device.bluetooth;
        int toggleW = this.labelWidth(on ? "蓝牙：开" : "蓝牙：关") + 14;
        boolean hover = this.inside(x, y, toggleW, CHIP_H);
        c.fill(x, y, x + toggleW, y + CHIP_H, hover ? 0xFFFFF5C0 : COLOR_FACE);
        c.outline(x, y, x + toggleW, y + CHIP_H, hover ? 0xFFC8A23C : COLOR_FACE_EDGE);
        c.fill(x + 4, y + 5, x + 10, y + 11, on ? 0xFF2E9B3E : 0xFF9A9A9A);
        c.fill(x + 5, y + 6, x + 9, y + 9, on ? 0xFF7BE08A : 0xFFC8C8C8);
        c.text(x + 13, y + 3, on ? "蓝牙：开" : "蓝牙：关", COLOR_TEXT);
        int refreshW = this.labelWidth("刷新");
        this.chip(c, "刷新", x + w - refreshW, y);

        if (!on) {
            c.text(x, y + 22, "蓝牙已关闭，附近设备不可见。", COLOR_TEXT);
            c.text(x, y + 34, "打开蓝牙后可以扫描、配对电脑，", COLOR_MUTED);
            c.text(x, y + 45, "在两台电脑之间传文件，", COLOR_MUTED);
            c.text(x, y + 56, "或把桌面投屏到附近的电视。", COLOR_MUTED);
            c.text(x, y + h - 12, "点左上角的开关即可开启。", COLOR_DIM);
            return;
        }

        List<DeviceSnapshot.Peer> peers = this.btPeers();
        // 本机身份单独占一行：列表里只出现其它机器，避免把自己误认成可配对设备。
        String selfName = this.device.name == null || this.device.name.isBlank()
                ? (this.kind == ComputerKind.LAPTOP ? "笔记本" : "台式机") : this.device.name;
        String selfLine = "本机：" + selfName + "  " + this.device.mac;
        c.text(x, y + 18, this.fitText(selfLine, w), COLOR_MUTED);
        this.tip(x, y + 16, w, 12, selfLine);
        Map<String, Integer> labels = new HashMap<>();
        for (DeviceSnapshot.Peer peer : peers) {
            labels.merge(peer.label(), 1, Integer::sum);
        }
        int listY = y + 31;
        int listH = h - 33 - 34;
        c.fill(x, listY, x + w, listY + listH, 0xFFFFFFFF);
        c.outline(x, listY, x + w, listY + listH, 0xFF7F9DB9);
        c.text(x + 3, listY + 3, "设备列表（附近 / 已配对）", COLOR_MUTED);
        int rows = Math.max(1, (listH - 15) / 13);
        if (peers.isEmpty()) {
            c.text(x + 6, listY + 18, "没有发现其它设备。", COLOR_DIM);
        }
        for (int i = 0; i < rows && i < peers.size(); i++) {
            DeviceSnapshot.Peer peer = peers.get(i);
            int ry = listY + 16 + i * 13;
            boolean selected = peer.mac().equalsIgnoreCase(win.btMac);
            boolean rowHover = this.inside(x + 1, ry, w - 2, 13);
            if (selected) {
                c.fill(x + 1, ry, x + w - 1, ry + 13, 0xFF316AC5);
            } else if (rowHover) {
                c.fill(x + 1, ry, x + w - 1, ry + 13, 0xFFD9E8FF);
            }
            c.fill(x + 5, ry + 5, x + 9, ry + 9, peer.online() ? 0xFF3FBF4F : 0xFFB0B0B0);
            String label = peer.label();
            // 未命名或重名时补一段 MAC 尾号，列表里每一行都能区分。
            if (peer.name() == null || peer.name().isBlank() || labels.getOrDefault(label, 0) > 1) {
                label = label + " " + shortMac(peer.mac());
            }
            int kindW = c.textWidth(peer.kindLabel());
            int labelW = Math.max(8, x + w - kindW - 20 - (x + 12));
            c.text(x + 12, ry + 2, this.fitText(label, labelW),
                    selected ? 0xFFFFFFFF : COLOR_TEXT);
            this.tip(x + 12, ry, labelW, 12, label);
            c.text(x + w - 8 - kindW, ry + 2, peer.kindLabel(), selected ? 0xFFE8F0FF : COLOR_DIM);
            if (peer.paired()) {
                c.fill(x + w - 12 - kindW, ry + 4, x + w - 10 - kindW, ry + 8, 0xFF2E9B3E);
            }
        }

        DeviceSnapshot.Peer selected = this.btSelected(win);
        int buttonY = y + h - 30;
        int bx = x;
        if (selected == null) {
            c.text(x, buttonY + 3, "先在上面的列表里选中一台设备。", COLOR_DIM);
        } else {
            if (selected.paired()) {
                this.chip(c, "取消配对", bx, buttonY);
                bx += this.labelWidth("取消配对") + 4;
            } else {
                this.chip(c, "配对", bx, buttonY);
                bx += this.labelWidth("配对") + 4;
            }
            if (selected.tv()) {
                this.chip(c, "投屏到电视", bx, buttonY);
                bx += this.labelWidth("投屏到电视") + 4;
            } else {
                this.chip(c, "发送文件", bx, buttonY);
                bx += this.labelWidth("发送文件") + 4;
                this.chip(c, "投屏", bx, buttonY);
            }
        }
        String hint = win.status.isBlank()
                ? (selected == null ? "" : selected.mac())
                : win.status;
        c.text(x, y + h - 12, this.fitText(hint, w),
                win.status.isBlank() ? COLOR_DIM : COLOR_ACCENT);
        this.tip(x, y + h - 13, w, 12, hint);
    }

    private void renderBtPicker(Canvas c, Win win, int x, int y, int w, int h) {
        String heading = "发送文件到 " + (win.btMac.isBlank() ? "设备" : win.btMac);
        int cancelW = this.labelWidth("取消");
        c.text(x, y, this.fitText(heading, w - cancelW - 6), COLOR_ACCENT);
        this.tip(x, y - 1, w - cancelW - 6, 11, heading);
        this.chip(c, "取消", x + w - cancelW, y - 3);
        c.text(x, y + 12, this.fitText(win.pickPath, w), COLOR_MUTED);
        this.tip(x, y + 10, w, 12, win.pickPath);
        List<VirtualFileSystem.Node> nodes = this.device.fs.list(win.pickPath);
        int listY = y + 24;
        int rows = Math.max(1, (h - 28) / 12);
        int start = Math.min(win.scroll, Math.max(0, nodes.size() - rows));
        if (nodes.isEmpty()) {
            c.text(x, listY, "（空目录）", COLOR_DIM);
        }
        for (int i = 0; i < rows && start + i < nodes.size(); i++) {
            VirtualFileSystem.Node node = nodes.get(start + i);
            int ry = listY + i * 12;
            boolean hover = this.inside(x, ry - 1, w, 12);
            if (hover) {
                c.fill(x - 2, ry - 1, x + w + 2, ry + 11, 0xFFD9E8FF);
            }
            if (node.isDir()) {
                this.folderIcon(c, x, ry);
            } else {
                this.fileIcon(c, x, ry);
            }
            c.text(x + 11, ry, this.fitText(node.name, w - 12),
                    node.isDir() ? 0xFF1A3C88 : COLOR_TEXT);
            this.tip(x + 9, ry - 1, w - 9, 12, node.name);
        }
        c.text(x, y + h - 11, this.fitText("单击文件立即发送；点文件夹继续进入。", w), COLOR_DIM);
    }

    private List<DeviceSnapshot.Peer> btPeers() {
        List<DeviceSnapshot.Peer> list = new ArrayList<>();
        if (this.device.nearby != null) {
            list.addAll(this.device.nearby);
        }
        if (this.device.paired != null) {
            for (DeviceSnapshot.Peer peer : this.device.paired) {
                boolean known = list.stream().anyMatch(other -> other.mac().equalsIgnoreCase(peer.mac()));
                if (!known) {
                    list.add(peer);
                }
            }
        }
        return list;
    }

    /** 列表里区分重名设备用的 MAC 尾号。 */
    private static String shortMac(String mac) {
        if (mac == null || mac.length() < 5) {
            return "";
        }
        return mac.substring(mac.length() - 5);
    }

    private DeviceSnapshot.Peer btSelected(Win win) {
        if (win.btMac == null || win.btMac.isBlank()) {
            return null;
        }
        for (DeviceSnapshot.Peer peer : this.btPeers()) {
            if (peer.mac().equalsIgnoreCase(win.btMac)) {
                return peer;
            }
        }
        return null;
    }

    /** 首次启动：XP 欢迎面板，点输入框打字、回车或点按钮保存。 */
    private void renderSetup(Canvas c) {
        c.gradient(0, 0, c.width(), c.height(), 0xFF0C4FA8, 0xFF072E68);
        c.fill(0, 0, c.width(), 20, 0xFF0A3D86);
        c.fill(0, 20, c.width(), 21, 0x66FFFFFF);
        this.shadowed(c, "哈基坤 OS · 设备初始化", 8, 6, 0xFFFFFFFF);
        c.text(c.width() - 8 - c.textWidth("首次启动"), 6, "首次启动", 0xFFB8D4F0);

        int panelX = 22;
        int panelY = 40;
        int panelW = c.width() - 44;
        int panelH = 156;
        c.fill(panelX + 4, panelY + 4, panelX + panelW + 4, panelY + panelH + 4, 0x55000000);
        c.fill(panelX, panelY, panelX + panelW, panelY + panelH, COLOR_FACE);
        c.outline(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF08316B);
        c.gradient(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + TITLEBAR + 1,
                0xFF0A5BD8, 0xFF4A9BF5);
        this.shadowed(c, "欢迎使用", panelX + 8, panelY + 5, 0xFFFFFFFF);

        String kindLabel = this.kind == ComputerKind.LAPTOP ? "笔记本" : "台式机";
        int x = panelX + 12;
        c.text(x, panelY + 26, "这是一台新的" + kindLabel + "，第一次使用前请为它命名。", COLOR_TEXT);
        c.text(x, panelY + 40, "虚拟 MAC：" + this.device.mac, COLOR_ACCENT);
        c.text(x, panelY + 52, "自动分配，存档内唯一且不可修改。", COLOR_DIM);

        int fieldY = panelY + 70;
        int fieldW = panelW - 24;
        c.fill(x, fieldY, x + fieldW, fieldY + 20, 0xFFFFFFFF);
        c.outline(x, fieldY, x + fieldW, fieldY + 20,
                this.focus == Focus.SETUP ? 0xFF2A62D8 : 0xFF7F9DB9);
        if (this.setupText.isEmpty()) {
            c.text(x + 6, fieldY + 6, "输入设备名称，例如：哈基坤的台式机", 0xFF9A9A9A);
        } else {
            this.fieldText(c, x + 6, fieldY + 6, fieldW - 8, this.setupText,
                    this.focus == Focus.SETUP, COLOR_TEXT);
        }
        if (this.focus == Focus.SETUP && this.blink()) {
            int caretX = this.caretX(x + 6, this.setupText, this.edit.caret, x + fieldW - 4);
            c.fill(caretX, fieldY + 5, caretX + 1, fieldY + 15, COLOR_TEXT);
        }

        int buttonY = fieldY + 30;
        int buttonW = this.setupButtonWidth();
        boolean hover = this.inside(x, buttonY, buttonW, 20);
        c.fill(x, buttonY, x + buttonW, buttonY + 20, hover ? 0xFFFFF9D8 : COLOR_FACE);
        c.outline(x, buttonY, x + buttonW, buttonY + 20, hover ? 0xFFC8A23C : COLOR_FACE_EDGE);
        c.centered("保存并进入桌面", x + buttonW / 2, buttonY + 6, COLOR_TEXT);
        c.text(x + buttonW + 10, buttonY + 6, "回车也可保存", COLOR_DIM);

        String hint = this.localFocus
                ? "点击输入框即可输入，Esc 或 Shift 离开屏幕。"
                : "右键这台电脑，把光标放到它的屏幕上开始设置。";
        c.text(x, panelY + panelH - 15, hint, this.localFocus ? 0xFF0A4AA8 : COLOR_WARN);
    }

    private void renderOff(Canvas c) {
        c.fill(0, 0, c.width(), c.height(), 0xFF030506);
        c.gradient(0, c.height() / 3, c.width(), c.height() * 2 / 3, 0x161B4A8E, 0x00000000);
        c.centered("已关机", c.width() / 2, c.height() / 2 - 30, 0xFF8A98A6, 2.0F);
        c.centered(this.device.name, c.width() / 2, c.height() / 2 - 2, 0xFF5F6D7B);
        String hint = this.device.suspended
                ? "软关机完成，运行状态已保留；单击电源键恢复。"
                : "单击右下角电源键开机。";
        c.centered(hint, c.width() / 2, c.height() / 2 + 12, 0xFF44515F);
        c.text(8, c.height() - 14, this.device.mac, 0xFF3C4856);
    }

    /** 电源键始终画在右下角，任何状态都能开机或呼出关机菜单。 */
    private void renderPower(Canvas c) {
        int cx = c.width() - 12;
        int cy = c.height() - 12;
        int left = c.width() - 20;
        int top = c.height() - 20;
        boolean hover = this.inside(left, top, POWER_SIZE, POWER_SIZE);
        boolean enabled = this.device.named();
        int base;
        if (!enabled) {
            base = 0xFF8E959E;
        } else if (this.device.powered) {
            base = 0xFF2E9B3E;
        } else {
            base = 0xFFB23A2E;
        }
        if (hover && enabled) {
            base = shift(base, 28);
        }
        c.fill(left - 1, top - 1, left + POWER_SIZE + 1, top + POWER_SIZE + 1, 0x44000000);
        for (int dy = -6; dy <= 5; dy++) {
            int half = (int) Math.sqrt(36 - dy * dy);
            if (dy <= -5) {
                half = Math.min(half, 3);
            }
            if (dy >= 4) {
                half = Math.min(half, 4);
            }
            c.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, base);
        }
        c.fill(cx - 2, cy - 5, cx + 3, cy - 4, shift(base, 34));
        this.powerGlyph(c, cx, cy, enabled ? 0xFFFFFFFF : 0xFFE8ECF0);
        if (hover) {
            String hint = !enabled ? "请先为设备命名"
                    : this.device.powered ? "单击关机（软关机 / 硬关机）" : "单击开机";
            this.tooltip(c, hint, cx, top - 21);
        }
    }

    private void renderShutdown(Canvas c) {
        c.fill(0, 0, c.width(), c.height(), 0x66000000);
        int[] d = this.dialogRect();
        int dx = d[0];
        int dy = d[1];
        c.fill(dx + 4, dy + 4, dx + DIALOG_W + 4, dy + DIALOG_H + 4, 0x66000000);
        c.fill(dx, dy, dx + DIALOG_W, dy + DIALOG_H, COLOR_FACE);
        c.outline(dx, dy, dx + DIALOG_W, dy + DIALOG_H, 0xFF08316B);
        c.gradient(dx + 2, dy + 2, dx + DIALOG_W - 2, dy + TITLEBAR + 1,
                0xFF0A5BD8, 0xFF4A9BF5);
        int titleW = c.textWidth("关闭计算机");
        c.text(dx + DIALOG_W / 2 - titleW / 2 + 1, dy + 6, "关闭计算机", 0x88000000);
        c.text(dx + DIALOG_W / 2 - titleW / 2, dy + 5, "关闭计算机", 0xFFFFFFFF);
        int cbx = dx + DIALOG_W - 17;
        boolean closeHover = this.inside(cbx, dy + 3, 13, 13);
        c.gradient(cbx, dy + 3, cbx + 13, dy + 16,
                closeHover ? 0xFFF08A7A : 0xFFE05B4A, closeHover ? 0xFFC9483A : 0xFFB23427);
        c.outline(cbx, dy + 3, cbx + 13, dy + 16, 0xFF7A1E16);
        c.centered("x", cbx + 6, dy + 6, 0xFFFFFFFF);

        c.text(dx + 12, dy + 27, "您希望如何关闭这台电脑？", COLOR_TEXT);
        int bw = (DIALOG_W - 36) / 2;
        int softX = dx + 12;
        int hardX = softX + bw + 12;
        int by = dy + 42;
        this.dialogButton(c, softX, by, bw, 26, "软关机", 0xFF3C8F2E);
        this.dialogButton(c, hardX, by, bw, 26, "硬关机", 0xFFB03A2E);
        c.centered("保留运行状态", softX + bw / 2, by + 30, COLOR_MUTED);
        c.centered("清空运行状态", hardX + bw / 2, by + 30, COLOR_MUTED);
        int cancelW = this.labelWidth("取消");
        this.chip(c, "取消", dx + DIALOG_W - 12 - cancelW, dy + DIALOG_H - 24);
    }

    private void dialogButton(Canvas c, int x, int y, int w, int h, String label, int base) {
        boolean hover = this.inside(x, y, w, h);
        c.gradient(x, y, x + w, y + h, shift(base, hover ? 30 : 8), shift(base, -32));
        c.outline(x, y, x + w, y + h, 0xFF08316B);
        c.centered(label, x + w / 2, y + (h - 9) / 2, 0xFFFFFFFF, 1.35F);
    }

    private int[] dialogRect() {
        return new int[]{(this.width() - DIALOG_W) / 2, (this.height() - DIALOG_H) / 2 - 4};
    }

    private void renderStartMenu(Canvas c) {
        int my = this.menuY();
        int mh = this.menuHeight();
        c.fill(MENU_X + 4, my + 4, MENU_X + MENU_W + 4, my + mh + 4, 0x66000000);
        c.fill(MENU_X, my, MENU_X + MENU_W, my + mh, COLOR_FACE);
        c.outline(MENU_X, my, MENU_X + MENU_W, my + mh, 0xFF08316B);
        c.gradient(MENU_X + 2, my + 2, MENU_X + MENU_W - 2, my + MENU_HEADER,
                0xFF0A5BD8, 0xFF3F8BF0);
        c.fill(MENU_X + 8, my + 5, MENU_X + 26, my + 23, 0xFFB8D4F0);
        c.outline(MENU_X + 8, my + 5, MENU_X + 26, my + 23, 0xFF4A7CB8);
        c.fill(MENU_X + 14, my + 9, MENU_X + 20, my + 15, 0xFF6E8EA8);
        c.fill(MENU_X + 10, my + 16, MENU_X + 24, my + 21, 0xFF6E8EA8);
        int nameW = MENU_W - 36;
        this.shadowed(c, this.fitText(this.device.name, nameW), MENU_X + 30, my + 9, 0xFFFFFFFF);
        this.tip(MENU_X + 28, my + 4, MENU_W - 32, MENU_HEADER - 2, this.device.name);

        int ry = my + MENU_HEADER + 2;
        for (int i = 0; i < APP_NAMES.length; i++) {
            boolean hover = this.inside(MENU_X + 2, ry, MENU_W - 4, MENU_ROW);
            if (hover) {
                c.fill(MENU_X + 2, ry, MENU_X + MENU_W - 2, ry + MENU_ROW, 0xFF316AC5);
            }
            this.appIcon(c, i, MENU_X + 7, ry + 2);
            c.text(MENU_X + 29, ry + 6,
                    this.fitText(APP_NAMES[i], MENU_W - 36), hover ? 0xFFFFFFFF : COLOR_TEXT);
            this.tip(MENU_X + 27, ry, MENU_W - 31, MENU_ROW, APP_NAMES[i]);
            ry += MENU_ROW;
        }

        int fy = my + mh - MENU_FOOTER;
        c.fill(MENU_X + 8, fy - 1, MENU_X + MENU_W - 8, fy, 0xFF9A968A);
        boolean footerHover = this.inside(MENU_X + 2, fy, MENU_W - 4, MENU_FOOTER);
        if (footerHover) {
            c.fill(MENU_X + 2, fy, MENU_X + MENU_W - 2, my + mh - 2, 0xFF316AC5);
        }
        this.powerGlyph(c, MENU_X + 13, fy + 12, footerHover ? 0xFFFFFFFF : 0xFFB23A2E);
        c.text(MENU_X + 30, fy + 8, this.fitText("关闭计算机", MENU_W - 38),
                footerHover ? 0xFFFFFFFF : COLOR_TEXT);
        this.tip(MENU_X + 2, fy, MENU_W - 4, MENU_FOOTER, "关闭计算机");
    }

    private int menuHeight() {
        return MENU_HEADER + 2 + APP_NAMES.length * MENU_ROW + MENU_FOOTER;
    }

    private int menuY() {
        return this.height() - TASKBAR - this.menuHeight();
    }

    private void renderToast(Canvas c) {
        if (!this.toastActive()) {
            return;
        }
        int w = Math.min(c.width() - 20, c.textWidth(this.toast) + 16);
        int x = (c.width() - w) / 2;
        c.fill(x, 26, x + w, 43, 0xFFFFFBC8);
        c.outline(x, 26, x + w, 43, 0xFF000000);
        c.pushClip(x + 4, 26, x + w - 4, 43);
        c.text(x + 8, 32, this.fitText(this.toast, w - 12), 0xFF1A1A1A);
        c.popClip();
        this.tip(x, 26, w, 17, this.toast);
    }

    private void renderCursor(Canvas c) {
        if (!this.localFocus || this.mouseX < 0.0D) {
            return;
        }
        int mx = (int) Math.round(this.mouseX);
        int my = (int) Math.round(this.mouseY);
        for (int i = 0; i < 11; i++) {
            int w = Math.min(i + 1, 7);
            c.fill(mx, my + i, mx + w, my + i + 1, 0xFF000000);
        }
        for (int i = 1; i < 10; i++) {
            int w = Math.min(i + 1, 7);
            if (w > 2) {
                c.fill(mx + 1, my + i, mx + w - 1, my + i + 1, 0xFFFFFFFF);
            }
        }
        c.fill(mx + 2, my + 8, mx + 5, my + 10, 0xFF000000);
        c.fill(mx + 3, my + 8, mx + 4, my + 9, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------ 小图标与按钮

    /** 五个应用各画一枚 16×16 的像素图标，菜单、桌面和标题栏共用。 */
    private void appIcon(Canvas c, int app, int x, int y) {
        switch (app) {
            case 0 -> {
                c.fill(x, y + 1, x + 16, y + 12, 0xFFC9CFD8);
                c.outline(x, y + 1, x + 16, y + 12, 0xFF5B6675);
                c.fill(x + 2, y + 3, x + 14, y + 10, 0xFF1B3E72);
                c.fill(x + 3, y + 4, x + 13, y + 9, 0xFF3E86D8);
                c.fill(x + 5, y + 13, x + 11, y + 14, 0xFF8A929E);
                c.fill(x + 2, y + 14, x + 14, y + 16, 0xFF6C7480);
            }
            case 1 -> {
                c.fill(x, y + 1, x + 16, y + 15, 0xFF111111);
                c.outline(x, y + 1, x + 16, y + 15, 0xFF8A8A8A);
                c.fill(x + 3, y + 5, x + 4, y + 6, 0xFFFFFFFF);
                c.fill(x + 4, y + 6, x + 5, y + 7, 0xFFFFFFFF);
                c.fill(x + 4, y + 8, x + 5, y + 9, 0xFFFFFFFF);
                c.fill(x + 3, y + 9, x + 4, y + 10, 0xFFFFFFFF);
                c.fill(x + 7, y + 9, x + 12, y + 10, 0xFFFFFFFF);
            }
            case 2 -> {
                // P2V：物理机显示器 + 向屏幕内落下的传输箭头。
                c.fill(x, y, x + 16, y + 11, 0xFFC9CFD8);
                c.outline(x, y, x + 16, y + 11, 0xFF5B6675);
                c.fill(x + 2, y + 2, x + 14, y + 9, 0xFF1B3E72);
                c.fill(x + 3, y + 3, x + 13, y + 8, 0xFF2E7BD6);
                c.fill(x + 7, y + 3, x + 9, y + 5, 0xFFB6F0C2);
                c.fill(x + 5, y + 5, x + 11, y + 6, 0xFFB6F0C2);
                c.fill(x + 6, y + 6, x + 10, y + 7, 0xFFB6F0C2);
                c.fill(x + 7, y + 7, x + 9, y + 8, 0xFFB6F0C2);
                c.fill(x + 5, y + 11, x + 11, y + 13, 0xFF8A929E);
                c.fill(x + 2, y + 13, x + 14, y + 15, 0xFF6C7480);
            }
            case 3 -> {
                c.fill(x + 2, y, x + 14, y + 16, 0xFFFFFEFA);
                c.outline(x + 2, y, x + 14, y + 16, 0xFF7A7A7A);
                c.fill(x + 11, y, x + 14, y + 3, 0xFFD8D8D8);
                for (int i = 0; i < 3; i++) {
                    c.fill(x + 4, y + 5 + i * 3, x + 12, y + 6 + i * 3, 0xFF3E7FBE);
                }
            }
            case 4 -> {
                c.fill(x, y + 1, x + 16, y + 15, 0xFFC9CFD8);
                c.outline(x, y + 1, x + 16, y + 15, 0xFF5B6675);
                c.fill(x + 1, y + 2, x + 15, y + 5, 0xFF3F7FE0);
                c.fill(x + 3, y + 7, x + 7, y + 10, 0xFFE2A33C);
                c.fill(x + 9, y + 7, x + 13, y + 10, 0xFF3E9B4F);
                c.fill(x + 3, y + 11, x + 7, y + 14, 0xFFC74A3E);
                c.fill(x + 9, y + 11, x + 13, y + 14, 0xFF6E8EA8);
            }
            case 6 -> {
                c.fill(x, y, x + 16, y + 16, 0xFF1E1E1E);
                c.outline(x, y, x + 16, y + 16, 0xFF5A5A5A);
                c.fill(x, y, x + 16, y + 3, 0xFF007ACC);
                c.fill(x + 2, y + 5, x + 4, y + 14, 0xFF333333);
                c.fill(x + 5, y + 6, x + 10, y + 7, 0xFF4EC9B0);
                c.fill(x + 5, y + 9, x + 8, y + 10, 0xFFDCDCAA);
                c.fill(x + 5, y + 12, x + 12, y + 13, 0xFFCE9178);
                c.fill(x + 12, y + 5, x + 15, y + 10, 0xFF2D2D30);
                c.fill(x + 13, y + 6, x + 14, y + 9, 0xFF569CD6);
            }
            case 7 -> {
                // Electron：深色底上的蓝色电子轨道，和桌面其他图标一样是 16×16 像素。
                c.fill(x, y, x + 16, y + 16, 0xFF101A2E);
                c.outline(x, y, x + 16, y + 16, 0xFF3C6FA8);
                c.fill(x + 2, y + 2, x + 14, y + 14, 0xFF1B3E72);
                c.fill(x + 3, y + 3, x + 13, y + 13, 0xFF2E7BD6);
                c.fill(x + 4, y + 7, x + 12, y + 9, 0xFF9AD4FF);
                c.fill(x + 7, y + 4, x + 9, y + 12, 0xFF9AD4FF);
                c.fill(x + 5, y + 5, x + 7, y + 7, 0xFFFFFFFF);
                c.fill(x + 9, y + 9, x + 11, y + 11, 0xFFFFFFFF);
            }
            default -> {
                c.fill(x + 4, y + 3, x + 12, y + 13, 0xFF1B3E72);
                c.fill(x + 5, y + 4, x + 11, y + 12, 0xFF3E86D8);
                c.fill(x + 7, y, x + 9, y + 5, 0xFF1B3E72);
                c.fill(x + 6, y + 1, x + 10, y + 3, 0xFF9AD4FF);
                c.fill(x + 8, y + 6, x + 9, y + 9, 0xFFFFFFFF);
                c.fill(x + 3, y + 14, x + 13, y + 15, 0xFF1B3E72);
            }
        }
    }

    private void folderIcon(Canvas c, int x, int y) {
        c.fill(x, y + 1, x + 7, y + 2, 0xFFD9A63C);
        c.fill(x, y + 2, x + 8, y + 7, 0xFFF2C64E);
        c.outline(x, y + 1, x + 8, y + 7, 0xFFA87A1C);
    }

    private void fileIcon(Canvas c, int x, int y) {
        c.fill(x, y, x + 7, y + 8, 0xFFFFFFFF);
        c.outline(x, y, x + 7, y + 8, 0xFF8A8A8A);
        c.fill(x + 1, y + 2, x + 6, y + 3, 0xFF9AB8E0);
        c.fill(x + 1, y + 4, x + 5, y + 5, 0xFFB8C8DC);
        c.fill(x + 1, y + 6, x + 6, y + 7, 0xFFB8C8DC);
    }

    private void powerGlyph(Canvas c, int cx, int cy, int color) {
        c.fill(cx - 1, cy - 5, cx + 1, cy, color);
        c.fill(cx - 4, cy - 3, cx - 3, cy + 2, color);
        c.fill(cx + 3, cy - 3, cx + 4, cy + 2, color);
        c.fill(cx - 3, cy + 2, cx - 1, cy + 3, color);
        c.fill(cx + 1, cy + 2, cx + 3, cy + 3, color);
        c.fill(cx - 1, cy + 3, cx + 1, cy + 4, color);
    }

    private void tooltip(Canvas c, String text, int centerX, int topY) {
        int w = c.textWidth(text) + 8;
        int x = Math.max(4, Math.min(c.width() - w - 4, centerX - w / 2));
        int y = Math.max(4, topY);
        c.fill(x, y, x + w, y + 15, 0xFFFFFBC8);
        c.outline(x, y, x + w, y + 15, 0xFF000000);
        c.text(x + 4, y + 3, text, 0xFF1A1A1A);
    }

    /** 单行控件里的文字：超出宽度时截断并以省略号结尾。 */
    private String fitText(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.textWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsis = this.textWidth("…");
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (this.textWidth(text.substring(0, mid)) + ellipsis <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, Math.max(0, low)).stripTrailing() + "…";
    }

    /** 登记一个悬浮气泡候选；鼠标停留一段时间后显示完整名称。 */
    private void tip(int x, int y, int w, int h, String text) {
        if (text != null && !text.isBlank()) {
            this.tips.add(new Tip(x, y, w, h, text));
        }
    }

    private void renderTips(Canvas c) {
        Tip hover = null;
        for (int i = this.tips.size() - 1; i >= 0; i--) {
            Tip each = this.tips.get(i);
            if (this.inside(each.x(), each.y(), each.width(), each.height())) {
                hover = each;
                break;
            }
        }
        if (hover == null) {
            this.tipText = "";
            return;
        }
        long now = System.currentTimeMillis();
        if (!hover.text().equals(this.tipText)) {
            this.tipText = hover.text();
            this.tipSince = now;
            this.tipX = this.mouseX;
            this.tipY = this.mouseY;
            return;
        }
        if (now - this.tipSince < 480L) {
            return;
        }
        int maxWidth = Math.max(40, Math.min(190, c.width() - 12));
        List<FormattedCharSequence> lines = c.split(hover.text(), maxWidth - 8);
        if (lines.isEmpty()) {
            return;
        }
        if (lines.size() > 6) {
            lines = lines.subList(0, 6);
        }
        int width = 0;
        for (FormattedCharSequence line : lines) {
            width = Math.max(width, c.textWidth(line));
        }
        width += 8;
        int height = lines.size() * c.lineHeight() + 6;
        int x = (int) Math.round(this.mouseX + 10.0D);
        int y = (int) Math.round(this.mouseY + 14.0D);
        if (x + width > c.width() - 4) {
            x = c.width() - width - 4;
        }
        if (y + height > c.height() - 4) {
            y = (int) Math.round(this.mouseY) - height - 8;
        }
        x = Math.max(4, x);
        y = Math.max(4, y);
        c.fill(x, y, x + width, y + height, 0xFFFFFBC8);
        c.outline(x, y, x + width, y + height, 0xFF7A6A00);
        c.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFFFFFFFF);
        int lineY = y + 3;
        for (FormattedCharSequence line : lines) {
            c.text(x + 4, lineY, line, 0xFF1A1A1A);
            lineY += c.lineHeight();
        }
    }

    private record Tip(int x, int y, int width, int height, String text) {
    }

    // ------------------------------------------------------------------ 文本编辑

    /** 切换文本框并把整段文本交给选区模型；光标默认落在末尾。 */
    private void beginEdit(Focus target, Win win, String text) {
        this.focus = target;
        this.focusWin = win;
        this.edit.set(text);
        this.editDragging = false;
        if (win != null && (target == Focus.ADDRESS_FILES || target == Focus.ADDRESS_P2V
                || target == Focus.ADDRESS_BROWSER)) {
            win.addressText = text == null ? "" : text;
        }
    }

    /** 点击文本框：光标落在点击处，drag 为 true 时继续按住可以拖选。 */
    private void beginEditAt(Focus target, Win win, String text, int index, boolean drag) {
        this.beginEdit(target, win, text);
        this.edit.click(index, false);
        this.editDragging = drag;
    }

    /** 拖选时按当前焦点把鼠标位置换算成字符索引。 */
    private void dragEditTo(double mx, double my) {
        if (this.focus == Focus.SETUP) {
            this.edit.dragTo(this.textIndexAt(40, this.edit.text, mx));
            return;
        }
        Win win = this.focusWin;
        if (win == null) {
            return;
        }
        int x = win.x + 6;
        int y = win.y + TITLEBAR + 4;
        int w = win.w - 12;
        int h = win.h - TITLEBAR - 7;
        switch (this.focus) {
            case TERMINAL -> this.edit.dragTo(this.textIndexAt(
                    x + this.textWidth(this.prompt()), this.edit.text, mx));
            case EDITOR -> this.edit.dragTo(this.editorIndexAt(win, x, y, w, h, mx, my));
            case RENAME -> this.edit.dragTo(this.textIndexAt(x + 4, this.edit.text, mx));
            case FILE_NAME -> {
                int boxW = Math.min(w - 8, 186);
                int boxH = 70;
                int bx = x + (w - boxW) / 2;
                this.edit.dragTo(this.textIndexAt(bx + 13, this.edit.text, mx));
            }
            case ADDRESS_FILES -> this.edit.dragTo(this.textIndexAt(x + 3, this.edit.text, mx));
            case ADDRESS_P2V -> this.edit.dragTo(this.textIndexAt(
                    x + this.textWidth("宿主") + 7, this.edit.text, mx));
            case ADDRESS_BROWSER -> this.edit.dragTo(this.textIndexAt(
                    win.browserFieldLeft, this.edit.text, mx));
            default -> {
            }
        }
    }

    /** 点击位置换算成字符索引：按前缀宽度找最近的字符边界。 */
    private int textIndexAt(int left, String text, double mx) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double offset = Math.max(0.0D, mx - left);
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            int next = width + this.textWidth(String.valueOf(text.charAt(i)));
            if (offset < (width + next) / 2.0D) {
                return i;
            }
            width = next;
        }
        return text.length();
    }

    private int caretX(int left, String text, int caret, int maxX) {
        int at = Math.max(0, Math.min(caret, text.length()));
        int width = Math.max(1, maxX - left);
        int from = this.fieldStart(value(text), at, width);
        return Math.min(left + this.textWidth(value(text).substring(from, at)), maxX);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    /** 每段前缀的像素宽度，用来在长文本里滑出以光标为中心的可见窗口。 */
    private int[] prefixWidths(String text) {
        int[] prefix = new int[text.length() + 1];
        for (int i = 1; i <= text.length(); i++) {
            prefix[i] = prefix[i - 1] + this.textWidth(text.substring(i - 1, i));
        }
        return prefix;
    }

    /** 光标在框内时最靠左的起始字符，保证从起点到光标的整段文字塞得进 width。 */
    private int fieldStart(String text, int caret, int width) {
        int at = Math.max(0, Math.min(caret, text.length()));
        int[] prefix = this.prefixWidths(text);
        int low = 0;
        int high = at;
        while (low < high) {
            int mid = (low + high) / 2;
            if (prefix[at] - prefix[mid] <= width) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    /** 从 from 起还能完整放进 width 的最末字符（不含）。 */
    private int fieldEnd(String text, int from, int width) {
        int[] prefix = this.prefixWidths(text);
        int low = from;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (prefix[mid] - prefix[from] <= width) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * 画一行可编辑文本：选区蓝底、正文与（调用方另画的）闪烁光标。
     *
     * <p>编辑时按光标滑出可见窗口并裁掉尾部，文字永远待在自己的框里；
     * 未编辑时一律省略号收尾，长路径也不会压到旁边的按钮上。</p>
     */
    private void fieldText(Canvas c, int left, int baseline, int width, String text,
                           boolean active, int color) {
        if (width <= 0) {
            return;
        }
        String whole = value(text);
        String shown;
        int from = 0;
        int to = whole.length();
        if (active) {
            from = this.fieldStart(whole, this.edit.caret, width);
            to = this.fieldEnd(whole, from, width);
            if (from > 0 && from < whole.length() && Character.isLowSurrogate(whole.charAt(from))) {
                from++;
            }
            if (to > from && to < whole.length() && Character.isLowSurrogate(whole.charAt(to))) {
                to--;
            }
            shown = whole.substring(from, to);
        } else {
            shown = this.fitText(whole, width);
        }
        int right = left + width;
        c.pushClip(left, baseline - 2, right, baseline + 9);
        if (active && this.edit.hasSelection()) {
            int[] prefix = this.prefixWidths(whole);
            int start = Math.max(from, Math.min(this.edit.selStart(), whole.length()));
            int end = Math.min(to, Math.max(this.edit.selEnd(), from));
            if (end > start) {
                int sx = left + prefix[start] - prefix[from];
                int ex = left + prefix[end] - prefix[from];
                c.fill(sx, baseline - 1, Math.min(ex, right), baseline + 9, 0xFFB5D5FF);
            }
        }
        c.text(left, baseline, shown, color);
        c.popClip();
    }

    /** 记事本按字符换行后的显示行，记录每段在原文中的起止索引。 */
    private record EditorLine(String text, int start, int end) {
    }

    private List<EditorLine> editorLayout(String content, int width) {
        List<EditorLine> lines = new ArrayList<>();
        String value = content == null ? "" : content;
        int lineStart = 0;
        for (String logical : value.split("\n", -1)) {
            if (logical.isEmpty()) {
                lines.add(new EditorLine("", lineStart, lineStart));
            } else {
                int at = 0;
                while (at < logical.length()) {
                    int end = at;
                    int used = 0;
                    while (end < logical.length()) {
                        int cw = this.textWidth(String.valueOf(logical.charAt(end)));
                        if (end > at && used + cw > width) {
                            break;
                        }
                        used += cw;
                        end++;
                    }
                    lines.add(new EditorLine(logical.substring(at, end), lineStart + at, lineStart + end));
                    at = end;
                }
            }
            lineStart += logical.length() + 1;
        }
        if (lines.isEmpty()) {
            lines.add(new EditorLine("", 0, 0));
        }
        return lines;
    }

    private int editorIndexAt(Win win, int x, int y, int w, int h, double mx, double my) {
        List<EditorLine> lines = this.editorLayout(win.content, w - 4);
        int maxLines = Math.max(1, (h - 16) / LINE);
        int start = Math.min(win.scroll, Math.max(0, lines.size() - maxLines));
        int row = (int) Math.floor((my - (y + 17)) / LINE);
        EditorLine line = lines.get(Math.max(0, Math.min(lines.size() - 1, start + row)));
        return line.start() + this.textIndexAt(x, line.text(), mx);
    }

    private void chip(Canvas c, String label, int x, int y) {
        int w = this.labelWidth(label);
        boolean hover = this.inside(x, y, w, CHIP_H);
        c.fill(x, y, x + w, y + CHIP_H, hover ? 0xFFFFF5C0 : COLOR_FACE);
        c.outline(x, y, x + w, y + CHIP_H, hover ? 0xFFC8A23C : COLOR_FACE_EDGE);
        c.text(x + 5, y + 3, label, COLOR_TEXT);
    }

    /** 不可用的按钮：保留位置，文字变灰。 */
    private void chip(Canvas c, String label, int x, int y, int textColor) {
        int w = this.labelWidth(label);
        c.fill(x, y, x + w, y + CHIP_H, 0xFFF1EFE6);
        c.outline(x, y, x + w, y + CHIP_H, 0xFFCFCBBE);
        c.text(x + 5, y + 3, label, textColor);
    }

    private void shadowed(Canvas c, String text, int x, int y, int argb) {
        c.text(x + 1, y + 1, text, 0x88000000);
        c.text(x, y, text, argb);
    }

    private void centeredShadowed(Canvas c, String text, int centerX, int y, int argb) {
        int w = c.textWidth(text);
        c.text(centerX - w / 2 + 1, y + 1, text, 0x88000000);
        c.text(centerX - w / 2, y, text, argb);
    }

    private static int shift(int argb, int delta) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.max(0, Math.min(255, ((argb >>> 16) & 0xFF) + delta));
        int g = Math.max(0, Math.min(255, ((argb >>> 8) & 0xFF) + delta));
        int b = Math.max(0, Math.min(255, (argb & 0xFF) + delta));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    /** 按钮宽度：与画布上的字体宽度一致，点击判定才能和画面对齐。 */
    private int labelWidth(String label) {
        return font().width(label) + 10;
    }

    private int setupButtonWidth() {
        return font().width("保存并进入桌面") + 24;
    }

    // ------------------------------------------------------------------ 输入

    private boolean blink() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    private boolean toastActive() {
        return !this.toast.isBlank() && System.currentTimeMillis() < this.toastUntil;
    }

    private boolean inside(double x, double y, int w, int h) {
        return this.mouseX >= x && this.mouseX < x + w && this.mouseY >= y && this.mouseY < y + h;
    }

    private boolean powerHit() {
        return this.inside(this.width() - 20, this.height() - 20, POWER_SIZE, POWER_SIZE);
    }

    public void mouseMove(double x, double y) {
        this.mouseX = Math.max(0.0D, Math.min(this.width(), x));
        this.mouseY = Math.max(0.0D, Math.min(this.height(), y));
        this.chromiumHover();
    }

    public void mouseLeave() {
        this.mouseX = -1.0D;
        this.mouseY = -1.0D;
        this.dragging = null;
        this.editDragging = false;
        this.viewerDragWin = null;
    }

    public void mouseDrag(double x, double y) {
        this.mouseMove(x, y);
        if (this.editDragging) {
            this.dragEditTo(this.mouseX, this.mouseY);
            return;
        }
        if (this.viewerDragWin != null) {
            Win win = this.viewerDragWin;
            if (this.viewerDragMode == 1) {
                win.panX += (int) Math.round(this.mouseX - this.viewerDragX);
                win.panY += (int) Math.round(this.mouseY - this.viewerDragY);
                win.panX = Math.max(-win.w, Math.min(win.w, win.panX));
                win.panY = Math.max(-win.h, Math.min(win.h, win.panY));
            } else if (this.viewerDragMode == 2) {
                this.seekViewer(win, this.mouseX, win.x + 6, win.w - 12);
            }
            this.viewerDragX = this.mouseX;
            this.viewerDragY = this.mouseY;
            return;
        }
        if (this.dragging != null) {
            int nx = (int) this.mouseX - this.dragOffX;
            int ny = (int) this.mouseY - this.dragOffY;
            this.dragging.x = Math.max(0, Math.min(Math.max(0, this.width() - this.dragging.w), nx));
            this.dragging.y = Math.max(0,
                    Math.min(Math.max(0, this.height() - TASKBAR - this.dragging.h), ny));
        }
        // IDE 的栏宽用拖动分隔条调整，窗口本身不移动。
        if (this.focusWin != null && this.focusWin.ide != null && this.dragging == null) {
            this.focusWin.ide.mouseDragged(
                    ((int) Math.round(this.mouseX) - this.focusWin.x) * IDE_SCALE,
                    ((int) Math.round(this.mouseY) - this.focusWin.y - TITLEBAR) * IDE_SCALE);
        }
    }

    public void mouseUp() {
        this.dragging = null;
        this.editDragging = false;
        this.viewerDragWin = null;
        if (this.focusWin != null && this.focusWin.ide != null) {
            this.focusWin.ide.mouseReleased();
        }
    }

    public boolean mouseDown(double x, double y, int button, boolean shift) {
        this.mouseMove(x, y);
        this.editDragging = false;
        this.viewerDragWin = null;
        if (button != 0 && button != 2) {
            return false;
        }
        boolean secondary = button == 2;
        double mx = this.mouseX;
        double my = this.mouseY;

        // 菜单优先：点到菜单项就执行，点到菜单外先收起，再按这次点击的上下文继续。
        if (this.menu != null) {
            if (this.clickMenu(mx, my)) {
                return true;
            }
            this.menu = null;
        }

        if (!this.device.named()) {
            if (this.powerHit()) {
                this.toast("请先为这台设备命名。");
                return true;
            }
            return this.clickSetup(x, y);
        }
        if (!this.device.powered) {
            if (!secondary && this.powerHit()) {
                HajikunSmartClient.request(this.pos, "boot", "");
                this.toast("正在开机…");
            }
            return true;
        }
        if (!secondary && this.powerHit()) {
            this.startMenu = false;
            this.shutdownDialog = true;
            this.focus = Focus.NONE;
            this.focusWin = null;
            return true;
        }
        if (this.shutdownDialog) {
            if (!secondary) {
                return this.clickShutdown(mx, my);
            }
            this.shutdownDialog = false;
        }
        int ty = this.height() - TASKBAR;
        if (this.startMenu) {
            if (!secondary && my < ty && this.inside(MENU_X, this.menuY(), MENU_W, this.menuHeight())) {
                return this.clickStartMenu(mx, my);
            }
            this.startMenu = false;
            return true;
        }
        if (my >= ty) {
            if (secondary) {
                this.showTaskbarMenu(mx, my);
                return true;
            }
            if (this.inside(START_X, ty + 2, START_W, 18)) {
                this.startMenu = true;
                return true;
            }
            String clock = LocalTime.now().format(CLOCK);
            int clockX = this.width() - 24 - this.textWidth(clock);
            String state = this.device.suspended ? "已恢复" : "运行中";
            int stateX = clockX - 8 - this.textWidth(state);
            int bx = START_X + START_W + 6;
            int end = stateX - 16;
            Win topVisible = null;
            for (Win each : this.windows) {
                if (!each.minimized) {
                    topVisible = each;
                }
            }
            for (Win win : this.windows) {
                if (bx + 30 > end) {
                    break;
                }
                int w = Math.min(76, this.textWidth(win.title) + 16);
                if (this.inside(bx, ty + 2, w, TASKBAR - 4)) {
                    // 点最小化的窗口：还原并置顶；点当前活动窗口：最小化；其余：置顶。
                    if (win.minimized) {
                        win.minimized = false;
                        this.bringToFront(win);
                    } else if (win == topVisible) {
                        this.minimize(win);
                    } else {
                        this.bringToFront(win);
                    }
                    return true;
                }
                bx += w + 3;
            }
            return true;
        }
        if (this.topWinAt(mx, my) == null) {
            for (int i = 0; i < APP_NAMES.length; i++) {
                if (this.inside(this.iconX(i), this.iconY(i), ICON_W, ICON_H)) {
                    if (secondary) {
                        this.showAppMenu(i, mx, my);
                    } else {
                        this.openApp(DESKTOP_APPS[i], true);
                    }
                    return true;
                }
            }
            if (secondary) {
                this.showMenu(this.desktopMenu(), mx, my);
                return true;
            }
        }
        Win win = this.topWinAt(mx, my);
        if (win == null) {
            this.focus = Focus.NONE;
            this.focusWin = null;
            return true;
        }
        this.bringToFront(win);
        // 一口乂按钮在所有窗口里位置一致：最右关闭，中间最大化，最左最小化。
        int closeX = win.x + win.w - 17;
        int maxX = closeX - 15;
        int minX = maxX - 15;
        if (!secondary && this.inside(closeX, win.y + 3, 13, 13)) {
            this.closeWindow(win);
            return true;
        }
        if (!secondary && this.inside(maxX, win.y + 3, 13, 13)) {
            this.toggleMaximize(win);
            return true;
        }
        if (!secondary && this.inside(minX, win.y + 3, 13, 13)) {
            this.minimize(win);
            return true;
        }
        if (my >= win.y && my < win.y + TITLEBAR) {
            if (secondary) {
                this.showMenu(this.windowMenu(win), mx, my);
            } else {
                // 拖动最大化的窗口时先还原，再按抓取点移动，和桌面系统手感一致。
                if (win.maximized) {
                    this.toggleMaximize(win);
                }
                this.dragging = win;
                this.dragOffX = Math.max(0, Math.min(win.w - 12, (int) mx - win.x));
                this.dragOffY = (int) my - win.y;
            }
            return true;
        }
        if (win.app == App.JAVAIDE) {
            // IDE 自己管理内部控件；点击只负责置顶并把坐标换算进内容区。
            this.focus = Focus.NONE;
            this.focusWin = win;
            if (secondary) {
                return true;
            }
            if (win.ide != null) {
                win.ide.mouseClicked(((int) Math.round(mx) - win.x) * IDE_SCALE,
                        ((int) Math.round(my) - win.y - TITLEBAR) * IDE_SCALE, button + 1);
            }
            return true;
        }
        if (secondary) {
            this.openWindowContentMenu(win, mx, my);
            return true;
        }
        this.focus = Focus.NONE;
        this.focusWin = null;
        return this.clickContent(win, mx, my);
    }

    private boolean clickShutdown(double mx, double my) {
        int[] d = this.dialogRect();
        int dx = d[0];
        int dy = d[1];
        int bw = (DIALOG_W - 36) / 2;
        int softX = dx + 12;
        int hardX = softX + bw + 12;
        int by = dy + 42;
        if (this.inside(softX, by, bw, 26)) {
            this.shutdownDialog = false;
            HajikunSmartClient.request(this.pos, "softoff", "");
            this.toast("软关机：运行状态已保留");
            return true;
        }
        if (this.inside(hardX, by, bw, 26)) {
            this.shutdownDialog = false;
            HajikunSmartClient.request(this.pos, "hardoff", "");
            // 硬关机等于直接断电：这次运行里打开的窗口和终端记录都随之丢弃。
            this.dropSession();
            this.toast("硬关机：运行状态已清空");
            return true;
        }
        int cancelW = this.labelWidth("取消");
        if (this.inside(dx + DIALOG_W - 12 - cancelW, dy + DIALOG_H - 24, cancelW, CHIP_H)
                || this.inside(dx + DIALOG_W - 17, dy + 3, 13, 13)) {
            this.shutdownDialog = false;
            return true;
        }
        if (!this.inside(dx, dy, DIALOG_W, DIALOG_H)) {
            this.shutdownDialog = false;
        }
        return true;
    }

    private boolean clickStartMenu(double mx, double my) {
        int ry = this.menuY() + MENU_HEADER + 2;
        for (int i = 0; i < APP_NAMES.length; i++) {
            if (this.inside(MENU_X + 2, ry, MENU_W - 4, MENU_ROW)) {
                this.startMenu = false;
                this.openApp(DESKTOP_APPS[i], true);
                return true;
            }
            ry += MENU_ROW;
        }
        int fy = this.menuY() + this.menuHeight() - MENU_FOOTER;
        if (this.inside(MENU_X + 2, fy, MENU_W - 4, MENU_FOOTER)) {
            this.startMenu = false;
            this.shutdownDialog = true;
            this.focus = Focus.NONE;
            this.focusWin = null;
        }
        return true;
    }

    // ------------------------------------------------------------------ 右键菜单

    /**
     * 菜单字号与行高。
     *
     * <p>320 像素宽的画布聚焦时在 1600p 窗口里约放大 4 倍，1 倍字等于 36 物理像素，
     * 右键菜单会像满屏巨字。菜单统一按 0.75 倍字号、10 像素行高排版，密度接近
     * 桌面浏览器的右键菜单；字仍然用游戏字体按比例绘制，不是把画布拉糊。</p>
     */
    private static final float MENU_FONT = 0.75F;
    private static final int MENU_TITLE_H = 14;
    private static final int MENU_ROW_H = 10;
    private static final int MENU_SEP_H = 5;
    private static final int MENU_PAD_X = 6;
    /** 子菜单箭头（">"）占用的右侧宽度。 */
    private static final int MENU_ARROW_W = 9;

    /** 菜单文字按菜单字号排版后的宽度；换行和截断都按这个宽度算。 */
    private int menuTextWidth(String text) {
        return Math.round(this.textWidth(text) * MENU_FONT);
    }

    /** 菜单单行文字：放不下就截断加省略号，完整名称交给悬浮气泡。 */
    private String menuFit(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.menuTextWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsis = this.menuTextWidth("…");
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (this.menuTextWidth(text.substring(0, mid)) + ellipsis <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, Math.max(0, low)).stripTrailing() + "…";
    }

    /** 一层菜单项：分隔线没有动作，带子菜单的项点击后保持展开。 */
    private static final class Menu {
        private final String title;
        private final List<MenuEntry> entries = new ArrayList<>();

        private Menu(String title) {
            this.title = title == null ? "" : title;
        }

        private Menu item(String label, Runnable action) {
            return this.item(label, action, true);
        }

        private Menu item(String label, Runnable action, boolean enabled) {
            this.entries.add(new MenuEntry(label, action, enabled, null, false));
            return this;
        }

        private Menu sub(String label, Menu child) {
            this.entries.add(new MenuEntry(label, null, true, child, false));
            return this;
        }

        private Menu sep() {
            this.entries.add(new MenuEntry("", null, false, null, true));
            return this;
        }
    }

    private record MenuEntry(String label, Runnable action, boolean enabled, Menu submenu,
                             boolean separator) {
    }

    private record MenuBox(Menu menu, int x, int y, int w, int h) {
    }

    private record MenuRow(Menu menu, int x, int y, int w, int h, MenuEntry entry) {
    }

    private void showMenu(Menu menu, double mx, double my) {
        this.startMenu = false;
        this.menu = menu;
        this.menuAnchorX = mx;
        this.menuAnchorY = my;
    }

    private int contextMenuWidth(Menu menu) {
        int width = menu.title.isBlank() ? 0 : this.menuTextWidth(menu.title) + MENU_PAD_X * 2;
        for (MenuEntry entry : menu.entries) {
            if (entry.separator) {
                continue;
            }
            int item = this.menuTextWidth(entry.label) + MENU_PAD_X * 2
                    + (entry.submenu == null ? 0 : MENU_ARROW_W);
            width = Math.max(width, item);
        }
        return Math.max(52, Math.min(150, width));
    }

    private int contextMenuHeight(Menu menu) {
        int height = menu.title.isBlank() ? 3 : MENU_TITLE_H;
        for (MenuEntry entry : menu.entries) {
            height += entry.separator ? MENU_SEP_H : MENU_ROW_H;
        }
        return height + 3;
    }

    private int[] contextMenuPosition(Menu menu, int x, int y) {
        int w = this.contextMenuWidth(menu);
        int h = this.contextMenuHeight(menu);
        int px = Math.max(2, Math.min(this.width() - w - 2, x));
        int py = Math.max(2, Math.min(this.height() - h - 2, y));
        return new int[]{px, py, w, h};
    }

    /** 按当前鼠标位置摊开菜单与悬停子菜单；绘制和点击判定共用同一份几何。 */
    private void walkMenu(Menu menu, int x, int y, List<MenuBox> boxes, List<MenuRow> rows, int depth) {
        int w = this.contextMenuWidth(menu);
        int h = this.contextMenuHeight(menu);
        boxes.add(new MenuBox(menu, x, y, w, h));
        int cy = y + (menu.title.isBlank() ? 2 : MENU_TITLE_H);
        for (MenuEntry entry : menu.entries) {
            int rowH = entry.separator ? MENU_SEP_H : MENU_ROW_H;
            rows.add(new MenuRow(menu, x, cy, w, rowH, entry));
            if (!entry.separator && entry.submenu != null && depth < 3
                    && this.inside(x + 1, cy, w - 2, rowH)) {
                int subW = this.contextMenuWidth(entry.submenu);
                int sx = x + w - 2;
                if (sx + subW > this.width() - 2) {
                    sx = x - subW + 2;
                }
                sx = Math.max(2, sx);
                int sy = Math.max(2, Math.min(this.height() - this.contextMenuHeight(entry.submenu) - 2, cy - 1));
                this.walkMenu(entry.submenu, sx, sy, boxes, rows, depth + 1);
            }
            cy += rowH;
        }
    }

    private void renderMenus(Canvas c) {
        if (this.menu == null) {
            return;
        }
        int[] pos = this.contextMenuPosition(this.menu, (int) this.menuAnchorX, (int) this.menuAnchorY);
        List<MenuBox> boxes = new ArrayList<>();
        List<MenuRow> rows = new ArrayList<>();
        this.walkMenu(this.menu, pos[0], pos[1], boxes, rows, 0);
        for (MenuBox box : boxes) {
            c.fill(box.x() + 2, box.y() + 2, box.x() + box.w() + 2, box.y() + box.h() + 2, 0x55000000);
            c.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), 0xFFF7F5EA);
            c.outline(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), 0xFF7F9DB9);
            if (!box.menu().title.isBlank()) {
                c.gradient(box.x() + 2, box.y() + 2, box.x() + box.w() - 2, box.y() + MENU_TITLE_H - 2,
                        0xFF1756B8, 0xFF5A9DE8);
                String title = this.menuFit(box.menu().title, box.w() - MENU_PAD_X * 2);
                c.text(box.x() + MENU_PAD_X, box.y() + 3, title, 0xFFFFFFFF, MENU_FONT);
                if (!title.equals(box.menu().title)) {
                    this.tip(box.x() + 2, box.y() + 2, box.w() - 4, MENU_TITLE_H - 3, box.menu().title);
                }
            }
            for (MenuRow row : rows) {
                if (row.menu() != box.menu()) {
                    continue;
                }
                if (row.entry().separator) {
                    c.fill(box.x() + 5, row.y() + 2, box.x() + box.w() - 5, row.y() + 3, 0xFFC8C4B8);
                    continue;
                }
                boolean hover = this.inside(row.x() + 1, row.y(), row.w() - 2, row.h());
                if (hover && row.entry().enabled) {
                    c.fill(box.x() + 1, row.y(), box.x() + box.w() - 1, row.y() + row.h(), 0xFF2F6BC8);
                }
                int color = !row.entry().enabled ? 0xFFA8A8A8 : hover ? 0xFFFFFFFF : COLOR_TEXT;
                boolean arrow = row.entry().submenu != null;
                int labelW = box.w() - MENU_PAD_X - 2 - (arrow ? MENU_ARROW_W : MENU_PAD_X);
                String label = this.menuFit(row.entry().label, labelW);
                c.text(box.x() + MENU_PAD_X, row.y() + 2, label, color, MENU_FONT);
                // 只有真正被省略号截断的行才登记气泡；否则气泡会盖住刚展开的子菜单。
                if (!label.equals(row.entry().label)) {
                    this.tip(box.x() + 2, row.y(), box.w() - 4, row.h(), row.entry().label);
                }
                if (arrow) {
                    c.text(box.x() + box.w() - MENU_ARROW_W, row.y() + 1, ">", color, MENU_FONT);
                }
            }
        }
    }

    private boolean clickMenu(double mx, double my) {
        if (this.menu == null) {
            return false;
        }
        int[] pos = this.contextMenuPosition(this.menu, (int) this.menuAnchorX, (int) this.menuAnchorY);
        List<MenuBox> boxes = new ArrayList<>();
        List<MenuRow> rows = new ArrayList<>();
        this.walkMenu(this.menu, pos[0], pos[1], boxes, rows, 0);
        MenuRow hit = null;
        for (MenuRow row : rows) {
            if (!row.entry().separator && this.inside(row.x(), row.y(), row.w(), row.h())) {
                hit = row;
            }
        }
        if (hit == null) {
            return false;
        }
        if (hit.entry().submenu != null) {
            return true;
        }
        this.menu = null;
        if (hit.entry().enabled && hit.entry().action != null) {
            hit.entry().action.run();
        }
        return true;
    }

    private void showAppMenu(int index, double mx, double my) {
        App app = DESKTOP_APPS[index];
        Menu menu = new Menu(APP_NAMES[index]);
        menu.item("打开", () -> this.openApp(app, true));
        if (app == App.FILES) {
            menu.item("在终端中打开", () -> this.openTerminal(this.device.cwd));
        }
        if (app == App.EDITOR) {
            menu.item("新建文本文档", () -> this.createTextFile("/home/user"));
        }
        menu.sep();
        menu.item("属性", () -> this.openApp(App.SETTINGS, true));
        this.showMenu(menu, mx, my);
    }

    private Menu desktopMenu() {
        Menu create = new Menu("新建");
        create.item("文件夹", () -> this.createFolder("/home/user"));
        create.item("文本文档", () -> this.createTextFile("/home/user"));
        Menu menu = new Menu("桌面");
        menu.sub("新建", create);
        menu.item("导入文件（P2V）", () -> this.startP2vImport("/home/user"));
        menu.item("在终端中打开", () -> this.openTerminal("/home/user"));
        menu.sep();
        menu.item("刷新", () -> HajikunSmartClient.request(this.pos, "refresh", ""));
        menu.item("属性", () -> this.openApp(App.SETTINGS, true));
        return menu;
    }

    private void showTaskbarMenu(double mx, double my) {
        Menu menu = new Menu("任务栏");
        menu.item("层叠窗口", this::cascadeWindows);
        menu.item("关闭全部窗口", () -> {
            for (Win each : new ArrayList<>(this.windows)) {
                this.closeWindow(each);
            }
            this.focus = Focus.NONE;
            this.focusWin = null;
        });
        menu.sep();
        menu.item("刷新", () -> HajikunSmartClient.request(this.pos, "refresh", ""));
        menu.item("控制面板", () -> this.openApp(App.SETTINGS, true));
        this.showMenu(menu, mx, my);
    }

    private Menu windowMenu(Win win) {
        Menu menu = new Menu(win.title);
        menu.item(win.minimized ? "还原" : "移到最前", () -> {
            win.minimized = false;
            this.bringToFront(win);
        });
        menu.item("最小化", () -> this.minimize(win), !win.minimized);
        menu.item(win.maximized ? "还原大小" : "最大化", () -> this.toggleMaximize(win));
        if (win.app == App.FILES) {
            menu.item("在终端中打开当前目录", () -> this.openTerminal(win.path));
        }
        if (win.app == App.EDITOR) {
            menu.item("打开文件所在目录",
                    () -> this.openFilesAt(VirtualFileSystem.parent(win.savedPath)));
        }
        menu.sep();
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private void openWindowContentMenu(Win win, double mx, double my) {
        switch (win.app) {
            case FILES -> {
                int x = win.x + 6;
                int y = win.y + TITLEBAR + 4;
                int w = win.w - 12;
                int h = win.h - TITLEBAR - 7;
                String name = win.naming || !win.confirmDelete.isBlank()
                        ? null : this.fileRowAt(win, x, y, w, h);
                if (name != null) {
                    win.selected = name;
                    this.showMenu(this.fileRowMenu(win, name), mx, my);
                } else {
                    win.selected = "";
                    this.showMenu(this.filesBlankMenu(win), mx, my);
                }
            }
            case TERMINAL -> this.showMenu(this.terminalMenu(win), mx, my);
            case EDITOR -> this.showMenu(this.editorMenu(win), mx, my);
            case P2V -> this.showMenu(this.p2vMenu(win), mx, my);
            case BLUETOOTH -> this.showMenu(this.bluetoothMenu(win), mx, my);
            case SETTINGS -> this.showMenu(this.settingsMenu(win), mx, my);
            case VIEWER -> this.showMenu(this.viewerMenu(win), mx, my);
            case BROWSER -> this.showMenu(this.browserMenu(win), mx, my);
            default -> {
            }
        }
    }

    private Menu fileRowMenu(Win win, String name) {
        String path = this.filesChild(win, name);
        VirtualFileSystem.Node node = path == null ? null : this.device.fs.find(path);
        boolean dir = node != null && node.isDir();
        boolean locked = VirtualFileSystem.isProtected(path);
        Menu menu = new Menu(name);
        menu.item("打开", () -> this.openNode(win, name));
        if (dir) {
            menu.item("导入文件（P2V）到此文件夹", () -> this.startP2vImport(path));
        } else if (node != null && node.isBinary()) {
            menu.item("用媒体查看器打开", () -> this.openViewer(path, name, node));
        } else {
            menu.item("用记事本打开", () -> this.openInEditor(win, name));
        }
        menu.item("在终端中打开", () -> this.openTerminal(dir ? path
                : VirtualFileSystem.parent(path)));
        menu.sep();
        menu.item("重命名…", () -> this.startRename(win, name), !locked);
        menu.item("删除", () -> this.confirmDeleteFile(win, name), !locked);
        menu.sep();
        menu.item("属性", () -> win.status = path == null ? name
                : path + (dir ? " · 文件夹" : " · 文件"));
        return menu;
    }

    private Menu filesBlankMenu(Win win) {
        Menu create = new Menu("新建");
        create.item("文件夹", () -> this.createFolder(win.path));
        create.item("文本文档", () -> this.createTextFile(win.path));
        Menu menu = new Menu(win.path);
        menu.sub("新建", create);
        menu.item("导入文件（P2V）", () -> this.startP2vImport(win.path));
        menu.item("在终端中打开", () -> this.openTerminal(win.path));
        menu.sep();
        menu.item("刷新", () -> {
            HajikunSmartClient.request(this.pos, "refresh", "");
            win.status = "";
            win.selected = "";
        });
        menu.item("属性", () -> win.status = win.path + " · "
                + this.device.fs.list(win.path).size() + " 个项目");
        return menu;
    }

    private Menu terminalMenu(Win win) {
        Menu menu = new Menu("命令提示符");
        menu.item("复制全部输出", () -> {
            StringBuilder text = new StringBuilder();
            for (String line : win.log) {
                text.append(line).append('\n');
            }
            text.append(this.prompt()).append(win.input);
            setClipboard(text.toString());
            this.toast("已复制终端内容");
        });
        menu.item("粘贴", () -> win.input = win.input + clipboard());
        menu.item("清屏", () -> win.log.clear());
        menu.sep();
        menu.item("新建窗口", this::newTerminalWindow);
        menu.item("打开当前目录", () -> this.openFilesAt(this.device.cwd));
        menu.sep();
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private Menu editorMenu(Win win) {
        boolean locked = VirtualFileSystem.isProtected(win.savedPath);
        Menu menu = new Menu("记事本");
        menu.item("保存", () -> this.saveEditor(win));
        menu.item("复制全文", () -> {
            setClipboard(win.content);
            this.toast("已复制正文");
        });
        menu.item("粘贴", () -> {
            win.content = win.content + clipboard();
            win.input = win.content;
        });
        menu.sep();
        menu.item("打开所在目录", () -> this.openFilesAt(VirtualFileSystem.parent(win.savedPath)));
        menu.item("在终端中打开所在目录",
                () -> this.openTerminal(VirtualFileSystem.parent(win.savedPath)));
        menu.item("删除此文件", () -> {
            if (locked) {
                this.toast("系统文件受保护，不能删除。");
                return;
            }
            HajikunSmartClient.request(this.pos, "fs_delete", win.savedPath);
            this.toast("正在删除 " + win.savedPath);
        }, !locked);
        menu.sep();
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private Menu bluetoothMenu(Win win) {
        DeviceSnapshot.Peer selected = this.btSelected(win);
        Menu menu = new Menu("蓝牙设备");
        menu.item(this.device.bluetooth ? "关闭蓝牙" : "打开蓝牙",
                () -> HajikunSmartClient.request(this.pos, "bt_toggle",
                        this.device.bluetooth ? "off" : "on"));
        menu.item("重新扫描", () -> HajikunSmartClient.request(this.pos, "refresh", ""));
        if (selected != null) {
            menu.sep();
            menu.item(selected.label(), null, false);
            menu.item(selected.paired() ? "取消配对" : "配对", () -> {
                if (selected.paired()) {
                    HajikunSmartClient.request(this.pos, "bt_unpair", selected.mac());
                    win.status = "正在取消配对…";
                } else {
                    HajikunSmartClient.request(this.pos, "bt_pair", selected.mac());
                    win.status = "正在配对…";
                }
            });
            if (!selected.tv()) {
                menu.item("发送文件…", () -> {
                    if (!selected.paired()) {
                        win.status = "先配对，再发送文件。";
                        return;
                    }
                    win.picking = true;
                    win.pickPath = "/home/user";
                    win.scroll = 0;
                });
            }
            menu.item(selected.tv() ? "投屏到电视" : "投屏", () -> {
                HajikunSmartClient.request(this.pos, "cast", selected.mac());
                win.status = "正在连接电视…";
            });
        }
        menu.sep();
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private Menu settingsMenu(Win win) {
        Menu menu = new Menu("控制面板");
        menu.item("刷新", () -> HajikunSmartClient.request(this.pos, "refresh", ""));
        menu.item("复制 MAC 地址", () -> {
            setClipboard(this.device.mac == null ? "" : this.device.mac);
            this.toast("已复制 MAC 地址");
        });
        menu.sep();
        menu.item("软关机", () -> HajikunSmartClient.request(this.pos, "softoff", ""));
        menu.item("硬关机", () -> {
            HajikunSmartClient.request(this.pos, "hardoff", "");
            this.dropSession();
        });
        menu.sep();
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private Menu viewerMenu(Win win) {
        boolean video = win.mediaMime.startsWith("video/");
        boolean audio = win.mediaMime.startsWith("audio/");
        Menu menu = new Menu("媒体查看器");
        if (video) {
            menu.item(win.player != null && win.player.playing() ? "暂停" : "播放", () -> {
                if (win.player != null) {
                    win.player.toggle();
                }
            });
            menu.item("从头播放", () -> {
                if (win.player != null) {
                    win.player.rewind();
                    win.player.play();
                }
            });
        } else if (audio) {
            menu.item(MediaAudio.playing() ? "停止播放" : "播放", () -> {
                MediaCache.Blob blob = MediaCache.blob(this.pos, win.mediaPath);
                if (MediaAudio.playing()) {
                    MediaAudio.stop();
                } else if (blob != null) {
                    MediaCache.playAudio(blob.data());
                }
            });
        }
        menu.item("打开所在目录", () -> this.openFilesAt(VirtualFileSystem.parent(win.mediaPath)));
        menu.item("在终端中打开所在目录",
                () -> this.openTerminal(VirtualFileSystem.parent(win.mediaPath)));
        menu.sep();
        menu.item("属性", () -> win.status = win.mediaPath + " · " + win.mediaMime);
        menu.item("关闭", () -> this.closeWindow(win));
        return menu;
    }

    private void confirmDeleteFile(Win win, String name) {
        String path = this.filesChild(win, name);
        if (path != null && VirtualFileSystem.isProtected(path)) {
            win.status = "系统文件受保护，不能删除。";
            return;
        }
        win.confirmDelete = name;
        win.selected = name;
    }

    private void createFolder(String dir) {
        String base = dir == null || dir.isBlank() ? "/home/user" : dir;
        String name = "新建文件夹";
        int index = 2;
        while (this.device.fs.find(this.childPath(base, name)) != null) {
            name = "新建文件夹" + index++;
        }
        HajikunSmartClient.request(this.pos, "shell",
                "mkdir \"" + this.childPath(base, name) + "\"");
        this.toast("正在新建文件夹…");
    }

    private void createTextFile(String dir) {
        String base = dir == null || dir.isBlank() ? "/home/user" : dir;
        String name = "新建文本文档.txt";
        int index = 2;
        while (this.device.fs.find(this.childPath(base, name)) != null) {
            name = "新建文本文档" + index++ + ".txt";
        }
        HajikunSmartClient.request(this.pos, "shell",
                "touch \"" + this.childPath(base, name) + "\"");
        this.toast("正在新建文本文档…");
    }

    private String childPath(String dir, String name) {
        return (dir.equals("/") ? "" : dir) + "/" + name;
    }

    /** 打开一个终端窗口并切换到指定目录；目录跟随快照更新，提示符不会过期。 */
    private void openTerminal(String path) {
        this.menu = null;
        Win terminal = this.windowOf(App.TERMINAL);
        if (terminal == null) {
            terminal = this.createWindow(App.TERMINAL);
            this.windows.add(terminal);
        }
        this.bringToFront(terminal);
        this.beginEdit(Focus.TERMINAL, terminal, terminal.input);
        String target = path == null || path.isBlank() ? this.device.cwd : path;
        if (target != null && !target.isBlank() && !target.equals(this.device.cwd)) {
            this.pendingShell = terminal;
            HajikunSmartClient.request(this.pos, "shell", "cd \"" + target + "\"");
        }
    }

    private void openFilesAt(String path) {
        this.menu = null;
        Win files = this.windowOf(App.FILES);
        if (files == null) {
            files = this.createWindow(App.FILES);
            this.windows.add(files);
        }
        files.path = path == null || path.isBlank() ? "/home/user" : path;
        files.scroll = 0;
        files.selected = "";
        this.bringToFront(files);
    }

    private void openInEditor(Win files, String name) {
        String path = this.filesChild(files, name);
        VirtualFileSystem.Node node = path == null ? null : this.device.fs.find(path);
        if (node == null || node.isDir()) {
            return;
        }
        Win editor = this.windowOf(App.EDITOR);
        if (editor == null) {
            editor = this.createWindow(App.EDITOR);
            this.windows.add(editor);
        }
        editor.savedPath = path;
        editor.content = node.content == null ? "" : node.content;
        editor.input = editor.content;
        editor.scroll = 0;
        editor.title = "记事本 · " + name;
        this.bringToFront(editor);
        this.beginEdit(Focus.EDITOR, editor, editor.content);
    }

    /** 把记事本正文写回虚拟机；系统文件不允许覆盖。 */
    private void saveEditor(Win win) {
        if (VirtualFileSystem.isProtected(win.savedPath)) {
            win.status = "系统文件受保护，不能覆盖。";
            this.toast(win.status);
            return;
        }
        HajikunSmartClient.request(this.pos, "write", win.savedPath + "\n" + win.content);
        win.status = "已保存 " + win.savedPath;
        this.toast("已保存到 " + win.savedPath);
    }

    private void newTerminalWindow() {
        Win terminal = this.createWindow(App.TERMINAL);
        this.windows.add(terminal);
        this.beginEdit(Focus.TERMINAL, terminal, terminal.input);
    }

    private void bringToFront(Win win) {
        if (this.windows.remove(win)) {
            this.windows.add(win);
        }
    }

    private void closeWindow(Win win) {
        this.releaseMedia(win);
        for (BrowserTab tab : win.tabs) {
            this.releaseChromiumTab(tab);
        }
        this.windows.remove(win);
        if (this.focusWin == win) {
            this.focus = Focus.NONE;
            this.focusWin = null;
        }
        if (this.dragging == win) {
            this.dragging = null;
        }
        if (this.viewerDragWin == win) {
            this.viewerDragWin = null;
        }
        if (win.ide != null) {
            // 关窗只摘下窗口，会话仍留在 ideSession 里：再次打开 IDE 会接着上一次的对话。
            win.ide.preserve();
            win.ide = null;
        }
    }

    /** 最小化：窗口从桌面消失，任务栏按钮保留；IDE 的会话继续在后台保留。 */
    private void minimize(Win win) {
        win.minimized = true;
        if (this.focusWin == win) {
            this.focus = Focus.NONE;
            this.focusWin = null;
        }
        if (this.dragging == win) {
            this.dragging = null;
        }
        if (win.ide != null) {
            win.ide.preserve();
        }
    }

    /** 最大化铺满任务栏以上的桌面，再点一次还原到原来的位置和尺寸。 */
    private void toggleMaximize(Win win) {
        if (win.maximized) {
            win.maximized = false;
            win.x = Math.max(0, Math.min(this.width() - win.restoreW, win.restoreX));
            win.y = Math.max(0, Math.min(this.height() - TASKBAR - win.restoreH, win.restoreY));
            win.w = win.restoreW;
            win.h = win.restoreH;
        } else {
            win.restoreX = win.x;
            win.restoreY = win.y;
            win.restoreW = win.w;
            win.restoreH = win.h;
            win.maximized = true;
            win.x = 0;
            win.y = 0;
            win.w = this.width();
            win.h = this.height() - TASKBAR;
        }
        if (this.dragging == win) {
            this.dragging = null;
        }
    }

    private void releaseMedia(Win win) {
        if (win.player != null) {
            win.player.close();
            win.player = null;
        }
        MediaAudio.stop();
    }

    private void cascadeWindows() {
        int offset = 0;
        for (Win win : this.windows) {
            if (win.minimized) {
                continue;
            }
            if (win.maximized) {
                // 层叠前先回到窗口尺寸，否则最大化窗口会互相盖满整屏。
                win.maximized = false;
                win.w = win.restoreW;
                win.h = win.restoreH;
            }
            win.x = Math.max(0, Math.min(16 + offset, this.width() - win.w - 4));
            win.y = Math.max(0, Math.min(10 + offset, this.height() - TASKBAR - win.h - 2));
            offset += 7;
        }
    }

    private String prompt() {
        String cwd = this.device == null || this.device.cwd == null || this.device.cwd.isBlank()
                ? "/home/user" : this.device.cwd;
        String path = cwd.replace('/', '\\');
        if (path.equals("\\")) {
            path = "";
        }
        return "C:" + path + "> ";
    }

    private static String clipboard() {
        try {
            return Minecraft.getInstance().keyboardHandler.getClipboard();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static void setClipboard(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text == null ? "" : text);
        } catch (RuntimeException ignored) {
        }
    }

    /** 打开 P2V 窗口并把传入目标指向 dir；Swing 文件选择器在游戏里不可用，只能走窗口浏览。 */
    private void startP2vImport(String dir) {
        this.menu = null;
        String target = dir == null || dir.isBlank() ? P2V_DIR : dir;
        this.openApp(App.P2V, true);
        Win win = this.windowOf(App.P2V);
        if (win == null) {
            return;
        }
        win.p2vDir = target;
        if (!win.hostLoaded || win.hostPath.isBlank()) {
            win.hostPath = System.getProperty("user.home", "");
            this.reloadHost(win);
        }
        win.p2vStatus = "传入到 " + target + "：勾选文件后点「传入」。";
        this.toast("P2V 已打开：选中物理机文件后点「传入」");
    }

    private boolean clickSetup(double mx, double my) {
        int x = 34;
        int fieldY = 110;
        int fieldW = this.width() - 44 - 24;
        if (this.inside(x, fieldY, fieldW, 20)) {
            this.beginEditAt(Focus.SETUP, null, this.setupText,
                    this.textIndexAt(x + 6, this.setupText, mx), true);
            return true;
        }
        int buttonY = fieldY + 30;
        if (this.inside(x, buttonY, this.setupButtonWidth(), 20)) {
            this.saveSetup();
            return true;
        }
        this.focus = Focus.NONE;
        return true;
    }

    private void saveSetup() {
        String value = this.setupText == null ? "" : this.setupText.trim();
        if (value.isEmpty()) {
            this.toast("设备名不能为空。");
            return;
        }
        if (value.length() > 32) {
            this.toast("设备名最多 32 个字符。");
            return;
        }
        HajikunSmartClient.request(this.pos, "rename", value);
        this.toast("正在保存设备信息…");
    }

    private boolean clickContent(Win win, double mx, double my) {
        int x = win.x + 6;
        int y = win.y + TITLEBAR + 4;
        int w = win.w - 12;
        int h = win.h - TITLEBAR - 7;
        switch (win.app) {
            case FILES -> {
                return this.clickFiles(win, mx, my, false);
            }
            case TERMINAL -> {
                int promptY = y + h - LINE;
                int inputX = x + this.textWidth(this.prompt());
                boolean onPrompt = my >= promptY - 2 && my < promptY + LINE;
                this.beginEditAt(Focus.TERMINAL, win, win.input,
                        onPrompt ? this.textIndexAt(inputX, win.input, mx) : win.input.length(),
                        onPrompt);
                return true;
            }
            case P2V -> {
                return this.clickP2v(win, mx, my);
            }
            case BLUETOOTH -> {
                return this.clickBluetooth(win, mx, my);
            }
            case VIEWER -> {
                return this.clickViewer(win, mx, my, x, y, w, h);
            }
            case BROWSER -> {
                return this.clickBrowser(win, mx, my, x, y, w, h);
            }
            case EDITOR -> {
                int saveW = this.labelWidth("保存");
                if (this.inside(x + w - saveW, y - 4, saveW, CHIP_H)) {
                    this.saveEditor(win);
                    return true;
                }
                boolean body = my >= y + 14;
                this.beginEditAt(Focus.EDITOR, win, win.content,
                        body ? this.editorIndexAt(win, x, y, w, h, mx, my) : win.content.length(),
                        body);
                return true;
            }
            case SETTINGS -> {
                int fieldY = y + 14 * 4 + 3;
                if (this.inside(x, fieldY, w - 50, CHIP_H)) {
                    this.beginEditAt(Focus.RENAME, win, win.input,
                            this.textIndexAt(x + 4, win.input, mx), true);
                    return true;
                }
                if (this.inside(x + w - 44, fieldY, this.labelWidth("改名"), CHIP_H)) {
                    HajikunSmartClient.request(this.pos, "rename", win.input);
                    return true;
                }
                int cx = x;
                if (this.inside(cx, fieldY + 24, this.labelWidth("软关机"), CHIP_H)) {
                    HajikunSmartClient.request(this.pos, "softoff", "");
                    return true;
                }
                cx += this.labelWidth("软关机") + 5;
                if (this.inside(cx, fieldY + 24, this.labelWidth("硬关机"), CHIP_H)) {
                    HajikunSmartClient.request(this.pos, "hardoff", "");
                    return true;
                }
                cx += this.labelWidth("硬关机") + 5;
                if (this.inside(cx, fieldY + 24, this.labelWidth("刷新"), CHIP_H)) {
                    HajikunSmartClient.request(this.pos, "refresh", "");
                    return true;
                }
            }
            default -> {
            }
        }
        return false;
    }

    /** 媒体查看器：图片缩放/平移，音视频播放、进度、音量与倍速。 */
    private boolean clickViewer(Win win, double mx, double my, int x, int y, int w, int h) {
        if (win.mediaPath.isBlank()) {
            return true;
        }
        if (win.mediaMime.startsWith("image/")) {
            int fy = y + h - 13;
            int fitW = this.labelWidth("适应");
            int plusW = this.labelWidth("＋");
            int minusW = this.labelWidth("－");
            int fitX = x + w - fitW;
            int plusX = fitX - 4 - plusW;
            int minusX = plusX - 4 - minusW;
            if (this.inside(minusX, fy, minusW, CHIP_H)) {
                this.zoomImage(win, 1.0D / 1.25D);
                return true;
            }
            if (this.inside(plusX, fy, plusW, CHIP_H)) {
                this.zoomImage(win, 1.25D);
                return true;
            }
            if (this.inside(fitX, fy, fitW, CHIP_H)) {
                win.zoom = 1.0D;
                win.panX = 0;
                win.panY = 0;
                return true;
            }
            if (my < fy && my >= y + TITLEBAR) {
                this.viewerDragWin = win;
                this.viewerDragMode = 1;
                this.viewerDragX = mx;
                this.viewerDragY = my;
            }
            return true;
        }
        boolean video = win.mediaMime.startsWith("video/");
        boolean audio = win.mediaMime.startsWith("audio/");
        if (!video && !audio) {
            return true;
        }
        int barY = y + h - 30;
        String toggle = video
                ? (win.player != null && win.player.playing() ? "暂停" : "播放")
                : (MediaAudio.playing() ? "停止" : "播放");
        int cx = x;
        if (this.inside(cx, barY, this.labelWidth(toggle), CHIP_H)) {
            this.toggleViewer(win);
            return true;
        }
        cx += this.labelWidth(toggle) + 4;
        String speedLabel = this.mediaSpeedLabel(win.speed);
        if (this.inside(cx, barY, this.labelWidth(speedLabel), CHIP_H)) {
            this.cycleViewerSpeed(win);
            return true;
        }
        cx += this.labelWidth(speedLabel) + 4;
        if (this.inside(cx, barY, this.labelWidth("－"), CHIP_H)) {
            MediaAudio.setVolume(MediaAudio.volume() - 0.1F);
            return true;
        }
        cx += this.labelWidth("－") + 3;
        if (this.inside(cx, barY, this.labelWidth("＋"), CHIP_H)) {
            MediaAudio.setVolume(MediaAudio.volume() + 0.1F);
            return true;
        }
        if (my >= y + h - 14 && my <= y + h + 2) {
            this.seekViewer(win, mx, x, w);
            this.viewerDragWin = win;
            this.viewerDragMode = 2;
            this.viewerDragX = mx;
            this.viewerDragY = my;
        }
        return true;
    }

    private void zoomImage(Win win, double factor) {
        win.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, win.zoom * factor));
        if (win.zoom <= 1.001D) {
            win.panX = 0;
            win.panY = 0;
        }
    }

    private void toggleViewer(Win win) {
        if (win.mediaMime.startsWith("video/")) {
            if (win.player != null) {
                win.player.toggle();
            }
            return;
        }
        MediaCache.Blob blob = MediaCache.blob(this.pos, win.mediaPath);
        if (MediaAudio.playing()) {
            long at = MediaAudio.positionMillis();
            if (at >= 0L) {
                win.audioPosMs = at;
            }
            MediaAudio.stop();
        } else if (blob != null) {
            if (win.audioDurationMs > 0L && win.audioPosMs >= win.audioDurationMs - 400L) {
                win.audioPosMs = 0L;
            }
            MediaAudio.playAt(blob.data(), win.speed, win.audioPosMs);
        }
    }

    private void cycleViewerSpeed(Win win) {
        float next = nextMediaSpeed(win.speed);
        win.speed = next;
        if (win.mediaMime.startsWith("video/")) {
            if (win.player != null) {
                win.player.setSpeed(next);
            }
            return;
        }
        if (!MediaAudio.playing()) {
            return;
        }
        long at = Math.max(0L, MediaAudio.positionMillis());
        win.audioPosMs = at;
        MediaCache.Blob blob = MediaCache.blob(this.pos, win.mediaPath);
        if (blob != null) {
            MediaAudio.playAt(blob.data(), next, at);
        }
    }

    /** 按进度条位置跳转；视频跳到对应帧，音频从原始时间轴续播。 */
    private void seekViewer(Win win, double mx, int x, int w) {
        double ratio = Math.max(0.0D, Math.min(1.0D, (mx - x) / Math.max(1, w)));
        if (win.mediaMime.startsWith("video/")) {
            if (win.player != null) {
                win.player.seek(Math.round(ratio * win.player.durationMillis()));
            }
            return;
        }
        long duration = win.audioDurationMs > 0L ? win.audioDurationMs
                : (MediaAudio.playing() ? MediaAudio.durationMillis() : 0L);
        if (duration <= 0L) {
            return;
        }
        long at = Math.round(ratio * duration);
        win.audioPosMs = at;
        if (MediaAudio.playing()) {
            MediaAudio.seekMillis(at);
        }
    }

    // ------------------------------------------------------------------ 文件管理器交互

    private boolean clickFiles(Win win, double mx, double my, boolean secondary) {
        int x = win.x + 6;
        int y = win.y + TITLEBAR + 4;
        int w = win.w - 12;
        int h = win.h - TITLEBAR - 7;

        if (win.naming) {
            int boxW = Math.min(w - 8, 186);
            int boxH = 70;
            int bx = x + (w - boxW) / 2;
            int by = y + (h - boxH) / 2;
            int bw = (boxW - 30) / 2;
            if (this.inside(bx + 10, by + boxH - 20, bw, 15)) {
                this.requestRename(win);
                return true;
            }
            if (this.inside(bx + 20 + bw, by + boxH - 20, bw, 15)) {
                win.naming = false;
                this.clearFileFocus(win);
                return true;
            }
            if (this.inside(bx + 10, by + 33, boxW - 20, 15)) {
                this.beginEditAt(Focus.FILE_NAME, win, win.renameText,
                        this.textIndexAt(bx + 13, win.renameText, mx), true);
                return true;
            }
            if (!this.inside(bx, by, boxW, boxH)) {
                win.naming = false;
                this.clearFileFocus(win);
            }
            return true;
        }
        if (!win.confirmDelete.isBlank()) {
            int boxW = Math.min(w - 8, 176);
            int boxH = 64;
            int bx = x + (w - boxW) / 2;
            int by = y + (h - boxH) / 2;
            int bw = (boxW - 30) / 2;
            if (this.inside(bx + 10, by + boxH - 22, bw, 15)) {
                String path = this.filesChild(win, win.confirmDelete);
                win.confirmDelete = "";
                win.status = "正在删除…";
                win.selected = "";
                MediaCache.forget(this.pos, path);
                HajikunSmartClient.request(this.pos, "fs_delete", path == null ? "" : path);
                return true;
            }
            if (this.inside(bx + 20 + bw, by + boxH - 22, bw, 15)
                    || !this.inside(bx, by, boxW, boxH)) {
                win.confirmDelete = "";
            }
            return true;
        }
        if (!win.menuName.isBlank()) {
            int[] rect = this.fileMenuRect(win);
            int menuW = rect[2];
            int menuH = rect[3];
            if (this.inside(rect[0], rect[1], menuW, menuH)) {
                int index = (int) ((my - rect[1] - 2) / 13);
                String name = win.menuName;
                win.menuName = "";
                if (index == 0) {
                    this.openNode(win, name);
                } else if (index == 1) {
                    this.startRename(win, name);
                } else if (index == 2) {
                    String path = this.filesChild(win, name);
                    if (path != null && VirtualFileSystem.isProtected(path)) {
                        win.status = "系统文件受保护，不能删除。";
                    } else {
                        win.confirmDelete = name;
                    }
                }
                return true;
            }
            win.menuName = "";
            return true;
        }

        int addrY = y - 1;
        if (this.inside(x - 1, addrY, w + 2, 12)) {
            this.beginEditAt(Focus.ADDRESS_FILES, win, win.path,
                    this.textIndexAt(x + 3, win.path, mx), true);
            return true;
        }

        int barY = y + 12;
        int cx = x;
        if (this.inside(cx, barY, this.labelWidth("上级"), CHIP_H)) {
            win.path = VirtualFileSystem.parent(win.path);
            win.scroll = 0;
            win.selected = "";
            win.status = "";
            return true;
        }
        cx += this.labelWidth("上级") + 4;
        if (this.inside(cx, barY, this.labelWidth("新建文件夹"), CHIP_H)) {
            String name = "新建文件夹";
            int index = 2;
            while (this.device.fs.find(this.filesChild(win, name)) != null) {
                name = "新建文件夹" + index++;
            }
            HajikunSmartClient.request(this.pos, "shell",
                    "mkdir \"" + (win.path.equals("/") ? "" : win.path) + "/" + name + "\"");
            win.status = "正在新建文件夹…";
            return true;
        }
        cx += this.labelWidth("新建文件夹") + 4;
        if (this.inside(cx, barY, this.labelWidth("新建文件"), CHIP_H)) {
            String name = "新建文档.txt";
            int index = 2;
            while (this.device.fs.find(this.filesChild(win, name)) != null) {
                name = "新建文档" + index++ + ".txt";
            }
            HajikunSmartClient.request(this.pos, "shell",
                    "touch \"" + (win.path.equals("/") ? "" : win.path) + "/" + name + "\"");
            win.status = "正在新建文件…";
            return true;
        }
        cx += this.labelWidth("新建文件") + 4;
        if (this.inside(cx, barY, this.labelWidth("刷新"), CHIP_H)) {
            HajikunSmartClient.request(this.pos, "refresh", "");
            win.status = "";
            return true;
        }

        String name = this.fileRowAt(win, x, y, w, h);
        if (name != null) {
            if (secondary) {
                win.selected = name;
                win.menuName = name;
                win.menuX = (int) mx;
                win.menuY = (int) my;
                win.status = "";
                return true;
            }
            boolean doubleClick = name.equals(win.lastClickName)
                    && System.currentTimeMillis() - win.lastClickAt <= DOUBLE_CLICK_MS;
            win.selected = name;
            win.lastClickAt = System.currentTimeMillis();
            win.lastClickName = doubleClick ? "" : name;
            win.status = "";
            if (doubleClick) {
                this.openNode(win, name);
            }
            return true;
        }
        if (secondary) {
            win.menuName = "";
        }
        win.selected = "";
        win.status = "";
        return true;
    }

    /** 鼠标落在哪一行；没有命中返回 null。 */
    private String fileRowAt(Win win, int x, int y, int w, int h) {
        int listY = y + 29;
        int rows = this.fileRows(h, 29);
        List<VirtualFileSystem.Node> nodes = this.device.fs.list(win.path);
        int start = Math.min(win.scroll, Math.max(0, nodes.size() - rows));
        for (int i = 0; i < rows && start + i < nodes.size(); i++) {
            int ry = listY + i * ROW;
            if (this.inside(x, ry - 2, w, ROW - 1)) {
                return nodes.get(start + i).name;
            }
        }
        return null;
    }

    private void openNode(Win win, String name) {
        String path = this.filesChild(win, name);
        if (path == null) {
            return;
        }
        VirtualFileSystem.Node node = this.device.fs.find(path);
        if (node == null) {
            win.status = "找不到 " + name;
            return;
        }
        if (node.isDir()) {
            win.path = path;
            win.scroll = 0;
            win.selected = "";
            return;
        }
        if (node.isBinary()) {
            this.openViewer(path, name, node);
            return;
        }
        Win editor = this.windowOf(App.EDITOR);
        if (editor == null) {
            this.openApp(App.EDITOR, false);
            editor = this.windowOf(App.EDITOR);
        }
        if (editor != null) {
            editor.savedPath = path;
            editor.content = node.content == null ? "" : node.content;
            editor.input = editor.content;
            editor.scroll = 0;
            editor.title = "记事本 · " + name;
            this.beginEdit(Focus.EDITOR, editor, editor.content);
        }
    }

    private void openViewer(String path, String name, VirtualFileSystem.Node node) {
        Win viewer = this.windowOf(App.VIEWER);
        if (viewer == null) {
            viewer = this.createWindow(App.VIEWER);
            this.windows.add(viewer);
        }
        if (viewer.player != null) {
            viewer.player.close();
            viewer.player = null;
        }
        MediaAudio.stop();
        viewer.mediaPath = path;
        viewer.mediaName = name;
        viewer.mediaHash = node.media == null ? "" : node.media;
        viewer.mediaMime = node.mime == null || node.mime.isBlank()
                ? this.mimeOfName(name) : node.mime;
        viewer.title = "媒体查看器 · " + name;
        viewer.status = "";
        viewer.zoom = 1.0D;
        viewer.panX = 0;
        viewer.panY = 0;
        viewer.speed = 1.0F;
        viewer.audioPosMs = 0L;
        viewer.audioDurationMs = -1L;
        this.bringToFront(viewer);
        this.focus = Focus.NONE;
        this.focusWin = null;
        MediaCache.ensure(this.pos, path, viewer.mediaHash);
    }

    private String shortMime(String mime) {
        if (mime == null) {
            return "";
        }
        return switch (mime) {
            case "image/png" -> "PNG";
            case "image/jpeg" -> "JPG";
            case "image/bmp" -> "BMP";
            case "audio/wav" -> "WAV";
            case "video/hkv" -> "录像";
            default -> mime.startsWith("image/") ? "图片"
                    : mime.startsWith("audio/") ? "音频"
                    : mime.startsWith("video/") ? "视频" : "";
        };
    }

    private String mimeOfName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".hkv")) {
            return "video/hkv";
        }
        return "application/octet-stream";
    }

    private void startRename(Win win, String name) {
        String path = this.filesChild(win, name);
        if (path == null) {
            return;
        }
        if (VirtualFileSystem.isProtected(path)) {
            win.status = "系统文件受保护，不能重命名。";
            return;
        }
        win.naming = true;
        win.renameText = name;
        win.selected = name;
        this.beginEdit(Focus.FILE_NAME, win, name);
    }

    private void requestRename(Win win) {
        String old = win.selected;
        String path = this.filesChild(win, old);
        String name = win.renameText == null ? "" : win.renameText.trim();
        if (path == null || name.isEmpty()) {
            win.status = "名称不能为空。";
            return;
        }
        win.naming = false;
        this.clearFileFocus(win);
        win.status = "正在重命名…";
        String next = this.childPath(VirtualFileSystem.parent(path), name);
        MediaCache.forget(this.pos, path);
        Win viewer = this.windowOf(App.VIEWER);
        if (viewer != null && path.equals(viewer.mediaPath)) {
            viewer.mediaPath = next;
            viewer.mediaName = name;
            viewer.title = "媒体查看器 · " + name;
            viewer.mediaHash = "";
            if (viewer.player != null) {
                viewer.player.close();
                viewer.player = null;
            }
        }
        HajikunSmartClient.request(this.pos, "fs_rename", path + "\n" + name);
    }

    private void clearFileFocus(Win win) {
        if (this.focusWin == win) {
            this.focus = Focus.NONE;
            this.focusWin = null;
        }
    }

    // ------------------------------------------------------------------ 蓝牙交互

    private boolean clickBluetooth(Win win, double mx, double my) {
        int x = win.x + 6;
        int y = win.y + TITLEBAR + 4;
        int w = win.w - 12;
        int h = win.h - TITLEBAR - 7;
        if (win.picking) {
            int cancelW = this.labelWidth("取消");
            if (this.inside(x + w - cancelW, y - 3, cancelW, CHIP_H)) {
                win.picking = false;
                win.scroll = 0;
                return true;
            }
            List<VirtualFileSystem.Node> nodes = this.device.fs.list(win.pickPath);
            int listY = y + 24;
            int rows = Math.max(1, (h - 28) / 12);
            int start = Math.min(win.scroll, Math.max(0, nodes.size() - rows));
            for (int i = 0; i < rows && start + i < nodes.size(); i++) {
                int ry = listY + i * 12;
                if (!this.inside(x, ry - 1, w, 12)) {
                    continue;
                }
                VirtualFileSystem.Node node = nodes.get(start + i);
                String path = (win.pickPath.equals("/") ? "" : win.pickPath) + "/" + node.name;
                if (node.isDir()) {
                    win.pickPath = path;
                    win.scroll = 0;
                    return true;
                }
                win.picking = false;
                win.scroll = 0;
                win.status = "正在发送 " + node.name + " …";
                HajikunSmartClient.request(this.pos, "bt_send", win.btMac + "\n" + path);
                return true;
            }
            return true;
        }
        int toggleW = this.labelWidth(this.device.bluetooth ? "蓝牙：开" : "蓝牙：关") + 14;
        if (this.inside(x, y, toggleW, CHIP_H)) {
            HajikunSmartClient.request(this.pos, "bt_toggle", this.device.bluetooth ? "off" : "on");
            return true;
        }
        int refreshW = this.labelWidth("刷新");
        if (this.inside(x + w - refreshW, y, refreshW, CHIP_H)) {
            HajikunSmartClient.request(this.pos, "refresh", "");
            return true;
        }
        if (!this.device.bluetooth) {
            return true;
        }
        List<DeviceSnapshot.Peer> peers = this.btPeers();
        int listY = y + 31;
        int listH = h - 33 - 34;
        int rows = Math.max(1, (listH - 15) / 13);
        for (int i = 0; i < rows && i < peers.size(); i++) {
            int ry = listY + 16 + i * 13;
            if (this.inside(x + 1, ry, w - 2, 13)) {
                win.btMac = peers.get(i).mac();
                win.status = "";
                return true;
            }
        }
        DeviceSnapshot.Peer selected = this.btSelected(win);
        if (selected == null) {
            return true;
        }
        int buttonY = y + h - 30;
        int bx = x;
        String action = selected.paired() ? "取消配对" : "配对";
        if (this.inside(bx, buttonY, this.labelWidth(action), CHIP_H)) {
            if (selected.paired()) {
                HajikunSmartClient.request(this.pos, "bt_unpair", selected.mac());
                win.status = "正在取消配对…";
            } else {
                HajikunSmartClient.request(this.pos, "bt_pair", selected.mac());
                win.status = "正在配对…";
            }
            return true;
        }
        bx += this.labelWidth(action) + 4;
        if (selected.tv()) {
            if (this.inside(bx, buttonY, this.labelWidth("投屏到电视"), CHIP_H)) {
                HajikunSmartClient.request(this.pos, "cast", selected.mac());
                win.status = "正在连接电视…";
            }
            return true;
        }
        if (this.inside(bx, buttonY, this.labelWidth("发送文件"), CHIP_H)) {
            if (!selected.paired()) {
                win.status = "先配对，再发送文件。";
                return true;
            }
            win.picking = true;
            win.pickPath = "/home/user";
            win.scroll = 0;
            return true;
        }
        bx += this.labelWidth("发送文件") + 4;
        if (this.inside(bx, buttonY, this.labelWidth("投屏"), CHIP_H)) {
            HajikunSmartClient.request(this.pos, "cast", selected.mac());
            win.status = "正在连接电视…";
        }
        return true;
    }

    private Win windowOf(App app) {
        for (int i = this.windows.size() - 1; i >= 0; i--) {
            if (this.windows.get(i).app == app) {
                return this.windows.get(i);
            }
        }
        return null;
    }

    private Win topWinAt(double mx, double my) {
        for (int i = this.windows.size() - 1; i >= 0; i--) {
            Win win = this.windows.get(i);
            if (win.minimized) {
                continue;
            }
            if (mx >= win.x && mx < win.x + win.w && my >= win.y && my < win.y + win.h) {
                return win;
            }
        }
        return null;
    }

    private int textWidth(String text) {
        return font().width(text);
    }

    public boolean scroll(double x, double y, double delta) {
        this.mouseMove(x, y);
        if (this.menu != null) {
            this.menu = null;
            return true;
        }
        if (this.shutdownDialog) {
            return true;
        }
        Win win = this.topWinAt(this.mouseX, this.mouseY);
        if (win != null) {
            if (win.app == App.JAVAIDE && win.ide != null) {
                win.ide.mouseScrolled(((int) Math.round(this.mouseX) - win.x) * IDE_SCALE,
                        ((int) Math.round(this.mouseY) - win.y - TITLEBAR) * IDE_SCALE,
                        (int) Math.signum(delta));
                return true;
            }
            int direction = (int) Math.signum(delta);
            if (win.app == App.P2V) {
                win.scroll = Math.max(0, Math.min(win.hostMaxScroll, win.scroll - direction * 2));
            } else if (win.app == App.BROWSER) {
                BrowserTab tab = win.tab();
                if (tab.chromiumPage && tab.chromium >= 0
                        && this.inside(tab.pageX, tab.pageY, tab.pageW, tab.pageH)) {
                    WebKitEngine.wheel(tab.chromium,
                            (float) (this.mouseX - tab.pageX),
                            (float) (this.mouseY - tab.pageY),
                            direction * 110.0F, false);
                } else {
                    tab.scroll = Math.max(0, Math.min(tab.maxScroll,
                            tab.scroll - direction * 3));
                }
            } else {
                win.scroll = Math.max(0, win.scroll - direction * 3);
            }
            return true;
        }
        return false;
    }

    public boolean typing() {
        if (this.focus != Focus.NONE) {
            return true;
        }
        if (this.chromiumTypingTab() != null) {
            return true;
        }
        IdeApp ide = this.ideTarget();
        return ide != null && ide.typing();
    }

    /** 键盘归属：最后点过的 IDE 窗口；点开别的窗口或桌面后自动让出。 */
    private IdeApp ideTarget() {
        if (this.focusWin != null && this.focusWin.app == App.JAVAIDE) {
            return this.focusWin.ide;
        }
        return null;
    }

    /** 供全屏 JavaIDE 读取最新设备状态。 */
    public DeviceSnapshot snapshot() {
        return this.device;
    }

    /** 当前焦点是否是虚拟机里的文本框（终端、编辑器、名称、地址栏）。 */
    private boolean editingText() {
        return this.focus != Focus.NONE
                && (this.focus == Focus.SETUP || this.focusWin != null);
    }

    private int editLimit() {
        return switch (this.focus) {
            case SETUP -> 32;
            case FILE_NAME -> 40;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- Electron 浏览器

    /**
     * 浏览器主界面：标签栏、导航工具栏、网页内容与状态行。
     *
     * <p>画面按虚拟机像素排版，网页先由 {@link BrowserClient} 解析成「块」再画出来：
     * 标题、正文、链接、图片、表单字段和提交按钮都能点。页面热区在每次渲染时重建，
     * 点击用上一帧的坐标，所以排版与命中永远不会错位。</p>
     */
    private void renderBrowser(Canvas c, Win win, int x, int y, int w, int h) {
        BrowserTab tab = win.tab();
        tab.hits.clear();
        this.loadBrowserStore();
        int stripH = 13;
        int barH = 14;
        int statusH = 11;

        // 网页先画、浏览器的标签栏与工具栏后画：文字没有裁剪栈，页面滚动时
        // 行首那半行字会越过内容区顶边；后画的工具栏正好把越界部分盖住，
        // 看起来和真正的裁剪一致（否则「搜索」会浮到地址栏上）。
        int fy = y + stripH + 1 + barH + 1;
        int findH = tab.findOpen ? 13 : 0;
        int pageY = fy + findH + 1;
        int pageH = Math.max(10, y + h - statusH - pageY);
        if (tab.findOpen && !tab.findQuery.equals(tab.lastFindQuery)
                && System.currentTimeMillis() - tab.lastFindAt > 200L) {
            tab.lastFindQuery = tab.findQuery;
            tab.lastFindAt = System.currentTimeMillis();
            this.runBrowserFind(tab);
        }
        c.fill(x - 3, pageY, x + w + 3, y + h, 0xFFFFFFFF);
        c.pushClip(x, pageY, x + w, pageY + pageH);
        this.renderBrowserPage(c, win, tab, x + 1, pageY + 1, w - 2, pageH - 2);
        c.popClip();
        if (tab.maxScroll > 0) {
            int trackY = pageY + 2;
            int trackH = Math.max(6, pageH - 4);
            int thumbH = Math.max(8, trackH * pageH / (pageH + tab.maxScroll));
            int thumbY = trackY + (trackH - thumbH) * tab.scroll / Math.max(1, tab.maxScroll);
            c.fill(x + w - 3, trackY, x + w - 1, trackY + trackH, 0xFFEDEBE0);
            c.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumbH, 0xFFB0AEA4);
        }

        // 标签栏：活动标签用浅色纸面，其余压暗；最右是新建标签页。
        c.fill(x - 3, y - 3, x + w + 3, y + stripH, 0xFFD9D6C9);
        int closeW = 13;
        int tabW = 58;
        int visible = Math.max(1, (w - closeW) / (tabW + 2));
        int first = Math.max(0, Math.min(win.activeTab - visible + 1, win.tabs.size() - visible));
        int tx = x;
        for (int i = first; i < win.tabs.size() && i < first + visible; i++) {
            BrowserTab each = win.tabs.get(i);
            boolean active = i == win.activeTab;
            int right = tx + tabW;
            c.fill(tx, y - 1, right, y + stripH, active ? 0xFFF7F5EA : 0xFFC3C9D2);
            c.outline(tx, y - 1, right, y + stripH, active ? 0xFF9C9A8E : 0xFF9AA3AE);
            c.text(tx + 3, y + 2, this.fitText(each.title, tabW - 15),
                    active ? COLOR_TEXT : COLOR_MUTED);
            this.tip(tx, y, right - 11 - tx, stripH, each.title);
            c.text(right - 9, y + 2, "✕", 0xFF7A5A5A);
            tab.hits.add(new BrowserHit(tx, y, right - 10 - tx, stripH, "tab",
                    Integer.toString(i), ""));
            tab.hits.add(new BrowserHit(right - 10, y, 9, stripH, "tab_close",
                    Integer.toString(i), ""));
            tx = right + 1;
        }
        int plusX = x + w - closeW;
        c.fill(plusX, y, plusX + closeW, y + stripH, 0xFFC3C9D2);
        c.outline(plusX, y, plusX + closeW, y + stripH, 0xFF9AA3AE);
        c.centered("+", plusX + closeW / 2, y + 2, 0xFF2A3A50);
        tab.hits.add(new BrowserHit(plusX, y, closeW, stripH, "new_tab", "", ""));

        // 导航工具栏：← → ⟳ ⌂ 书签 查找 阅读 菜单 | 地址栏 | 前往。
        int by = y + stripH + 1;
        c.gradient(x - 3, by, x + w + 3, by + barH, 0xFFF7F5EA, 0xFFDDD9C8);
        c.fill(x - 3, by + barH, x + w + 3, by + barH + 1, 0xFFB7B3A4);
        int bx = x;
        WebKitEngine.State chromeState = tab.chromium >= 0
                ? WebKitEngine.state(tab.chromium) : null;
        boolean canBack = chromeState != null ? chromeState.canBack() : tab.historyAt > 0;
        boolean canForward = chromeState != null ? chromeState.canForward()
                : tab.historyAt >= 0 && tab.historyAt < tab.history.size() - 1;
        bx = this.browserTool(c, tab, "←", bx, by, "back",
                canBack, false, "后退");
        bx = this.browserTool(c, tab, "→", bx + 1, by, "forward",
                canForward, false, "前进");
        bx = this.browserTool(c, tab, "⟳", bx + 1, by, "reload", !tab.loading, false, "刷新");
        String home = this.browserSettings.home == null || this.browserSettings.home.isBlank()
                ? BROWSER_HOME : this.browserSettings.home;
        bx = this.browserTool(c, tab, "⌂", bx + 1, by, "home", true, false,
                "主页 · " + home);
        boolean starred = this.isBookmarked(tab.url);
        bx = this.browserTool(c, tab, starred ? "★" : "☆", bx + 1, by, "bookmark", true,
                starred, starred ? "移除书签" : "添加书签");
        bx = this.browserTool(c, tab, "⌕", bx + 1, by, "find", true,
                tab.findOpen, "页内查找");
        bx = this.browserTool(c, tab, "A", bx + 1, by, "reader", true,
                tab.reader, "阅读模式");
        bx = this.browserTool(c, tab, "≡", bx + 1, by, "menu", true, false, "浏览器菜单");
        int goW = 26;
        int ax = bx + 3;
        int aw = Math.max(24, x + w - 3 - goW - 2 - ax);
        boolean editing = this.focus == Focus.ADDRESS_BROWSER && this.focusWin == win
                && win.browserField.isBlank();
        c.fill(ax, by + 1, ax + aw, by + barH - 1, 0xFFFFFFFF);
        c.outline(ax, by + 1, ax + aw, by + barH - 1, editing ? 0xFF2A62D8 : 0xFF7F9DB9);
        String shownAddress = editing ? this.edit.text : tab.address;
        if (editing) {
            this.fieldText(c, ax + 3, by + 4, aw - 5, this.edit.text, true, COLOR_ACCENT);
            if (this.blink()) {
                int caret = this.caretX(ax + 3, this.edit.text, this.edit.caret, ax + aw - 2);
                c.fill(caret, by + 3, caret + 1, by + barH - 2, 0xFF202020);
            }
        } else {
            c.text(ax + 3, by + 4, this.fitText(shownAddress, aw - 6), COLOR_ACCENT);
            this.tip(ax, by + 1, aw, barH - 2, shownAddress);
        }
        tab.hits.add(new BrowserHit(ax, by + 1, aw, barH - 2, "focus_address", "", ""));
        int goX = ax + aw + 2;
        c.gradient(goX, by + 1, goX + goW, by + barH - 1, 0xFF7CB6F8, 0xFF2E6FC8);
        c.outline(goX, by + 1, goX + goW, by + barH - 1, 0xFF1B4A86);
        c.centered("前往", goX + goW / 2, by + 3, 0xFFFFFFFF);
        tab.hits.add(new BrowserHit(goX, by + 1, goW, barH - 2, "go", "", ""));

        if (tab.findOpen) {
            c.fill(x - 3, fy, x + w + 3, fy + findH, 0xFFEDEBE0);
            c.fill(x - 3, fy + findH, x + w + 3, fy + findH + 1, 0xFFB7B3A4);
            int searchX = x + 1;
            int searchW = Math.max(20, w - 78);
            boolean findEditing = this.focus == Focus.FIND && this.focusWin == win;
            c.fill(searchX, fy + 1, searchX + searchW, fy + 12, 0xFFFFFFFF);
            c.outline(searchX, fy + 1, searchX + searchW, fy + 12,
                    findEditing ? 0xFF2A62D8 : 0xFF9C9A8E);
            String query = findEditing ? this.edit.text : tab.findQuery;
            boolean shown = !query.isBlank();
            if (findEditing) {
                if (shown) {
                    this.fieldText(c, searchX + 3, fy + 3, searchW - 6, query, true, COLOR_TEXT);
                } else {
                    c.text(searchX + 3, fy + 3, "在页面中查找", COLOR_DIM);
                }
            } else {
                c.text(searchX + 3, fy + 3,
                        shown ? this.fitText(query, searchW - 6) : "在页面中查找",
                        shown ? COLOR_TEXT : COLOR_DIM);
            }
            if (findEditing && this.blink()) {
                int caret = this.caretX(searchX + 3, query, this.edit.caret, searchX + searchW - 2);
                c.fill(caret, fy + 2, caret + 1, fy + 11, 0xFF202020);
            }
            tab.hits.add(new BrowserHit(searchX, fy + 1, searchW, 11, "focus_find", "", ""));
            int closeX = x + w - 11;
            int nextX = closeX - 13;
            int prevX = nextX - 13;
            String count;
            boolean anyMatch;
            if (tab.chromiumPage && tab.chromium >= 0) {
                anyMatch = tab.findMatches > 0;
                count = anyMatch ? tab.findActive + "/" + tab.findMatches : "0/0";
            } else {
                anyMatch = !tab.matches.isEmpty();
                count = anyMatch ? (tab.matchAt + 1) + "/" + tab.matches.size() : "0/0";
            }
            c.text(prevX - 4 - this.textWidth(count), fy + 3, count, COLOR_MUTED);
            this.browserChip(c, tab, "‹", prevX, fy + 1, "find_prev", anyMatch,
                    "上一个匹配");
            this.browserChip(c, tab, "›", nextX, fy + 1, "find_next", anyMatch,
                    "下一个匹配");
            this.browserChip(c, tab, "✕", closeX, fy + 1, "find_close", true, "关闭查找");
            if (tab.findOpen && !tab.findQuery.equals(tab.lastFindQuery)
                    && System.currentTimeMillis() - tab.lastFindAt > 200L) {
                tab.lastFindQuery = tab.findQuery;
                tab.lastFindAt = System.currentTimeMillis();
                this.runBrowserFind(tab);
            }
        }

        // 状态行
        int sy = y + h - 9;
        c.fill(x - 3, sy - 2, x + w + 3, y + h + 3, 0xFFF4F2E8);
        String status;
        if (tab.loading) {
            long dots = (System.currentTimeMillis() / 350L) % 4L;
            status = "正在加载" + ".".repeat((int) dots);
        } else if (!tab.error.isBlank()) {
            status = "加载失败：" + tab.error;
        } else if (!tab.notice.isBlank() && System.currentTimeMillis() < tab.noticeUntil) {
            status = tab.notice;
        } else if (tab.enginePage != null) {
            ElectronEngine.Page enginePage = tab.enginePage;
            status = (enginePage.fromCache() ? "缓存 · " : "")
                    + (enginePage.status() > 0 ? "HTTP " + enginePage.status() + " · " : "")
                    + (enginePage.engine().isBlank() ? "Node" : enginePage.engine());
        } else {
            status = tab.url;
        }
        c.pushClip(x, sy, x + w - 60, sy + 9);
        c.text(x, sy, this.fitText(status, w - 66), tab.error.isBlank() ? COLOR_MUTED : 0xFFA03020);
        c.popClip();
        String count = Math.round(tab.pageZoom * 100.0F) + "%"
                + (tab.reader ? " · 阅读" : "") + " · "
                + (tab.chromiumPage ? "W" : tab.enginePage != null ? "N" : "J") + " · "
                + (win.activeTab + 1) + "/" + win.tabs.size();
        c.text(x + w - this.textWidth(count), sy, count, COLOR_DIM);
    }

    /** 工具栏上的 14×12 小按钮；按下不可用时只画灰色、不登记热区。 */
    private int browserTool(Canvas c, BrowserTab tab, String glyph, int x, int y,
                            String action, boolean enabled, boolean active, String tooltip) {
        int bw = 14;
        int bg = !enabled ? 0xFFF0EEE4
                : active ? 0xFFCFE3F7
                : this.inside(x, y + 1, bw, 12) ? 0xFFDCEBFA : 0xFFEDEBE0;
        c.fill(x, y + 1, x + bw, y + 13, bg);
        c.outline(x, y + 1, x + bw, y + 13, active ? 0xFF2A62D8 : 0xFFB7B3A4);
        c.centered(glyph, x + bw / 2, y + 3, enabled ? 0xFF1A3350 : 0xFFB0AEA4);
        if (enabled) {
            tab.hits.add(new BrowserHit(x, y + 1, bw, 12, action, "", ""));
        }
        this.tip(x, y + 1, bw, 12, tooltip);
        return x + bw;
    }

    /** 查找条上的 12×11 小按钮，样式比工具栏更紧凑。 */
    private void browserChip(Canvas c, BrowserTab tab, String glyph, int x, int y,
                             String action, boolean enabled, String tooltip) {
        int bw = 12;
        int bg = !enabled ? 0xFFF0EEE4
                : this.inside(x, y, bw, 11) ? 0xFFDCEBFA : 0xFFF7F5EA;
        c.fill(x, y, x + bw, y + 11, bg);
        c.outline(x, y, x + bw, y + 11, 0xFFB7B3A4);
        c.centered(glyph, x + bw / 2, y + 1, enabled ? 0xFF1A3350 : 0xFFB0AEA4);
        if (enabled) {
            tab.hits.add(new BrowserHit(x, y, bw, 11, action, "", ""));
        }
        this.tip(x, y, bw, 11, tooltip);
    }

    /** 画出当前页，并更新滚动上限；加载中与空页各有占位提示。 */
    private void renderBrowserPage(Canvas c, Win win, BrowserTab tab, int x, int top, int w, int h) {
        tab.pageX = x;
        tab.pageY = top;
        tab.pageW = w;
        tab.pageH = h;
        if (tab.chromiumPage && tab.chromium >= 0) {
            this.renderChromium(c, win, tab, x, top, w, h);
            tab.maxScroll = 0;
            tab.scroll = 0;
            return;
        }
        tab.scroll = Math.max(0, Math.min(tab.scroll, tab.maxScroll));
        int start = top - tab.scroll;
        int end;
        if (tab.enginePage != null) {
            ElectronEngine.Page page = tab.enginePage;
            if (!page.error().isBlank()) {
                c.fill(x - 3, top - 1, x + w + 3, top + h, 0xFFFFFFFF);
                c.text(x + 8, start + 10, "无法打开页面", 0xFFA03020, 1.2F);
                List<FormattedCharSequence> lines = c.split(page.error(), Math.max(40, w - 16));
                int ly = start + 28;
                for (int i = 0; i < lines.size() && i < 8; i++) {
                    c.text(x + 8, ly, lines.get(i), COLOR_TEXT);
                    ly += c.lineHeight();
                }
                c.text(x + 8, ly + 6, "检查地址或网络后重试，也可以在菜单里更换搜索引擎。", COLOR_MUTED);
                end = start + Math.max(60, ly - start + 24);
            } else {
                // 窗口尺寸、缩放或阅读模式变化时让 Node 重新排版，不重新联网。
                if (tab.layoutWidth != w && !tab.relayouting && tab.layoutWidth > 0) {
                    this.requestRelayout(win, tab, w, false);
                }
                int bg = page.background() == 0 ? 0xFFFFFFFF : page.background();
                c.fill(x - 4, start - 2, x + w + 4,
                        start + Math.max(page.height(), h) + 2, bg);
                end = this.renderElectronItems(c, win, tab, x, start, w, page);
            }
        } else if (tab.loading && tab.page == null) {
            c.text(x + 6, start + 8, "正在建立连接…", COLOR_MUTED);
            end = start + 26;
        } else if (tab.page == null) {
            c.text(x + 6, start + 8, "输入网址或关键词开始浏览。", COLOR_MUTED);
            end = start + 26;
        } else {
            end = this.renderBrowserBlocks(c, tab, x, start, w);
        }
        tab.maxScroll = Math.max(0, end - start + 6 - h);
        this.revealMatch(tab, top, h);
        if (tab.scroll > tab.maxScroll) {
            tab.scroll = tab.maxScroll;
        }
    }

    /** 把 WebKit 的最新画面贴到页面上；帧以标签页为单位缓存。 */
    private void renderChromium(Canvas c, Win win, BrowserTab tab, int x, int top, int w, int h) {
        WebKitEngine.State state = WebKitEngine.state(tab.chromium);
        this.applyChromiumState(tab, state);
        WebKitEngine.resize(tab.chromium, w, h);
        WebKitEngine.Frame frame = WebKitEngine.pollFrame(tab.chromium);
        if (frame != null) {
            this.uploadChromiumFrame(tab, frame);
        }
        ChromiumTexture texture = this.chromiumTextures.get(tab.chromium);
        if (texture != null) {
            c.image(x, top, x + Math.min(w, texture.width()),
                    top + Math.min(h, texture.height()), texture.id());
            if (tab.loading) {
                int barW = Math.max(12, Math.min(w, texture.width()) * 2 / 5);
                c.fill(x, top, x + barW, top + 2, 0xFF2A62D8);
            }
            return;
        }
        c.fill(x, top, x + w, top + h, 0xFFFFFFFF);
        String message;
        if (!WebKitEngine.available()) {
            message = WebKitEngine.failure().isBlank()
                    ? "正在启动 Chromium…" : WebKitEngine.failure();
        } else if (!tab.error.isBlank()) {
            message = "无法打开页面：" + tab.error;
        } else {
            message = "正在加载网页…";
        }
        int ly = top + 8;
        for (FormattedCharSequence line : c.split(message, Math.max(40, w - 12))) {
            if (ly > top + h - 10) {
                break;
            }
            c.text(x + 6, ly, line, tab.error.isBlank() ? COLOR_MUTED : 0xFFA03020);
            ly += c.lineHeight();
        }
    }

    /** Chromium 的地址、标题与加载状态同步到标签页；data: 地址不写进地址栏。 */
    private void applyChromiumState(BrowserTab tab, WebKitEngine.State state) {
        if (state == null) {
            return;
        }
        tab.loading = state.loading();
        if (!state.error().isBlank()) {
            tab.error = state.error();
        } else if (!state.loading()) {
            tab.error = "";
        }
        String url = state.url();
        if (url.isBlank() || url.startsWith("data:")) {
            if (!state.title().isBlank()) {
                tab.title = state.title();
            }
            return;
        }
        if (!url.equals(tab.url)) {
            tab.url = url;
            tab.address = url;
            // 只做“同一页的规范写法”就地更新（补斜杠、去锚点）。
            // 内核后退时 state.url 会先一步变成上一页，此时界面游标还没移，
            // 若直接覆写会把前进记录改成当前页，导致前进按钮彻底失效。
            if (tab.historyAt >= 0 && tab.historyAt < tab.history.size()
                    && WebKitEngine.sameUrl(tab.history.get(tab.historyAt), url)) {
                tab.history.set(tab.historyAt, url);
            }
            // 内置页面只同步地址，不计入浏览历史。
            if (!url.startsWith("electron://") && !url.startsWith("file://")) {
                this.recordVisit(state.title().isBlank() ? url : state.title(), url);
            }
        }
        if (!state.title().isBlank()) {
            tab.title = state.title();
        }
    }

    /**
     * 把最新画面写进纹理。
     *
     * <p>尺寸没变时直接写现有 NativeImage 的本地内存再上传，不换纹理、也不再新建
     * 本地图：每帧省掉一次 3.6 MB 的申请与释放，动画和视频期间渲染线程不会被
     * 新页缺页打断。内核交来的像素数组会被复用，所以必须在当帧写掉。</p>
     */
    private void uploadChromiumFrame(BrowserTab tab, WebKitEngine.Frame frame) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || frame.pixels() == null) {
            return;
        }
        ChromiumTexture cached = this.chromiumTextures.get(tab.chromium);
        if (cached != null && cached.width() == frame.width()
                && cached.height() == frame.height()) {
            NativeImage pixels = cached.texture().getPixels();
            if (pixels != null && pixels.getWidth() == frame.width()
                    && pixels.getHeight() == frame.height()) {
                try {
                    writeChromiumPixels(pixels, frame);
                    cached.texture().upload();
                } catch (RuntimeException ignored) {
                    // 纹理已被释放：下一帧会重新建一张。
                }
                return;
            }
        }
        NativeImage image = new NativeImage(frame.width(), frame.height(), false);
        writeChromiumPixels(image, frame);
        DynamicTexture texture = new DynamicTexture(() -> "Chromium page", image);
        texture.upload();
        Identifier id = Identifier.fromNamespaceAndPath("hajikun",
                "chromium/" + tab.chromium + "-" + frame.seq());
        client.getTextureManager().register(id, texture);
        if (cached != null) {
            client.getTextureManager().release(cached.id());
        }
        this.chromiumTextures.put(tab.chromium,
                new ChromiumTexture(id, texture, frame.width(), frame.height()));
    }

    /** 整块写像素：ABGR 在本地内存里按小端整数排布。 */
    private static void writeChromiumPixels(NativeImage image, WebKitEngine.Frame frame) {
        IntBuffer ints = image.getPixelBytes().order(ByteOrder.nativeOrder()).asIntBuffer();
        ints.put(frame.pixels(), 0, frame.width() * frame.height());
    }

    private void releaseChromiumTexture(BrowserTab tab) {
        ChromiumTexture texture = this.chromiumTextures.remove(tab.chromium);
        Minecraft client = Minecraft.getInstance();
        if (texture != null && client != null) {
            client.getTextureManager().release(texture.id());
        }
    }

    /** 内核事件：内部链接、新窗口、下载与查找结果都在这里落地。 */
    private void onChromiumEvent(WebKitEngine.Event event) {
        BrowserTab tab = this.findChromiumTab(event.tab());
        if (tab == null || event.data() == null) {
            return;
        }
        switch (event.kind()) {
            case "nav" -> {
                Win win = this.winOf(tab);
                String url = jsonString(event.data(), "url", "");
                if (win != null && !url.isBlank()) {
                    this.browserGo(win, tab, url, true);
                }
            }
            case "history" -> {
                // 内核沿着自己的历史前后移动：只同步游标，不能截断前进记录。
                Win win = this.winOf(tab);
                String url = jsonString(event.data(), "url", "");
                if (win == null || url.isBlank()) {
                    return;
                }
                int found = -1;
                for (int i = 0; i < tab.history.size(); i++) {
                    if (WebKitEngine.sameUrl(tab.history.get(i), url)) {
                        found = i;
                        break;
                    }
                }
                if (found >= 0) {
                    tab.historyAt = found;
                }
                if (url.startsWith("electron://")) {
                    this.browserGo(win, tab, url, false);
                }
                // 网络页内核已经在加载，渲染时 applyChromiumState 会同步地址与标题。
            }
            case "open" -> {
                Win win = this.winOf(tab);
                String url = jsonString(event.data(), "url", "");
                if (win != null && !url.isBlank()) {
                    BrowserTab fresh = this.newBrowserTab(win);
                    this.browserGo(win, fresh, url, true);
                }
            }
            case "download" -> this.acceptChromiumDownload(tab, event.data());
            case "find" -> {
                tab.findMatches = Math.max(0, jsonInt(event.data(), "matches", 0));
                tab.findActive = Math.max(0, jsonInt(event.data(), "active", 0));
            }
            case "log" -> {
                String message = jsonString(event.data(), "message", "");
                if (!message.isBlank()) {
                    tab.notice = message;
                    tab.noticeUntil = System.currentTimeMillis() + 5000L;
                }
            }
            default -> {
            }
        }
    }

    /**
     * Chromium 把文件先写到宿主临时目录；这里把不超过 32MB 的文件搬进虚拟机，
     * 超过上限就只记录真实路径，避免一次下载把内存和网络包撑爆。
     */
    private void acceptChromiumDownload(BrowserTab tab, JsonObject data) {
        String name = this.safeDownloadName(jsonString(data, "name", "download.bin"), "");
        String path = jsonString(data, "path", "");
        String url = jsonString(data, "url", "");
        String state = jsonString(data, "state", "");
        long bytes = data.has("bytes") && !data.get("bytes").isJsonNull()
                ? data.get("bytes").getAsLong() : 0L;
        boolean done = data.has("done") && !data.get("done").isJsonNull()
                && data.get("done").getAsBoolean();
        if (!done) {
            tab.notice = "正在下载 " + name + (bytes > 0 ? " · " + this.browserSize(bytes) : "");
            tab.noticeUntil = System.currentTimeMillis() + 8000L;
            return;
        }
        if (!"completed".equals(state)) {
            tab.notice = "下载失败：" + (state.isBlank() ? "已取消" : state);
            tab.noticeUntil = System.currentTimeMillis() + 6000L;
            this.toast(tab.notice);
            return;
        }
        tab.notice = "已下载 " + name;
        tab.noticeUntil = System.currentTimeMillis() + 6000L;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            long size;
            byte[] content = null;
            try {
                java.nio.file.Path host = java.nio.file.Path.of(path);
                size = java.nio.file.Files.size(host);
                if (size <= 32L * 1024L * 1024L) {
                    content = java.nio.file.Files.readAllBytes(host);
                }
            } catch (Exception exception) {
                size = -1L;
            }
            final long finalSize = size;
            final byte[] finalContent = content;
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> this.finishChromiumDownload(tab, name, url, path,
                        finalSize, finalContent));
            }
        });
    }

    private void finishChromiumDownload(BrowserTab tab, String name, String url, String path,
                                        long size, byte[] content) {
        if (size < 0L) {
            tab.notice = "下载文件已丢失：" + name;
            tab.noticeUntil = System.currentTimeMillis() + 6000L;
            return;
        }
        String vmPath;
        if (content != null) {
            HajikunSmartClient.upload(this.pos, BROWSER_DOWNLOAD_DIR, name,
                    "application/octet-stream", content);
            vmPath = BROWSER_DOWNLOAD_DIR + "/" + name;
        } else {
            vmPath = path;
        }
        this.downloads.add(0, new BrowserDownload(name, url, vmPath,
                System.currentTimeMillis(), size));
        while (this.downloads.size() > 40) {
            this.downloads.remove(this.downloads.size() - 1);
        }
        this.saveBrowserDownloads();
        tab.notice = "已保存 " + vmPath;
        tab.noticeUntil = System.currentTimeMillis() + 6000L;
        this.toast(tab.notice);
    }

    private BrowserTab findChromiumTab(int id) {
        for (Win win : this.windows) {
            for (BrowserTab tab : win.tabs) {
                if (tab.chromium == id) {
                    return tab;
                }
            }
        }
        return null;
    }

    private Win winOf(BrowserTab tab) {
        for (Win win : this.windows) {
            if (win.tabs.contains(tab)) {
                return win;
            }
        }
        return null;
    }

    /** 网页有没有键盘焦点；有则按键与字符走 Chromium。 */
    private BrowserTab chromiumTypingTab() {
        if (this.focus != Focus.NONE || this.focusWin == null
                || this.focusWin.app != App.BROWSER) {
            return null;
        }
        BrowserTab tab = this.focusWin.tab();
        return tab.chromiumPage && tab.chromiumTyping && tab.chromium >= 0 ? tab : null;
    }

    private boolean chromiumKey(BrowserTab tab, int key, boolean shift, boolean ctrl) {
        if (key == InputConstants.KEY_ESCAPE) {
            tab.chromiumTyping = false;
            this.chromiumGuardUntil = System.currentTimeMillis() + 120L;
            return true;
        }
        if (ctrl && key == InputConstants.KEY_V) {
            String text = clipboard();
            if (text != null && !text.isEmpty()) {
                WebKitEngine.text(tab.chromium, text);
            }
            this.chromiumGuardUntil = System.currentTimeMillis() + 120L;
            return true;
        }
        String code = this.chromiumKeyName(key);
        if (code != null) {
            WebKitEngine.key(tab.chromium, true, code, shift, ctrl, false);
            WebKitEngine.key(tab.chromium, false, code, shift, ctrl, false);
            if (ctrl) {
                this.chromiumGuardUntil = System.currentTimeMillis() + 120L;
            }
            return true;
        }
        if (ctrl && key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            String letter = String.valueOf((char) ('a' + (key - InputConstants.KEY_A)));
            WebKitEngine.key(tab.chromium, true, letter, shift, true, false);
            WebKitEngine.key(tab.chromium, false, letter, shift, true, false);
            this.chromiumGuardUntil = System.currentTimeMillis() + 120L;
            return true;
        }
        return false;
    }

    private String chromiumKeyName(int key) {
        return switch (key) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> "Enter";
            case InputConstants.KEY_BACKSPACE -> "Backspace";
            case InputConstants.KEY_TAB -> "Tab";
            case InputConstants.KEY_DELETE -> "Delete";
            case InputConstants.KEY_LEFT -> "Left";
            case InputConstants.KEY_RIGHT -> "Right";
            case InputConstants.KEY_UP -> "Up";
            case InputConstants.KEY_DOWN -> "Down";
            case InputConstants.KEY_HOME -> "Home";
            case InputConstants.KEY_END -> "End";
            case InputConstants.KEY_PAGEUP -> "PageUp";
            case InputConstants.KEY_PAGEDOWN -> "PageDown";
            case InputConstants.KEY_F5 -> "F5";
            default -> null;
        };
    }

    /** 鼠标在网页区域移动时转发 hover，节流到约 30Hz。 */
    private void chromiumHover() {
        Win win = this.topWinAt(this.mouseX, this.mouseY);
        if (win == null || win.app != App.BROWSER) {
            return;
        }
        BrowserTab tab = win.tab();
        if (!tab.chromiumPage || tab.chromium < 0
                || !this.inside(tab.pageX, tab.pageY, tab.pageW, tab.pageH)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.chromiumMoveAt < 30L) {
            return;
        }
        this.chromiumMoveAt = now;
        WebKitEngine.mouse(tab.chromium, "move",
                (float) (this.mouseX - tab.pageX), (float) (this.mouseY - tab.pageY),
                0, 1, false, false, false);
    }

    /** 把 Node 的显示列表画成像素；热区在绘制时登记，点击命中与画面永远一致。 */
    private int renderElectronItems(Canvas c, Win win, BrowserTab tab, int x, int top,
                                    int w, ElectronEngine.Page page) {
        boolean editing = this.focus == Focus.ADDRESS_BROWSER && this.focusWin == win;
        for (ElectronEngine.Item item : page.items()) {
            int ix = x + item.x();
            int iy = top + item.y();
            switch (item.kind()) {
                case "rect" -> {
                    if (item.w() > 0 && item.h() > 0 && (item.color() >>> 24) != 0) {
                        c.fill(ix, iy, ix + item.w(), iy + item.h(), item.color());
                    }
                }
                case "text" -> {
                    String text = item.text();
                    if (text == null || text.isEmpty()) {
                        break;
                    }
                    float scale = item.scale() <= 0F ? 1F : item.scale();
                    int color = item.color() == 0 ? COLOR_TEXT : item.color();
                    if (scale > 1.05F) {
                        c.text(ix, iy, text, color, scale);
                    } else {
                        c.text(ix, iy, text, color);
                    }
                }
                case "link" -> tab.hits.add(new BrowserHit(ix, iy,
                        Math.max(2, item.w()), Math.max(2, item.h()), "url", item.href(), ""));
                case "image" -> {
                    ElectronEngine.Picture picture = page.pictures().get(item.src());
                    if (picture != null && picture.texture() != null) {
                        c.image(ix, iy, ix + Math.max(1, item.w()), iy + Math.max(1, item.h()),
                                picture.texture());
                    } else {
                        c.fill(ix, iy, ix + Math.max(1, item.w()), iy + Math.max(1, item.h()),
                                0xFFF0F0EA);
                        c.outline(ix, iy, ix + Math.max(1, item.w()), iy + Math.max(1, item.h()),
                                0xFFC8C4B8);
                        String alt = item.alt().isBlank() ? "图片" : item.alt();
                        c.text(ix + 3, iy + 3, this.fitText(alt + "（无法显示）",
                                Math.max(4, item.w() - 6)), COLOR_DIM);
                    }
                }
                case "field" -> this.renderElectronField(c, win, tab, item, ix, iy, editing);
                case "button" -> {
                    tab.hits.add(new BrowserHit(ix, iy, Math.max(2, item.w()),
                            Math.max(2, item.h()), "submit", "", item.form()));
                    this.tip(ix, iy, Math.max(2, item.w()), Math.max(2, item.h()), item.label());
                }
                default -> {
                }
            }
        }
        // 页内查找的命中高亮，当前命中用橙色描边。
        for (int i = 0; i < tab.matches.size(); i++) {
            ElectronEngine.Match match = tab.matches.get(i);
            int mx = x + match.x();
            int my = top + match.y();
            int mw = Math.max(2, match.w());
            int mh = Math.max(2, match.h());
            if (i == tab.matchAt) {
                c.fill(mx - 1, my - 1, mx + mw + 1, my + mh + 1, 0x88FF9A1E);
                c.outline(mx - 2, my - 2, mx + mw + 2, my + mh + 2, 0xFFD46A00);
            } else {
                c.fill(mx, my, mx + mw, my + mh, 0x55FFD34A);
            }
        }
        return top + Math.max(0, page.height());
    }

    /** 表单控件：文本框、下拉框与复选框都由 Java 画，编辑中的值与光标实时更新。 */
    private void renderElectronField(Canvas c, Win win, BrowserTab tab, ElectronEngine.Item item,
                                     int x, int y, boolean editingWindow) {
        String name = item.name() == null ? "" : item.name();
        if (item.box()) {
            boolean checked = tab.checkState.containsKey(name)
                    ? Boolean.TRUE.equals(tab.checkState.get(name)) : item.checked();
            c.fill(x, y, x + Math.max(9, item.w()), y + Math.max(9, item.h()), 0xFFFFFFFF);
            c.outline(x, y, x + Math.max(9, item.w()), y + Math.max(9, item.h()), 0xFF7F7F7F);
            if (checked) {
                c.text(x + 1, y - 1, "✓", 0xFF1F7A1F);
            }
            tab.hits.add(new BrowserHit(x, y, Math.max(9, item.w()), Math.max(9, item.h()),
                    "check", name, item.form()));
            return;
        }
        String value = tab.fields.containsKey(name) ? tab.fields.get(name) : item.value();
        if (value == null) {
            value = "";
        }
        boolean active = editingWindow && !win.browserField.isBlank()
                && name.equals(win.browserField);
        int fw = Math.max(12, item.w());
        int fh = Math.max(12, item.h());
        c.fill(x, y, x + fw, y + fh, 0xFFFFFFFF);
        c.outline(x, y, x + fw, y + fh, active ? 0xFF2A62D8 : 0xFF7F9DB9);
        if (active) {
            String shown = this.edit.text;
            this.fieldText(c, x + 3, y + 3, fw - 5, shown, true, COLOR_TEXT);
            win.browserFieldLeft = x + 3;
            if (this.blink()) {
                int caret = this.caretX(x + 3, shown, this.edit.caret, x + fw - 2);
                c.fill(caret, y + 2, caret + 1, y + fh - 2, 0xFF202020);
            }
        } else if (value.isEmpty()) {
            c.text(x + 3, y + 3, this.fitText(item.placeholder(), fw - 6), COLOR_DIM);
        } else {
            String shown = item.password() ? "•".repeat(Math.min(24, value.length())) : value;
            if (fh > 20) {
                int lineY = y + 3;
                for (FormattedCharSequence line : c.split(shown, fw - 6)) {
                    if (lineY > y + fh - 10) {
                        break;
                    }
                    c.text(x + 3, lineY, line, COLOR_TEXT);
                    lineY += c.lineHeight();
                }
            } else {
                c.text(x + 3, y + 3, this.fitText(shown, fw - 6), COLOR_TEXT);
            }
        }
        tab.hits.add(new BrowserHit(x, y, fw, fh, "edit", name, item.form()));
    }

    /** 按块排版当前页；返回内容的底边，用来计算滚动范围。 */
    private int renderBrowserBlocks(Canvas c, BrowserTab tab, int x, int py, int w) {
        BrowserClient.Result page = tab.page;
        int left = x + 5;
        int maxW = Math.max(20, w - 12);
        int lh = c.lineHeight() + 1;
        if (BROWSER_HOME.equals(tab.url)) {
            c.gradient(left, py, left + maxW, py + 30, 0xFF0B3E7A, 0xFF1E6FC8);
            c.fill(left, py + 30, left + maxW, py + 31, 0xFF9AD4FF);
            c.text(left + 6, py + 5, "浏览器", 0xFFFFFFFF, 1.4F);
            c.text(left + 6, py + 19, "哈基坤 OS · 真实网络 · 标签页 · 表单", 0xFFCFE4FF);
            py += 38;
        }
        List<BrowserClient.Block> blocks = page.blocks();
        if (blocks.isEmpty() && !page.text().isBlank()) {
            blocks = List.of(new BrowserClient.Block(BrowserClient.Kind.TEXT, page.text(),
                    "", "", 0, "", "", "", ""));
        }
        int lines = 0;
        for (BrowserClient.Block block : blocks) {
            if (lines > 2500) {
                c.text(left, py, "（页面过长，剩余内容已省略）", COLOR_DIM);
                py += lh;
                break;
            }
            switch (block.kind()) {
                case HEADING -> {
                    py += block.level() <= 2 ? 5 : 2;
                    int color = block.level() <= 2 ? 0xFF163A66 : 0xFF2A4A70;
                    for (FormattedCharSequence line : c.split(block.text(), maxW)) {
                        c.text(left, py, line, color);
                        py += lh;
                        lines++;
                    }
                    if (block.level() <= 2) {
                        c.fill(left, py - 1, left + Math.min(maxW, this.textWidth(block.text())),
                                py, 0xFFA8C4E8);
                    }
                    py += 2;
                }
                case TEXT -> {
                    int before = py;
                    for (String paragraph : block.text().split("\n", -1)) {
                        if (paragraph.isBlank()) {
                            py += lh;
                            continue;
                        }
                        for (FormattedCharSequence line : c.split(paragraph, maxW)) {
                            c.text(left, py, line, 0xFF242424);
                            py += lh;
                            lines++;
                        }
                    }
                    if (py == before) {
                        py += 3;
                    }
                }
                case LINK -> {
                    String label = block.text().isBlank() ? block.href() : block.text();
                    for (FormattedCharSequence line : c.split(label, maxW)) {
                        c.text(left, py, line, 0xFF1A4FA0);
                        tab.hits.add(new BrowserHit(left, py - 1, maxW, lh, "url",
                                block.href(), ""));
                        py += lh;
                        lines++;
                    }
                    c.fill(left, py - 1,
                            left + Math.min(maxW, this.textWidth(label)), py, 0xFF9AB8E0);
                    py += 1;
                }
                case IMAGE -> {
                    py += 2;
                    int dh = this.browserImage(c, block, left, py, maxW);
                    if (dh <= 0) {
                        c.fill(left, py, left + maxW, py + 15, 0xFFF0F0EA);
                        c.outline(left, py, left + maxW, py + 15, 0xFFC8C4B8);
                        String alt = block.text().isBlank() ? "图片" : "图片：" + block.text();
                        c.pushClip(left + 3, py + 3, left + maxW - 3, py + 14);
                        c.text(left + 3, py + 4, alt + "（加载中或无法显示）", COLOR_DIM);
                        c.popClip();
                        dh = 17;
                    }
                    py += dh + 3;
                }
                case VIDEO -> {
                    py += 2;
                    c.fill(left, py, left + maxW, py + 18, 0xFF101418);
                    c.outline(left, py, left + maxW, py + 18, 0xFF3C6FA8);
                    c.pushClip(left + 4, py + 3, left + maxW - 4, py + 16);
                    c.text(left + 4, py + 5, "视频：" + block.src(), 0xFFB8D8F8);
                    c.popClip();
                    py += 21;
                }
                case FIELD -> {
                    py += 2;
                    String name = block.name() == null ? "" : block.name();
                    String value = tab.fields.containsKey(name) ? tab.fields.get(name) : block.value();
                    boolean active = this.focus == Focus.ADDRESS_BROWSER && this.focusWin != null
                            && this.focusWin.tab() == tab && name.equals(this.focusWin.browserField);
                    c.fill(left, py, left + maxW, py + 12, 0xFFFFFFFF);
                    c.outline(left, py, left + maxW, py + 12,
                            active ? 0xFF2A62D8 : 0xFF9C9A8E);
                    this.fieldText(c, left + 3, py + 3, maxW - 6,
                            active ? this.edit.text : value, active, COLOR_TEXT);
                    if (active) {
                        this.focusWin.browserFieldLeft = left + 3;
                        if (this.blink()) {
                            int caret = this.caretX(left + 3, this.edit.text,
                                    this.edit.caret, left + maxW - 2);
                            c.fill(caret, py + 2, caret + 1, py + 10, 0xFF202020);
                        }
                    }
                    tab.hits.add(new BrowserHit(left, py, maxW, 12, "edit", name, block.form()));
                    py += 14;
                }
                case BUTTON -> {
                    String label = block.text().isBlank() ? "提交" : block.text();
                    int bw = Math.min(maxW, this.textWidth(label) + 12);
                    c.gradient(left, py, left + bw, py + 12, 0xFF7CB6F8, 0xFF2E6FC8);
                    c.outline(left, py, left + bw, py + 12, 0xFF1B4A86);
                    c.centered(label, left + bw / 2, py + 2, 0xFFFFFFFF);
                    tab.hits.add(new BrowserHit(left, py, bw, 12, "submit", "", block.form()));
                    py += 15;
                }
                case DIVIDER -> {
                    c.fill(left, py + 2, left + maxW, py + 3, 0xFFD8D4C4);
                    py += 7;
                }
            }
            py += 1;
        }
        return py + 4;
    }

    /** 网页图片：真实网络图走 WebMedia，虚拟机里的图片走 MediaCache。 */
    private int browserImage(Canvas c, BrowserClient.Block block, int left, int top, int maxW) {
        String src = block.src() == null ? "" : block.src();
        if (src.startsWith("vm:")) {
            String spec = src.substring(3);
            int bar = spec.indexOf('|');
            String path = bar < 0 ? spec : spec.substring(0, bar);
            String hash = bar < 0 ? "" : spec.substring(bar + 1);
            MediaCache.ensure(this.pos, path, hash);
            Identifier texture = MediaCache.image(this.pos, path, hash);
            if (texture == null) {
                return 0;
            }
            int[] size = MediaCache.imageSize(this.pos, path);
            int dw = Math.max(1, Math.min(maxW, size[0]));
            int dh = Math.max(1, size[1] * dw / Math.max(1, size[0]));
            dh = Math.min(dh, 110);
            c.image(left, top, left + dw, top + dh, texture);
            return dh;
        }
        WebMedia.Image image = WebMedia.image(this.pos, src);
        if (image == null) {
            return 0;
        }
        int dw = Math.max(1, Math.min(maxW, image.width));
        int dh = Math.max(1, image.height * dw / Math.max(1, image.width));
        dh = Math.min(dh, 110);
        c.image(left, top, left + dw, top + dh, image.frame(System.currentTimeMillis()));
        return dh;
    }

    /** 点击浏览器：先看上一帧收集的热区，再处理空白区域。 */
    private boolean clickBrowser(Win win, double mx, double my, int x, int y, int w, int h) {
        BrowserTab tab = win.tab();
        tab.chromiumTyping = false;
        for (int i = tab.hits.size() - 1; i >= 0; i--) {
            BrowserHit hit = tab.hits.get(i);
            if (!this.inside(hit.x(), hit.y(), hit.width(), hit.height())) {
                continue;
            }
            switch (hit.action()) {
                case "tab" -> {
                    win.activeTab = Math.max(0, Math.min(win.tabs.size() - 1,
                            parseInt(hit.value(), 0)));
                    win.browserField = "";
                    win.browserFieldForm = "";
                    win.addressText = win.tab().address;
                    this.focus = Focus.NONE;
                    this.focusWin = win;
                }
                case "tab_close" -> this.closeBrowserTab(win, parseInt(hit.value(), 0));
                case "new_tab" -> this.newBrowserTab(win);
                case "focus_address" -> {
                    win.browserField = "";
                    win.browserFieldForm = "";
                    win.addressText = tab.address;
                    win.browserFieldLeft = hit.x() + 3;
                    this.beginEditAt(Focus.ADDRESS_BROWSER, win, tab.address,
                            this.textIndexAt(win.browserFieldLeft, tab.address, mx), false);
                }
                case "go" -> {
                    this.focus = Focus.NONE;
                    this.focusWin = win;
                    this.browserGo(win, tab, tab.address, true);
                }
                case "back" -> this.browserHistory(win, tab, -1);
                case "forward" -> this.browserHistory(win, tab, 1);
                case "reload" -> this.browserReload(win, tab);
                case "home" -> this.browserGo(win, tab, this.browserSettings.home, true);
                case "url" -> this.browserGo(win, tab, hit.value(), true);
                case "bookmark" -> this.toggleBookmark(tab);
                case "find" -> this.openBrowserFind(win, tab);
                case "reader" -> {
                    tab.reader = !tab.reader;
                    if (tab.chromiumPage && tab.chromium >= 0) {
                        WebKitEngine.reader(tab.chromium, tab.reader);
                    } else if (tab.enginePage != null) {
                        this.requestRelayout(win, tab, this.browserPageWidth(win), false);
                    }
                    this.toast(tab.reader ? "阅读模式已开启" : "阅读模式已关闭");
                }
                case "menu" -> this.showMenu(this.browserMenu(win), mx, my);
                case "find_prev" -> this.stepBrowserFind(tab, -1);
                case "find_next" -> this.stepBrowserFind(tab, 1);
                case "find_close" -> {
                    tab.findOpen = false;
                    tab.matches.clear();
                    tab.matchAt = -1;
                    tab.findMatches = 0;
                    tab.findActive = 0;
                    if (tab.chromiumPage && tab.chromium >= 0) {
                        WebKitEngine.find(tab.chromium, "", true, false);
                    }
                    this.focus = Focus.NONE;
                }
                case "check" -> {
                    String name = hit.value() == null ? "" : hit.value();
                    tab.checkState.put(name, !this.browserCheckDefault(tab, name));
                }
                case "edit" -> {
                    String name = hit.value() == null ? "" : hit.value();
                    String value = tab.fields.get(name);
                    if (value == null) {
                        value = this.browserFieldDefault(tab, name);
                        tab.fields.put(name, value);
                    }
                    win.browserField = name;
                    win.browserFieldForm = hit.extra() == null ? "" : hit.extra();
                    win.browserFieldLeft = hit.x() + 3;
                    win.addressText = value;
                    this.beginEditAt(Focus.ADDRESS_BROWSER, win, value,
                            this.textIndexAt(win.browserFieldLeft, value, mx), false);
                }
                case "submit" -> {
                    String form = hit.extra() == null ? "" : hit.extra();
                    this.focus = Focus.NONE;
                    this.focusWin = win;
                    this.browserSubmit(win, tab, form);
                }
                default -> {
                }
            }
            return true;
        }
        // 网页区域：把点击原样交给 Chromium，表单、链接、视频控件都由它处理。
        if (tab.chromiumPage && tab.chromium >= 0
                && this.inside(tab.pageX, tab.pageY, tab.pageW, tab.pageH)) {
            this.focus = Focus.NONE;
            this.focusWin = win;
            tab.chromiumTyping = true;
            int px = (int) Math.round(mx - tab.pageX);
            int py = (int) Math.round(my - tab.pageY);
            long now = System.currentTimeMillis();
            int clicks = now - tab.chromiumClickAt < 400L
                    && Math.abs(px - tab.chromiumClickX) < 6
                    && Math.abs(py - tab.chromiumClickY) < 6 ? 2 : 1;
            tab.chromiumClickAt = now;
            tab.chromiumClickX = px;
            tab.chromiumClickY = py;
            WebKitEngine.mouse(tab.chromium, "down", px, py, 0, clicks,
                    false, false, false);
            WebKitEngine.mouse(tab.chromium, "up", px, py, 0, clicks,
                    false, false, false);
            this.chromiumMoveAt = now;
            return true;
        }
        this.focus = Focus.NONE;
        this.focusWin = win;
        return true;
    }
    private void submitBrowserEdit(Win win) {
        BrowserTab tab = win.tab();
        String text = this.edit.text;
        if (this.focus == Focus.FIND) {
            tab.findQuery = text;
            this.runBrowserFind(tab);
            return;
        }
        if (win.browserField.isBlank()) {
            this.focus = Focus.NONE;
            this.focusWin = null;
            this.browserGo(win, tab, text, true);
        } else {
            tab.fields.put(win.browserField, text);
            String form = win.browserFieldForm;
            this.focus = Focus.NONE;
            this.focusWin = null;
            this.browserSubmit(win, tab, form);
        }
    }

    /** 打开一个地址：electron:// 与 file:// 本地渲染，其余走 Node 引擎或真实 HTTP。 */
    private void browserGo(Win win, BrowserTab tab, String raw, boolean record) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.startsWith("electron://")) {
            String target = this.browserAction(win, tab, value);
            if (target == null) {
                return;
            }
            value = target;
            // 统一成 electron://home 的形式，避免地址栏和热区里出现 electron:///home。
            String rest = value.substring("electron://".length());
            int cut = 0;
            while (cut < rest.length() && rest.charAt(cut) == '/') {
                cut++;
            }
            value = "electron://" + rest.substring(cut);
        }
        String url = value.startsWith("electron://") || value.startsWith("file://")
                ? value : BrowserClient.normalize(value);
        if (this.isBrowserDownload(url)) {
            this.startBrowserDownload(tab, url);
            return;
        }
        tab.url = url;
        tab.address = url;
        tab.scroll = 0;
        tab.maxScroll = 0;
        tab.error = "";
        tab.loading = false;
        tab.page = null;
        tab.enginePage = null;
        tab.matches.clear();
        tab.matchAt = -1;
        tab.matchShown = -2;
        tab.findMatches = 0;
        tab.findActive = 0;
        tab.chromiumTyping = false;
        if (record) {
            this.pushBrowserHistory(tab, url);
        }
        // Chromium 可用时所有网络页面都交给真正的浏览器内核；内置页也由它排版。
        boolean chromium = WebKitEngine.available() || WebKitEngine.start();
        if (url.startsWith("electron://")) {
            String html = this.localPageHtml(url, tab);
            tab.chromiumInternal = true;
            tab.title = this.browserInternalTitle(url);
            if (chromium && this.beginChromium(win, tab, url, html)) {
                this.recordVisit(tab.title, url);
                return;
            }
            if (this.ensureBrowserEngine()) {
                tab.loading = true;
                tab.startedAt = System.currentTimeMillis();
                this.browserEngineNavigate(win, tab, url, html);
            } else {
                tab.page = this.localPage(url, tab);
                tab.title = tab.page.title();
                tab.layoutWidth = 0;
            }
        } else if (this.isVmFile(url)) {
            tab.chromiumInternal = false;
            tab.chromiumPage = false;
            tab.page = this.filePage(url);
            tab.title = tab.page.title();
            tab.layoutWidth = 0;
        } else if (chromium && this.beginChromium(win, tab, url, null)) {
            return;
        } else {
            tab.chromiumInternal = false;
            tab.loading = true;
            tab.startedAt = System.currentTimeMillis();
            if (this.ensureBrowserEngine()) {
                this.browserEngineNavigate(win, tab, url, null);
            } else {
                this.browserFetch(win, tab, url);
            }
        }
        if (this.focus == Focus.ADDRESS_BROWSER && this.focusWin == win
                && win.browserField.isBlank()) {
            win.addressText = tab.address;
            this.edit.set(tab.address);
            this.edit.click(tab.address.length(), false);
        }
    }

    /** 把一次新导航写进标签页历史；同一地址连续回车不重复入栈。 */
    private void pushBrowserHistory(BrowserTab tab, String url) {
        if (tab.historyAt >= 0 && tab.historyAt < tab.history.size()) {
            String current = tab.history.get(tab.historyAt);
            if (WebKitEngine.sameUrl(current, url)) {
                // 同一页的规范写法（补斜杠等）：更新写法，也保留前进记录。
                tab.history.set(tab.historyAt, url);
                return;
            }
        }
        while (tab.history.size() > tab.historyAt + 1) {
            tab.history.remove(tab.history.size() - 1);
        }
        tab.history.add(url);
        tab.historyAt = tab.history.size() - 1;
        if (tab.history.size() > 50) {
            tab.history.remove(0);
            tab.historyAt = Math.max(0, tab.historyAt - 1);
        }
    }

    private String browserInternalTitle(String url) {
        if (BROWSER_BOOKMARKS.equals(url)) {
            return "书签管理器";
        }
        if (BROWSER_HISTORY.equals(url)) {
            return "历史记录";
        }
        if (BROWSER_DOWNLOADS.equals(url)) {
            return "下载内容";
        }
        if (BROWSER_SETTINGS.equals(url)) {
            return "浏览器设置";
        }
        if (BROWSER_ABOUT.equals(url)) {
            return "关于浏览器";
        }
        if (BROWSER_WIKI.equals(url)) {
            return "哈基坤 Wiki";
        }
        return "起始页";
    }

    /** 虚拟机路径的 file:// 交给 Java 文件页；宿主盘符仍当普通文件。 */
    private boolean isVmFile(String url) {
        if (url == null || !url.startsWith("file://")) {
            return false;
        }
        String rest = url.substring("file://".length());
        return !(rest.length() >= 3 && rest.charAt(0) == '/'
                && Character.isLetter(rest.charAt(1)) && rest.charAt(2) == ':');
    }

    /** 把页面交给 Chromium；标签页窗口按当前页面区域尺寸创建。 */
    private boolean beginChromium(Win win, BrowserTab tab, String url, String html) {
        if (tab.chromium < 0) {
            int w = tab.pageW > 0 ? tab.pageW : 300;
            int h = tab.pageH > 0 ? tab.pageH : 160;
            tab.chromium = WebKitEngine.createTab(this::onChromiumEvent, w, h, tab.pageZoom);
        }
        if (tab.chromium < 0) {
            return false;
        }
        // 每次交页都把界面侧历史同步进内核，后退/前进从第一页起就是准确的。
        WebKitEngine.seedHistory(tab.chromium, tab.history, tab.historyAt);
        tab.chromiumPage = true;
        tab.chromiumInternal = html != null;
        tab.loading = true;
        tab.error = "";
        int w = tab.pageW > 0 ? tab.pageW : 300;
        int h = tab.pageH > 0 ? tab.pageH : 160;
        if (html != null) {
            WebKitEngine.loadHtml(tab.chromium, url, html, url, w, h, tab.pageZoom);
        } else {
            WebKitEngine.load(tab.chromium, url, w, h, tab.pageZoom);
        }
        return true;
    }

    // ---------------------------------------------------------------- 引擎接线

    /** 第一次打开浏览器时启动 Node SDK；失败就退回 Java 渲染，不再反复拉起进程。 */
    private boolean ensureBrowserEngine() {
        if (ElectronEngine.available()) {
            return true;
        }
        if (!this.browserEngineTried) {
            this.browserEngineTried = true;
            ElectronEngine.start();
        }
        return ElectronEngine.available();
    }

    private int browserPageWidth(Win win) {
        return Math.max(80, win.w - 2);
    }

    /** 向 Node 请求一个页面；electron:// 页面直接传生成的 HTML，不联网。 */
    private void browserEngineNavigate(Win win, BrowserTab tab, String url, String html) {
        int width = this.browserPageWidth(win);
        tab.layoutWidth = width;
        tab.relayouting = false;
        ElectronEngine.navigate(tab.session, url, width, tab.pageZoom, tab.reader,
                html, url, this.browserCookieList(),
                page -> this.acceptEnginePage(win, tab, page, width, true));
    }

    /** 缩放、阅读模式与窗口尺寸变化时只重排，不重新下载网页。 */
    private void requestRelayout(Win win, BrowserTab tab, int width, boolean fresh) {
        if (tab.relayouting) {
            return;
        }
        tab.relayouting = true;
        tab.layoutWidth = width;
        String url = tab.url;
        ElectronEngine.relayout(tab.session, url, width, tab.pageZoom, tab.reader, page -> {
            tab.relayouting = false;
            if (page.error().isBlank()) {
                this.acceptEnginePage(win, tab, page, width, false);
            }
        });
    }

    /** 引擎返回的页面：更新地址、标题、Cookie，并把访问写进历史。 */
    private void acceptEnginePage(Win win, BrowserTab tab, ElectronEngine.Page page,
                                  int width, boolean fresh) {
        tab.loading = false;
        tab.layoutWidth = width;
        if (!page.error().isBlank()) {
            tab.enginePage = page;
            tab.page = null;
            tab.error = page.error();
            tab.title = "无法访问";
            tab.maxScroll = 0;
            return;
        }
        tab.enginePage = page;
        tab.page = null;
        tab.error = "";
        String finalUrl = page.url() == null || page.url().isBlank() ? tab.url : page.url();
        if (fresh && tab.historyAt >= 0 && tab.historyAt < tab.history.size()) {
            tab.history.set(tab.historyAt, finalUrl);
        }
        tab.url = finalUrl;
        tab.address = finalUrl;
        tab.title = page.title().isBlank() ? this.browserHost(finalUrl) : page.title();
        this.mergeBrowserCookies(page.cookies());
        if (fresh) {
            this.recordVisit(tab.title, tab.url);
            tab.matches.clear();
            tab.matchAt = -1;
            if (tab.findOpen && !tab.findQuery.isBlank()) {
                this.runBrowserFind(tab);
            }
        }
        if (this.focus == Focus.ADDRESS_BROWSER && this.focusWin == win
                && win.browserField.isBlank()) {
            win.addressText = tab.address;
            this.edit.set(tab.address);
            this.edit.click(tab.address.length(), false);
        }
    }

    private void openBrowserFind(Win win, BrowserTab tab) {
        if (tab.findOpen) {
            tab.findOpen = false;
            tab.matches.clear();
            tab.matchAt = -1;
            this.focus = Focus.NONE;
            return;
        }
        tab.findOpen = true;
        String query = tab.findQuery == null ? "" : tab.findQuery;
        this.beginEditAt(Focus.FIND, win, query, query.length(), false);
        this.runBrowserFind(tab);
    }

    /** 页内查找：Chromium 页交给内核查找，Java 排版页在命中矩形里找。 */
    private void runBrowserFind(BrowserTab tab) {
        String query = tab.findQuery == null ? "" : tab.findQuery.trim();
        tab.matches.clear();
        tab.matchAt = -1;
        tab.matchShown = -2;
        tab.findMatches = 0;
        tab.findActive = 0;
        if (query.isEmpty()) {
            if (tab.chromiumPage && tab.chromium >= 0) {
                WebKitEngine.find(tab.chromium, "", true, false);
            }
            return;
        }
        if (tab.chromiumPage && tab.chromium >= 0) {
            WebKitEngine.find(tab.chromium, query, true, false);
            return;
        }
        if (tab.enginePage == null || !ElectronEngine.available()) {
            return;
        }
        ElectronEngine.find(tab.session, query, matches -> {
            if (!query.equals(tab.findQuery == null ? "" : tab.findQuery.trim())) {
                return;
            }
            tab.matches.clear();
            tab.matches.addAll(matches);
            tab.matchAt = matches.isEmpty() ? -1 : 0;
            tab.matchShown = -2;
        });
    }

    /** 复选框当前是否勾选：用户点过就以记录为准，否则取 HTML 的默认勾选。 */
    private boolean browserCheckDefault(BrowserTab tab, String name) {
        if (tab.checkState.containsKey(name)) {
            return Boolean.TRUE.equals(tab.checkState.get(name));
        }
        if (tab.enginePage != null) {
            for (ElectronEngine.Item item : tab.enginePage.items()) {
                if (item.box() && name.equals(item.name())) {
                    return item.checked();
                }
            }
        }
        return false;
    }

    private void stepBrowserFind(BrowserTab tab, int delta) {
        if (tab.chromiumPage && tab.chromium >= 0) {
            String query = tab.findQuery == null ? "" : tab.findQuery.trim();
            if (query.isEmpty()) {
                return;
            }
            WebKitEngine.find(tab.chromium, query, delta >= 0, true);
            return;
        }
        if (tab.matches.isEmpty()) {
            this.runBrowserFind(tab);
            return;
        }
        int size = tab.matches.size();
        tab.matchAt = ((tab.matchAt + delta) % size + size) % size;
        tab.matchShown = -2;
    }

    /** 把当前命中滚进可视区域；同一命中只处理一次，不跟用户滚动抢滚动条。 */
    private void revealMatch(BrowserTab tab, int top, int height) {
        if (tab.matchAt < 0 || tab.matchAt >= tab.matches.size()) {
            return;
        }
        if (tab.matchShown == tab.matchAt) {
            return;
        }
        tab.matchShown = tab.matchAt;
        ElectronEngine.Match match = tab.matches.get(tab.matchAt);
        if (match.y() < tab.scroll + 4) {
            tab.scroll = Math.max(0, match.y() - 10);
        } else if (match.y() + match.h() > tab.scroll + height - 4) {
            tab.scroll = Math.min(tab.maxScroll,
                    match.y() + match.h() - height + 12);
        }
    }

    /** electron:// 里的动作链接：改书签/历史/设置后跳到对应页面，不把动作本身记进历史。 */
    private String browserAction(Win win, BrowserTab tab, String url) {
        if (url.startsWith("electron://search?")) {
            String query = this.queryParam(url, "q");
            return query.isBlank() ? BROWSER_HOME : this.browserSettings.searchUrl(query);
        }
        if (url.startsWith("electron://bookmark-add")) {
            this.toggleBookmark(tab);
            return BROWSER_BOOKMARKS;
        }
        if (url.startsWith("electron://bookmark-del")) {
            int index = parseInt(this.queryParam(url, "i"), -1);
            if (index >= 0 && index < this.bookmarks.size()) {
                this.bookmarks.remove(index);
                this.saveBrowserBookmarks();
                this.toast("已删除书签");
            }
            return BROWSER_BOOKMARKS;
        }
        if (url.startsWith("electron://history-del")) {
            int index = parseInt(this.queryParam(url, "i"), -1);
            if (index >= 0 && index < this.visits.size()) {
                this.visits.remove(index);
                this.saveBrowserHistory();
                this.toast("已删除这条历史记录");
            }
            return BROWSER_HISTORY;
        }
        if (url.startsWith("electron://history-clear")) {
            this.visits.clear();
            this.saveBrowserHistory();
            this.toast("历史记录已清空");
            return BROWSER_HISTORY;
        }
        if (url.startsWith("electron://download-del")) {
            int index = parseInt(this.queryParam(url, "i"), -1);
            if (index >= 0 && index < this.downloads.size()) {
                this.downloads.remove(index);
                this.saveBrowserDownloads();
            }
            return BROWSER_DOWNLOADS;
        }
        if (url.startsWith("electron://downloads-clear")) {
            this.downloads.clear();
            this.saveBrowserDownloads();
            this.toast("下载记录已清空");
            return BROWSER_DOWNLOADS;
        }
        if (url.startsWith("electron://settings?")) {
            String search = this.queryParam(url, "search");
            if (search.equals("bing") || search.equals("google") || search.equals("wiki")) {
                this.browserSettings.search = search;
                this.saveBrowserSettings();
                this.toast("搜索引擎已切换为 " + this.browserSettings.searchName());
            }
            return BROWSER_SETTINGS;
        }
        if (url.startsWith("electron://set-home")) {
            String home = this.queryParam(url, "url");
            if (!home.isBlank()) {
                this.browserSettings.home = home;
                this.saveBrowserSettings();
                this.toast("主页已设为 " + home);
            }
            return BROWSER_SETTINGS;
        }
        if (url.startsWith("electron://zoom")) {
            float zoom = 1.0F;
            try {
                zoom = Float.parseFloat(this.queryParam(url, "z"));
            } catch (NumberFormatException ignored) {
                zoom = 1.0F;
            }
            tab.pageZoom = Math.max(0.5F, Math.min(3.0F, zoom));
            this.browserSettings.zoom = tab.pageZoom;
            this.saveBrowserSettings();
            this.requestRelayout(win, tab, this.browserPageWidth(win), false);
            this.toast("缩放 " + Math.round(tab.pageZoom * 100.0F) + "%");
            return null;
        }
        if (url.startsWith("electron://cookies-clear")) {
            this.browserCookies.clear();
            this.saveBrowserCookies();
            this.toast("Cookie 已清空");
            return BROWSER_SETTINGS;
        }
        return url;
    }

    private String queryParam(String url, String key) {
        int mark = url.indexOf('?');
        if (mark < 0 || mark + 1 >= url.length()) {
            return "";
        }
        for (String pair : url.substring(mark + 1).split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (!name.equals(key)) {
                continue;
            }
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (RuntimeException exception) {
                return value;
            }
        }
        return "";
    }

    private boolean isBrowserDownload(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return false;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".zip", ".7z", ".rar", ".jar", ".exe", ".msi", ".pdf",
                ".png", ".jpg", ".jpeg", ".gif", ".webp", ".mp3", ".ogg", ".wav", ".mp4",
                ".webm", ".txt", ".json", ".csv", ".apk"}) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 下载链接：Node 抓字节，Java 通过 P2V 通道写进虚拟机下载目录。 */
    private void startBrowserDownload(BrowserTab tab, String url) {
        if (!ElectronEngine.available()) {
            this.toast("下载需要 Node.js 引擎（未找到 node.exe）");
            return;
        }
        tab.notice = "正在下载 " + url;
        tab.noticeUntil = System.currentTimeMillis() + 15000L;
        ElectronEngine.download(tab.session, url, result -> {
            if (!result.error().isBlank() || result.data() == null) {
                tab.notice = "下载失败：" + result.error();
                tab.noticeUntil = System.currentTimeMillis() + 6000L;
                return;
            }
            String name = this.safeDownloadName(result.name(), result.mime());
            HajikunSmartClient.upload(this.pos, BROWSER_DOWNLOAD_DIR, name, result.mime(),
                    result.data());
            String path = BROWSER_DOWNLOAD_DIR + "/" + name;
            this.downloads.add(0, new BrowserDownload(name, url, path,
                    System.currentTimeMillis(), result.data().length));
            while (this.downloads.size() > 40) {
                this.downloads.remove(this.downloads.size() - 1);
            }
            this.saveBrowserDownloads();
            tab.notice = "已保存 " + path;
            tab.noticeUntil = System.currentTimeMillis() + 6000L;
            this.toast("已下载到 " + path);
        });
    }

    private String safeDownloadName(String name, String mime) {
        String value = name == null || name.isBlank() ? "download.bin" : name.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_");
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            value = "download.bin";
        }
        if (value.length() > 80) {
            value = value.substring(0, 80);
        }
        if (!value.contains(".")) {
            String ext = switch (mime == null ? "" : mime) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "image/gif" -> ".gif";
                case "text/html" -> ".html";
                case "text/plain" -> ".txt";
                default -> "";
            };
            value += ext;
        }
        return value;
    }

    private boolean isBookmarked(String url) {
        if (url == null) {
            return false;
        }
        for (BrowserBookmark bookmark : this.bookmarks) {
            if (url.equals(bookmark.url())) {
                return true;
            }
        }
        return false;
    }

    private void toggleBookmark(BrowserTab tab) {
        if (tab.url == null || tab.url.isBlank()) {
            return;
        }
        for (int i = 0; i < this.bookmarks.size(); i++) {
            if (tab.url.equals(this.bookmarks.get(i).url())) {
                this.bookmarks.remove(i);
                this.saveBrowserBookmarks();
                this.toast("已移除书签");
                return;
            }
        }
        String title = tab.title == null || tab.title.isBlank() ? tab.url : tab.title;
        this.bookmarks.add(0, new BrowserBookmark(title, tab.url));
        while (this.bookmarks.size() > 60) {
            this.bookmarks.remove(this.bookmarks.size() - 1);
        }
        this.saveBrowserBookmarks();
        this.toast("已添加书签：" + title);
    }

    private void recordVisit(String title, String url) {
        if (url == null || url.isBlank() || url.startsWith("electron://")
                || url.startsWith("file://")) {
            return;
        }
        for (int i = 0; i < this.visits.size(); i++) {
            if (url.equals(this.visits.get(i).url())) {
                this.visits.remove(i);
                break;
            }
        }
        this.visits.add(0, new BrowserVisit(title == null || title.isBlank() ? url : title,
                url, System.currentTimeMillis()));
        while (this.visits.size() > BROWSER_HISTORY_MAX) {
            this.visits.remove(this.visits.size() - 1);
        }
        this.saveBrowserHistory();
    }

    // ---------------------------------------------------------------- 浏览器磁盘

    private void loadBrowserStore() {
        if (this.browserStoreLoaded) {
            return;
        }
        this.browserStoreLoaded = true;
        if (this.device == null || this.device.fs == null) {
            return;
        }
        JsonObject root = this.readBrowserStore(BROWSER_BOOKMARK_FILE);
        if (root != null && root.has("items") && root.get("items").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("items")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String url = jsonString(item, "url", "");
                if (!url.isBlank()) {
                    this.bookmarks.add(new BrowserBookmark(jsonString(item, "title", url), url));
                }
            }
        }
        root = this.readBrowserStore(BROWSER_HISTORY_FILE);
        if (root != null && root.has("items") && root.get("items").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("items")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String url = jsonString(item, "url", "");
                if (!url.isBlank()) {
                    this.visits.add(new BrowserVisit(jsonString(item, "title", url), url,
                            item.has("at") ? item.get("at").getAsLong() : 0L));
                }
            }
        }
        root = this.readBrowserStore(BROWSER_SETTINGS_FILE);
        if (root != null) {
            this.browserSettings.search = jsonString(root, "search", "bing");
            String home = jsonString(root, "home", BROWSER_HOME);
            this.browserSettings.home = home.isBlank() ? BROWSER_HOME : home;
            this.browserSettings.zoom = Math.max(0.5F, Math.min(3.0F,
                    (float) (root.has("zoom") ? root.get("zoom").getAsFloat() : 1.0F)));
        }
        root = this.readBrowserStore(BROWSER_DOWNLOAD_FILE);
        if (root != null && root.has("items") && root.get("items").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("items")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String path = jsonString(item, "path", "");
                if (!path.isBlank()) {
                    this.downloads.add(new BrowserDownload(jsonString(item, "name", path),
                            jsonString(item, "url", ""), path,
                            item.has("at") ? item.get("at").getAsLong() : 0L,
                            item.has("bytes") ? item.get("bytes").getAsLong() : 0L));
                }
            }
        }
        root = this.readBrowserStore(BROWSER_COOKIE_FILE);
        if (root != null && root.has("cookies") && root.get("cookies").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("cookies")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject cookie = element.getAsJsonObject();
                String name = jsonString(cookie, "name", "");
                if (!name.isBlank()) {
                    this.browserCookies.put(name + "|" + jsonString(cookie, "domain", ""),
                            new ElectronEngine.Cookie(name, jsonString(cookie, "value", ""),
                                    jsonString(cookie, "domain", ""),
                                    cookie.has("expires") ? cookie.get("expires").getAsLong() : 0L));
                }
            }
        }
    }

    private JsonObject readBrowserStore(String path) {
        VirtualFileSystem.Node node = this.device.fs.find(path);
        if (node == null || node.isDir() || node.content == null || node.content.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(node.content).getAsJsonObject();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String jsonString(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int jsonInt(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private void writeBrowserStore(String path, String json) {
        if (this.device == null || !this.device.powered) {
            return;
        }
        HajikunSmartClient.request(this.pos, "write", path + "\n" + json);
    }

    private void saveBrowserBookmarks() {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (BrowserBookmark bookmark : this.bookmarks) {
            JsonObject item = new JsonObject();
            item.addProperty("title", bookmark.title());
            item.addProperty("url", bookmark.url());
            items.add(item);
        }
        root.add("items", items);
        this.writeBrowserStore(BROWSER_BOOKMARK_FILE, root.toString());
    }

    private void saveBrowserHistory() {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (BrowserVisit visit : this.visits) {
            JsonObject item = new JsonObject();
            item.addProperty("title", visit.title());
            item.addProperty("url", visit.url());
            item.addProperty("at", visit.at());
            items.add(item);
        }
        root.add("items", items);
        this.writeBrowserStore(BROWSER_HISTORY_FILE, root.toString());
    }

    private void saveBrowserDownloads() {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (BrowserDownload download : this.downloads) {
            JsonObject item = new JsonObject();
            item.addProperty("name", download.name());
            item.addProperty("url", download.url());
            item.addProperty("path", download.path());
            item.addProperty("at", download.at());
            item.addProperty("bytes", download.bytes());
            items.add(item);
        }
        root.add("items", items);
        this.writeBrowserStore(BROWSER_DOWNLOAD_FILE, root.toString());
    }

    private void saveBrowserSettings() {
        JsonObject root = new JsonObject();
        root.addProperty("search", this.browserSettings.search);
        root.addProperty("home", this.browserSettings.home);
        root.addProperty("zoom", this.browserSettings.zoom);
        this.writeBrowserStore(BROWSER_SETTINGS_FILE, root.toString());
    }

    /** Cookie 与 Java 兜底共用一份文件，Node 的 Cookie 会带上过期时间。 */
    private void saveBrowserCookies() {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (ElectronEngine.Cookie cookie : this.browserCookies.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", cookie.name());
            item.addProperty("value", cookie.value());
            item.addProperty("domain", cookie.domain());
            item.addProperty("path", "/");
            item.addProperty("secure", false);
            item.addProperty("expires", cookie.expires());
            items.add(item);
        }
        root.add("cookies", items);
        this.writeBrowserStore(BROWSER_COOKIE_FILE, root.toString());
    }

    private void mergeBrowserCookies(List<ElectronEngine.Cookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ElectronEngine.Cookie cookie : cookies) {
            if (cookie == null || cookie.name() == null || cookie.name().isBlank()) {
                continue;
            }
            String key = cookie.name() + "|" + cookie.domain();
            ElectronEngine.Cookie old = this.browserCookies.get(key);
            if (old == null || !old.value().equals(cookie.value())
                    || old.expires() != cookie.expires()) {
                this.browserCookies.put(key, cookie);
                changed = true;
            }
        }
        if (changed) {
            this.saveBrowserCookies();
        }
    }

    private List<ElectronEngine.Cookie> browserCookieList() {
        long now = System.currentTimeMillis();
        List<ElectronEngine.Cookie> out = new ArrayList<>();
        for (ElectronEngine.Cookie cookie : this.browserCookies.values()) {
            if (cookie.expires() > 0L && cookie.expires() < now) {
                continue;
            }
            out.add(cookie);
        }
        return out;
    }

    private void browserFetch(Win win, BrowserTab tab, String url) {
        BrowserClient.fetch(this.pos, url, result -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> this.acceptBrowserResult(win, tab, result));
            }
        });
    }

    private void acceptBrowserResult(Win win, BrowserTab tab, BrowserClient.Result result) {
        tab.loading = false;
        if (result.error() != null && !result.error().isBlank()) {
            tab.error = result.error();
            tab.page = this.errorPage(result.url().isBlank() ? tab.url : result.url(), result.error());
            tab.title = "无法访问";
            return;
        }
        tab.page = result;
        tab.error = "";
        tab.url = result.url();
        tab.address = result.url();
        tab.title = result.title().isBlank() ? this.browserHost(result.url()) : result.title();
        if (this.focus == Focus.ADDRESS_BROWSER && this.focusWin == win
                && win.browserField.isBlank()) {
            win.addressText = tab.address;
            this.edit.set(tab.address);
            this.edit.click(tab.address.length(), false);
        }
        // Cookie 有变化时静默写回这台电脑的磁盘，下次打开仍是登录状态。
        if (this.device != null && this.device.powered && BrowserClient.profileDirty(this.pos)) {
            HajikunSmartClient.request(this.pos, "write",
                    BROWSER_COOKIE_FILE + "\n" + BrowserClient.profile(this.pos));
            BrowserClient.profileSaved(this.pos);
        }
    }

    /** 内置页面的表单（例如起始页搜索框）：把字段拼成查询串，交给 electron:// 动作处理。 */
    private boolean submitLocalForm(Win win, BrowserTab tab, String action,
                                    Map<String, String> values) {
        if (action == null || !action.startsWith("electron://")) {
            return false;
        }
        StringBuilder url = new StringBuilder(action);
        char separator = action.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            url.append(separator)
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
            separator = '&';
        }
        this.focus = Focus.NONE;
        this.focusWin = win;
        this.browserGo(win, tab, url.toString(), true);
        return true;
    }

    /** 表单提交：带上当前字段值，GET 拼到地址栏、POST 走表单体。 */
    private void browserSubmit(Win win, BrowserTab tab, String formId) {
        if (tab.enginePage != null && ElectronEngine.available()) {
            this.browserEngineSubmit(win, tab, formId);
            return;
        }
        BrowserClient.Result page = tab.page;
        if (page == null) {
            return;
        }
        String id = formId == null ? "" : formId;
        BrowserClient.FormInfo form = null;
        for (BrowserClient.FormInfo info : page.forms()) {
            if (info.id().equals(id)) {
                form = info;
                break;
            }
        }
        Map<String, String> values = new HashMap<>();
        for (BrowserClient.Block block : page.blocks()) {
            if (block.kind() != BrowserClient.Kind.FIELD) {
                continue;
            }
            String owner = block.form() == null ? "" : block.form();
            if (owner.equals(id)) {
                values.put(block.name(), tab.fields.getOrDefault(block.name(), block.value()));
            }
        }
        if (form == null && values.isEmpty()) {
            return;
        }
        String action = form == null ? "" : form.action();
        String method = form == null ? "get" : form.method();
        if (this.submitLocalForm(win, tab, action, values)) {
            return;
        }
        tab.loading = true;
        tab.error = "";
        BrowserClient.submit(this.pos, page.url(), action, method, values, result -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> this.acceptBrowserResult(win, tab, result));
            }
        });
    }

    /** 引擎页面的表单：从显示列表里按 form id 收集字段，再到 Node 做真正的提交。 */
    private void browserEngineSubmit(Win win, BrowserTab tab, String formId) {
        ElectronEngine.Page page = tab.enginePage;
        if (page == null) {
            return;
        }
        String id = formId == null ? "" : formId;
        String action = "";
        String method = "get";
        Map<String, String> values = new HashMap<>();
        for (ElectronEngine.Item item : page.items()) {
            String owner = item.form() == null ? "" : item.form();
            if (!owner.equals(id)) {
                continue;
            }
            if (!item.action().isBlank()) {
                action = item.action();
                method = item.method().isBlank() ? method : item.method();
            }
            if (!"field".equals(item.kind())) {
                continue;
            }
            String name = item.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (item.box()) {
                if (this.browserCheckDefault(tab, name)) {
                    values.put(name, item.value().isBlank() ? "on" : item.value());
                }
            } else {
                values.put(name, tab.fields.getOrDefault(name, item.value()));
            }
        }
        if (action.isBlank() && values.isEmpty()) {
            return;
        }
        if (this.submitLocalForm(win, tab, action, values)) {
            return;
        }
        tab.loading = true;
        tab.error = "";
        tab.matches.clear();
        tab.matchAt = -1;
        int width = this.browserPageWidth(win);
        ElectronEngine.submit(tab.session, page.url(), action, method, values, width,
                tab.pageZoom, tab.reader, this.browserCookieList(),
                result -> this.acceptEnginePage(win, tab, result, width, true));
    }

    private String browserFieldDefault(BrowserTab tab, String name) {
        if (tab.enginePage != null) {
            for (ElectronEngine.Item item : tab.enginePage.items()) {
                if ("field".equals(item.kind()) && name.equals(item.name())) {
                    return item.value() == null ? "" : item.value();
                }
            }
        }
        if (tab.page == null) {
            return "";
        }
        for (BrowserClient.Block block : tab.page.blocks()) {
            if (block.kind() == BrowserClient.Kind.FIELD && name.equals(block.name())) {
                return block.value() == null ? "" : block.value();
            }
        }
        return "";
    }

    private void browserHistory(Win win, BrowserTab tab, int delta) {
        if (tab.chromiumPage && tab.chromium >= 0) {
            WebKitEngine.nav(tab.chromium, delta < 0 ? "back" : "forward");
            return;
        }
        int at = tab.historyAt + delta;
        if (at < 0 || at >= tab.history.size()) {
            return;
        }
        tab.historyAt = at;
        this.browserGo(win, tab, tab.history.get(at), false);
    }

    private BrowserTab newBrowserTab(Win win) {
        BrowserTab tab = new BrowserTab();
        this.loadBrowserStore();
        tab.pageZoom = this.browserSettings.zoom;
        tab.history.add(tab.url);
        tab.historyAt = 0;
        win.tabs.add(tab);
        win.activeTab = win.tabs.size() - 1;
        win.browserField = "";
        win.browserFieldForm = "";
        win.addressText = tab.address;
        this.focus = Focus.NONE;
        this.focusWin = win;
        return tab;
    }

    private void closeBrowserTab(Win win, int index) {
        if (index < 0 || index >= win.tabs.size()) {
            return;
        }
        if (win.tabs.size() == 1) {
            // 最后一个标签不关窗，回到起始页，避免把整个浏览器关掉。
            this.browserGo(win, win.tabs.get(0), BROWSER_HOME, true);
        } else {
            BrowserTab removed = win.tabs.remove(index);
            this.releaseChromiumTab(removed);
            if (win.activeTab > index) {
                win.activeTab--;
            } else if (win.activeTab == index) {
                win.activeTab = Math.min(index, win.tabs.size() - 1);
            }
        }
        win.browserField = "";
        win.browserFieldForm = "";
        win.addressText = win.tab().address;
        this.focus = Focus.NONE;
        this.focusWin = win;
    }

    /** 关标签或换内核前释放 Chromium 的标签页与纹理。 */
    private void releaseChromiumTab(BrowserTab tab) {
        if (tab.chromium >= 0) {
            WebKitEngine.closeTab(tab.chromium);
            this.releaseChromiumTexture(tab);
        }
        tab.chromium = -1;
        tab.chromiumPage = false;
        tab.chromiumTyping = false;
    }

    private Menu browserMenu(Win win) {
        BrowserTab tab = win.tab();
        WebKitEngine.State chromium = tab.chromium >= 0
                ? WebKitEngine.state(tab.chromium) : null;
        boolean canBack = chromium != null ? chromium.canBack() : tab.historyAt > 0;
        boolean canForward = chromium != null
                ? chromium.canForward() : tab.historyAt < tab.history.size() - 1;

        // 菜单项按功能收进子菜单：一层最多十行，整个菜单在 247 像素的画布里放得下，
        // 不用把二十来项排成一长条。
        Menu library = new Menu("书签与历史");
        library.item(this.isBookmarked(tab.url) ? "移除书签" : "添加书签",
                () -> this.toggleBookmark(tab));
        library.item("书签管理器", () -> this.browserGo(win, tab, BROWSER_BOOKMARKS, true));
        library.item("历史记录", () -> this.browserGo(win, tab, BROWSER_HISTORY, true));
        library.item("下载内容", () -> this.browserGo(win, tab, BROWSER_DOWNLOADS, true));

        Menu page = new Menu("页面");
        page.item(tab.reader ? "关闭阅读模式" : "阅读模式", () -> {
            tab.reader = !tab.reader;
            if (tab.chromiumPage && tab.chromium >= 0) {
                WebKitEngine.reader(tab.chromium, tab.reader);
            } else {
                this.requestRelayout(win, tab, this.browserPageWidth(win), false);
            }
        });
        page.item("复制网址", () -> setClipboard(tab.url));
        page.item("打开下载目录", () -> this.openFilesAt(BROWSER_DOWNLOAD_DIR));
        page.item("清除 Cookie", () -> {
            this.browserCookies.clear();
            this.saveBrowserCookies();
            this.toast("Cookie 已清空，重新打开网页即可重新登录");
        });

        Menu zoom = new Menu("缩放");
        zoom.item("放大 +10%", () -> this.browserZoom(win, tab, 0.1F), tab.pageZoom < 2.9F);
        zoom.item("缩小 -10%", () -> this.browserZoom(win, tab, -0.1F), tab.pageZoom > 0.6F);
        zoom.item("实际大小 100%", () -> this.browserZoom(win, tab, 0.0F));

        Menu menu = new Menu("浏览器");
        menu.item("后退", () -> this.browserHistory(win, tab, -1), canBack);
        menu.item("前进", () -> this.browserHistory(win, tab, 1), canForward);
        menu.item("刷新", () -> this.browserReload(win, tab), !tab.loading);
        menu.item("主页", () -> this.browserGo(win, tab, this.browserSettings.home, true));
        menu.sep();
        menu.item("新标签页", () -> this.newBrowserTab(win));
        menu.item("关闭当前标签", () -> this.closeBrowserTab(win, win.activeTab));
        menu.sep();
        menu.sub("书签与历史", library);
        menu.sub("页面", page);
        menu.sub("缩放", zoom);
        menu.sep();
        menu.item("设置", () -> this.browserGo(win, tab, BROWSER_SETTINGS, true));
        return menu;
    }

    /** 刷新：Chromium 页走内核重新加载，Java 页重新取一次。 */
    private void browserReload(Win win, BrowserTab tab) {
        if (tab.chromiumPage && tab.chromium >= 0) {
            WebKitEngine.nav(tab.chromium, "reload");
            tab.loading = true;
            tab.error = "";
            return;
        }
        this.browserGo(win, tab, tab.url, false);
    }

    /** 缩放：Chromium 页直接缩放渲染；Java 页重排。 */
    private void browserZoom(Win win, BrowserTab tab, float delta) {
        float next = delta == 0F ? 1.0F : tab.pageZoom + delta;
        tab.pageZoom = Math.max(0.5F, Math.min(3.0F,
                Math.round(next * 10.0F) / 10.0F));
        this.browserSettings.zoom = tab.pageZoom;
        this.saveBrowserSettings();
        if (tab.chromiumPage && tab.chromium >= 0) {
            WebKitEngine.zoom(tab.chromium, tab.pageZoom);
        } else if (tab.enginePage != null) {
            this.requestRelayout(win, tab, this.browserPageWidth(win), false);
        }
        this.toast("缩放 " + Math.round(tab.pageZoom * 100.0F) + "%");
    }

    /** 内置页面：起始页、Wiki、书签、历史、下载与设置，不依赖网络。 */
    private BrowserClient.Result localPage(String url, BrowserTab tab) {
        List<BrowserClient.Block> blocks = new ArrayList<>();
        String title;
        if (BROWSER_BOOKMARKS.equals(url)) {
            title = "书签管理器";
            blocks.add(bHeading(1, "书签管理器"));
            blocks.add(bText("共 " + this.bookmarks.size() + " 个书签。在网页里点 ☆ 可收藏当前页面。"));
            for (int i = 0; i < this.bookmarks.size(); i++) {
                BrowserBookmark mark = this.bookmarks.get(i);
                blocks.add(bLink(mark.title() + "  ·  " + mark.url(), mark.url()));
                blocks.add(bLink("删除这个书签", "electron://bookmark-del?i=" + i));
            }
            blocks.add(bLink("返回起始页", BROWSER_HOME));
        } else if (BROWSER_HISTORY.equals(url)) {
            title = "历史记录";
            blocks.add(bHeading(1, "历史记录"));
            blocks.add(bText("共 " + this.visits.size() + " 条访问记录。"));
            for (int i = 0; i < this.visits.size(); i++) {
                BrowserVisit visit = this.visits.get(i);
                String when = visit.at() <= 0 ? "" : BROWSER_STAMP.format(
                        Instant.ofEpochMilli(visit.at())) + "  ";
                blocks.add(bLink(when + visit.title(), visit.url()));
                blocks.add(bLink("删除这条记录", "electron://history-del?i=" + i));
            }
            if (!this.visits.isEmpty()) {
                blocks.add(bLink("清空全部历史", "electron://history-clear"));
            }
            blocks.add(bLink("返回起始页", BROWSER_HOME));
        } else if (BROWSER_DOWNLOADS.equals(url)) {
            title = "下载内容";
            blocks.add(bHeading(1, "下载内容"));
            blocks.add(bText("下载保存在 " + BROWSER_DOWNLOAD_DIR + "，共 "
                    + this.downloads.size() + " 条记录。"));
            for (int i = 0; i < this.downloads.size(); i++) {
                BrowserDownload item = this.downloads.get(i);
                String when = item.at() <= 0 ? "" : BROWSER_STAMP.format(
                        Instant.ofEpochMilli(item.at())) + "  ";
                blocks.add(bLink(when + item.name() + "  ·  " + this.browserSize(item.bytes()),
                        "file://" + item.path()));
                blocks.add(bLink("删除这条记录", "electron://download-del?i=" + i));
            }
            if (!this.downloads.isEmpty()) {
                blocks.add(bLink("清空下载记录", "electron://downloads-clear"));
            }
            blocks.add(bLink("打开下载目录", "file://" + BROWSER_DOWNLOAD_DIR));
            blocks.add(bLink("返回起始页", BROWSER_HOME));
        } else if (BROWSER_SETTINGS.equals(url)) {
            title = "浏览器设置";
            blocks.add(bHeading(1, "浏览器设置"));
            blocks.add(bHeading(2, "搜索引擎"));
            blocks.add(bText("当前：" + this.browserSettings.searchName()));
            blocks.add(bLink("必应", "electron://settings?search=bing"));
            blocks.add(bLink("Google", "electron://settings?search=google"));
            blocks.add(bLink("维基百科", "electron://settings?search=wiki"));
            blocks.add(bHeading(2, "主页"));
            blocks.add(bText("当前主页：" + this.browserSettings.home));
            blocks.add(bLink("把当前地址设为主页",
                    "electron://set-home?url=" + URLEncoder.encode(tab.url, StandardCharsets.UTF_8)));
            blocks.add(bLink("主页设为起始页", BROWSER_HOME));
            blocks.add(bHeading(2, "缩放"));
            blocks.add(bLink("75%", "electron://zoom?z=0.75"));
            blocks.add(bLink("100%", "electron://zoom?z=1.0"));
            blocks.add(bLink("125%", "electron://zoom?z=1.25"));
            blocks.add(bLink("150%", "electron://zoom?z=1.5"));
            blocks.add(bHeading(2, "隐私"));
            blocks.add(bText("已保存 " + this.browserCookieList().size() + " 个 Cookie。"));
            blocks.add(bLink("清空 Cookie", "electron://cookies-clear"));
            blocks.add(bLink("内核：" + (WebKitEngine.available()
                    ? WebKitEngine.engine() : "Java 兜底"), BROWSER_ABOUT));
            blocks.add(bLink("返回起始页", BROWSER_HOME));
        } else if (BROWSER_ABOUT.equals(url)) {
            title = "关于浏览器";
            blocks.add(bHeading(1, "浏览器"));
            blocks.add(bText("哈基坤 OS 内置的完整浏览器：进程内 WebKit 内核，真实 HTTP 请求、"
                    + "Cookie 会话、标签页、网页表单与本地文件浏览。"));
            blocks.add(bHeading(2, "能做什么"));
            blocks.add(bText("• 地址栏输入网址或中文关键词，回车即打开。\n"
                    + "• ← → 前进后退，⟳ 重新加载，⌂ 回到起始页，+ 新建标签页。\n"
                    + "• 网页的排版、图片、音视频、表单与扫码登录都由 WebKit 在游戏进程内渲染。\n"
                    + "• 右键空白处可以复制网址、刷新或管理标签页。\n"
                    + "• 本地页面支持 file:///home/user 这样的虚拟机路径。"));
            blocks.add(bHeading(2, "登录与 Cookie"));
            blocks.add(bText("每台电脑有独立的 Cookie 会话，保存在 "
                    + BROWSER_COOKIE_FILE + "，换一台电脑不会共享登录状态。"));
            blocks.add(bLink("返回起始页", BROWSER_HOME));
        } else if ("electron://wiki".equals(url)) {
            title = "哈基坤 Wiki";
            blocks.add(bHeading(1, "哈基坤 Wiki"));
            blocks.add(bText("这台电脑运行哈基坤 OS。文件系统、终端、智能体 IDE 与浏览器都在"
                    + "同一个虚拟机里工作，数据随方块本体保存。"));
            blocks.add(bHeading(2, "目录结构"));
            blocks.add(bText("/home/user 是用户目录，文档、图片与 IDE 会话都在这里；"
                    + "/系统 中的文件受保护，不能删除或重命名。"));
            blocks.add(bLink("浏览 /home/user", "file:///home/user"));
            blocks.add(bLink("浏览 /系统", "file:///系统"));
            blocks.add(bHeading(2, "网络"));
            blocks.add(bText("浏览器使用真实的 HTTP 连接。没有网络时页面会显示"
                    + "「无法访问此网站」，并保留地址方便重试。"));
            blocks.add(bLink("浏览器说明", BROWSER_ABOUT));
        } else {
            title = "起始页";
            blocks.add(bHeading(1, "浏览器"));
            blocks.add(bText("在地址栏输入网址、中文关键词或本地路径，按回车开始浏览。"));
            blocks.add(bHeading(2, "快捷入口"));
            blocks.add(bLink("哈基坤 Wiki  ·  electron://wiki", "electron://wiki"));
            blocks.add(bLink("必应搜索  ·  https://www.bing.com", "https://www.bing.com"));
            blocks.add(bLink("维基百科  ·  https://zh.wikipedia.org", "https://zh.wikipedia.org"));
            blocks.add(bLink("本地文件  ·  /home/user", "file:///home/user"));
            blocks.add(bLink("浏览器说明  ·  electron://about", BROWSER_ABOUT));
            List<String> recent = new ArrayList<>();
            for (int i = tab.history.size() - 1; i >= 0 && recent.size() < 5; i--) {
                String seen = tab.history.get(i);
                if (!seen.startsWith("electron://") && !recent.contains(seen)) {
                    recent.add(seen);
                }
            }
            if (!recent.isEmpty()) {
                blocks.add(bHeading(2, "最近访问"));
                for (String seen : recent) {
                    blocks.add(bLink(seen, seen));
                }
            }
        }
        return new BrowserClient.Result(url, title, "", List.of(), blocks, List.of(),
                "", 200, "", "");
    }

    /** electron:// 内置页面的 HTML：Node 排版器负责渲染，入口与 Java 兜底保持一致。 */
    private String localPageHtml(String url, BrowserTab tab) {
        String title;
        StringBuilder body = new StringBuilder(1536);
        if (BROWSER_BOOKMARKS.equals(url)) {
            title = "书签管理器";
            body.append("<h1>书签管理器</h1><p class=\"muted\">共 ")
                    .append(this.bookmarks.size())
                    .append(" 个书签。在网页里点 ☆ 可收藏当前页面。</p>");
            if (this.bookmarks.isEmpty()) {
                body.append("<div class=\"card\"><p>还没有书签。</p></div>");
            }
            for (int i = 0; i < this.bookmarks.size(); i++) {
                BrowserBookmark mark = this.bookmarks.get(i);
                body.append("<div class=\"card\"><a href=\"").append(htmlEsc(mark.url()))
                        .append("\">").append(htmlEsc(mark.title())).append("</a>")
                        .append("<div class=\"small muted\">").append(htmlEsc(mark.url()))
                        .append("</div><a class=\"small\" href=\"electron://bookmark-del?i=")
                        .append(i).append("\">删除这个书签</a></div>");
            }
            body.append("<p class=\"actions\"><a href=\"").append(BROWSER_HOME)
                    .append("\">返回起始页</a></p>");
        } else if (BROWSER_HISTORY.equals(url)) {
            title = "历史记录";
            body.append("<h1>历史记录</h1><p class=\"muted\">共 ").append(this.visits.size())
                    .append(" 条访问记录。</p>");
            if (this.visits.isEmpty()) {
                body.append("<div class=\"card\"><p>还没有访问记录。</p></div>");
            }
            for (int i = 0; i < this.visits.size(); i++) {
                BrowserVisit visit = this.visits.get(i);
                String when = visit.at() <= 0 ? "" : BROWSER_STAMP.format(
                        Instant.ofEpochMilli(visit.at())) + "  ";
                body.append("<div class=\"card\"><a href=\"").append(htmlEsc(visit.url()))
                        .append("\">").append(htmlEsc(when + visit.title())).append("</a>")
                        .append("<div class=\"small muted\">").append(htmlEsc(visit.url()))
                        .append("</div><a class=\"small\" href=\"electron://history-del?i=")
                        .append(i).append("\">删除这条记录</a></div>");
            }
            if (!this.visits.isEmpty()) {
                body.append("<p><a href=\"electron://history-clear\">清空全部历史</a></p>");
            }
            body.append("<p class=\"actions\"><a href=\"").append(BROWSER_HOME)
                    .append("\">返回起始页</a></p>");
        } else if (BROWSER_DOWNLOADS.equals(url)) {
            title = "下载内容";
            body.append("<h1>下载内容</h1><p class=\"muted\">文件保存在 ")
                    .append(htmlEsc(BROWSER_DOWNLOAD_DIR)).append("，共 ")
                    .append(this.downloads.size()).append(" 条记录。</p>");
            if (this.downloads.isEmpty()) {
                body.append("<div class=\"card\"><p>还没有下载。</p></div>");
            }
            for (int i = 0; i < this.downloads.size(); i++) {
                BrowserDownload item = this.downloads.get(i);
                String when = item.at() <= 0 ? "" : BROWSER_STAMP.format(
                        Instant.ofEpochMilli(item.at())) + "  ";
                body.append("<div class=\"card\"><a href=\"file://")
                        .append(htmlEsc(item.path())).append("\">")
                        .append(htmlEsc(item.name())).append("</a>")
                        .append("<div class=\"small muted\">").append(htmlEsc(when))
                        .append(this.browserSize(item.bytes())).append("  ·  ")
                        .append(htmlEsc(item.path()))
                        .append("</div><a class=\"small\" href=\"electron://download-del?i=")
                        .append(i).append("\">删除这条记录</a></div>");
            }
            body.append("<p>");
            if (!this.downloads.isEmpty()) {
                body.append("<a href=\"electron://downloads-clear\">清空下载记录</a>  ");
            }
            body.append("<a href=\"file://").append(htmlEsc(BROWSER_DOWNLOAD_DIR))
                    .append("\">打开下载目录</a></p>");
            body.append("<p class=\"actions\"><a href=\"").append(BROWSER_HOME)
                    .append("\">返回起始页</a></p>");
        } else if (BROWSER_SETTINGS.equals(url)) {
            title = "浏览器设置";
            body.append("<h1>浏览器设置</h1>")
                    .append("<h2>搜索引擎</h2><p>当前：")
                    .append(htmlEsc(this.browserSettings.searchName())).append("</p>")
                    .append("<p><a href=\"electron://settings?search=bing\">必应</a>  ")
                    .append("<a href=\"electron://settings?search=google\">Google</a>  ")
                    .append("<a href=\"electron://settings?search=wiki\">维基百科</a></p>")
                    .append("<h2>主页</h2><p>当前：")
                    .append(htmlEsc(this.browserSettings.home)).append("</p>")
                    .append("<p><a href=\"electron://set-home?url=")
                    .append(URLEncoder.encode(tab.url, StandardCharsets.UTF_8))
                    .append("\">把当前地址设为主页</a>  ")
                    .append("<a href=\"electron://set-home?url=")
                    .append(URLEncoder.encode(BROWSER_HOME, StandardCharsets.UTF_8))
                    .append("\">主页设为起始页</a></p>")
                    .append("<h2>缩放</h2><p>")
                    .append("<a href=\"electron://zoom?z=0.75\">75%</a>  ")
                    .append("<a href=\"electron://zoom?z=1.0\">100%</a>  ")
                    .append("<a href=\"electron://zoom?z=1.25\">125%</a>  ")
                    .append("<a href=\"electron://zoom?z=1.5\">150%</a></p>")
                    .append("<h2>隐私</h2><p>已保存 ").append(this.browserCookieList().size())
                    .append(" 个 Cookie。</p>")
                    .append("<p><a href=\"electron://cookies-clear\">清空 Cookie</a></p>")
                    .append("<h2>内核</h2><p>")
                    .append(htmlEsc(WebKitEngine.available()
                            ? WebKitEngine.engine() : "Java 兜底"))
                    .append("</p><p class=\"actions\"><a href=\"").append(BROWSER_HOME)
                    .append("\">返回起始页</a></p>");
        } else if (BROWSER_ABOUT.equals(url)) {
            title = "关于浏览器";
            body.append("<h1>浏览器</h1>")
                    .append("<p class=\"muted\">哈基坤 OS 内置浏览器 · WebKit 内核</p>")
                    .append("<h2>能做什么</h2>")
                    .append("<p>地址栏输入网址或中文关键词，回车即打开；← → 前进后退，")
                    .append("⟳ 重新加载，⌂ 回到主页。网页由进程内的 WebKit 渲染：")
                    .append("HTML/CSS、图片、音视频、表单、弹窗、扫码登录与输入法都可用。</p>")
                    .append("<p>右键空白处可以刷新、复制网址、切换缩放或管理标签页；")
                    .append("⌕ 页内查找，A 阅读模式，★ 收藏当前页面。</p>")
                    .append("<h2>登录与 Cookie</h2><p>每台电脑有独立的 Cookie 会话，保存在 ")
                    .append(htmlEsc(BROWSER_COOKIE_FILE))
                    .append("，换一台电脑不会共享登录状态。</p>")
                    .append("<p class=\"actions\"><a href=\"").append(BROWSER_HOME)
                    .append("\">返回起始页</a></p>");
        } else if (BROWSER_WIKI.equals(url)) {
            title = "哈基坤 Wiki";
            body.append("<h1>哈基坤 Wiki</h1>")
                    .append("<p>这台电脑运行哈基坤 OS。文件系统、终端、智能体 IDE 与浏览器")
                    .append("都在同一个虚拟机里工作，数据随方块本体保存。</p>")
                    .append("<h2>目录结构</h2>")
                    .append("<p>/home/user 是用户目录，文档、图片与 IDE 会话都在这里；")
                    .append("/系统 中的文件受保护，不能删除或重命名。</p>")
                    .append("<p><a href=\"file:///home/user\">浏览 /home/user</a>  ")
                    .append("<a href=\"file:///%E7%B3%BB%E7%BB%9F\">浏览 /系统</a></p>")
                    .append("<h2>网络</h2>")
                    .append("<p>浏览器使用真实的 HTTP 连接。没有网络时页面会显示")
                    .append("「无法访问此网站」，并保留地址方便重试。</p>")
                    .append("<p><a href=\"").append(BROWSER_ABOUT)
                    .append("\">浏览器说明</a>  <a href=\"").append(BROWSER_HOME)
                    .append("\">返回起始页</a></p>");
        } else {
            title = "起始页";
            body.append("<div class=\"hero\"><h1>浏览器</h1>")
                    .append("<p class=\"muted\">哈基坤 OS 内置浏览器 · 离线起始页</p></div>")
                    .append("<form action=\"electron://search\" method=\"get\">")
                    .append("<input class=\"field\" name=\"q\" placeholder=\"搜索或输入网址\"> ")
                    .append("<button>搜索</button></form>")
                    .append("<h2>快捷入口</h2>")
                    .append("<p><a href=\"electron://wiki\">哈基坤 Wiki</a>  ")
                    .append("<a href=\"https://www.bing.com\">必应</a>  ")
                    .append("<a href=\"https://zh.wikipedia.org\">维基百科</a></p>")
                    .append("<p><a href=\"file:///home/user\">本地文件 /home/user</a>  ")
                    .append("<a href=\"").append(BROWSER_BOOKMARKS).append("\">书签</a>  ")
                    .append("<a href=\"").append(BROWSER_HISTORY).append("\">历史</a>  ")
                    .append("<a href=\"").append(BROWSER_DOWNLOADS).append("\">下载</a>  ")
                    .append("<a href=\"").append(BROWSER_SETTINGS).append("\">设置</a></p>");
            if (!this.visits.isEmpty()) {
                body.append("<h2>最近访问</h2>");
                for (int i = 0; i < this.visits.size() && i < 6; i++) {
                    BrowserVisit visit = this.visits.get(i);
                    body.append("<div class=\"card\"><a href=\"")
                            .append(htmlEsc(visit.url())).append("\">")
                            .append(htmlEsc(visit.title())).append("</a>")
                            .append("<div class=\"small muted\">")
                            .append(htmlEsc(visit.url())).append("</div></div>");
                }
            }
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + htmlEsc(title)
                + "</title><style>" + BROWSER_PAGE_CSS
                + "</style></head><body><div class=\"wrap\">" + body
                + "</div></body></html>";
    }

    private static String htmlEsc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private String browserSize(long bytes) {
        if (bytes <= 0L) {
            return "未知大小";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** file:// 页面：目录可继续点，文本直接排版，图片用虚拟机媒体缓存显示。 */
    private BrowserClient.Result filePage(String url) {
        String raw = url.length() > 7 ? url.substring(7) : "/";
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            decoded = raw;
        }
        String path = this.normalizeVmPath("/", decoded);
        if (path == null) {
            path = "/";
        }
        VirtualFileSystem.Node node = this.device.fs.find(path);
        List<BrowserClient.Block> blocks = new ArrayList<>();
        String title;
        if (node == null) {
            title = "找不到页面";
            blocks.add(bHeading(1, "找不到页面"));
            blocks.add(bText("虚拟机里没有这个路径：" + path));
            blocks.add(bLink("返回 /home/user", "file:///home/user"));
            return new BrowserClient.Result(url, title, "", List.of(), blocks, List.of(),
                    "", 200, "", "");
        }
        if (node.isDir()) {
            title = path.equals("/") ? "根目录" : VirtualFileSystem.fileName(path);
            blocks.add(bHeading(1, "目录 " + path));
            if (!path.equals("/")) {
                blocks.add(bLink("↰ 上级目录", "file://" + VirtualFileSystem.parent(path)));
            }
            int count = 0;
            for (VirtualFileSystem.Node child : node.children.values()) {
                String childPath = (path.equals("/") ? "" : path) + "/" + child.name;
                String label = (child.isDir() ? "[目录] " : "[文件] ") + child.name;
                blocks.add(bLink(label, "file://" + childPath));
                count++;
            }
            if (count == 0) {
                blocks.add(bText("（空目录）"));
            } else {
                blocks.add(bText("共 " + count + " 项。"));
            }
            blocks.add(bLink("返回起始页", BROWSER_HOME));
        } else if (node.isBinary()) {
            title = node.name;
            String mime = node.mime == null ? "" : node.mime;
            blocks.add(bHeading(1, node.name));
            blocks.add(bText("类型 " + (mime.isBlank() ? "未知" : mime)
                    + " · " + this.mediaSize(MediaCache.blob(this.pos, path))));
            if (mime.startsWith("image/")) {
                blocks.add(new BrowserClient.Block(BrowserClient.Kind.IMAGE, node.name, "",
                        "vm:" + path + "|" + (node.media == null ? "" : node.media),
                        0, "", "", "", ""));
            } else {
                blocks.add(bText("这个文件不是网页，双击文件管理器里的同名文件可以用查看器打开。"));
            }
            blocks.add(bLink("返回上级目录", "file://" + VirtualFileSystem.parent(path)));
        } else {
            title = node.name;
            blocks.add(bHeading(1, path));
            String content = node.content == null ? "" : node.content;
            if (content.length() > 20000) {
                content = content.substring(0, 20000) + "\n…（文件过长，已截断）";
            }
            blocks.add(bText(content.isBlank() ? "（空文件）" : content));
            blocks.add(bText("大小 " + node.size + " 字节"));
            blocks.add(bLink("返回上级目录", "file://" + VirtualFileSystem.parent(path)));
        }
        return new BrowserClient.Result(url, title, "", List.of(), blocks, List.of(),
                "", 200, "", "");
    }

    private BrowserClient.Result errorPage(String url, String error) {
        List<BrowserClient.Block> blocks = new ArrayList<>();
        blocks.add(bHeading(1, "无法访问此网站"));
        blocks.add(bText(url.isBlank() ? "地址为空。" : url));
        blocks.add(bText(error));
        blocks.add(bLink("重试", url.isBlank() ? BROWSER_HOME : url));
        blocks.add(bLink("返回起始页", BROWSER_HOME));
        return new BrowserClient.Result(url, "无法访问", "", List.of(), blocks, List.of(),
                error, 0, "", "");
    }

    private static BrowserClient.Block bHeading(int level, String text) {
        return new BrowserClient.Block(BrowserClient.Kind.HEADING, text, "", "", level,
                "", "", "", "");
    }

    private static BrowserClient.Block bText(String text) {
        return new BrowserClient.Block(BrowserClient.Kind.TEXT, text, "", "", 0,
                "", "", "", "");
    }

    private static BrowserClient.Block bLink(String text, String href) {
        return new BrowserClient.Block(BrowserClient.Kind.LINK, text, href, "", 0,
                "", "", "", "");
    }

    private String browserHost(String url) {
        String value = url == null ? "" : url;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        return slash > 0 ? value.substring(0, slash) : value;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** 把编辑结果写回当前字段；所有输入路径都走这里，字段与光标不会脱节。 */
    private void applyEdit() {
        if (this.focus == Focus.SETUP) {
            this.setupText = this.edit.text;
            return;
        }
        if (this.focusWin == null) {
            return;
        }
        switch (this.focus) {
            case TERMINAL, RENAME -> this.focusWin.input = this.edit.text;
            case FILE_NAME -> this.focusWin.renameText = this.edit.text;
            case EDITOR -> {
                this.focusWin.content = this.edit.text;
                this.focusWin.input = this.edit.text;
            }
            case ADDRESS_FILES, ADDRESS_P2V -> this.focusWin.addressText = this.edit.text;
            case ADDRESS_BROWSER -> {
                BrowserTab tab = this.focusWin.tab();
                this.focusWin.addressText = this.edit.text;
                if (this.focusWin.browserField.isBlank()) {
                    tab.address = this.edit.text;
                } else {
                    tab.fields.put(this.focusWin.browserField, this.edit.text);
                }
            }
            case FIND -> this.focusWin.tab().findQuery = this.edit.text;
            default -> {
            }
        }
    }

    private void copySelection() {
        if (this.edit.hasSelection()) {
            setClipboard(this.edit.selection());
        }
    }

    private void cutSelection() {
        if (this.edit.hasSelection()) {
            setClipboard(this.edit.selection());
            this.edit.replaceSelection("", 0);
            this.applyEdit();
        }
    }

    private void pasteClipboard() {
        String text = clipboard();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (this.focus != Focus.EDITOR) {
            text = TextEdit.singleLine(text);
        }
        this.edit.insert(text, this.editLimit());
        this.applyEdit();
    }

    public boolean keyPressed(int key, boolean shift, boolean ctrl) {
        BrowserTab chromium = this.chromiumTypingTab();
        if (chromium != null && this.chromiumKey(chromium, key, shift, ctrl)) {
            return true;
        }
        IdeApp ide = this.ideTarget();
        if (ide != null) {
            return ide.keyPressed(key, shift, ctrl);
        }
        if (this.editingText()) {
            if (ctrl && key == InputConstants.KEY_A) {
                this.edit.selectAll();
                return true;
            }
            if (ctrl && key == InputConstants.KEY_C) {
                this.copySelection();
                return true;
            }
            if (ctrl && key == InputConstants.KEY_X) {
                this.cutSelection();
                return true;
            }
            if (ctrl && key == InputConstants.KEY_V) {
                this.pasteClipboard();
                return true;
            }
            if (key == InputConstants.KEY_LEFT) {
                if (ctrl) {
                    this.edit.wordMove(-1, shift);
                } else {
                    this.edit.move(-1, shift);
                }
                return true;
            }
            if (key == InputConstants.KEY_RIGHT) {
                if (ctrl) {
                    this.edit.wordMove(1, shift);
                } else {
                    this.edit.move(1, shift);
                }
                return true;
            }
            if (key == InputConstants.KEY_HOME) {
                this.edit.home(shift);
                return true;
            }
            if (key == InputConstants.KEY_END) {
                this.edit.lineEnd(shift);
                return true;
            }
            if (this.focus == Focus.EDITOR
                    && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
                this.edit.moveLine(key == InputConstants.KEY_UP ? -1 : 1, shift);
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                this.edit.backspace();
                this.applyEdit();
                return true;
            }
            if (key == InputConstants.KEY_DELETE) {
                this.edit.deleteForward();
                this.applyEdit();
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                switch (this.focus) {
                    case SETUP -> this.saveSetup();
                    case TERMINAL -> this.runCommand(this.focusWin);
                    case RENAME -> HajikunSmartClient.request(this.pos, "rename", this.focusWin.input);
                    case FILE_NAME -> this.requestRename(this.focusWin);
                    case EDITOR -> {
                        this.edit.insert("\n", 0);
                        this.applyEdit();
                    }
                    case ADDRESS_FILES -> this.navigateFiles(this.focusWin, this.edit.text);
                    case ADDRESS_P2V -> this.navigateHost(this.focusWin, this.edit.text);
                    case ADDRESS_BROWSER -> this.submitBrowserEdit(this.focusWin);
                    default -> {
                    }
                }
                return true;
            }
            if (key == InputConstants.KEY_TAB) {
                return true;
            }
            return false;
        }
        return false;
    }

    public boolean charTyped(int codePoint) {
        BrowserTab chromium = this.chromiumTypingTab();
        if (chromium != null) {
            if (System.currentTimeMillis() < this.chromiumGuardUntil) {
                return true;
            }
            if (codePoint >= 32) {
                WebKitEngine.text(chromium.chromium, new String(Character.toChars(codePoint)));
            }
            return true;
        }
        IdeApp ide = this.ideTarget();
        if (ide != null) {
            return ide.charTyped(codePoint);
        }
        if (codePoint < 32 || !this.editingText()) {
            return false;
        }
        String text = new String(Character.toChars(codePoint));
        if (this.focus != Focus.EDITOR) {
            text = TextEdit.singleLine(text);
        }
        this.edit.insert(text, this.editLimit());
        this.applyEdit();
        return true;
    }

    /** 文件管理器地址栏：支持绝对路径、~ 与相对当前目录。 */
    private void navigateFiles(Win win, String typed) {
        String target = this.normalizeVmPath(win.path, typed);
        if (target == null) {
            win.status = "路径无效：" + typed;
            this.toast(win.status);
            return;
        }
        VirtualFileSystem.Node node = this.device.fs.find(target);
        if (node == null || !node.isDir()) {
            win.status = "目录不存在：" + target;
            this.toast(win.status);
            return;
        }
        win.path = target;
        win.scroll = 0;
        win.selected = "";
        this.focus = Focus.NONE;
        this.focusWin = null;
        win.status = "已定位到 " + target;
    }

    /** P2V 地址栏：只读浏览宿主机目录，支持 ~、环境变量与盘符。 */
    private void navigateHost(Win win, String typed) {
        String value = typed == null ? "" : typed.trim();
        if (value.isEmpty()) {
            win.hostPath = "";
            win.hostLoaded = false;
            win.p2vStatus = "已列出物理机磁盘。";
            this.focus = Focus.NONE;
            this.focusWin = null;
            return;
        }
        File directory = new File(this.normalizeHostPath(value));
        if (!directory.isDirectory()) {
            win.p2vStatus = "物理机目录不存在：" + value;
            this.toast(win.p2vStatus);
            return;
        }
        win.hostPath = directory.getAbsolutePath();
        win.hostLoaded = false;
        win.p2vStatus = "已定位到 " + win.hostPath;
        this.focus = Focus.NONE;
        this.focusWin = null;
    }

    private String normalizeVmPath(String current, String typed) {
        if (typed == null) {
            return null;
        }
        String value = typed.trim().replace('\\', '/');
        if (value.isEmpty()) {
            return null;
        }
        if (value.equals("~")) {
            return "/home/user";
        }
        if (value.startsWith("~/")) {
            value = "/home/user/" + value.substring(2);
        }
        if (!value.startsWith("/")) {
            String base = current == null || current.isBlank() ? "/home/user" : current;
            value = (base.equals("/") ? "" : base) + "/" + value;
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(part);
        }
        return "/" + String.join("/", parts);
    }

    private String normalizeHostPath(String typed) {
        String value = typed.trim();
        if (value.equals("~")) {
            return System.getProperty("user.home", value);
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return System.getProperty("user.home", "") + value.substring(1);
        }
        int percent = value.indexOf('%');
        while (percent >= 0) {
            int close = value.indexOf('%', percent + 1);
            if (close < 0) {
                break;
            }
            String name = value.substring(percent + 1, close);
            String env = System.getenv(name);
            if (env != null) {
                value = value.substring(0, percent) + env + value.substring(close + 1);
                percent = value.indexOf('%', percent + env.length());
            } else {
                percent = value.indexOf('%', close + 1);
            }
        }
        return value;
    }

    private void runCommand(Win win) {
        String command = win.input.trim();
        if (command.isEmpty()) {
            return;
        }
        win.log.add(this.prompt() + command);
        win.input = "";
        this.edit.clear();
        this.pendingShell = win;
        HajikunSmartClient.request(this.pos, "shell", command);
    }

    private void openApp(App app, boolean focusWindow) {
        for (Win win : this.windows) {
            if (win.app == app) {
                // 最小化的窗口从任务栏或图标再次打开时先还原，而不是只置顶。
                win.minimized = false;
                this.windows.remove(win);
                this.windows.add(win);
                if (focusWindow) {
                    this.focus = Focus.NONE;
                    this.focusWin = null;
                }
                this.menu = null;
                return;
            }
        }
        this.windows.add(this.createWindow(app));
        if (focusWindow) {
            this.focus = Focus.NONE;
            this.focusWin = null;
        }
        this.menu = null;
    }

    /** 新建一个窗口但不入栈；调用方负责加入窗口列表。 */
    private Win createWindow(App app) {
        int[] size = switch (app) {
            case FILES -> new int[]{230, 140};
            case TERMINAL -> new int[]{244, 152};
            case P2V -> new int[]{300, 190};
            case EDITOR -> new int[]{232, 150};
            case SETTINGS -> new int[]{236, 148};
            case BLUETOOTH -> new int[]{252, 176};
            case VIEWER -> new int[]{250, 170};
            // 浏览器默认给一个够看网页的尺寸，同时完整落在 320×247 的虚拟机画布内，
            // 标题栏的「一口乂」与地址栏「前往」都不能被屏幕边缘裁掉。
            case BROWSER -> new int[]{312, 224};
            // IDE 铺满任务栏以上，像真正的开发机一样独占桌面。
            case JAVAIDE -> new int[]{this.width(), this.height() - TASKBAR};
        };
        int offset = (this.appSeq++ % 4) * 7;
        int wx = Math.max(0, Math.min(16 + offset, this.width() - size[0] - 4));
        int wy = Math.max(0, Math.min(10 + offset, this.height() - TASKBAR - size[1] - 2));
        Win win = new Win(app, wx, wy, size[0], size[1]);
        switch (app) {
            case FILES -> {
                win.path = this.device.cwd;
                win.title = "我的电脑";
            }
            case TERMINAL -> {
                win.title = "命令提示符";
                win.log.add("哈基坤 OS 命令提示符 · 输入 help 查看命令");
            }
            case P2V -> {
                win.title = "P2V协议";
                win.hostPath = System.getProperty("user.home", "");
                win.hostLoaded = false;
                win.p2vStatus = "浏览物理机目录，勾选文件后点「传入」。";
            }
            case EDITOR -> {
                win.title = "记事本";
                win.savedPath = "/home/user/文档/无标题.txt";
                win.content = "";
            }
            case SETTINGS -> {
                win.title = "控制面板";
                win.input = this.device.name;
            }
            case BLUETOOTH -> {
                win.title = "蓝牙设备";
                win.status = "";
            }
            case BROWSER -> {
                win.title = "浏览器";
                this.loadBrowserStore();
                // Cookie 跟着这台电脑的磁盘走：换一台电脑登录状态互不影响。
                VirtualFileSystem.Node cookies = this.device.fs.find(BROWSER_COOKIE_FILE);
                if (cookies != null && !cookies.isDir()) {
                    BrowserClient.loadProfile(this.pos, cookies.content);
                }
                BrowserTab tab = new BrowserTab();
                String home = this.browserSettings.home == null || this.browserSettings.home.isBlank()
                        ? BROWSER_HOME : this.browserSettings.home;
                tab.url = home;
                tab.address = home;
                tab.pageZoom = this.browserSettings.zoom;
                tab.history.add(home);
                tab.historyAt = 0;
                win.tabs.add(tab);
                win.activeTab = 0;
                win.addressText = tab.address;
                // 开机直接排版主页；Node 引擎不可用时退回 Java 块排版。
                this.focus = Focus.NONE;
                this.focusWin = win;
                this.browserGo(win, tab, home, false);
            }
            case JAVAIDE -> {
                win.title = "智能体 IDE";
                if (this.ideSession == null) {
                    this.ideSession = new IdeApp(this.pos, this.device);
                }
                win.ide = this.ideSession;
                win.ide.setCloseListener(() -> this.closeWindow(win));
                // 和桌面系统一样：IDE 默认最大化，点「□」还原成一扇带边框的窗口。
                win.restoreX = Math.max(0, (this.width() - Math.max(240, this.width() - 80)) / 2);
                win.restoreY = 10;
                win.restoreW = Math.max(240, this.width() - 80);
                win.restoreH = Math.max(150, this.height() - TASKBAR - 46);
                win.maximized = true;
                win.x = 0;
                win.y = 0;
                win.w = this.width();
                win.h = this.height() - TASKBAR;
            }
            default -> {
            }
        }
        return win;
    }

    /** 浏览器页面里的一个点击热区：整页渲染时收集，点击时按坐标命中。 */
    private record BrowserHit(int x, int y, int width, int height, String action,
                              String value, String extra) {
    }

    /** Chromium 一帧画面贴成的动态纹理；尺寸变化时整体重建。 */
    private record ChromiumTexture(Identifier id, DynamicTexture texture,
                                   int width, int height) {
    }

    /** 每个标签页在 Node 里独占一个会话，避免后台标签页互相覆盖最近一次排版。 */
    private static final AtomicLong BROWSER_SESSIONS = new AtomicLong(1L);

    /** Electron 的一个标签页：地址、历史、加载结果、表单值与页面热区。 */
    private static final class BrowserTab {
        private final long session = BROWSER_SESSIONS.getAndIncrement();
        private String url = BROWSER_HOME;
        private String title = "新标签页";
        private String address = BROWSER_HOME;
        private final List<String> history = new ArrayList<>();
        private int historyAt = -1;
        private int scroll;
        private int maxScroll;
        private boolean loading;
        private long startedAt;
        private String error = "";
        /** Node 引擎的显示列表；为空时退回 Java 的块排版。 */
        private ElectronEngine.Page enginePage;
        private BrowserClient.Result page;
        /** 上次排版使用的宽度、缩放与阅读模式，用来判断是否需要重排。 */
        private int layoutWidth;
        private float pageZoom = 1.0F;
        private boolean reader;
        private boolean relayouting;
        private final List<ElectronEngine.Match> matches = new ArrayList<>();
        private int matchAt = -1;
        private int matchShown = -2;
        private String findQuery = "";
        private String lastFindQuery = "";
        private long lastFindAt;
        private boolean findOpen;
        /** 表单字段的当前值；键是 HTML 里的 name。 */
        private final Map<String, String> fields = new HashMap<>();
        /** 复选框的勾选状态；只有勾选时才随表单提交。 */
        private final Map<String, Boolean> checkState = new HashMap<>();
        /** 下载等临时提示，显示在状态行里。 */
        private String notice = "";
        private long noticeUntil;
        /** 每次渲染重新收集的页面热区，保证布局与点击共用同一套坐标。 */
        private final List<BrowserHit> hits = new ArrayList<>();
        /** Chromium 标签页编号；-1 表示这一页仍由 Java 绘制。 */
        private int chromium = -1;
        /** 当前页是否由 Chromium 渲染（内置页与 http(s) 页面为 true）。 */
        private boolean chromiumPage;
        /** 内置页 URL 由 Java 决定，Chromium 的 data: 地址不覆盖地址栏。 */
        private boolean chromiumInternal;
        /** 网页获得键盘焦点后，按键与字符都转发给 Chromium。 */
        private boolean chromiumTyping;
        /** 上一帧页面区域（虚拟机坐标），鼠标换算成网页坐标时使用。 */
        private int pageX;
        private int pageY;
        private int pageW;
        private int pageH;
        /** Chromium 页内查找的命中数量与当前序号。 */
        private int findMatches;
        private int findActive;
        private long chromiumClickAt;
        private int chromiumClickX = -999;
        private int chromiumClickY = -999;
    }

    /** 书签、历史与下载条目。 */
    private record BrowserBookmark(String title, String url) {
    }

    private record BrowserVisit(String title, String url, long at) {
    }

    private record BrowserDownload(String name, String url, String path, long at, long bytes) {
    }

    /** 浏览器设置：搜索引擎、主页与默认缩放。 */
    private static final class BrowserSettings {
        private String search = "bing";
        private String home = BROWSER_HOME;
        private float zoom = 1.0F;

        private String searchUrl(String query) {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            return switch (this.search) {
                case "google" -> "https://www.google.com/search?q=" + encoded;
                case "wiki" -> "https://zh.wikipedia.org/w/index.php?search=" + encoded;
                default -> "https://www.bing.com/search?q=" + encoded;
            };
        }

        private String searchName() {
            return switch (this.search) {
                case "google" -> "Google";
                case "wiki" -> "维基百科";
                default -> "必应";
            };
        }
    }

    /** 一个应用窗口；界面状态都保存在这里，虚拟机数据仍然只在服务端。 */
    private static final class Win {
        private final App app;
        private int x;
        private int y;
        private int w;
        private int h;
        /** 最小化后不参与绘制与命中，任务栏按钮仍保留，点一下就恢复。 */
        private boolean minimized;
        /** 最大化时铺满任务栏以上的桌面；还原回到下面记录的位置与尺寸。 */
        private boolean maximized;
        private int restoreX;
        private int restoreY;
        private int restoreW;
        private int restoreH;
        private String title = "";
        private final List<String> log = new ArrayList<>();
        private String input = "";
        private String path = "/home/user";
        private String status = "";
        private String savedPath = "/home/user/文档/无标题.txt";
        private String content = "";
        private int scroll;
        private boolean loading;
        /** 文件管理器：选中项、双击判定、右键菜单与删除/重命名弹窗。 */
        private String selected = "";
        private String lastClickName = "";
        private long lastClickAt;
        private String menuName = "";
        private int menuX;
        private int menuY;
        private boolean naming;
        private String renameText = "";
        private String confirmDelete = "";
        /** 蓝牙：选中设备、文件选择器路径与状态。 */
        private String btMac = "";
        private boolean picking;
        private String pickPath = "/home/user";
        /** P2V：物理机当前目录、条目缓存、勾选集合与双击判定。 */
        private String hostPath = "";
        /** 文件管理器 / P2V 地址栏正在编辑的文本。 */
        private String addressText = "";
        private List<P2VImport.HostEntry> hostEntries = new ArrayList<>();
        private final Set<String> hostPicked = new HashSet<>();
        private boolean hostLoaded;
        private int hostMaxScroll;
        private String hostLastClick = "";
        private long hostLastAt;
        private String p2vStatus = "";
        /** 传入目标目录；默认是导入目录，从文件管理器右键进来时改成当前目录。 */
        private String p2vDir = P2V_DIR;
        /** 媒体查看器：当前文件、缓存散列与录像播放器。 */
        private String mediaPath = "";
        private String mediaName = "";
        private String mediaMime = "";
        private String mediaHash = "";
        private VideoPlayer player;
        /** 媒体查看器：图片缩放/平移、播放倍速与音频续播位置。 */
        private double zoom = 1.0D;
        private int panX;
        private int panY;
        private float speed = 1.0F;
        private long audioPosMs;
        private long audioDurationMs = -1L;
        /** JavaIDE 的会话状态；窗口关闭或电脑关机时释放。 */
        private IdeApp ide;
        /** Electron 浏览器：标签页与当前标签。 */
        private final List<BrowserTab> tabs = new ArrayList<>();
        private int activeTab;
        /** 正在编辑的网页表单字段名；空表示地址栏。 */
        private String browserField = "";
        private String browserFieldForm = "";
        /** 当前文本框的左侧像素，拖选时用来把鼠标换算成字符索引。 */
        private int browserFieldLeft;

        private BrowserTab tab() {
            if (this.tabs.isEmpty()) {
                BrowserTab fresh = new BrowserTab();
                fresh.history.add(fresh.url);
                fresh.historyAt = 0;
                this.tabs.add(fresh);
                this.activeTab = 0;
            }
            this.activeTab = Math.max(0, Math.min(this.activeTab, this.tabs.size() - 1));
            return this.tabs.get(this.activeTab);
        }

        private Win(App app, int x, int y, int w, int h) {
            this.app = app;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}
