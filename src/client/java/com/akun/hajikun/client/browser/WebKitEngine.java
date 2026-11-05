package com.akun.hajikun.client.browser;

import com.akun.hajikun.HajikunSmartMod;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import netscape.javascript.JSObject;
import org.lwjgl.sdl.SDLVideo;

/**
 * 虚拟机内置浏览器内核：JavaFX WebKit 直接在游戏进程里渲染网页，离屏窗口截图后
 * 作为纹理贴到虚拟机屏幕上。
 *
 * <p>整个链路都在 JVM 内部完成：WebKit 排版、解码、音视频播放，Java 只负责把
 * 画面搬进纹理并转发鼠标键盘；不会启动 electron.exe、node.exe 或任何外部窗口。
 * 网页内容因此真正显示在虚拟机的显示器上，而不是宿主机的弹窗。</p>
 *
 * <p>线程约定：{@link WebView}/{@link WebEngine} 只能在 JavaFX 线程访问，所有
 * 指令都通过 {@link #runFx(Runnable)} 排队；游戏线程只读 volatile 的快照与状态。</p>
 */
@SuppressWarnings("removal")
public final class WebKitEngine {
    /** 离屏舞台尺寸；比任何虚拟机网页区域都大，页面按自身尺寸摆放。 */
    private static final int STAGE_WIDTH = 1600;
    private static final int STAGE_HEIGHT = 1000;
    /**
     * 网页按几倍逻辑尺寸排版。聚焦时 320 像素宽的虚拟机屏幕约占 1300 物理像素，
     * 网页区域约 298×190，乘 4 后正好每个 CSS 像素落在约一个屏幕像素上：
     * 文字按桌面浏览器的大小排版，放大投屏时也不会糊成马赛克。
     */
    private static final int RENDER_SCALE = 4;
    /** 页面在动（加载、输入、动画、视频）时的抓帧节拍：约 30 帧每秒。 */
    private static final long SNAPSHOT_INTERVAL_MS = 33L;
    /** 静止页面的巡检节拍：只为接住闪烁光标这类低频变化，几乎不占 CPU。 */
    private static final long IDLE_SNAPSHOT_INTERVAL_MS = 400L;
    /** 一次输入或页面变动之后保持高帧率的时间，覆盖过渡动画与惯性滚动。 */
    private static final long ACTIVE_WINDOW_MS = 900L;
    /** 超过这个时间没被绘制，说明窗口被最小化或挡住了，暂停抓帧。 */
    private static final long HIDDEN_AFTER_MS = 1500L;
    /**
     * 单次抓帧的时间预算。
     *
     * <p>抓帧是 WebKit 在 JavaFX 线程上做离屏渲染，再读回像素并转成 ABGR；
     * 它和游戏渲染抢同一颗 CPU，页面一复杂就会拖慢游戏帧。实测超过这个预算时，
     * 按超出量把节拍拉长（最多 {@link #SLOWEST_INTERVAL_MS}），先保住游戏的流畅，
     * 页面动画慢几帧肉眼几乎看不出来。</p>
     */
    private static final long CAPTURE_BUDGET_MS = 18L;
    private static final long SLOWEST_INTERVAL_MS = 66L;

    private static final Map<Integer, Tab> TABS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_TAB = new AtomicInteger(1);
    private static final AtomicLong FRAME_SEQ = new AtomicLong(1L);
    private static final ConcurrentLinkedQueue<Runnable> FX_QUEUE = new ConcurrentLinkedQueue<>();

    private static volatile boolean started;
    private static volatile boolean ready;
    private static volatile boolean fxThreadKnown;
    private static volatile String failure = "";
    private static volatile String engineName = "WebKit";
    private static volatile String profileDir = "";
    private static volatile String lastLog = "";

    /** 以下只在 JavaFX 线程里使用。 */
    private static Stage stage;
    private static Pane root;
    private static javafx.animation.AnimationTimer timer;

    private static CookieHandler previousCookieHandler;
    private static FileCookieStore cookieStore;

    private WebKitEngine() {
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 一帧网页画面，像素为 NativeImage 小端上的 ABGR 整数。
     *
     * <p>{@code pixels} 归内核所有并循环复用：界面必须在本帧内把它写进纹理，
     * 不能留存引用。这样链路上没有每帧新建的本地内存，渲染线程也不用再为
     * 3.6 MB 的新页做缺页处理，动画与视频期间的抖动随之消失。</p>
     */
    public record Frame(int[] pixels, int width, int height, long seq) {
    }

    /** 浏览器状态；字段含义与旧的 Chromium 后端保持一致。 */
    public record State(String url, String title, boolean loading, boolean canBack,
                        boolean canForward, boolean muted, float zoom, boolean internal,
                        String error) {
        static final State EMPTY = new State("", "", false, false, false, false, 1.0F,
                false, "");
    }

    /** 内核事件；{@code kind} 为 nav / history / open / download / find / log。 */
    public record Event(String kind, int tab, JsonObject data) {
    }

    private static final class Tab {
        final int id;
        volatile Consumer<Event> owner;
        volatile int width = 320;
        volatile int height = 180;
        volatile float zoom = 1.0F;
        volatile boolean closed;
        volatile boolean muted;
        volatile boolean internal;
        volatile String displayUrl = "";
        volatile String title = "";
        volatile boolean loading;
        volatile String error = "";
        volatile boolean captureWanted;
        volatile long lastCapture;
        /** 页面最近一次“可能有变化”的时刻：输入、导航、DOM 变动、动画与视频。 */
        volatile long paintAt;
        /** 界面最近一次真正把它画到屏幕上的时刻；窗口不可见时停止抓帧。 */
        volatile long visibleAt;
        volatile State state = State.EMPTY;
        volatile Frame frame;

        /** 以下只在 JavaFX 线程里读写。 */
        WebView view;
        WebEngine engine;
        JSObject windowJs;
        WritableImage image;
        int[] pixels;
        /** 本帧写入的 ABGR 缓冲。 */
        int[] abgr;
        /** 上一次交出去的 ABGR 缓冲；既用来比对静止画面，也在下次抓帧时轮换成写入缓冲。 */
        int[] handed;
        boolean hasPending;
        boolean pendingInternal;
        String pendingAddress = "";
        String pendingUrl = "";
        String pendingHtml;
        /** 上一次抓帧的耗时（毫秒），用来判断是否需要降频。只在 JavaFX 线程读写。 */
        long captureCostMs;
        /** 复用的截图参数，省掉每帧一次小对象分配。 */
        javafx.scene.SnapshotParameters params;
        final List<String> history = new ArrayList<>();
        int historyAt = -1;
        String findQuery = "";
        int findActive;

        Tab(int id) {
            this.id = id;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    public static boolean available() {
        return ready;
    }

    public static boolean booting() {
        return started && !ready && failure.isEmpty();
    }

    public static String failure() {
        return failure;
    }

    public static String engine() {
        return engineName;
    }

    public static String profileDir() {
        return profileDir;
    }

    /**
     * 启动 JavaFX 工具包；第一次调用后立即返回，后续调用不会重复初始化。
     *
     * @return JavaFX 类是否存在；工具包本身异步初始化
     */
    public static synchronized boolean start() {
        if (ready || started) {
            return true;
        }
        try {
            Class.forName("javafx.application.Platform", false, WebKitEngine.class.getClassLoader());
        } catch (Throwable missing) {
            failure = "缺少 JavaFX WebKit 运行时：" + describe(missing);
            HajikunSmartMod.LOGGER.warn("WebKit 不可用：{}", failure);
            return false;
        }
        started = true;
        failure = "";
        engineName = "WebKit (JavaFX " + System.getProperty("javafx.runtime.version", "25") + ")";
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("hajikun/webkit");
        profileDir = dir.toString();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        installCookies(dir);
        // 软件渲染：不和游戏抢 OpenGL 上下文，虚拟机里的网页也不需要 GPU。
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.verbose", "false");
        Thread boot = new Thread(WebKitEngine::bootToolkit, "hajikun-webkit-boot");
        boot.setDaemon(true);
        boot.start();
        return true;
    }

    private static void bootToolkit() {
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                fxThreadKnown = true;
                try {
                    initFx();
                } catch (Throwable throwable) {
                    failure = "JavaFX 初始化失败：" + describe(throwable);
                    HajikunSmartMod.LOGGER.warn("WebKit 初始化失败", throwable);
                }
                ready = true;
                drainFxQueue();
            });
        } catch (IllegalStateException alreadyStarted) {
            // 别的代码先启动了 JavaFX：直接在 FX 线程补初始化。
            Platform.runLater(() -> {
                try {
                    if (stage == null) {
                        initFx();
                    }
                } catch (Throwable throwable) {
                    failure = "JavaFX 初始化失败：" + describe(throwable);
                }
                ready = true;
                drainFxQueue();
            });
        } catch (Throwable throwable) {
            failure = "JavaFX 启动失败：" + describe(throwable);
            started = false;
            HajikunSmartMod.LOGGER.warn("WebKit 启动失败", throwable);
        }
    }

