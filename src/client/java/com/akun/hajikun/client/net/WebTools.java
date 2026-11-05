package com.akun.hajikun.client.net;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/**
 * 智能体与 Chrome 共用的真实 Web 访问：搜索、抓取正文。
 *
 * <p>搜索走 DuckDuckGo 的轻量 HTML 入口，失败时回退到 Bing；网页会被压成纯文本，
 * 避免把脚本和样式塞进上下文。所有请求都在后台线程执行，结果回到客户端线程。</p>
 */
public final class WebTools {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "hajikun-web");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final Pattern DDG_ITEM = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=<a[^>]+class=\"result__a\"|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "result__snippet[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BING_ITEM = Pattern.compile(
            "<li class=\"b_algo\".*?<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private WebTools() {
    }

    /** 联网搜索，最多返回 8 条结果。 */
    public static void search(String query, Consumer<String> done) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            done.accept("缺少搜索关键字。");
            return;
        }
        EXECUTOR.execute(() -> {
            String result;
            try {
                result = searchDuckDuckGo(text);
                if (result.startsWith("没有找到")) {
                    result = searchBing(text);
                }
            } catch (Exception exception) {
                try {
                    result = searchBing(text);
                } catch (Exception second) {
                    result = "搜索失败：" + shortMessage(second);
                }
            }
            finish(done, result);
        });
    }

    /** 抓取网页并转成纯文本。 */
    public static void fetch(String rawUrl, Consumer<String> done) {
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty()) {
            done.accept("缺少网页地址。");
            return;
        }
        EXECUTOR.execute(() -> {
            String result;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .GET().build();
                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = response.body();
                if (body == null) {
                    result = "网页没有返回内容。";
                } else if (body.length > 4 * 1024 * 1024) {
                    result = "网页超过 4 MB，已拒绝读取。";
                } else {
                    String charset = response.headers().firstValue("content-type").orElse("");
                    String html = new String(body, StandardCharsets.UTF_8);
                    String title = title(html);
                    String text = strip(html);
                    if (text.isBlank()) {
                        result = "网页没有可读正文（可能是脚本渲染页面）：" + url;
                    } else {
                        result = "标题：" + (title.isBlank() ? "（无）" : title)
                                + "\n地址：" + response.uri()
                                + "\n状态：HTTP " + response.statusCode()
                                + (charset.toLowerCase(Locale.ROOT).contains("gb") ? "\n（页面可能使用 GBK）" : "")
                                + "\n\n" + shorten(text, 12000);
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result = "网页请求被中断。";
            } catch (Exception exception) {
                result = "无法访问 " + url + "：" + shortMessage(exception);
            }
            finish(done, result);
        });
    }

    private static String searchDuckDuckGo(String query) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = get(url);
        List<String> lines = new ArrayList<>();
        Matcher matcher = DDG_ITEM.matcher(html);
        while (matcher.find() && lines.size() < 8) {
            String href = decodeRedirect(matcher.group(1));
            String title = plain(matcher.group(2));
            if (href.isEmpty() || title.isEmpty()) {
                continue;
            }
            Matcher snippet = DDG_SNIPPET.matcher(matcher.group(3));
            String summary = snippet.find() ? plain(snippet.group(1)) : "";
            lines.add((lines.size() + 1) + ". " + title + "\n   " + href
                    + (summary.isEmpty() ? "" : "\n   " + shorten(summary, 220)));
        }
        if (lines.isEmpty()) {
            return "没有找到相关结果（DuckDuckGo）。";
        }
        return "搜索结果 · " + query + "\n" + String.join("\n", lines)
                + "\n\n需要细节时用 fetch_url 打开具体网页。";
    }

    private static String searchBing(String query) throws Exception {
        String url = "https://www.bing.com/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&setlang=zh-hans";
        String html = get(url);
        List<String> lines = new ArrayList<>();
        Matcher matcher = BING_ITEM.matcher(html);
        while (matcher.find() && lines.size() < 8) {
            String href = matcher.group(1).trim();
            String title = plain(matcher.group(2));
            if (href.startsWith("//")) {
                href = "https:" + href;
            }
            if (title.isEmpty() || !href.startsWith("http")) {
                continue;
            }
            Matcher snippet = BING_SNIPPET.matcher(matcher.group(3));
            String summary = snippet.find() ? plain(snippet.group(1)) : "";
            lines.add((lines.size() + 1) + ". " + title + "\n   " + href
                    + (summary.isEmpty() ? "" : "\n   " + shorten(summary, 220)));
        }
        if (lines.isEmpty()) {
            return "没有找到相关结果。可以试试换关键词，或直接 fetch_url 已知地址。";
        }
        return "搜索结果 · " + query + "\n" + String.join("\n", lines)
                + "\n\n需要细节时用 fetch_url 打开具体网页。";
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body() == null ? "" : response.body();
    }

    private static String decodeRedirect(String href) {
        String value = href == null ? "" : href.trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        int index = value.indexOf("uddg=");
        if (index >= 0) {
            String encoded = value.substring(index + 5);
            int end = encoded.indexOf('&');
            if (end >= 0) {
                encoded = encoded.substring(0, end);
            }
            try {
                value = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return value;
    }

    /** 网页正文压成纯文本：去掉脚本、样式、标签，保留段落换行。 */
    public static String strip(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<noscript.*?</noscript>", " ")
                .replaceAll("(?is)<template.*?</template>", " ")
                .replaceAll("(?is)<svg.*?</svg>", " ")
                .replaceAll("(?is)<head.*?</head>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr|section|article|header|footer|nav|"
                        + "blockquote|pre|table|ul|ol)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text)
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text;
    }

    private static String plain(String html) {
        return strip(html).replace('\n', ' ').trim();
    }

    private static String title(String html) {
        Matcher matcher = Pattern.compile("<title[^>]*>(.*?)</title>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html == null ? "" : html);
        return matcher.find() ? plain(matcher.group(1)) : "";
    }

    private static String decodeEntities(String text) {
        Matcher matcher = Pattern.compile("&(#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z]+);").matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement;
            try {
                if (token.startsWith("#x") || token.startsWith("#X")) {
                    replacement = new String(Character.toChars(Integer.parseInt(token.substring(2), 16)));
                } else if (token.startsWith("#")) {
                    replacement = new String(Character.toChars(Integer.parseInt(token.substring(1))));
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
            } catch (RuntimeException exception) {
                replacement = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 只接受 http/https；裸域名补 https。 */
    public static String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        if (value.indexOf(' ') < 0 && value.indexOf('.') > 0) {
            return "https://" + value;
        }
        return "";
    }

    private static void finish(Consumer<String> done, String text) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> done.accept(text));
        } else {
            done.accept(text);
        }
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "\n…（已截断）";
    }

    private static String shortMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
