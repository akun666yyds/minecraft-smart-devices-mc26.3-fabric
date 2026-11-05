package com.akun.hajikun.client.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

/**
 * 游戏内浏览器的网络层：真实 HTTP、Cookie 会话、表单提交与 OAuth 2.0 授权码流程。
 *
 * <p>网页先被解析成一串「块」：标题、正文、链接、图片、视频与表单字段。
 * 界面按块排版，链接可以直接点击，表单可以填写并提交；Cookie 保存在每台电脑
 * 自己的会话里，因此登录状态在页面之间保持。OAuth 使用 PKCE（RFC 7636），
 * 不保存 client_secret，适合游戏客户端这类公开客户端。</p>
 */
public final class BrowserClient {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<Long, Session> SESSIONS = new HashMap<>();
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "hajikun-browser");
                thread.setDaemon(true);
                return thread;
            });

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>",
            Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Set<String> SKIP_SCHEMES = Set.of(
            "javascript", "mailto", "tel", "data", "blob", "about", "file");
    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");
    private static final Set<String> BREAKS = Set.of(
            "p", "div", "section", "article", "header", "footer", "nav", "ul", "ol",
            "table", "tr", "blockquote", "pre", "main", "aside", "figure", "figcaption");

    private BrowserClient() {
    }

    /** 页面上可点击的链接：显示文字与绝对地址。 */
    public record Link(String text, String href) {
    }

    /** 页面块：正文、链接、图片、视频、表单字段与按钮。 */
    public enum Kind { HEADING, TEXT, LINK, IMAGE, VIDEO, FIELD, BUTTON, DIVIDER }

    public record Block(Kind kind, String text, String href, String src, int level,
                        String form, String name, String type, String value) {
        static Block text(Kind kind, String text) {
            return new Block(kind, text, "", "", 0, "", "", "", "");
        }

        static Block heading(int level, String text) {
            return new Block(Kind.HEADING, text, "", "", level, "", "", "", "");
        }

        static Block link(String text, String href) {
            return new Block(Kind.LINK, text, href, "", 0, "", "", "", "");
        }

        static Block image(String src, String alt) {
            return new Block(Kind.IMAGE, alt == null ? "" : alt, "", src, 0, "", "", "", "");
        }

        static Block video(String src, String poster) {
            return new Block(Kind.VIDEO, poster == null ? "" : poster, "", src, 0, "", "", "", "");
        }

        static Block field(String form, String name, String type, String value) {
            return new Block(Kind.FIELD, "", "", "", 0, form == null ? "" : form,
                    name == null ? "" : name, type == null ? "text" : type, value == null ? "" : value);
        }

        static Block button(String form, String label) {
            return new Block(Kind.BUTTON, label == null ? "提交" : label, "", "", 0,
                    form == null ? "" : form, "", "submit", "");
        }

        static Block divider() {
            return Block.text(Kind.DIVIDER, "");
        }
    }

    /** 一个表单：action 已经解析成绝对地址，method 统一转成小写。 */
    public record FormInfo(String id, String action, String method) {
    }

    /** 浏览器里加载结果。{@code error} 非空时界面显示错误而不是正文。 */
    public record Result(String url, String title, String text, List<Link> links, List<Block> blocks,
                         List<FormInfo> forms, String error, int status, String oauth,
                         String oauthToken) {
    }

    private static final class Session {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .cookieHandler(this.cookies)
                .build();
        private String profile = "";
        private String oauthAuthorize = "";
        private String oauthTokenUrl = "";
        private String oauthClientId = "";
        private String oauthClientSecret = "";
        private String oauthRedirect = "";
        private String oauthScope = "";
        private String oauthTokenFile = "";
        private String oauthState = "";
        private String oauthVerifier = "";
    }

    private static Session session(BlockPos pos) {
        return SESSIONS.computeIfAbsent(pos.asLong(), ignored -> new Session());
    }

    /** 每个网页请求都带上一台电脑自己的 Cookie 会话。 */
    public static void fetch(BlockPos pos, String rawUrl, Consumer<Result> callback) {
        open(pos, normalize(rawUrl), "GET", null, null, callback);
    }

    /** 兼容旧调用：没有电脑坐标时使用一个临时会话。 */
    public static void fetch(String rawUrl, Consumer<Result> callback) {
        open(BlockPos.ZERO, normalize(rawUrl), "GET", null, null, callback);
    }

    /** 提交表单：action 可以为空，表示提交到当前页面。 */
    public static void submit(BlockPos pos, String pageUrl, String action, String method,
                              Map<String, String> values, Consumer<Result> callback) {
        String target = action == null || action.isBlank() ? pageUrl : resolve(pageUrl, action.trim());
        if (target == null) {
            callback.accept(error(pageUrl, "表单地址无效"));
            return;
        }
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        boolean get = method == null || method.equalsIgnoreCase("get");
        if (get) {
            String separator = target.contains("?") ? "&" : "?";
            open(pos, target + (body.length() == 0 ? "" : separator + body), "GET", null, null, callback);
        } else {
            open(pos, target, "POST", body.toString(),
                    "application/x-www-form-urlencoded; charset=UTF-8", callback);
        }
    }

    /** 图片等二进制资源；同样带上会话 Cookie。 */
    public static void bytes(BlockPos pos, String rawUrl, ByteConsumer callback) {
        String url = absolute(rawUrl);
        if (url.isEmpty()) {
            callback.accept(null, "", "不支持的图片地址");
            return;
        }
        if (url.startsWith("data:")) {
            byte[] data = WebMedia.dataUri(url);
            callback.accept(data, data == null ? "" : "image", data == null ? "data URI 解码失败" : null);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                HttpResponse<byte[]> response = session(pos).client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(15))
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "image/*,*/*;q=0.8")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                byte[] data = response.body();
                if (response.statusCode() >= 400) {
                    callback.accept(null, "", "HTTP " + response.statusCode());
                    return;
                }
                if (data == null || data.length > MAX_IMAGE_BYTES) {
                    callback.accept(null, "", "图片过大");
                    return;
                }
                String mime = response.headers().firstValue("content-type").orElse("image/*");
                callback.accept(data, mime, null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                callback.accept(null, "", "请求被中断");
            } catch (Exception exception) {
                callback.accept(null, "", "下载失败：" + shortMessage(exception));
            }
        });
    }

    public interface ByteConsumer {
        void accept(byte[] data, String mime, String error);
    }

    // ------------------------------------------------------------------ 会话持久化

    /** 从虚拟机里的浏览器配置文件恢复 Cookie。 */
    public static void loadProfile(BlockPos pos, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Session session = session(pos);
            session.profile = json;
            if (root.has("cookies") && root.get("cookies").isJsonArray()) {
                for (var element : root.getAsJsonArray("cookies")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject cookie = element.getAsJsonObject();
                    HttpCookie parsed = new HttpCookie(str(cookie, "name", ""), str(cookie, "value", ""));
                    parsed.setDomain(str(cookie, "domain", ""));
                    parsed.setPath(str(cookie, "path", "/"));
                    if (cookie.has("secure")) {
                        parsed.setSecure(cookie.get("secure").getAsBoolean());
                    }
                    session.cookies.getCookieStore().add(URI.create("https://"
                            + parsed.getDomain() + parsed.getPath()), parsed);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** 生成可写回虚拟机文件的 Cookie 配置。 */
    public static String profile(BlockPos pos) {
        Session session = session(pos);
        JsonObject root = new JsonObject();
        JsonArray cookies = new JsonArray();
        for (HttpCookie cookie : session.cookies.getCookieStore().getCookies()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", cookie.getName());
            item.addProperty("value", cookie.getValue());
            item.addProperty("domain", cookie.getDomain() == null ? "" : cookie.getDomain());
            item.addProperty("path", cookie.getPath() == null ? "/" : cookie.getPath());
            item.addProperty("secure", cookie.getSecure());
            cookies.add(item);
        }
        root.add("cookies", cookies);
        return root.toString();
    }

    /** Cookie 有变化时返回 true，界面据此把配置写回虚拟机。 */
    public static boolean profileDirty(BlockPos pos) {
        Session session = session(pos);
        return !profile(pos).equals(session.profile);
    }

    public static void profileSaved(BlockPos pos) {
        Session session = session(pos);
        session.profile = profile(pos);
    }

    // ------------------------------------------------------------------ OAuth 2.0

    /**
     * 开始第三方授权登录：生成 PKCE code_verifier / code_challenge 与 state，
     * 返回授权页地址，直接用浏览器打开即可。provider 从虚拟机里的配置文件读取。
     */
    public static String beginOAuth(BlockPos pos, JsonObject provider) {
        Session session = session(pos);
        session.oauthAuthorize = str(provider, "authorizeUrl", "");
        session.oauthTokenUrl = str(provider, "tokenUrl", "");
        session.oauthClientId = str(provider, "clientId", "");
        session.oauthClientSecret = str(provider, "clientSecret", "");
        session.oauthRedirect = str(provider, "redirectUri", "");
        session.oauthScope = str(provider, "scope", "");
        session.oauthTokenFile = str(provider, "tokenFile", "/home/user/.config/oauth-token.json");
        if (session.oauthAuthorize.isBlank() || session.oauthClientId.isBlank()
                || session.oauthRedirect.isBlank()) {
            return "";
        }
        session.oauthVerifier = randomToken(48);
        session.oauthState = randomToken(24);
        String challenge = base64Url(sha256(session.oauthVerifier));
        StringBuilder url = new StringBuilder(session.oauthAuthorize);
        url.append(session.oauthAuthorize.contains("?") ? "&" : "?");
        url.append("response_type=code")
                .append("&client_id=").append(encode(session.oauthClientId))
                .append("&redirect_uri=").append(encode(session.oauthRedirect))
                .append("&state=").append(encode(session.oauthState))
                .append("&code_challenge=").append(encode(challenge))
                .append("&code_challenge_method=S256");
        if (!session.oauthScope.isBlank()) {
            url.append("&scope=").append(encode(session.oauthScope));
        }
        return url.toString();
    }

    public static boolean oauthPending(BlockPos pos) {
        return !session(pos).oauthState.isBlank();
    }

    /** 授权页回跳后换取令牌；返回提示文字与令牌原文。 */
    private static OAuthOutcome finishOAuth(Session session, String callbackUrl) {
        if (session.oauthState.isBlank()) {
            return new OAuthOutcome("", "");
        }
        Map<String, String> query = query(callbackUrl);
        String state = query.getOrDefault("state", "");
        String code = query.getOrDefault("code", "");
        String error = query.getOrDefault("error", "");
        if (!error.isBlank()) {
            session.oauthState = "";
            return new OAuthOutcome("OAuth 授权被拒绝：" + error, "");
        }
        if (code.isBlank()) {
            return new OAuthOutcome("", "");
        }
        if (!state.equals(session.oauthState)) {
            session.oauthState = "";
            return new OAuthOutcome("OAuth 回调 state 校验失败，已中止。", "");
        }
        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(session.oauthRedirect)
                + "&client_id=" + encode(session.oauthClientId)
                + "&code_verifier=" + encode(session.oauthVerifier);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(session.oauthTokenUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!session.oauthClientSecret.isBlank()) {
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (session.oauthClientId + ":" + session.oauthClientSecret)
                                .getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = session.client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 != 2) {
                session.oauthState = "";
                return new OAuthOutcome("OAuth 令牌交换失败（HTTP " + response.statusCode() + "）："
                        + text, "");
            }
            String kind = "Bearer";
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                kind = str(json, "token_type", "Bearer");
                if (!json.has("access_token")) {
                    session.oauthState = "";
                    return new OAuthOutcome("OAuth 响应里没有 access_token。", "");
                }
            } catch (RuntimeException ignored) {
            }
            session.oauthState = "";
            session.profile = "";
            return new OAuthOutcome("OAuth 登录成功（" + kind + "），令牌已保存。", text);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new OAuthOutcome("OAuth 令牌交换被中断。", "");
        } catch (Exception exception) {
            return new OAuthOutcome("OAuth 令牌交换失败：" + shortMessage(exception), "");
        }
    }

    private record OAuthOutcome(String message, String token) {
    }

    // ------------------------------------------------------------------ HTTP

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private static void open(BlockPos pos, String url, String method, String body,
                             String contentType, Consumer<Result> callback) {
        EXECUTOR.execute(() -> {
            Session session = session(pos);
            Result result;
            String oauth = "";
            String token = "";
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                if ("POST".equals(method)) {
                    builder.header("Content-Type", contentType == null
                            ? "application/x-www-form-urlencoded; charset=UTF-8" : contentType);
                    builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                } else {
                    builder.GET();
                }
                HttpResponse<String> response = session.client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                String finalUrl = response.uri().toString();
                String page = response.body() == null ? "" : response.body();
                OAuthOutcome outcome = finishOAuth(session, finalUrl);
                oauth = outcome.message();
                token = outcome.token();
                Page parsed = parse(page, finalUrl);
                String statusText = parsed.text();
                if (!oauth.isBlank()) {
                    statusText = oauth + "\n\n" + statusText;
                    parsed.blocks.add(0, Block.text(Kind.TEXT, oauth));
                }
                result = new Result(finalUrl, parsed.title(), statusText, parsed.links(), parsed.blocks(),
                        parsed.forms(), null, response.statusCode(), oauth, token);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result = error(url, "请求被中断");
            } catch (Exception exception) {
                result = error(url, "无法访问：" + shortMessage(exception));
            }
            callback.accept(result);
        });
    }

    private static Result error(String url, String message) {
        return new Result(url, "", "", List.of(), List.of(), List.of(), message, 0, "", "");
    }

    private static String shortMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }

    /** 允许直接输入 bilibili.com 这样的简写；不像网址的输入交给必应搜索。 */
    public static String normalize(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            return "https://www.bing.com/";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        boolean chinese = url.codePoints().anyMatch(code -> code >= 0x4E00 && code <= 0x9FFF);
        if (chinese || url.indexOf(' ') >= 0 || url.indexOf('.') < 0) {
            return "https://www.bing.com/search?q=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        }
        return "https://" + url;
    }

    // ------------------------------------------------------------------ HTML 解析

    /** 媒体下载只接受解析阶段已转好的绝对地址或 data URI。 */
    private static String absolute(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String value = rawUrl.trim();
        if (value.startsWith("data:")) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return "";
    }

    private record Page(String title, String text, List<Link> links, List<Block> blocks,
                        List<FormInfo> forms) {
    }

    private static Page parse(String html, String baseUrl) {
        String title = extractTitle(html);
        List<Block> blocks = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        List<FormInfo> forms = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        StringBuilder text = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        StringBuilder linkText = null;
        String linkHref = null;
        String formId = "";
        String formAction = "";
        String formMethod = "get";
        String textareaName = "";
        String videoSrc = "";
        String videoPoster = "";
        int heading = 0;
        int headDepth = 0;
        int skipDepth = 0;
        int last = 0;
        Matcher matcher = TAG.matcher(html);
        while (matcher.find()) {
            String between = html.substring(last, matcher.start());
            last = matcher.end();
            if (headDepth == 0 && skipDepth == 0) {
                appendText(linkText != null ? linkText : buffer, between);
            }
            boolean closing = !matcher.group(1).isEmpty();
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            Map<String, String> attrs = attributes(matcher.group(3));

            if (tag.equals("head")) {
                headDepth += closing ? -1 : 1;
                continue;
            }
            if (tag.equals("script") || tag.equals("style") || tag.equals("noscript")
                    || tag.equals("template")) {
                skipDepth += closing ? -1 : 1;
                continue;
            }
            if (headDepth > 0 || skipDepth > 0) {
                continue;
            }
            if (tag.equals("title")) {
                continue;
            }
            if (HEADINGS.contains(tag)) {
                if (closing) {
                    flush(blocks, text, buffer, heading == 0 ? 0 : heading, formId);
                    heading = 0;
                } else {
                    flush(blocks, text, buffer, 0, formId);
                    heading = tag.charAt(1) - '0';
                }
                continue;
            }
            if (BREAKS.contains(tag) || tag.equals("br") || tag.equals("li") || tag.equals("hr")) {
                flush(blocks, text, buffer, 0, formId);
                if (tag.equals("hr")) {
                    blocks.add(Block.divider());
                } else if (tag.equals("li") && !closing) {
                    buffer.append("• ");
                }
                continue;
            }
            if (tag.equals("a")) {
                if (!closing) {
                    flush(blocks, text, buffer, 0, formId);
                    linkHref = resolve(baseUrl, attrs.getOrDefault("href", "").trim());
                    linkText = linkHref == null ? null : new StringBuilder();
                } else if (linkText != null) {
                    String label = clean(linkText.toString());
                    linkText = null;
                    if (!label.isEmpty() && linkHref != null) {
                        blocks.add(Block.link(label, linkHref));
                        appendLine(text, label);
                        if (seenLinks.add(linkHref)) {
                            links.add(new Link(truncate(label, 34), linkHref));
                        }
                    }
                    linkHref = null;
                }
                continue;
            }
            if (tag.equals("img")) {
                flush(blocks, text, buffer, 0, formId);
                String src = resolve(baseUrl, attrs.getOrDefault("src", "").trim());
                if (src != null) {
                    blocks.add(Block.image(src, attrs.getOrDefault("alt", "")));
                }
                continue;
            }
            if (tag.equals("video")) {
                flush(blocks, text, buffer, 0, formId);
                if (closing) {
                    if (!videoSrc.isBlank()) {
                        blocks.add(Block.video(videoSrc, videoPoster));
                    }
                    videoSrc = "";
                    videoPoster = "";
                } else {
                    videoSrc = resolve(baseUrl, attrs.getOrDefault("src", "").trim());
                    videoPoster = resolve(baseUrl, attrs.getOrDefault("poster", "").trim());
                }
                continue;
            }
            if (tag.equals("source") && !videoSrc.isBlank()) {
                String src = resolve(baseUrl, attrs.getOrDefault("src", "").trim());
                if (src != null) {
                    videoSrc = src;
                }
                continue;
            }
            if (tag.equals("form")) {
                flush(blocks, text, buffer, 0, formId);
                if (closing) {
                    formId = "";
                } else {
                    formId = attrs.getOrDefault("id", "form" + forms.size());
                    formAction = resolve(baseUrl, attrs.getOrDefault("action", "").trim());
                    formMethod = attrs.getOrDefault("method", "get").toLowerCase(Locale.ROOT);
                    forms.add(new FormInfo(formId, formAction == null ? baseUrl : formAction,
                            formMethod.equals("post") ? "post" : "get"));
                }
                continue;
            }
            if (tag.equals("input")) {
                String type = attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
                String name = attrs.getOrDefault("name", "");
                if (type.equals("hidden")) {
                    continue;
                }
                if (type.equals("submit") || type.equals("button") || type.equals("image")) {
                    blocks.add(Block.button(formId, firstNonBlank(
                            attrs.get("value"), type.equals("submit") ? "提交" : "按钮")));
                } else if (!name.isBlank()) {
                    blocks.add(Block.field(formId, name, type, attrs.getOrDefault("value", "")));
                }
                continue;
            }
            if (tag.equals("button")) {
                if (closing) {
                    String label = clean(buffer.toString());
                    buffer.setLength(0);
                    blocks.add(Block.button(formId, label.isEmpty() ? "提交" : label));
                } else {
                    flush(blocks, text, buffer, 0, formId);
                }
                continue;
            }
            if (tag.equals("textarea")) {
                if (closing) {
                    blocks.add(Block.field(formId, textareaName, "textarea", clean(buffer.toString())));
                    buffer.setLength(0);
                    textareaName = "";
                } else {
                    flush(blocks, text, buffer, 0, formId);
                    textareaName = attrs.getOrDefault("name", "");
                }
                continue;
            }
            if (tag.equals("select")) {
                if (closing) {
                    String name = attrs.getOrDefault("name", "");
                    if (!name.isBlank()) {
                        blocks.add(Block.field(formId, name, "select", ""));
                    }
                    buffer.setLength(0);
                }
                continue;
            }
        }
        flush(blocks, text, buffer, heading, formId);
        String body = text.toString().trim();
        return new Page(title, body, links, blocks, forms);
    }

    private static void appendText(StringBuilder buffer, String raw) {
        if (raw.isEmpty()) {
            return;
        }
        buffer.append(decode(raw));
    }

    private static void flush(List<Block> blocks, StringBuilder text, StringBuilder buffer,
                              int heading, String formId) {
        String value = clean(buffer.toString());
        buffer.setLength(0);
        if (value.isEmpty()) {
            return;
        }
        blocks.add(heading > 0 ? Block.heading(heading, value) : Block.text(Kind.TEXT, value));
        appendLine(text, value);
    }

    private static void appendLine(StringBuilder text, String line) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(line);
    }

    private static String clean(String raw) {
        return raw.replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private static String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String resolve(String baseUrl, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String value = href.trim();
        if (value.startsWith("#")) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon > 0 && SKIP_SCHEMES.contains(value.substring(0, colon).toLowerCase(Locale.ROOT))) {
            return null;
        }
        try {
            URI resolved = URI.create(baseUrl).resolve(value.replace(" ", "%20"));
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            return resolved.toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, String> attributes(String raw) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(raw == null ? "" : raw);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            attrs.put(matcher.group(1).toLowerCase(Locale.ROOT), decode(value == null ? "" : value));
        }
        return attrs;
    }

    static String extractTitle(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? decode(matcher.group(1)).trim() : "";
    }

    private static String decode(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement;
            if (token.startsWith("#x") || token.startsWith("#X")) {
                replacement = codePoint(Integer.parseInt(token.substring(2), 16));
            } else if (token.startsWith("#")) {
                replacement = codePoint(Integer.parseInt(token.substring(1)));
            } else {
                replacement = switch (token.toLowerCase(Locale.ROOT)) {
                    case "nbsp" -> " ";
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "middot" -> "·";
                    case "mdash" -> "—";
                    case "ndash" -> "–";
                    case "hellip" -> "…";
                    case "copy" -> "©";
                    case "reg" -> "®";
                    default -> "";
                };
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String codePoint(int code) {
        return code > 0 && code <= 0x10FFFF ? new String(Character.toChars(code)) : "";
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new HashMap<>();
        int mark = url.indexOf('?');
        if (mark < 0) {
            return values;
        }
        String query = url.substring(mark + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            try {
                values.put(java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return values;
    }

    private static String str(JsonObject json, String key, String fallback) {
        return json != null && json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsString() : fallback;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return base64Url(data);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