    private static void initFx() {
        root = new Pane();
        root.setStyle("-fx-background-color: #ffffff;");
        Scene scene = new Scene(root, STAGE_WIDTH, STAGE_HEIGHT, Color.WHITE);
        Stage offscreen = new Stage(StageStyle.UTILITY);
        offscreen.setScene(scene);
        offscreen.setTitle("hajikun-webkit");
        offscreen.setResizable(false);
        offscreen.setX(-20000);
        offscreen.setY(-20000);
        offscreen.setWidth(STAGE_WIDTH);
        offscreen.setHeight(STAGE_HEIGHT);
        offscreen.setAlwaysOnTop(false);
        offscreen.setOnCloseRequest(event -> event.consume());
        offscreen.show();
        stage = offscreen;
        timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                captureTick();
            }
        };
        timer.start();
        HajikunSmartMod.LOGGER.info("WebKit 内核就绪，配置文件目录：{}", profileDir);
        // 离屏窗口显示的那一下会抢焦点，立刻把焦点还给游戏窗口。
        restoreGameFocus(350L);
    }

    /** 把前台焦点还给 MC 的 SDL 窗口，避免输入被离屏舞台吃掉。 */
    private static void restoreGameFocus(long delayMs) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        Runnable raise = () -> {
            Minecraft current = Minecraft.getInstance();
            if (current == null) {
                return;
            }
            try {
                SDLVideo.SDL_RaiseWindow(current.getWindow().handle());
            } catch (Throwable ignored) {
            }
        };
        if (delayMs <= 0L) {
            client.execute(raise);
            return;
        }
        Thread restorer = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                return;
            }
            client.execute(raise);
        }, "hajikun-webkit-focus");
        restorer.setDaemon(true);
        restorer.start();
    }

    /** 退出存档或关游戏时释放全部标签页；工具包保留，重进世界可以继续用。 */
    public static void shutdown() {
        for (Tab tab : TABS.values()) {
            runFx(() -> destroyView(tab));
        }
        TABS.clear();
        if (cookieStore != null) {
            cookieStore.save();
        }
        if (previousCookieHandler != null || cookieStore != null) {
            CookieHandler.setDefault(previousCookieHandler);
            previousCookieHandler = null;
            cookieStore = null;
        }
    }

    // ------------------------------------------------------------------ 指令

    /** 新建标签页并返回编号。工具包没就绪也先返回编号，初始化完成后自动补建。 */
    public static int createTab(Consumer<Event> owner, int width, int height, float zoom) {
        start();
        int id = NEXT_TAB.getAndIncrement();
        Tab tab = new Tab(id);
        tab.owner = owner;
        tab.width = Math.max(80, width);
        tab.height = Math.max(60, height);
        tab.zoom = zoom <= 0F ? 1F : zoom;
        tab.paintAt = System.currentTimeMillis();
        TABS.put(id, tab);
        runFx(() -> createView(tab));
        return id;
    }

    public static void load(int tab, String url, int width, int height, float zoom) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        int w = Math.max(80, width);
        int h = Math.max(60, height);
        float z = zoom <= 0F ? 1F : zoom;
        entry.width = w;
        entry.height = h;
        entry.zoom = z;
        entry.paintAt = System.currentTimeMillis();
        String address = url == null ? "" : url;
        runFx(() -> {
            if (entry.closed) {
                return;
            }
            entry.internal = false;
            entry.displayUrl = address;
            entry.error = "";
            entry.loading = true;
            pushHistory(entry, address);
            refreshState(entry);
            if (entry.engine == null) {
                entry.hasPending = true;
                entry.pendingInternal = false;
                entry.pendingUrl = address;
                entry.pendingHtml = null;
                return;
            }
            applyGeometry(entry);
            entry.engine.load(address);
        });
    }

    /** 载入内置页面 HTML；地址栏仍显示 {@code address}。 */
    public static void loadHtml(int tab, String address, String html, String baseUrl,
                                int width, int height, float zoom) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        int w = Math.max(80, width);
        int h = Math.max(60, height);
        float z = zoom <= 0F ? 1F : zoom;
        entry.width = w;
        entry.height = h;
        entry.zoom = z;
        entry.paintAt = System.currentTimeMillis();
        String target = address == null ? "" : address;
        String content = html == null ? "" : html;
        runFx(() -> {
            if (entry.closed) {
                return;
            }
            entry.internal = true;
            entry.displayUrl = target;
            entry.error = "";
            entry.loading = true;
            pushHistory(entry, target);
            refreshState(entry);
            if (entry.engine == null) {
                entry.hasPending = true;
                entry.pendingInternal = true;
                entry.pendingUrl = target;
                entry.pendingHtml = content;
                return;
            }
            applyGeometry(entry);
            entry.engine.loadContent(content);
        });
    }

    /**
     * 把界面侧已走过的历史灌进内核标签页。
     * <p>内核只认识自己 load 过的地址，第一次交页时若不补种，
     * 起始页等早先记录在内核里不存在，后退按钮会因为 `canBack = false` 而整块失效。</p>
     */
    public static void seedHistory(int tab, List<String> urls, int at) {
        Tab entry = TABS.get(tab);
        if (entry == null || urls == null || urls.isEmpty()) {
            return;
        }
        List<String> copy = new ArrayList<>(urls);
        int index = at;
        String wanted = index >= 0 && index < copy.size() ? copy.get(index) : null;
        runFx(() -> {
            if (entry.closed) {
                return;
            }
            entry.history.clear();
            for (String url : copy) {
                if (url == null || url.isBlank()) {
                    continue;
                }
                if (!entry.history.isEmpty()
                        && sameUrl(url, entry.history.get(entry.history.size() - 1))) {
                    continue;
                }
                entry.history.add(url);
            }
            // 去重后下标会前移：按“想要的地址”重新定位游标。
            int cursor = -1;
            if (wanted != null) {
                for (int i = 0; i < entry.history.size(); i++) {
                    if (sameUrl(entry.history.get(i), wanted)) {
                        cursor = i;
                        break;
                    }
                }
            }
            entry.historyAt = cursor >= 0
                    ? cursor : Math.max(0, Math.min(index, entry.history.size() - 1));
            while (entry.history.size() > 60) {
                entry.history.remove(0);
                entry.historyAt = Math.max(0, entry.historyAt - 1);
            }
            refreshState(entry);
        });
    }

    /**
     * 界面每帧报告页面区域尺寸；尺寸没变时只刷新“还在看”的时间戳。
     *
     * <p>抓帧不再由这里每帧强制触发：静止页面按低帧率巡检，
     * 只有输入、加载和页面自身动画会把它切到 30 帧。</p>
     */
    public static void resize(int tab, int width, int height) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - entry.visibleAt > 500L) {
            // 刚被重新翻开：先强制补一帧，别让用户看到旧画面。
            entry.captureWanted = true;
            entry.paintAt = now;
        }
        entry.visibleAt = now;
        int w = Math.max(80, width);
        int h = Math.max(60, height);
        if (entry.width == w && entry.height == h) {
            return;
        }
        entry.width = w;
        entry.height = h;
        entry.captureWanted = true;
        entry.paintAt = now;
        runFx(() -> {
            if (!entry.closed && entry.view != null) {
                applyGeometry(entry);
            }
        });
    }

    public static void mouse(int tab, String type, float x, float y, int button, int clicks,
                             boolean shift, boolean ctrl, boolean alt) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        entry.paintAt = System.currentTimeMillis();
        runFx(() -> fireMouse(entry, type, x, y, button, clicks, shift, ctrl, alt));
    }

    public static void wheel(int tab, float x, float y, float dy, boolean shift) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        entry.paintAt = System.currentTimeMillis();
        runFx(() -> fireWheel(entry, x, y, dy, shift));
    }

    public static void key(int tab, boolean down, String keyCode, boolean shift,
                           boolean ctrl, boolean alt) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        entry.paintAt = System.currentTimeMillis();
        runFx(() -> fireKey(entry, down, keyCode, shift, ctrl, alt));
    }

    public static void text(int tab, String value) {
        Tab entry = TABS.get(tab);
        if (entry == null || value == null || value.isEmpty()) {
            return;
        }
        entry.paintAt = System.currentTimeMillis();
        runFx(() -> {
            if (entry.view == null || entry.engine == null) {
                return;
            }
            try {
                entry.view.requestFocus();
            } catch (Throwable ignored) {
            }
            insertText(entry, value);
        });
    }

    public static void nav(int tab, String direction) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        entry.paintAt = System.currentTimeMillis();
        runFx(() -> {
            if (entry.engine == null) {
                return;
            }
            switch (direction == null ? "" : direction) {
                case "back" -> goHistory(entry, -1);
                case "forward" -> goHistory(entry, 1);
                case "reload" -> reloadPage(entry);
                case "stop" -> entry.engine.getLoadWorker().cancel();
                default -> {
                }
            }
        });
    }

    public static void zoom(int tab, float zoom) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        entry.zoom = zoom <= 0F ? 1F : zoom;
        entry.paintAt = System.currentTimeMillis();
        runFx(() -> {
            if (entry.view != null) {
                entry.view.setZoom(entry.zoom);
                applyGeometry(entry);
            }
            refreshState(entry);
        });
    }

    public static void mute(int tab, boolean muted) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        entry.muted = muted;
        runFx(() -> {
            if (entry.engine != null) {
                applyMute(entry);
            }
            refreshState(entry);
        });
    }

    public static void find(int tab, String query, boolean forward, boolean next) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        String needle = query == null ? "" : query;
        runFx(() -> runFind(entry, needle, forward, next));
    }

    public static void reader(int tab, boolean on) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        runFx(() -> {
            if (entry.engine == null) {
                return;
            }
            if (on) {
                try {
                    entry.engine.executeScript(READER_JS);
                } catch (Throwable throwable) {
                    notifyLog(entry, "阅读模式失败：" + describe(throwable));
                }
            } else {
                reloadPage(entry);
            }
        });
    }

    public static void closeTab(int tab) {
        Tab entry = TABS.remove(tab);
        if (entry == null) {
            return;
        }
        entry.closed = true;
        entry.frame = null;
        runFx(() -> destroyView(entry));
    }

    public static Frame pollFrame(int tab) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            Frame frame = entry.frame;
            entry.frame = null;
            return frame;
        }
    }

    public static State state(int tab) {
        Tab entry = TABS.get(tab);
        return entry == null ? State.EMPTY : entry.state;
    }

    // ------------------------------------------------------------------ 舞台与标签页

    private static void createView(Tab tab) {
        if (tab.closed || root == null) {
            return;
        }
        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        view.setPickOnBounds(true);
        view.setFocusTraversable(true);
        view.setMinSize(1, 1);
        view.setPrefSize(renderWidth(tab), renderHeight(tab));
        view.setZoom(tab.zoom);
        root.getChildren().add(view);
        tab.view = view;
        WebEngine engine = view.getEngine();
        tab.engine = engine;
        engine.setJavaScriptEnabled(true);
        engine.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/618.1 "
                + "(KHTML, like Gecko) HajikunVM/1.0 Safari/618.1");
        engine.titleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isBlank()) {
                return;
            }
            tab.title = newValue;
            refreshState(tab);
        });
        engine.locationProperty().addListener((observable, oldValue, newValue) -> {
            if (tab.internal || newValue == null || newValue.isBlank()
                    || "about:blank".equals(newValue)) {
                return;
            }
            tab.displayUrl = newValue;
            pushHistory(tab, newValue);
            refreshState(tab);
        });
        engine.getLoadWorker().stateProperty().addListener(
                (observable, oldValue, newValue) -> onWorkerState(tab, newValue));
        engine.getLoadWorker().exceptionProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                tab.error = newValue.getMessage() == null
                        ? describe(newValue) : newValue.getMessage();
                refreshState(tab);
            }
        });
        applyGeometry(tab);
        if (tab.hasPending) {
            tab.hasPending = false;
            if (tab.pendingInternal) {
                engine.loadContent(tab.pendingHtml == null ? "" : tab.pendingHtml);
            } else {
                engine.load(tab.pendingUrl);
            }
        }
    }

    private static void destroyView(Tab tab) {
        if (tab.view != null) {
            try {
                tab.view.getEngine().load(null);
            } catch (Throwable ignored) {
            }
            if (root != null) {
                root.getChildren().remove(tab.view);
            }
        }
        tab.view = null;
        tab.engine = null;
        tab.windowJs = null;
        tab.image = null;
        tab.pixels = null;
        tab.abgr = null;
        tab.handed = null;
        tab.frame = null;
    }

    /**
     * 网页视图按 {@link #RENDER_SCALE} 倍逻辑尺寸排版。
     *
     * <p>虚拟机画布只有 320 像素宽，但聚焦时它在屏幕上约占 1300 物理像素；
     * 若按 320 排版再放大，字号会变成满屏巨字且边缘发糊。按 4 倍排版后
     * 每个 CSS 像素正好对应约一个屏幕像素，页面是正常的桌面浏览器密度。</p>
     */
    private static void applyGeometry(Tab tab) {
        WebView view = tab.view;
        if (view == null) {
            return;
        }
        int w = renderWidth(tab);
        int h = renderHeight(tab);
        view.setPrefSize(w, h);
        view.setMinSize(1, 1);
        view.resize(w, h);
        view.setZoom(tab.zoom <= 0F ? 1F : tab.zoom);
        if (root != null) {
            root.applyCss();
            root.layout();
        }
    }

    private static int renderWidth(Tab tab) {
        return Math.min(STAGE_WIDTH, Math.max(1, tab.width) * RENDER_SCALE);
    }

    private static int renderHeight(Tab tab) {
        return Math.min(STAGE_HEIGHT, Math.max(1, tab.height) * RENDER_SCALE);
    }

    private static void onWorkerState(Tab tab, Worker.State state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case SCHEDULED, RUNNING -> {
                tab.loading = true;
                refreshState(tab);
            }
            case SUCCEEDED -> {
                tab.loading = false;
                tab.error = "";
                tab.paintAt = System.currentTimeMillis();
                String title = tab.engine == null ? "" : tab.engine.getTitle();
                if (title != null && !title.isBlank()) {
                    tab.title = title;
                }
                if (!tab.internal && tab.engine != null) {
                    String location = tab.engine.getLocation();
                    if (location != null && !location.isBlank()
                            && !"about:blank".equals(location)) {
                        tab.displayUrl = location;
                        pushHistory(tab, location);
                    }
                }
                refreshState(tab);
                hookPage(tab);
                if (tab.muted) {
                    applyMute(tab);
                }
            }
            case FAILED -> {
                tab.loading = false;
                if (tab.error.isBlank()) {
                    tab.error = "无法加载页面";
                }
                refreshState(tab);
            }
            case CANCELLED -> {
                tab.loading = false;
                refreshState(tab);
            }
        }
    }

    /** 页面加载完把 Java 桥挂到 window 上，并拦截新窗口与内置链接。 */
    private static void hookPage(Tab tab) {
        WebEngine engine = tab.engine;
        if (engine == null) {
            return;
        }
        try {
            Object window = engine.executeScript("window");
            if (window instanceof JSObject js) {
                tab.windowJs = js;
                js.setMember("hajikun", new Bridge(tab.id));
            }
        } catch (Throwable throwable) {
            logOnce("网页桥接失败：" + describe(throwable));
        }
        try {
            engine.executeScript(BRIDGE_JS);
        } catch (Throwable throwable) {
            logOnce("网页脚本注入失败：" + describe(throwable));
        }
    }

    // ------------------------------------------------------------------ 历史

    /**
     * 历史比较用的规范化键：忽略末尾斜杠与锚点，并把 electron:///home 与
     * electron://home 视作同一页。WebEngine 会把 example.com 补成 example.com/，
     * 若按字符串比较就会多出一条一模一样的记录，后退看起来像没反应。
     */
    public static String urlKey(String url) {
        if (url == null) {
            return "";
        }
        String key = url.trim();
        int hash = key.indexOf('#');
        if (hash >= 0) {
            key = key.substring(0, hash);
        }
        if (key.regionMatches(true, 0, "electron://", 0, 11)) {
            String rest = key.substring(11);
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            return "electron://" + rest.toLowerCase(Locale.ROOT);
        }
        int scheme = key.indexOf("://");
        if (scheme > 0) {
            String rest = key.substring(scheme + 3);
            if (rest.endsWith("/") && rest.indexOf('/') == rest.length() - 1) {
                key = key.substring(0, key.length() - 1);
            }
        }
        return key.toLowerCase(Locale.ROOT);
    }

    public static boolean sameUrl(String first, String second) {
        return urlKey(first).equals(urlKey(second));
    }

    private static void pushHistory(Tab tab, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (tab.historyAt >= 0 && tab.historyAt < tab.history.size()
                && sameUrl(url, tab.history.get(tab.historyAt))) {
            // 同一页的规范写法（补斜杠、去锚点）：就地替换，不新增记录。
            tab.history.set(tab.historyAt, url);
            return;
        }
        while (tab.history.size() > tab.historyAt + 1) {
            tab.history.remove(tab.history.size() - 1);
        }
        tab.history.add(url);
        tab.historyAt = tab.history.size() - 1;
        while (tab.history.size() > 60) {
            tab.history.remove(0);
            tab.historyAt = Math.max(0, tab.historyAt - 1);
        }
    }

    private static String currentHistory(Tab tab) {
        if (tab.historyAt >= 0 && tab.historyAt < tab.history.size()) {
            return tab.history.get(tab.historyAt);
        }
        return null;
    }

    private static void goHistory(Tab tab, int delta) {
        int at = tab.historyAt + delta;
        if (at < 0 || at >= tab.history.size()) {
            return;
        }
        String target = tab.history.get(at);
        tab.historyAt = at;
        if (target.startsWith("electron://")) {
            // 内置页面由界面重新生成 HTML；内核这里只负责发事件。
            tab.internal = true;
            tab.displayUrl = target;
            refreshState(tab);
            notifyEvent(tab, "history", data("url", target));
            return;
        }
        tab.internal = false;
        tab.displayUrl = target;
        tab.loading = true;
        refreshState(tab);
        notifyEvent(tab, "history", data("url", target));
        if (tab.engine != null) {
            tab.engine.load(target);
        }
    }

    private static void reloadPage(Tab tab) {
        String current = currentHistory(tab);
        if (current != null && current.startsWith("electron://")) {
            notifyEvent(tab, "history", data("url", current));
            return;
        }
        if (tab.engine != null) {
            tab.loading = true;
            refreshState(tab);
            tab.engine.reload();
        }
    }

    // ------------------------------------------------------------------ 输入

    private static void fireMouse(Tab tab, String type, float x, float y, int button,
                                  int clicks, boolean shift, boolean ctrl, boolean alt) {
        WebView view = tab.view;
        if (view == null) {
            return;
        }
        MouseButton mouseButton = switch (button) {
            case 1 -> MouseButton.SECONDARY;
            case 2 -> MouseButton.MIDDLE;
            default -> MouseButton.PRIMARY;
        };
        javafx.event.EventType<MouseEvent> eventType;
        boolean pressed = false;
        switch (type == null ? "" : type) {
            case "down" -> {
                eventType = MouseEvent.MOUSE_PRESSED;
                pressed = true;
                view.requestFocus();
            }
            case "up" -> eventType = MouseEvent.MOUSE_RELEASED;
            default -> {
                eventType = MouseEvent.MOUSE_MOVED;
                mouseButton = MouseButton.NONE;
            }
        }
        // 画布坐标是 1 倍逻辑像素，网页视图按 RENDER_SCALE 倍排版，命中点同步放大。
        float vx = x * RENDER_SCALE;
        float vy = y * RENDER_SCALE;
        try {
            MouseEvent event = new MouseEvent(eventType, vx, vy, vx, vy, mouseButton,
                    Math.max(1, clicks), shift, ctrl, alt, false,
                    pressed && mouseButton == MouseButton.PRIMARY,
                    pressed && mouseButton == MouseButton.MIDDLE,
                    pressed && mouseButton == MouseButton.SECONDARY,
                    true, false, true, null);
            view.fireEvent(event);
        } catch (Throwable throwable) {
            logOnce("鼠标事件失败：" + describe(throwable));
        }
    }

    private static void fireWheel(Tab tab, float x, float y, float dy, boolean shift) {
        WebView view = tab.view;
        if (view == null) {
            return;
        }
        // 坐标与滚动量都按排版倍数放大，滚一格仍然走同样多的可见内容。
        float vx = x * RENDER_SCALE;
        float vy = y * RENDER_SCALE;
        float vdy = dy * RENDER_SCALE;
        try {
            ScrollEvent event = new ScrollEvent(ScrollEvent.SCROLL, vx, vy, vx, vy,
                    shift, false, false, false, false, false,
                    0, vdy, 0, vdy,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
            view.fireEvent(event);
        } catch (Throwable throwable) {
            logOnce("滚轮事件失败：" + describe(throwable));
        }
    }

    private static void fireKey(Tab tab, boolean down, String name, boolean shift,
                                boolean ctrl, boolean alt) {
        WebView view = tab.view;
        WebEngine engine = tab.engine;
        if (view == null || engine == null) {
            return;
        }
        KeyCode code = keyCode(name);
        if (code == null) {
            // Ctrl+A/C/X/V/Z 这类组合直接用编辑命令，比合成按键可靠。
            String command = ctrl ? shortcut(name, shift) : null;
            if (command != null) {
                try {
                    engine.executeScript("try{document.execCommand('" + command + "');}catch(e){}");
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (!down) {
            return;
        }
        try {
            view.requestFocus();
            view.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                    shift, ctrl, alt, false));
            view.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code,
                    shift, ctrl, alt, false));
        } catch (Throwable throwable) {
            logOnce("键盘事件失败：" + describe(throwable));
        }
    }

    private static String shortcut(String name, boolean shift) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase()) {
            case "a" -> "selectAll";
            case "c" -> "copy";
            case "x" -> "cut";
            case "z" -> shift ? "redo" : "undo";
            case "y" -> "redo";
            default -> null;
        };
    }

    private static KeyCode keyCode(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "Enter" -> KeyCode.ENTER;
            case "Backspace" -> KeyCode.BACK_SPACE;
            case "Tab" -> KeyCode.TAB;
            case "Delete" -> KeyCode.DELETE;
            case "Left" -> KeyCode.LEFT;
            case "Right" -> KeyCode.RIGHT;
            case "Up" -> KeyCode.UP;
            case "Down" -> KeyCode.DOWN;
            case "Home" -> KeyCode.HOME;
            case "End" -> KeyCode.END;
            case "PageUp" -> KeyCode.PAGE_UP;
            case "PageDown" -> KeyCode.PAGE_DOWN;
            case "F5" -> KeyCode.F5;
            default -> null;
        };
    }

    private static void insertText(Tab tab, String value) {
        WebEngine engine = tab.engine;
        if (engine == null || value == null || value.isEmpty()) {
            return;
        }
        try {
            String json = new com.google.gson.Gson().toJson(value);
            engine.executeScript("try{document.execCommand('insertText',false," + json
                    + ");}catch(e){}");
        } catch (Throwable throwable) {
            logOnce("文字输入失败：" + describe(throwable));
        }
    }

    private static void applyMute(Tab tab) {
        WebEngine engine = tab.engine;
        if (engine == null) {
            return;
        }
        String script = "try{document.querySelectorAll('audio,video').forEach(function(m){"
                + "m.muted=" + (tab.muted ? "true" : "false") + ";"
                + "m.volume=" + (tab.muted ? "0" : "1") + ";});}catch(e){}";
        try {
            engine.executeScript(script);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 页内查找

    private static void runFind(Tab tab, String query, boolean forward, boolean next) {
        WebEngine engine = tab.engine;
        if (engine == null) {
            return;
        }
        if (query.isEmpty()) {
            tab.findQuery = "";
            tab.findActive = 0;
            try {
                engine.executeScript("try{window.getSelection().removeAllRanges();}catch(e){}");
            } catch (Throwable ignored) {
            }
            notifyEvent(tab, "find", findData(0, 0));
            return;
        }
        try {
            String json = new com.google.gson.Gson().toJson(query);
            String script = "try{window.find(" + json + ",false," + (forward ? "false" : "true")
                    + ",true,false,true,false);}catch(e){}"
                    + "(function(){var n=0,w=document.createTreeWalker(document.body,"
                    + "NodeFilter.SHOW_TEXT,null),q=" + json + ".toLowerCase();"
                    + "while(w.nextNode()){var t=w.currentNode.nodeValue;if(!t)continue;"
                    + "var s=t.toLowerCase(),i=0;while((i=s.indexOf(q,i))>=0){n++;i+=q.length;}}"
                    + "return n;})()";
            Object result = engine.executeScript(script);
            int matches = 0;
            if (result instanceof Number number) {
                matches = Math.max(0, number.intValue());
            }
            if (matches == 0) {
                tab.findActive = 0;
            } else if (!query.equals(tab.findQuery)) {
                tab.findActive = 1;
            } else if (next) {
                tab.findActive = tab.findActive % matches + 1;
            }
            tab.findQuery = query;
            notifyEvent(tab, "find", findData(matches, tab.findActive));
        } catch (Throwable throwable) {
            notifyEvent(tab, "find", findData(0, 0));
        }
    }

    private static JsonObject findData(int matches, int active) {
        JsonObject json = new JsonObject();
        json.addProperty("matches", matches);
        json.addProperty("active", active);
        return json;
    }

    // ------------------------------------------------------------------ 截图

    /**
     * AnimationTimer 回调：按“页面是否在动”决定抓帧节拍。
     *
     * <p>此前界面每帧都请求截图，即使静态页面也按 30 帧跑一次 WebKit 离屏渲染，
     * 游戏里就会周期性顿一下。现在输入、加载、DOM 变动、动画与视频会点亮
     * {@code paintAt}，只在活跃期间跑 30 帧；其余时间 0.4 秒巡检一次，
     * 窗口没被看着时完全不抓。</p>
     */
    private static void captureTick() {
        if (cookieStore != null) {
            cookieStore.flushIfDue();
        }
        if (TABS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Tab tab : TABS.values()) {
            if (tab.closed || tab.view == null) {
                continue;
            }
            if (now - tab.visibleAt > HIDDEN_AFTER_MS) {
                // 窗口被最小化或挡住：屏幕上看不到，别再为它渲染。
                tab.captureWanted = false;
                continue;
            }
            boolean active = tab.loading || tab.captureWanted
                    || now - tab.paintAt < ACTIVE_WINDOW_MS;
            long interval = active ? SNAPSHOT_INTERVAL_MS : IDLE_SNAPSHOT_INTERVAL_MS;
            if (active && tab.captureCostMs > CAPTURE_BUDGET_MS) {
                // 网页越复杂抓帧越贵：按超出预算的时长把节拍拉长，先把游戏帧率保住。
                interval = Math.min(SLOWEST_INTERVAL_MS,
                        interval + tab.captureCostMs - CAPTURE_BUDGET_MS);
            }
            if (now - tab.lastCapture < interval) {
                continue;
            }
            if (tab.frame != null) {
                // 界面还没取走上一帧，说明绘制跟不上；再截只会白烧 CPU。
                continue;
            }
            tab.lastCapture = now;
            tab.captureWanted = false;
            capture(tab);
        }
    }

    private static void capture(Tab tab) {
        WebView view = tab.view;
        if (view == null) {
            return;
        }
        int w = renderWidth(tab);
        int h = renderHeight(tab);
        long startedAt = System.nanoTime();
        try {
            if (tab.image == null || (int) tab.image.getWidth() != w
                    || (int) tab.image.getHeight() != h) {
                tab.image = new WritableImage(w, h);
                tab.pixels = new int[w * h];
                tab.abgr = new int[w * h];
                tab.handed = null;
            }
            if (tab.params == null) {
                tab.params = new javafx.scene.SnapshotParameters();
                tab.params.setFill(Color.WHITE);
            }
            view.snapshot(tab.params, tab.image);
            PixelReader reader = tab.image.getPixelReader();
            if (reader == null) {
                return;
            }
            int count = w * h;
            reader.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), tab.pixels, 0, w);
            // JavaFX 给的是 ARGB；NativeImage 的 RGBA 内存在小端机上按 ABGR 整数排布。
            int[] abgr = tab.abgr;
            int[] src = tab.pixels;
            for (int i = 0; i < count; i++) {
                int value = src[i];
                abgr[i] = (value & 0xFF00FF00)
                        | ((value & 0x000000FF) << 16)
                        | ((value >>> 16) & 0x000000FF);
            }
            // 静止页面直接跳过：不生成新帧，界面也就不会重写纹理。
            if (tab.handed != null && java.util.Arrays.equals(abgr, tab.handed)) {
                return;
            }
            // 两个缓冲轮流使用：刚交出去的这块保持原样，本帧内容写进另一块，
            // 下一次抓帧再交换。省掉整帧复制，也不再每帧申请 3.6 MB 本地内存。
            int[] previous = tab.handed;
            tab.handed = abgr;
            tab.abgr = previous != null && previous.length == count
                    ? previous : new int[count];
            Frame frame = new Frame(abgr, w, h, FRAME_SEQ.getAndIncrement());
            synchronized (tab) {
                tab.frame = frame;
            }
        } catch (Throwable throwable) {
            logOnce("网页截图失败：" + describe(throwable));
        } finally {
            // 记录这次实际花了多久（含失败），下一拍据此决定要不要降频。
            tab.captureCostMs = (System.nanoTime() - startedAt) / 1_000_000L;
        }
    }

    // ------------------------------------------------------------------ 状态与事件

    private static void refreshState(Tab tab) {
        boolean canBack = tab.historyAt > 0;
        boolean canForward = tab.historyAt >= 0 && tab.historyAt < tab.history.size() - 1;
        tab.state = new State(tab.displayUrl == null ? "" : tab.displayUrl,
                tab.title == null ? "" : tab.title, tab.loading, canBack, canForward,
                tab.muted, tab.zoom, tab.internal, tab.error == null ? "" : tab.error);
    }

    private static JsonObject data(String key, String value) {
        JsonObject json = new JsonObject();
        json.addProperty(key, value == null ? "" : value);
        return json;
    }

    private static void notifyEvent(Tab tab, String kind, JsonObject data) {
        Consumer<Event> owner = tab.owner;
        if (owner == null) {
            return;
        }
        Event event = new Event(kind, tab.id, data);
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            safeAccept(owner, event);
            return;
        }
        client.execute(() -> safeAccept(owner, event));
    }

    private static void safeAccept(Consumer<Event> owner, Event event) {
        try {
            owner.accept(event);
        } catch (RuntimeException exception) {
            HajikunSmartMod.LOGGER.warn("WebKit 事件处理失败：{}", exception.toString());
        }
    }

    private static void notifyLog(Tab tab, String message) {
        notifyEvent(tab, "log", data("message", message));
    }

    /** 网页通过 window.hajikun.* 回调 Java；方法必须 public 才能被 WebKit 调用。 */
    public static final class Bridge {
        private final int tab;

        Bridge(int tab) {
            this.tab = tab;
        }

        public void navigate(String url) {
            Tab entry = TABS.get(tab);
            if (entry != null) {
                notifyEvent(entry, "nav", data("url", url));
            }
        }

        public void open(String url) {
            Tab entry = TABS.get(tab);
            if (entry != null) {
                notifyEvent(entry, "open", data("url", url));
            }
        }

        public void log(String message) {
            Tab entry = TABS.get(tab);
            if (entry != null) {
                notifyLog(entry, message == null ? "" : message);
            }
        }

        /** 页面自己报告“画面动了”，抓帧节拍随即提到 30 帧。 */
        public void paint() {
            Tab entry = TABS.get(tab);
            if (entry != null) {
                entry.paintAt = System.currentTimeMillis();
            }
        }

        public String url() {
            Tab entry = TABS.get(tab);
            return entry == null ? "" : entry.displayUrl;
        }
    }

    // ------------------------------------------------------------------ 线程

    private static void runFx(Runnable task) {
        if (task == null) {
            return;
        }
        if (ready) {
            if (Platform.isFxApplicationThread()) {
                runSafely(task);
            } else {
                Platform.runLater(() -> runSafely(task));
            }
            return;
        }
        if (!started) {
            start();
        }
        FX_QUEUE.add(task);
    }

    private static void drainFxQueue() {
        Runnable task;
        while ((task = FX_QUEUE.poll()) != null) {
            runSafely(task);
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            logOnce("WebKit 指令失败：" + describe(throwable));
        }
    }

    // ------------------------------------------------------------------ Cookie

    private static void installCookies(Path dir) {
        try {
            CookieHandler current = CookieHandler.getDefault();
            if (current instanceof CookieManager manager
                    && manager.getCookieStore() instanceof FileCookieStore store) {
                cookieStore = store;
                return;
            }
            previousCookieHandler = current;
            cookieStore = new FileCookieStore(dir.resolve("cookies.txt"));
            CookieHandler.setDefault(new CookieManager(cookieStore, CookiePolicy.ACCEPT_ALL));
        } catch (Throwable throwable) {
            logOnce("Cookie 存储初始化失败：" + describe(throwable));
        }
    }

    /** 落盘到 config/hajikun/webkit/cookies.txt 的简易 CookieStore。 */
    private static final class FileCookieStore implements CookieStore {
        /** 合并写盘的最短间隔：连续 Set-Cookie 不再每次都同步写文件。 */
        private static final long SAVE_DEBOUNCE_MS = 2000L;
        private final Path file;
        private final List<Entry> entries = new ArrayList<>();
        private boolean dirty;
        private long lastSavedAt;

        record Entry(URI uri, HttpCookie cookie) {
        }

        FileCookieStore(Path file) {
            this.file = file;
            load();
        }

        synchronized void load() {
            if (file == null || !Files.isRegularFile(file)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 6) {
                        continue;
                    }
                    URI uri = URI.create(parts[0]);
                    HttpCookie cookie = new HttpCookie(parts[1],
                            new String(Base64.getUrlDecoder().decode(parts[4]),
                                    StandardCharsets.UTF_8));
                    if (!parts[2].isEmpty()) {
                        cookie.setDomain(parts[2]);
                    }
                    if (!parts[3].isEmpty()) {
                        cookie.setPath(parts[3]);
                    }
                    long maxAge = Long.parseLong(parts[5]);
                    if (maxAge >= 0L) {
                        cookie.setMaxAge(maxAge);
                    }
                    entries.add(new Entry(uri, cookie));
                }
            } catch (RuntimeException | IOException exception) {
                logOnce("读取 Cookie 失败：" + describe(exception));
            }
        }

        synchronized void save() {
            if (file == null) {
                return;
            }
            this.dirty = false;
            this.lastSavedAt = System.currentTimeMillis();
            this.write(this.serialize());
        }

        /** 只标脏；真正的磁盘写入交给 {@link #flushIfDue()} 合并处理。 */
        private synchronized void markDirty() {
            this.dirty = true;
        }

        /** AnimationTimer 每帧调用；最多两秒写一次，且把写盘放到独立线程。 */
        void flushIfDue() {
            if (!this.dirty || this.file == null) {
                return;
            }
            List<String> lines;
            synchronized (this) {
                if (!this.dirty) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - this.lastSavedAt < SAVE_DEBOUNCE_MS) {
                    return;
                }
                this.dirty = false;
                this.lastSavedAt = now;
                lines = this.serialize();
            }
            Thread writer = new Thread(() -> this.write(lines), "hajikun-cookie-save");
            writer.setDaemon(true);
            writer.start();
        }

        private List<String> serialize() {
            List<String> lines = new ArrayList<>();
            for (Entry entry : this.entries) {
                HttpCookie cookie = entry.cookie();
                if (cookie.hasExpired()) {
                    continue;
                }
                String value = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString((cookie.getValue() == null ? "" : cookie.getValue())
                                .getBytes(StandardCharsets.UTF_8));
                lines.add(entry.uri() + "\t" + cookie.getName() + "\t"
                        + (cookie.getDomain() == null ? "" : cookie.getDomain()) + "\t"
                        + (cookie.getPath() == null ? "/" : cookie.getPath()) + "\t"
                        + value + "\t" + cookie.getMaxAge());
            }
            return lines;
        }

        private void write(List<String> lines) {
            try {
                Files.createDirectories(this.file.getParent());
                Files.write(this.file, lines, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                logOnce("保存 Cookie 失败：" + describe(exception));
            }
        }

        @Override
        public synchronized void add(URI uri, HttpCookie cookie) {
            if (uri == null || cookie == null) {
                return;
            }
            entries.removeIf(entry -> entry.cookie().getName().equals(cookie.getName())
                    && sameHost(entry, cookie));
            if (!cookie.hasExpired()) {
                entries.add(new Entry(uri, cookie));
            }
            // 网络页会连续写入多枚 Cookie：这里只标脏，由定时器合并落盘。
            markDirty();
        }

        private boolean sameHost(Entry entry, HttpCookie cookie) {
            String a = entry.cookie().getDomain();
            String b = cookie.getDomain();
            if (a == null || b == null) {
                return a == null && b == null
                        && entry.uri().getHost() != null
                        && entry.uri().getHost().equalsIgnoreCase(cookie.getDomain());
            }
            return a.equalsIgnoreCase(b);
        }

        @Override
        public synchronized List<HttpCookie> get(URI uri) {
            List<HttpCookie> result = new ArrayList<>();
            if (uri == null) {
                return result;
            }
            for (Entry entry : entries) {
                if (!entry.cookie().hasExpired() && matches(entry, uri)) {
                    result.add(entry.cookie());
                }
            }
            return result;
        }

        private boolean matches(Entry entry, URI uri) {
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            HttpCookie cookie = entry.cookie();
            String domain = cookie.getDomain();
            if (domain != null && !domain.isBlank()) {
                String lowerHost = host.toLowerCase();
                String lowerDomain = domain.toLowerCase();
                if (!lowerHost.equals(lowerDomain) && !lowerHost.endsWith("." + lowerDomain)) {
                    return false;
                }
            } else if (entry.uri().getHost() == null
                    || !entry.uri().getHost().equalsIgnoreCase(host)) {
                return false;
            }
            String path = cookie.getPath();
            if (path != null && !path.isEmpty()) {
                String requestPath = uri.getPath() == null || uri.getPath().isEmpty()
                        ? "/" : uri.getPath();
                if (!requestPath.startsWith(path)) {
                    return false;
                }
            }
            if (cookie.getSecure() && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            return true;
        }

        @Override
        public synchronized List<HttpCookie> getCookies() {
            List<HttpCookie> result = new ArrayList<>();
            for (Entry entry : entries) {
                if (!entry.cookie().hasExpired()) {
                    result.add(entry.cookie());
                }
            }
            return result;
        }

        @Override
        public synchronized List<URI> getURIs() {
            List<URI> result = new ArrayList<>();
            for (Entry entry : entries) {
                if (!result.contains(entry.uri())) {
                    result.add(entry.uri());
                }
            }
            return result;
        }

        @Override
        public synchronized boolean remove(URI uri, HttpCookie cookie) {
            if (cookie == null) {
                return false;
            }
            Iterator<Entry> iterator = entries.iterator();
            boolean removed = false;
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.cookie().getName().equals(cookie.getName())
                        && (uri == null || entry.uri().equals(uri))) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                markDirty();
            }
            return removed;
        }

        @Override
        public synchronized boolean removeAll() {
            boolean empty = entries.isEmpty();
            entries.clear();
            markDirty();
            return !empty;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void logOnce(String message) {
        if (message == null || message.equals(lastLog)) {
            return;
        }
        lastLog = message;
        HajikunSmartMod.LOGGER.warn("WebKit: {}", message);
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ 注入脚本

    /** 拦截新窗口与 electron://、file:// 链接，其余导航交给 WebKit 自己处理。 */
    private static final String BRIDGE_JS = """
            (function(){
              if (window.__hajikunHooked) { return; }
              window.__hajikunHooked = true;
              try {
                window.open = function(u){ if(u && window.hajikun){ window.hajikun.open(String(u)); } return null; };
                document.addEventListener('click', function(e){
                  var a = e.target && e.target.closest ? e.target.closest('a') : null;
                  if(!a || !window.hajikun){ return; }
                  var href = a.getAttribute('href') || '';
                  var full = a.href || href;
                  if(a.target === '_blank'){ e.preventDefault(); window.hajikun.open(full); return; }
                  if(href.indexOf('electron://') === 0 || href.indexOf('file://') === 0){
                    e.preventDefault(); window.hajikun.navigate(full);
                  }
                }, true);
                document.addEventListener('submit', function(e){
                  var f = e.target;
                  if(!f || !window.hajikun){ return; }
                  var action = f.getAttribute('action') || '';
                  if(action.indexOf('electron://') === 0){
                    e.preventDefault(); window.hajikun.navigate(action);
                  }
                }, true);
                // 页面变动信号：DOM 改动、滚动、CSS/WAAPI 动画、音视频与画布动画
                // 都算“在动”，只有这些时候才需要 30 帧地往纹理上搬画面。
                var lastMark = 0;
                var mark = function(){
                  var t = Date.now();
                  if (t - lastMark < 33) { return; }
                  lastMark = t;
                  try { if (window.hajikun && window.hajikun.paint) { window.hajikun.paint(); } } catch(e) {}
                };
                try {
                  new MutationObserver(mark).observe(document.documentElement || document, {
                    subtree: true, childList: true, attributes: true, characterData: true
                  });
                } catch(e) {}
                document.addEventListener('scroll', mark, true);
                if (window.__hajikunPaintTimer) { clearInterval(window.__hajikunPaintTimer); }
                window.__hajikunPaintTimer = setInterval(function(){
                  try {
                    if (document.getAnimations && document.getAnimations().length > 0) { mark(); return; }
                    var media = document.querySelectorAll('video,audio');
                    for (var i = 0; i < media.length; i++) {
                      var m = media[i];
                      if (!m.paused && !m.ended && m.readyState > 2) { mark(); return; }
                    }
                    var canvases = document.querySelectorAll('canvas');
                    for (var j = 0; j < canvases.length; j++) {
                      var cv = canvases[j];
                      if (cv.width > 8 && cv.height > 8 && cv.offsetParent !== null) { mark(); return; }
                    }
                  } catch(e) {}
                }, 250);
              } catch(err) {}
            })();
            """;

    /** 阅读模式：去掉脚本、导航与广告位，只留正文。 */
    private static final String READER_JS = """
            (function(){
              try{
                var clone = document.body.cloneNode(true);
                var kill = clone.querySelectorAll('script,style,nav,aside,footer,header,form,iframe,video,audio,button,input');
                for (var i = 0; i < kill.length; i++){ kill[i].remove(); }
                document.body.innerHTML = '<div id="hajikun-reader">' + clone.innerHTML + '</div>';
                document.body.style.margin = '0';
                document.body.style.background = '#fbfaf6';
                var style = document.createElement('style');
                style.textContent = '#hajikun-reader{max-width:680px;margin:0 auto;padding:18px 16px 48px;'
                  + 'font:16px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;color:#222}'
                  + '#hajikun-reader h1{font-size:26px;line-height:1.3}'
                  + '#hajikun-reader img{max-width:100%;height:auto}#hajikun-reader a{color:#1a5fb4}';
                document.head.appendChild(style);
                window.scrollTo(0, 0);
                return 'ok';
              }catch(e){ return String(e); }
            })();
            """;
}
