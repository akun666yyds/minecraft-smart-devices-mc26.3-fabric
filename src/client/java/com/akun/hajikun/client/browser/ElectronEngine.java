package com.akun.hajikun.client.browser;

import com.akun.hajikun.HajikunSmartMod;
import com.akun.hajikun.client.media.ImageDecode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 内嵌的 Electron 浏览器 SDK：Java 启动 {@code node.exe}，通过标准输入输出的一行一条
 * JSON 协议把抓取、Cookie、HTML/CSS 排版交给 Node，Java 只把返回的显示列表画到
 * 虚拟机屏幕上。
 *
 * <p>Node 不可用时 {@link #available()} 返回 false，界面继续使用纯 Java 的
 * {@code BrowserClient} 作为兜底，因此离线或没装 Node 的玩家也不会卡死。</p>
 */
public final class ElectronEngine {
    private static final int SDK_REVISION = 4;
    private static final int MAX_TEXTURES = 64;
    private static final int TEX_WIDTH = 256;
    private static final int TEX_HEIGHT = 168;

    private static final Object WRITE_LOCK = new Object();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, Consumer<JsonObject>> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, int[]> TEXTURE_SIZES = new ConcurrentHashMap<>();
    private static final Map<String, Identifier> TEXTURES =
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                    if (this.size() > MAX_TEXTURES) {
                        Minecraft client = Minecraft.getInstance();
                        if (client != null) {
                            client.getTextureManager().release(eldest.getValue());
                        }
                        return true;
                    }
                    return false;
                }
            };

    private static Process process;
    private static BufferedWriter writer;
    private static Thread readerThread;
    private static volatile boolean available;
    private static volatile String engineName = "";
    private static volatile String failure = "";

    private ElectronEngine() {
    }

    // ------------------------------------------------------------------ 数据模型

    /** 显示列表里的一个元素；{@code kind} 决定使用哪些字段。 */
    public record Item(String kind, int x, int y, int w, int h, String text, int color,
                       float scale, boolean bold, String href, String name, String form,
                       String value, String placeholder, String label, String src, String alt,
                       String action, String method, boolean box, boolean checked,
                       boolean password) {
    }

    /** 已解码的网页图片。 */
    public record Picture(Identifier texture, int width, int height) {
    }

    /** 一次排版结果；{@code error} 非空时界面只显示错误信息。 */
    public record Page(String url, String title, List<Item> items, int height, int background,
                       Map<String, Picture> pictures, List<Cookie> cookies, boolean reader,
                       int status, boolean fromCache, String error, String engine) {
        static Page error(String url, String message) {
            return new Page(url, "", List.of(), 40, 0xFFFFFFFF, Map.of(), List.of(), false, 0,
                    false, message, engineName);
        }
    }

    /** 页内查找的命中矩形，坐标与显示列表一致。 */
    public record Match(int x, int y, int w, int h) {
    }

    /** 下载结果。 */
    public record Download(byte[] data, String mime, String name, String error) {
    }

    // ------------------------------------------------------------------ 生命周期

    public static boolean available() {
        return available && process != null && process.isAlive();
    }

    public static String engine() {
        return engineName;
    }

    public static String failure() {
        return failure;
    }

    /**
     * 已停用：浏览器改由进程内的 JavaFX WebKit 渲染，这里不再启动 node.exe。
     * 仅保留旧排版数据结构给纯 Java 兜底页面使用。
     */
    public static synchronized boolean start() {
        failure = "外部进程渲染已停用，请使用 WebKit 内核";
        available = false;
        return false;
    }

    private static synchronized boolean startLegacyUnused() {
        if (process != null && process.isAlive()) {
            return true;
        }
        failure = "";
        try {
            String node = findNode();
            if (node == null) {
                failure = "未找到 node.exe（可设置环境变量 HAJIKUN_NODE）";
                available = false;
                return false;
            }
            Path script = extractSdk();
            ProcessBuilder builder = new ProcessBuilder(node, script.toString());
            builder.redirectErrorStream(false);
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            Thread stderr = new Thread(() -> drainError(process.getErrorStream()), "hajikun-electron-err");
            stderr.setDaemon(true);
            stderr.start();
            readerThread = new Thread(() -> readLoop(reader), "hajikun-electron");
            readerThread.setDaemon(true);
            readerThread.start();
            available = true;
            sendHello();
            HajikunSmartMod.LOGGER.info("Electron 浏览器 SDK 已启动：{}", node);
            return true;
        } catch (IOException | RuntimeException exception) {
            failure = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            available = false;
            HajikunSmartMod.LOGGER.warn("Electron 浏览器 SDK 启动失败：{}", failure);
            return false;
        }
    }

    /** 断开连接时结束 Node 进程，避免后台残留。 */
    public static synchronized void shutdown() {
        unavailable("客户端已断开");
        for (Identifier texture : TEXTURES.values()) {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.getTextureManager().release(texture);
            }
        }
        TEXTURES.clear();
        if (process != null) {
            process.destroy();
            process = null;
        }
        writer = null;
        readerThread = null;
    }

    private static void unavailable(String reason) {
        available = false;
        failure = reason;
        for (Map.Entry<Integer, Consumer<JsonObject>> entry : PENDING.entrySet()) {
            Consumer<JsonObject> callback = entry.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("ok", false);
            json.addProperty("error", reason);
            dispatch(callback, json);
        }
        PENDING.clear();
    }

    private static String findNode() {
        String custom = System.getenv("HAJIKUN_NODE");
        if (custom != null && !custom.isBlank() && Files.isRegularFile(Path.of(custom))) {
            return custom;
        }
        List<String> candidates = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFiles != null) {
            candidates.add(programFiles + "\\nodejs\\node.exe");
        }
        if (programFilesX86 != null) {
            candidates.add(programFilesX86 + "\\nodejs\\node.exe");
        }
        if (localAppData != null) {
            candidates.add(localAppData + "\\Programs\\nodejs\\node.exe");
            candidates.add(localAppData + "\\nvm\\current\\node.exe");
        }
        candidates.add("C:\\Program Files\\nodejs\\node.exe");
        for (String candidate : candidates) {
            if (candidate != null && Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        // 交给 PATH 查找；存在时 ProcessBuilder 能直接启动。
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path exe = Path.of(dir, "node.exe");
                if (Files.isRegularFile(exe)) {
                    return exe.toString();
                }
            }
        }
        return null;
    }

    private static Path extractSdk() throws IOException {
        String resource = "/assets/hajikun_smart/electron/browser.mjs";
        byte[] data;
        try (InputStream stream = ElectronEngine.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("模组里缺少 " + resource);
            }
            data = stream.readAllBytes();
        }
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("hajikun/electron");
        Files.createDirectories(dir);
        Path script = dir.resolve("browser.mjs");
        if (!Files.isRegularFile(script) || !java.util.Arrays.equals(Files.readAllBytes(script), data)) {
            Files.write(script, data);
        }
        Path stamp = dir.resolve("sdk-revision.txt");
        Files.writeString(stamp, Integer.toString(SDK_REVISION));
        return script;
    }

    private static void drainError(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    HajikunSmartMod.LOGGER.warn("Electron SDK: {}", line);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void readLoop(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException ignored) {
        }
        if (available) {
            unavailable("Node 进程已退出");
        }
    }

    private static void handleLine(String line) {
        JsonObject json;
        try {
            json = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException exception) {
            return;
        }
        if (json.has("event") && "ready".equals(json.get("event").getAsString())) {
            engineName = str(json, "engine", engineName);
            return;
        }
        if (!json.has("id")) {
            if (json.has("error")) {
                HajikunSmartMod.LOGGER.warn("Electron SDK 未归属错误：{}", str(json, "error", ""));
            }
            return;
        }
        int id;
        try {
            id = json.get("id").getAsInt();
        } catch (RuntimeException exception) {
            return;
        }
        Consumer<JsonObject> callback = PENDING.remove(id);
        if (callback != null) {
            dispatch(callback, json);
        }
    }

    private static void dispatch(Consumer<JsonObject> callback, JsonObject json) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> callback.accept(json));
        } else {
            callback.accept(json);
        }
    }

    // ------------------------------------------------------------------ 指令

    private static void sendHello() {
        JsonObject request = new JsonObject();
        request.addProperty("op", "hello");
        JsonObject font = new JsonObject();
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.font != null) {
            for (int code = 32; code < 127; code++) {
                font.addProperty(Integer.toString(code),
                        client.font.width(new String(Character.toChars(code))));
            }
            int[] extras = {0x00B7, 0x00D7, 0x2022, 0x2026, 0x2014, 0x2713, 0x25B8, 0x25BE,
                    0x2606, 0x2315, 0x2630, 0x2190, 0x2192, 0x21BB, 0x2302};
            for (int code : extras) {
                String text = new String(Character.toChars(code));
                font.addProperty(Integer.toString(code), client.font.width(text));
            }
            font.addProperty("cjk", client.font.width("中"));
        }
        request.add("font", font);
        request.addProperty("defaultWidth", 6);
        request.addProperty("cjkWidth", 9);
        send(request, null);
    }

    private static int send(JsonObject request, Consumer<JsonObject> callback) {
        if (!start()) {
            if (callback != null) {
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("error", failure);
                dispatch(callback, error);
            }
            return -1;
        }
        int id = NEXT_ID.getAndIncrement();
        request.addProperty("id", id);
        if (callback != null) {
            PENDING.put(id, callback);
        }
        try {
            synchronized (WRITE_LOCK) {
                writer.write(request.toString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException exception) {
            PENDING.remove(id);
            available = false;
            if (callback != null) {
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("error", "写入 Node 失败：" + exception.getMessage());
                dispatch(callback, error);
            }
        }
        return id;
    }

    /** 打开一个网址；{@code html} 非空时直接排版这段 HTML（electron:// 页面）。 */
    public static void navigate(long session, String url, int width, float zoom, boolean reader,
                                String html, String base, List<Cookie> cookies,
                                Consumer<Page> callback) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "navigate");
        request.addProperty("session", session);
        request.addProperty("url", url == null ? "" : url);
        request.addProperty("width", Math.max(80, width));
        request.addProperty("zoom", zoom <= 0F ? 1F : zoom);
        request.addProperty("reader", reader);
        if (html != null) {
            request.addProperty("html", html);
            request.addProperty("base", base == null ? url : base);
        }
        request.add("cookies", cookieJson(cookies));
        send(request, json -> callback.accept(page(json, url)));
    }

    /** 重新排版（缩放、最大化、阅读模式），不重新联网。 */
    public static void relayout(long session, String url, int width, float zoom, boolean reader,
                                Consumer<Page> callback) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "relayout");
        request.addProperty("session", session);
        request.addProperty("url", url == null ? "" : url);
        request.addProperty("width", Math.max(80, width));
        request.addProperty("zoom", zoom <= 0F ? 1F : zoom);
        request.addProperty("reader", reader);
        send(request, json -> callback.accept(page(json, url)));
    }

    /** 提交表单。 */
    public static void submit(long session, String pageUrl, String action, String method,
                              Map<String, String> values, int width, float zoom, boolean reader,
                              List<Cookie> cookies, Consumer<Page> callback) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "submit");
        request.addProperty("session", session);
        request.addProperty("pageUrl", pageUrl == null ? "" : pageUrl);
        request.addProperty("action", action == null ? "" : action);
        request.addProperty("method", method == null ? "get" : method);
        request.addProperty("width", Math.max(80, width));
        request.addProperty("zoom", zoom <= 0F ? 1F : zoom);
        request.addProperty("reader", reader);
        JsonObject body = new JsonObject();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                body.addProperty(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        request.add("values", body);
        request.add("cookies", cookieJson(cookies));
        send(request, json -> callback.accept(page(json, pageUrl)));
    }

    /** 页内查找。 */
    public static void find(long session, String query, Consumer<List<Match>> callback) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "find");
        request.addProperty("session", session);
        request.addProperty("query", query == null ? "" : query);
        send(request, json -> {
            List<Match> matches = new ArrayList<>();
            for (JsonElement element : array(json, "matches")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                matches.add(new Match(intOf(item, "x", 0), intOf(item, "y", 0),
                        intOf(item, "w", 0), intOf(item, "h", 0)));
            }
            callback.accept(matches);
        });
    }

    /** 下载一个 http(s) 资源，返回原始字节。 */
    public static void download(long session, String url, Consumer<Download> callback) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "download");
        request.addProperty("session", session);
        request.addProperty("url", url == null ? "" : url);
        send(request, json -> {
            if (!bool(json, "ok")) {
                callback.accept(new Download(null, "", "", str(json, "error", "下载失败")));
                return;
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(str(json, "data", ""));
            } catch (IllegalArgumentException exception) {
                callback.accept(new Download(null, "", "", "下载数据损坏"));
                return;
            }
            callback.accept(new Download(data, str(json, "mime", "application/octet-stream"),
                    str(json, "name", "download.bin"), ""));
        });
    }

    /** 与 Node 保持一致的 Cookie 结构，由界面从虚拟机文件里读取。 */
    public record Cookie(String name, String value, String domain, long expires) {
    }

    private static JsonArray cookieJson(List<Cookie> cookies) {
        JsonArray array = new JsonArray();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie == null || cookie.name() == null || cookie.name().isBlank()) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("name", cookie.name());
                item.addProperty("value", cookie.value() == null ? "" : cookie.value());
                item.addProperty("domain", cookie.domain() == null ? "" : cookie.domain());
                item.addProperty("expires", cookie.expires());
                array.add(item);
            }
        }
        return array;
    }

    // ------------------------------------------------------------------ 解析

    private static Page page(JsonObject json, String fallbackUrl) {
        if (!bool(json, "ok")) {
            return Page.error(fallbackUrl, str(json, "error", "浏览器引擎请求失败"));
        }
        String url = str(json, "url", fallbackUrl);
        String title = str(json, "title", "");
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String kind = str(item, "t", "");
            if (kind.isBlank()) {
                continue;
            }
            items.add(new Item(kind, intOf(item, "x", 0), intOf(item, "y", 0),
                    intOf(item, "w", 0), intOf(item, "h", 0), str(item, "s", ""),
                    color(str(item, "c", "")), floatOf(item, "z", 1F), bool(item, "b"),
                    str(item, "href", ""), str(item, "name", ""), str(item, "form", ""),
                    str(item, "value", ""), str(item, "placeholder", ""),
                    str(item, "label", ""), str(item, "src", ""), str(item, "alt", ""),
                    str(item, "action", ""), str(item, "method", ""),
                    intOf(item, "check", 0) != 0, bool(item, "checked"),
                    intOf(item, "password", 0) != 0));
        }
        Map<String, Picture> pictures = new LinkedHashMap<>();
        for (JsonElement element : array(json, "images")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject image = element.getAsJsonObject();
            String src = str(image, "src", "");
            String data = str(image, "data", "");
            if (src.isBlank() || data.isBlank()) {
                continue;
            }
            Picture picture = texture(src, data, str(image, "mime", "image/png"));
            if (picture != null) {
                pictures.put(src, picture);
            }
        }
        List<Cookie> cookies = new ArrayList<>();
        for (JsonElement element : array(json, "cookies")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cookie = element.getAsJsonObject();
            String name = str(cookie, "name", "");
            if (name.isBlank()) {
                continue;
            }
            cookies.add(new Cookie(name, str(cookie, "value", ""),
                    str(cookie, "domain", ""), longOf(cookie, "expires", 0L)));
        }
        return new Page(url, title, List.copyOf(items), Math.max(0, intOf(json, "height", 0)),
                color(str(json, "background", "#FFFFFF")), pictures, List.copyOf(cookies),
                bool(json, "reader"), intOf(json, "status", 0), bool(json, "fromCache"),
                "", str(json, "engine", engineName));
    }

    private static Picture texture(String src, String base64, String mime) {
        String key;
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64);
            key = hash(data);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        Identifier cached = TEXTURES.get(key);
        if (cached != null) {
            int[] size = TEXTURE_SIZES.getOrDefault(key, new int[]{1, 1});
            return new Picture(cached, size[0], size[1]);
        }
        try {
            BufferedImage source = ImageDecode.read(data);
            int sw = Math.max(1, source.getWidth());
            int sh = Math.max(1, source.getHeight());
            double scale = Math.min(1.0D, Math.min(TEX_WIDTH / (double) sw, TEX_HEIGHT / (double) sh));
            int dw = Math.max(1, (int) Math.round(sw * scale));
            int dh = Math.max(1, (int) Math.round(sh * scale));
            NativeImage nativeImage = ImageDecode.toNative(source, dw, dh);
            DynamicTexture texture = new DynamicTexture(() -> "Electron image", nativeImage);
            texture.upload();
            Identifier identifier = Identifier.fromNamespaceAndPath("hajikun", "electron/" + key);
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                texture.close();
                return null;
            }
            client.getTextureManager().register(identifier, texture);
            TEXTURES.put(key, identifier);
            TEXTURE_SIZES.put(key, new int[]{dw, dh});
            return new Picture(identifier, dw, dh);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16));
                out.append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (Exception exception) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    /** 把 Node 的 #RRGGBB / #AARRGGBB / #RGB 转成画布用的 ARGB。 */
    public static int color(String value) {
        if (value == null) {
            return 0;
        }
        String text = value.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("transparent")) {
            return 0;
        }
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            if (text.length() == 3) {
                int r = Integer.parseInt(text.substring(0, 1), 16);
                int g = Integer.parseInt(text.substring(1, 2), 16);
                int b = Integer.parseInt(text.substring(2, 3), 16);
                return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
            long parsed = Long.parseLong(text, 16);
            if (text.length() <= 6) {
                return 0xFF000000 | (int) parsed;
            }
            return (int) parsed;
        } catch (NumberFormatException exception) {
            return 0xFF202020;
        }
    }

    private static JsonArray array(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return json.getAsJsonArray(key);
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

    private static long longOf(JsonObject json, String key, long fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsLong();
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
