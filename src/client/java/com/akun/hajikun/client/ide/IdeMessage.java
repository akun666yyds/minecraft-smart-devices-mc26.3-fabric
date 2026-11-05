package com.akun.hajikun.client.ide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条对话消息。同一个对象既能落盘保存，也能转换成 Ollama 与 OpenAI 兼容格式。
 *
 * <p>工具调用沿用 OpenAI 的约定：assistant 带 {@code tool_calls}，工具结果以
 * {@code role=tool} + {@code tool_call_id} 回填。Ollama 的格式差异在
 * {@link #toOllama()} 里抹平。</p>
 */
public final class IdeMessage {
    public String role = "user";
    public String content = "";
    public String reasoning = "";
    public String toolCallId = "";
    public String toolName = "";
    public final List<ToolCall> toolCalls = new ArrayList<>();
    /** Base64 图片，仅 user 消息使用。 */
    public final List<String> images = new ArrayList<>();

    public IdeMessage() {
    }

    public IdeMessage(String role, String content) {
        this.role = role;
        this.content = content == null ? "" : content;
    }

    public static IdeMessage toolResult(String callId, String name, String content) {
        IdeMessage message = new IdeMessage("tool", content);
        message.toolCallId = callId == null ? "" : callId;
        message.toolName = name == null ? "" : name;
        return message;
    }

    public boolean assistantWithTools() {
        return "assistant".equals(this.role) && !this.toolCalls.isEmpty();
    }

    public boolean emptyAssistant() {
        return "assistant".equals(this.role) && this.content.isBlank()
                && this.reasoning.isBlank() && this.toolCalls.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("role", this.role);
        json.addProperty("content", this.content);
        if (!this.reasoning.isBlank()) {
            json.addProperty("reasoning", this.reasoning);
        }
        if (!this.toolCallId.isBlank()) {
            json.addProperty("toolCallId", this.toolCallId);
        }
        if (!this.toolName.isBlank()) {
            json.addProperty("toolName", this.toolName);
        }
        if (!this.toolCalls.isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : this.toolCalls) {
                calls.add(call.toJson());
            }
            json.add("toolCalls", calls);
        }
        if (!this.images.isEmpty()) {
            JsonArray array = new JsonArray();
            for (String image : this.images) {
                array.add(image);
            }
            json.add("images", array);
        }
        return json;
    }

    public static IdeMessage fromJson(JsonObject json) {
        IdeMessage message = new IdeMessage();
        message.role = json.has("role") ? json.get("role").getAsString() : "user";
        message.content = json.has("content") ? json.get("content").getAsString() : "";
        message.reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : "";
        message.toolCallId = json.has("toolCallId") ? json.get("toolCallId").getAsString() : "";
        message.toolName = json.has("toolName") ? json.get("toolName").getAsString() : "";
        if (json.has("toolCalls")) {
            for (JsonElement element : json.getAsJsonArray("toolCalls")) {
                message.toolCalls.add(ToolCall.fromJson(element.getAsJsonObject()));
            }
        }
        if (json.has("images")) {
            for (JsonElement element : json.getAsJsonArray("images")) {
                message.images.add(element.getAsString());
            }
        }
        return message;
    }

    /** OpenAI 兼容格式（DashScope / Qwen）。 */
    public JsonObject toOpenAi() {
        JsonObject json = new JsonObject();
        json.addProperty("role", this.role);
        if ("assistant".equals(this.role) && !this.toolCalls.isEmpty()) {
            json.addProperty("content", this.content);
            JsonArray calls = new JsonArray();
            for (ToolCall call : this.toolCalls) {
                JsonObject item = new JsonObject();
                item.addProperty("id", call.id);
                item.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name);
                function.addProperty("arguments", call.arguments == null || call.arguments.isBlank()
                        ? "{}" : call.arguments);
                item.add("function", function);
                calls.add(item);
            }
            json.add("tool_calls", calls);
            return json;
        }
        if ("tool".equals(this.role)) {
            json.addProperty("tool_call_id", this.toolCallId);
            json.addProperty("content", this.content);
            return json;
        }
        if (!this.images.isEmpty()) {
            JsonArray parts = new JsonArray();
            if (!this.content.isBlank()) {
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", this.content);
                parts.add(text);
            }
            for (String image : this.images) {
                JsonObject part = new JsonObject();
                part.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:image/png;base64," + image);
                part.add("image_url", url);
                parts.add(part);
            }
            json.add("content", parts);
            return json;
        }
        json.addProperty("content", this.content);
        return json;
    }

    /** Ollama 原生 /api/chat 格式。 */
    public JsonObject toOllama() {
        JsonObject json = new JsonObject();
        json.addProperty("role", this.role);
        if ("assistant".equals(this.role) && !this.toolCalls.isEmpty()) {
            json.addProperty("content", this.content);
            JsonArray calls = new JsonArray();
            for (ToolCall call : this.toolCalls) {
                JsonObject item = new JsonObject();
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name);
                JsonElement arguments;
                try {
                    arguments = JsonParser.parseString(call.arguments == null || call.arguments.isBlank()
                            ? "{}" : call.arguments);
                } catch (RuntimeException exception) {
                    arguments = new JsonObject();
                }
                function.add("arguments", arguments);
                item.add("function", function);
                calls.add(item);
            }
            json.add("tool_calls", calls);
            return json;
        }
        json.addProperty("content", this.content);
        if (!this.images.isEmpty()) {
            JsonArray array = new JsonArray();
            for (String image : this.images) {
                array.add(image);
            }
            json.add("images", array);
        }
        if ("tool".equals(this.role) && !this.toolName.isBlank()) {
            json.addProperty("tool_name", this.toolName);
        }
        return json;
    }

    public static final class ToolCall {
        public String id = "";
        public String name = "";
        public String arguments = "{}";

        public ToolCall() {
        }

        public ToolCall(String id, String name, String arguments) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", this.id);
            json.addProperty("name", this.name);
            json.addProperty("arguments", this.arguments);
            return json;
        }

        public static ToolCall fromJson(JsonObject json) {
            return new ToolCall(
                    json.has("id") ? json.get("id").getAsString() : "",
                    json.has("name") ? json.get("name").getAsString() : "",
                    json.has("arguments") ? json.get("arguments").getAsString() : "{}");
        }

        public String summary() {
            String compact = this.arguments == null ? "" : this.arguments.replaceAll("\\s+", " ");
            if (compact.length() > 64) {
                compact = compact.substring(0, 64) + "…";
            }
            return this.name + (compact.isBlank() || compact.equals("{}") ? "" : " " + compact);
        }
    }
}
