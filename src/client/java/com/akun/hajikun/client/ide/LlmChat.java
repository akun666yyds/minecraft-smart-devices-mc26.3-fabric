package com.akun.hajikun.client.ide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 大模型流式对话：本地 Ollama 与 DashScope 的 OpenAI 兼容接口共用一套回调。
 *
 * <p>请求在后台线程执行，增量内容通过 {@link Sink#delta(String, boolean)} 实时送回，
 * 结束时给出完整的 {@link Turn}（含工具调用）。两种协议的差异只在本文件里处理。</p>
 */
public interface LlmChat {
    ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "hajikun-ide-llm");
        thread.setDaemon(true);
        return thread;
    });

    HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(EXECUTOR)
            .build();

    void chat(IdeConfig config, List<IdeMessage> messages, JsonArray tools, Sink sink);

    static LlmChat forConfig(IdeConfig config) {
        return config != null && config.qwen() ? new QwenChat() : new OllamaChat();
    }

    /** 一次完整回复：正文、思考过程与工具调用。 */
    record Turn(String content, String reasoning, List<IdeMessage.ToolCall> toolCalls, String finish) {
        public Turn {
            content = content == null ? "" : content;
            reasoning = reasoning == null ? "" : reasoning;
            toolCalls = toolCalls == null ? List.of() : toolCalls;
            finish = finish == null ? "" : finish;
        }

        public boolean hasTools() {
            return !this.toolCalls.isEmpty();
        }
    }

    interface Sink {
        void delta(String text, boolean reasoning);

        void turn(Turn turn);

        void error(String message);
    }

    // ------------------------------------------------------------------ Ollama

    final class OllamaChat implements LlmChat {
        @Override
        public void chat(IdeConfig config, List<IdeMessage> messages, JsonArray tools, Sink sink) {
            String endpoint = config.endpoint();
            if (endpoint.isEmpty()) {
                sink.error("Ollama 地址为空。");
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("model", config.ollamaModel);
            body.addProperty("stream", true);
            JsonArray array = new JsonArray();
            for (IdeMessage message : messages) {
                array.add(message.toOllama());
            }
            body.add("messages", array);
            if (tools != null && !tools.isEmpty()) {
                body.add("tools", tools);
            }
            // Ollama 的思考开关是顶层字段；关闭时不下发，兼容旧版服务端。
            if (config.thinking()) {
                body.addProperty("think", true);
            }
            JsonObject options = new JsonObject();
            options.addProperty("temperature", config.temperature);
            options.addProperty("num_predict", Math.max(128, config.maxTokens));
            body.add("options", options);

            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(endpoint + "/api/chat"))
                        .timeout(Duration.ofSeconds(240))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/x-ndjson")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
            } catch (IllegalArgumentException exception) {
                sink.error("Ollama 地址无效：" + exception.getMessage());
                return;
            }

            EXECUTOR.execute(() -> {
                StringBuilder content = new StringBuilder();
                StringBuilder reasoning = new StringBuilder();
                List<IdeMessage.ToolCall> calls = new ArrayList<>();
                AtomicBoolean finished = new AtomicBoolean(false);
                try {
                    HttpResponse<InputStream> response = HTTP.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        sink.error("Ollama HTTP " + response.statusCode() + "：" + readAll(response.body()));
                        return;
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            JsonObject json;
                            try {
                                json = JsonParser.parseString(line).getAsJsonObject();
                            } catch (RuntimeException exception) {
                                continue;
                            }
                            if (json.has("error")) {
                                sink.error("Ollama：" + json.get("error").getAsString());
                                return;
                            }
                            JsonObject message = json.has("message")
                                    ? json.getAsJsonObject("message") : null;
                            if (message != null) {
                                String text = optString(message, "content");
                                if (!text.isEmpty()) {
                                    content.append(text);
                                    sink.delta(text, false);
                                }
                                String think = optString(message, "thinking");
                                if (!think.isEmpty()) {
                                    reasoning.append(think);
                                    sink.delta(think, true);
                                }
                                if (message.has("tool_calls")) {
                                    for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                                        calls.add(parseCall(element.getAsJsonObject(), calls.size()));
                                    }
                                }
                            }
                            if (json.has("done") && json.get("done").getAsBoolean()) {
                                finished.set(true);
                                sink.turn(new Turn(content.toString(), reasoning.toString(), calls,
                                        optString(json, "done_reason")));
                                return;
                            }
                        }
                    }
                    if (!finished.get()) {
                        sink.turn(new Turn(content.toString(), reasoning.toString(), calls, "eof"));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    sink.error("Ollama 请求被中断。");
                } catch (IOException | RuntimeException exception) {
                    sink.error("Ollama 连接失败：" + shortMessage(exception));
                }
            });
        }

        private static IdeMessage.ToolCall parseCall(JsonObject json, int index) {
            JsonObject function = json.has("function") ? json.getAsJsonObject("function") : json;
            String name = optString(function, "name");
            String arguments = "{}";
            if (function.has("arguments")) {
                JsonElement value = function.get("arguments");
                arguments = value.isJsonPrimitive() ? value.getAsString() : value.toString();
            }
            String id = optString(json, "id");
            if (id.isEmpty()) {
                id = "call_" + (index + 1);
            }
            return new IdeMessage.ToolCall(id, name, arguments);
        }
    }

    // ------------------------------------------------------------------ DashScope / Qwen

    final class QwenChat implements LlmChat {
        @Override
        public void chat(IdeConfig config, List<IdeMessage> messages, JsonArray tools, Sink sink) {
            if (config.qwenKey == null || config.qwenKey.isBlank()) {
                sink.error("还没有填写百炼 API Key：打开设置 → 百炼 Qwen。");
                return;
            }
            String endpoint = config.endpoint();
            if (endpoint.isEmpty()) {
                sink.error("百炼接口地址为空。");
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("model", config.qwenModel);
            body.addProperty("stream", true);
            body.addProperty("temperature", config.temperature);
            body.addProperty("max_tokens", Math.max(128, config.maxTokens));
            JsonArray array = new JsonArray();
            for (IdeMessage message : messages) {
                array.add(message.toOpenAi());
            }
            body.add("messages", array);
            if (tools != null && !tools.isEmpty()) {
                body.add("tools", tools);
            }
            // 百炼：enable_thinking 控制是否思考，thinking_budget 控制思考深度。
            body.addProperty("enable_thinking", config.thinking());
            if (config.thinking() && config.thinkingBudget() > 0) {
                body.addProperty("thinking_budget", config.thinkingBudget());
            }

            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions"))
                        .timeout(Duration.ofSeconds(240))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.qwenKey.trim())
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
            } catch (IllegalArgumentException exception) {
                sink.error("百炼地址无效：" + exception.getMessage());
                return;
            }

            EXECUTOR.execute(() -> {
                StringBuilder content = new StringBuilder();
                StringBuilder reasoning = new StringBuilder();
                Map<Integer, ToolCallBuilder> builders = new LinkedHashMap<>();
                String finish = "";
                try {
                    HttpResponse<InputStream> response = HTTP.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        sink.error("百炼 HTTP " + response.statusCode() + "：" + errorText(response.body()));
                        return;
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String payload = line.substring(5).trim();
                            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                                continue;
                            }
                            JsonObject json;
                            try {
                                json = JsonParser.parseString(payload).getAsJsonObject();
                            } catch (RuntimeException exception) {
                                continue;
                            }
                            if (json.has("error")) {
                                JsonObject error = json.getAsJsonObject("error");
                                sink.error("百炼：" + (error.has("message")
                                        ? error.get("message").getAsString() : error.toString()));
                                return;
                            }
                            if (!json.has("choices") || json.getAsJsonArray("choices").isEmpty()) {
                                continue;
                            }
                            JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                            String reason = optString(choice, "finish_reason");
                            if (!reason.isEmpty() && !"null".equals(reason)) {
                                finish = reason;
                            }
                            JsonObject delta = choice.has("delta")
                                    ? choice.getAsJsonObject("delta") : null;
                            if (delta == null) {
                                continue;
                            }
                            String text = optString(delta, "content");
                            if (!text.isEmpty()) {
                                content.append(text);
                                sink.delta(text, false);
                            }
                            String think = optString(delta, "reasoning_content");
                            if (think.isEmpty()) {
                                think = optString(delta, "reasoning");
                            }
                            if (!think.isEmpty()) {
                                reasoning.append(think);
                                sink.delta(think, true);
                            }
                            if (delta.has("tool_calls")) {
                                for (JsonElement element : delta.getAsJsonArray("tool_calls")) {
                                    mergeCall(element.getAsJsonObject(), builders);
                                }
                            }
                        }
                    }
                    List<IdeMessage.ToolCall> calls = new ArrayList<>();
                    for (ToolCallBuilder builder : builders.values()) {
                        calls.add(builder.build());
                    }
                    sink.turn(new Turn(content.toString(), reasoning.toString(), calls, finish));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    sink.error("百炼请求被中断。");
                } catch (IOException | RuntimeException exception) {
                    sink.error("百炼连接失败：" + shortMessage(exception));
                }
            });
        }

        private static void mergeCall(JsonObject delta, Map<Integer, ToolCallBuilder> builders) {
            int index = delta.has("index") ? delta.get("index").getAsInt() : builders.size();
            ToolCallBuilder builder = builders.computeIfAbsent(index, ignored -> new ToolCallBuilder());
            String id = optString(delta, "id");
            if (!id.isEmpty()) {
                builder.id = id;
            }
            if (delta.has("function")) {
                JsonObject function = delta.getAsJsonObject("function");
                String name = optString(function, "name");
                if (!name.isEmpty()) {
                    builder.name = name;
                }
                String arguments = optString(function, "arguments");
                if (!arguments.isEmpty()) {
                    builder.arguments.append(arguments);
                }
            }
        }

        private static final class ToolCallBuilder {
            private String id = "";
            private String name = "";
            private final StringBuilder arguments = new StringBuilder();

            private IdeMessage.ToolCall build() {
                return new IdeMessage.ToolCall(this.id, this.name, this.arguments.toString());
            }
        }
    }

    // ------------------------------------------------------------------ 公共工具

    static String optString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = json.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    static String readAll(InputStream stream) throws IOException {
        try (stream) {
            byte[] data = stream.readNBytes(8192);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static String errorText(InputStream stream) {
        try {
            String text = readAll(stream);
            return text.isBlank() ? "（无响应内容）" : text;
        } catch (IOException exception) {
            return "（无法读取错误信息）";
        }
    }

    static String shortMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }

    /** 测试连接：Ollama 列出本地模型；Qwen 发一条最短请求。 */
    static public void test(IdeConfig config, Consumer<String> done) {
        if (config.qwen()) {
            List<IdeMessage> messages = new ArrayList<>();
            messages.add(new IdeMessage("user", "只回复 OK"));
            forConfig(config).chat(config, messages, null, new Sink() {
                private final StringBuilder text = new StringBuilder();

                @Override
                public void delta(String delta, boolean reasoning) {
                    if (!reasoning) {
                        this.text.append(delta);
                    }
                }

                @Override
                public void turn(Turn turn) {
                    done.accept("百炼连接成功：" + turn.content().strip().replace('\n', ' '));
                }

                @Override
                public void error(String message) {
                    done.accept("百炼连接失败：" + message);
                }
            });
            return;
        }
        String endpoint = config.endpoint();
        if (endpoint.isEmpty()) {
            done.accept("Ollama 地址为空。");
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint + "/api/tags"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
        } catch (IllegalArgumentException exception) {
            done.accept("Ollama 地址无效：" + exception.getMessage());
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                HttpResponse<String> response = HTTP.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    done.accept("Ollama HTTP " + response.statusCode());
                    return;
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                List<String> names = new ArrayList<>();
                if (json.has("models")) {
                    for (JsonElement element : json.getAsJsonArray("models")) {
                        JsonObject model = element.getAsJsonObject();
                        String name = optString(model, "name");
                        if (!name.isEmpty()) {
                            names.add(name);
                        }
                    }
                }
                String text = names.isEmpty() ? "（列表为空，请先 ollama pull）"
                        : String.join(", ", names.subList(0, Math.min(6, names.size())))
                        + (names.size() > 6 ? " …" : "");
                done.accept("Ollama 连接成功 · " + text);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                done.accept("Ollama 请求被中断。");
            } catch (IOException | RuntimeException exception) {
                done.accept("Ollama 连接失败：" + shortMessage(exception));
            }
        });
    }

    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
