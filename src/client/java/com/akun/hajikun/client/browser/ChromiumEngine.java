package com.akun.hajikun.client.browser;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.client.media.ImageDecode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * 真正的 Chromium 后端：Java 启动 Electron，每个标签页是一个 offscreen 窗口，
 * 画面通过 {@code paint} 事件送回 JPEG 帧，Java 只负责贴图和转发输入。
 *
 * <p>和旧的 {@link ElectronEngine}（自写 HTML 排版器）不同，这里跑的是完整的
 * Blink + V8：音视频、Canvas、WebGL、Cookie 登录、二维码、下载都由 Chromium
 * 自己处理。找不到 Electron 运行时的时候 {@link #available()} 返回 false，
 * 界面自动退回纯 Java 排版，不会卡住游戏。</p>
 */
public final class ChromiumEngine {
    private static final String SCRIPT_RESOURCE = "/assets/hajikun_smart/electron/chromium.cjs";

    private static final Map<Integer, Tab> TABS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_TAB = new AtomicInteger(1);
    private static final AtomicLong FRAME_SEQ = new AtomicLong(1L);
    private static final LinkedBlockingQueue<String> OUTBOX = new LinkedBlockingQueue<>();

    private static Process process;
    private static BufferedWriter writer;
    private static Thread writerThread;
    private static Thread readerThread;
    private static volatile boolean available;
    private static volatile boolean starting;
    private static volatile String failure = "";
    private static volatile String engineName = "";
    private static volatile String profileDir = "";

    private ChromiumEngine() {
    }

    // ------------------------------------------------------------------ 数据

    /** 一帧网页画面；{@code image} 交给调用者后由调用者释放。 */
    public record Frame(NativeImage image, int width, int height, long seq) {
    }

    /** 浏览器状态事件。 */
    public record State(String url, String title, boolean loading, boolean canBack,
                        boolean canForward, boolean muted, float zoom, boolean internal,
                        String error) {
        static final State EMPTY = new State("", "", false, false, false, false, 1.0F,
                false, "");
    }

    /** 内核事件；{@code kind} 为 paint / state / nav / open / download / find / log / ready。 */
    public record Event(String kind, int tab, JsonObject data) {
    }

    /** 下载进度；{@code done} 为 true 时文件已经落到宿主临时目录。 */
    public record Download(String url, String name, String path, long bytes, boolean done,
                           String error) {
    }

    private static final class Tab {
        final int id;
        volatile Consumer<Event> owner;
        volatile State state = State.EMPTY;
        volatile Frame frame;
        volatile int width;
        volatile int height;
        volatile boolean closed;

        Tab(int id) {
            this.id = id;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    public static boolean available() {
        return available && process != null && process.isAlive();
    }

    public static boolean booting() {
        return starting && !available();
    }

    public static String failure() {
        return failure;
    }

    public static String engine() {
        return engineName.isBlank() ? "Chromium" : engineName;
    }

    public static String profileDir() {
        return profileDir;
    }

    /** 启动 Electron；重复调用不会创建第二个进程。 */
    public static synchronized boolean start() {
        if (process != null && process.isAlive()) {
            return true;
        }
        if (starting) {
            return true;
        }
        starting = true;
        failure = "";
        try {
            String electron = findElectron();
            if (electron == null) {
                failure = "未找到 Electron 运行时（可设置 HAJIKUN_ELECTRON 或放到 config/hajikun/electron/runtime）";
                starting = false;
                available = false;
                return false;
            }
            Path script = extractScript();
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path profile = configDir.resolve("hajikun/electron/profile");
            Path downloads = configDir.resolve("hajikun/electron/downloads");
            Files.createDirectories(profile);
            Files.createDirectories(downloads);
            profileDir = profile.toString();
            ProcessBuilder builder = new ProcessBuilder(electron, script.toString());
            builder.redirectErrorStream(false);
            builder.environment().put("HAJIKUN_PROFILE", profile.toString());
            builder.environment().put("HAJIKUN_DOWNLOAD_DIR", downloads.toString());
            builder.environment().put("ELECTRON_DISABLE_SECURITY_WARNINGS", "1");
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            readerThread = new Thread(() -> readLoop(process.getInputStream()),
                    "hajikun-chromium-out");
            readerThread.setDaemon(true);
            readerThread.start();
            Thread errorThread = new Thread(() -> drainError(process.getErrorStream()),
                    "hajikun-chromium-err");
            errorThread.setDaemon(true);
            errorThread.start();
            writerThread = new Thread(ChromiumEngine::writeLoop, "hajikun-chromium-in");
            writerThread.setDaemon(true);
            writerThread.start();
            available = true;
            starting = false;
            HajikunSmartMod.LOGGER.info("Chromium 浏览器已启动：{}", electron);
            return true;
        } catch (IOException | RuntimeException exception) {
            failure = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            available = false;
            starting = false;
            HajikunSmartMod.LOGGER.warn("Chromium 启动失败：{}", failure);
            return false;
        }
    }

    public static synchronized void shutdown() {
        if (process == null) {
            available = false;
            return;
        }
        sendRaw("{\"op\":\"shutdown\"}");
        try {
            writer.flush();
        } catch (Exception ignored) {
        }
        Process current = process;
        Thread closer = new Thread(() -> {
            try {
                if (!current.waitFor(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    current.destroy();
                }
            } catch (InterruptedException exception) {
                current.destroy();
            }
        }, "hajikun-chromium-stop");
        closer.setDaemon(true);
        closer.start();
        for (Tab tab : TABS.values()) {
            closeFrame(tab);
        }
        TABS.clear();
        process = null;
        writer = null;
        available = false;
        starting = false;
        engineName = "";
    }

    private static String findElectron() {
        List<String> candidates = new ArrayList<>();
        String custom = System.getenv("HAJIKUN_ELECTRON");
        if (custom != null && !custom.isBlank()) {
            Path path = Path.of(custom);
            candidates.add(Files.isDirectory(path)
                    ? path.resolve("electron.exe").toString() : path.toString());
        }
        Path config = FabricLoader.getInstance().getConfigDir();
        candidates.add(config.resolve("hajikun/electron/runtime/electron.exe").toString());
        candidates.add(config.resolve("hajikun/electron/electron.exe").toString());
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        candidates.add(cwd.resolve("config/hajikun/electron/runtime/electron.exe").toString());
        // 开发环境的兜底：同目录下的参考浏览器工程自带 Electron 运行时。
        Path home = Path.of(System.getProperty("user.home", "."));
        candidates.add(home.resolve("Desktop/AllProjectsVibe/play/KungarooExplorer/"
                + "node_modules/electron/dist/electron.exe").toString());
        candidates.add(cwd.resolve("../KungarooExplorer/node_modules/electron/dist/electron.exe")
                .normalize().toString());
        candidates.add(cwd.resolve("../../KungarooExplorer/node_modules/electron/dist/electron.exe")
                .normalize().toString());
        for (String candidate : candidates) {
            try {
                if (candidate != null && Files.isRegularFile(Path.of(candidate))) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Path extractScript() throws IOException {
        byte[] data;
        try (InputStream stream = ChromiumEngine.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (stream == null) {
                throw new IOException("模组里缺少 " + SCRIPT_RESOURCE);
            }
            data = stream.readAllBytes();
        }
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("hajikun/electron");
        Files.createDirectories(dir);
        Path script = dir.resolve("chromium.cjs");
        if (!Files.isRegularFile(script) || !java.util.Arrays.equals(Files.readAllBytes(script), data)) {
            Files.write(script, data);
        }
        return script;
    }

    private static void drainError(InputStream stream) {
        int logged = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && logged < 12) {
                    logged++;
                    HajikunSmartMod.LOGGER.warn("Chromium: {}", line);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void readLoop(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException ignored) {
        }
        if (available) {
            available = false;
            failure = "Electron 进程已退出";
            for (Tab tab : TABS.values()) {
                notifyOwner(tab, new Event("log", tab.id, message(failure)));
            }
        }
    }

    private static void writeLoop() {
        while (true) {
            try {
                String line = OUTBOX.take();
                BufferedWriter out = writer;
                if (out == null) {
                    continue;
                }
                out.write(line);
                out.newLine();
                out.flush();
            } catch (InterruptedException exception) {
                return;
            } catch (IOException exception) {
                available = false;
                failure = "写入 Electron 失败：" + exception.getMessage();
                return;
            }
        }
    }

    private static void handleLine(String line) {
        JsonObject json;
        try {
            json = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException exception) {
            return;
        }
        String event = str(json, "event", "");
        if (event.isEmpty()) {
            if (!bool(json, "ok") && json.has("error")) {
                HajikunSmartMod.LOGGER.warn("Chromium 请求失败：{}", str(json, "error", ""));
            }
            return;
        }
        int id = intOf(json, "tab", 0);
        if ("ready".equals(event)) {
            engineName = str(json, "engine", "Chromium");
            HajikunSmartMod.LOGGER.info("Chromium 就绪：{}（配置：{}）", engineName,
                    str(json, "profile", ""));
            for (Tab tab : TABS.values()) {
                notifyOwner(tab, new Event("ready", tab.id, json));
            }
            return;
        }
        Tab tab = id <= 0 ? null : TABS.get(id);
        if ("paint".equals(event) && tab != null) {
            Frame frame = decodeFrame(json);
            if (frame == null) {
                return;
            }
            synchronized (tab) {
                closeFrame(tab);
                tab.frame = frame;
            }
            notifyOwner(tab, new Event("paint", id, null));
            return;
        }
        if ("state".equals(event) && tab != null) {
            tab.state = new State(str(json, "url", ""), str(json, "title", ""),
                    bool(json, "loading"), bool(json, "canBack"), bool(json, "canForward"),
                    bool(json, "muted"), floatOf(json, "zoom", 1.0F), bool(json, "internal"),
                    str(json, "error", ""));
            notifyOwner(tab, new Event("state", id, null));
            return;
        }
        if (tab != null) {
            notifyOwner(tab, new Event(event, id, json));
        }
    }

    private static Frame decodeFrame(JsonObject json) {
        String data = str(json, "data", "");
        if (data.isEmpty()) {
            return null;
        }
        try {
            byte[] jpeg = Base64.getDecoder().decode(data);
            BufferedImage image = ImageDecode.read(jpeg);
            int width = Math.max(1, image.getWidth());
            int height = Math.max(1, image.getHeight());
            NativeImage nativeImage = ImageDecode.toNative(image, width, height);
            return new Frame(nativeImage, width, height, FRAME_SEQ.getAndIncrement());
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static void closeFrame(Tab tab) {
        Frame frame = tab.frame;
        tab.frame = null;
        if (frame != null && frame.image() != null) {
            frame.image().close();
        }
    }

    private static void notifyOwner(Tab tab, Event event) {
        Consumer<Event> owner = tab.owner;
        if (owner == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                try {
                    owner.accept(event);
                } catch (RuntimeException exception) {
                    HajikunSmartMod.LOGGER.warn("Chromium 事件处理失败：{}", exception.toString());
                }
            });
        } else {
            owner.accept(event);
        }
    }

    private static JsonObject message(String text) {
        JsonObject json = new JsonObject();
        json.addProperty("message", text);
        return json;
    }

    // ------------------------------------------------------------------ 指令

    /** 新建一个标签页并返回编号；失败返回 -1。 */
    public static int createTab(Consumer<Event> owner, int width, int height, float zoom) {
        if (!start()) {
            return -1;
        }
        int id = NEXT_TAB.getAndIncrement();
        Tab tab = new Tab(id);
        tab.owner = owner;
        tab.width = Math.max(80, width);
        tab.height = Math.max(60, height);
        TABS.put(id, tab);
        JsonObject request = new JsonObject();
        request.addProperty("op", "create");
        request.addProperty("tab", id);
        request.addProperty("w", tab.width);
        request.addProperty("h", tab.height);
        request.addProperty("zoom", zoom <= 0F ? 1F : zoom);
        send(request);
        return id;
    }

    public static void load(int tab, String url, int width, int height, float zoom) {
        JsonObject request = base("load", tab, width, height, zoom);
        request.addProperty("url", url == null ? "" : url);
        request.addProperty("internal", false);
        send(request);
    }

    /** 载入内置页面的 HTML；地址栏仍显示 {@code address}。 */
    public static void loadHtml(int tab, String address, String html, String baseUrl,
                                int width, int height, float zoom) {
        JsonObject request = base("html", tab, width, height, zoom);
        request.addProperty("url", address == null ? "" : address);
        request.addProperty("base", baseUrl == null ? "" : baseUrl);
        request.addProperty("html", html == null ? "" : html);
        send(request);
    }

    private static JsonObject base(String op, int tab, int width, int height, float zoom) {
        JsonObject request = new JsonObject();
        request.addProperty("op", op);
        request.addProperty("tab", tab);
        request.addProperty("w", Math.max(80, width));
        request.addProperty("h", Math.max(60, height));
        request.addProperty("zoom", zoom <= 0F ? 1F : zoom);
        return request;
    }

    public static void resize(int tab, int width, int height) {
        Tab entry = TABS.get(tab);
        if (entry == null) {
            return;
        }
        int w = Math.max(80, width);
        int h = Math.max(60, height);
        if (entry.width == w && entry.height == h) {
            return;
        }
        entry.width = w;
        entry.height = h;
        JsonObject request = new JsonObject();
        request.addProperty("op", "resize");
        request.addProperty("tab", tab);
        request.addProperty("w", w);
        request.addProperty("h", h);
        send(request);
    }

    public static void mouse(int tab, String type, float x, float y, int button, int clicks,
                             boolean shift, boolean ctrl, boolean alt) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "mouse");
        request.addProperty("tab", tab);
        request.addProperty("type", type);
        request.addProperty("x", Math.round(x));
        request.addProperty("y", Math.round(y));
        request.addProperty("button", button);
        request.addProperty("clicks", Math.max(1, clicks));
        request.add("modifiers", modifiers(shift, ctrl, alt));
        send(request);
    }

    public static void wheel(int tab, float x, float y, float dy, boolean shift) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "wheel");
        request.addProperty("tab", tab);
        request.addProperty("x", Math.round(x));
        request.addProperty("y", Math.round(y));
        request.addProperty("dy", Math.round(dy));
        request.add("modifiers", modifiers(shift, false, false));
        send(request);
    }

    public static void key(int tab, boolean down, String keyCode, boolean shift,
                           boolean ctrl, boolean alt) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "key");
        request.addProperty("tab", tab);
        request.addProperty("type", down ? "down" : "up");
        request.addProperty("key", keyCode);
        request.add("modifiers", modifiers(shift, ctrl, alt));
        send(request);
    }

    public static void text(int tab, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("op", "text");
        request.addProperty("tab", tab);
        request.addProperty("text", value);
        send(request);
    }

    public static void nav(int tab, String direction) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "nav");
        request.addProperty("tab", tab);
        request.addProperty("dir", direction);
        send(request);
    }

    public static void zoom(int tab, float zoom) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "zoom");
        request.addProperty("tab", tab);
        request.addProperty("zoom", zoom);
        send(request);
    }

    public static void mute(int tab, boolean muted) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "mute");
        request.addProperty("tab", tab);
        request.addProperty("mute", muted);
        send(request);
    }

    public static void find(int tab, String query, boolean forward, boolean next) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "find");
        request.addProperty("tab", tab);
        request.addProperty("query", query == null ? "" : query);
        request.addProperty("forward", forward);
        request.addProperty("next", next);
        send(request);
    }

    public static void reader(int tab, boolean on) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "readermode");
        request.addProperty("tab", tab);
        request.addProperty("on", on);
        send(request);
    }

    public static void closeTab(int tab) {
        Tab entry = TABS.remove(tab);
        if (entry != null) {
            synchronized (entry) {
                closeFrame(entry);
            }
            entry.closed = true;
        }
        JsonObject request = new JsonObject();
        request.addProperty("op", "close");
        request.addProperty("tab", tab);
        send(request);
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

    private static void send(JsonObject request) {
        if (!start()) {
            return;
        }
        sendRaw(request.toString());
    }

    private static void sendRaw(String line) {
        if (writer == null) {
            return;
        }
        if (!OUTBOX.offer(line)) {
            // 队列满时丢掉最旧的一条，保证鼠标移动不会把内存撑爆。
            OUTBOX.poll();
            OUTBOX.offer(line);
        }
    }

    private static com.google.gson.JsonArray modifiers(boolean shift, boolean ctrl, boolean alt) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        if (shift) {
            array.add("shift");
        }
        if (ctrl) {
            array.add("control");
        }
        if (alt) {
            array.add("alt");
        }
        return array;
    }

    private static String str(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int intOf(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static float floatOf(JsonObject json, String key, float fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsFloat();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return false;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
