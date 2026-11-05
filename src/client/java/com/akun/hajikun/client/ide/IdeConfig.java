package com.akun.hajikun.client.ide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;

/**
 * JavaIDE 的模型配置：本地 Ollama 或阿里云百炼（DashScope / Qwen）。
 *
 * <p>配置保存在项目目录的 {@code .ide/settings.json} 里，随电脑一起存进世界存档；
 * API Key 只存在虚拟机内，不会出现在聊天记录或日志中。</p>
 */
public final class IdeConfig {
    /** 本地 Ollama 的默认模型。 */
    public static final String DEFAULT_OLLAMA_MODEL = "qwen3.6";
    /** 旧版本的默认模型：读档时自动升级到 {@link #DEFAULT_OLLAMA_MODEL}。 */
    private static final String LEGACY_OLLAMA_MODEL = "qwen2.5-coder:7b";

    public String provider = "ollama";
    public String ollamaUrl = "http://127.0.0.1:11434";
    public String ollamaModel = DEFAULT_OLLAMA_MODEL;
    public String qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public String qwenKey = "";
    public String qwenModel = "qwen3-coder-plus";
    public double temperature = 0.2D;
    public int maxTokens = 2048;
    /** 兼容旧配置：等价于 {@code thinkingLevel > 0}。 */
    public boolean thinking = false;
    /** 思考档位：0 关闭，1 低，2 中，3 高。 */
    public int thinkingLevel = 0;
    public int compressAt = 6000;
    public int maxSteps = 12;

    public boolean qwen() {
        return "qwen".equalsIgnoreCase(this.provider);
    }

    /** 是否让模型输出思考过程。 */
    public boolean thinking() {
        return this.thinkingLevel > 0;
    }

    public void setThinkingLevel(int level) {
        this.thinkingLevel = Math.max(0, Math.min(3, level));
        this.thinking = this.thinkingLevel > 0;
    }

    /** 点击一次循环：关闭 → 低 → 中 → 高 → 关闭。 */
    public void cycleThinking() {
        this.setThinkingLevel((this.thinkingLevel + 1) % 4);
    }

    public String thinkingLabel() {
        return switch (this.thinkingLevel) {
            case 1 -> "低";
            case 2 -> "中";
            case 3 -> "高";
            default -> "关闭";
        };
    }

    /** 百炼的思考预算；0 表示不启用。 */
    public int thinkingBudget() {
        return switch (this.thinkingLevel) {
            case 1 -> 1024;
            case 2 -> 4096;
            case 3 -> 12288;
            default -> 0;
        };
    }

    /** 给系统提示用的思考深度说明。 */
    public String thinkingHint() {
        return switch (this.thinkingLevel) {
            case 1 -> "先给出结论，只在关键处做简短推理。";
            case 2 -> "先分析再动手，列出关键假设与验证方式。";
            case 3 -> "充分推演：列出多种方案、权衡取舍，再选择最稳妥的一条并逐步验证。";
            default -> "";
        };
    }

    public String model() {
        return qwen() ? this.qwenModel : this.ollamaModel;
    }

    public String providerLabel() {
        return qwen() ? "百炼 Qwen" : "本地 Ollama";
    }

    public String endpoint() {
        String base = qwen() ? this.qwenUrl : this.ollamaUrl;
        String trimmed = base == null ? "" : base.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("provider", this.provider);
        json.addProperty("ollamaUrl", this.ollamaUrl);
        json.addProperty("ollamaModel", this.ollamaModel);
        json.addProperty("qwenUrl", this.qwenUrl);
        json.addProperty("qwenKey", this.qwenKey);
        json.addProperty("qwenModel", this.qwenModel);
        json.addProperty("temperature", this.temperature);
        json.addProperty("maxTokens", this.maxTokens);
        json.addProperty("thinking", this.thinking());
        json.addProperty("thinkingLevel", this.thinkingLevel);
        json.addProperty("compressAt", this.compressAt);
        json.addProperty("maxSteps", this.maxSteps);
        return json;
    }

    public static IdeConfig parse(String text) {
        IdeConfig config = new IdeConfig();
        if (text == null || text.isBlank()) {
            return config;
        }
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            config.provider = string(json, "provider", config.provider);
            config.ollamaUrl = string(json, "ollamaUrl", config.ollamaUrl);
            config.ollamaModel = string(json, "ollamaModel", config.ollamaModel);
            if (LEGACY_OLLAMA_MODEL.equals(config.ollamaModel)) {
                // 旧档里存的是默认值本身，直接升级；用户自选的其它模型保持原样。
                config.ollamaModel = DEFAULT_OLLAMA_MODEL;
            }
            config.qwenUrl = string(json, "qwenUrl", config.qwenUrl);
            config.qwenKey = string(json, "qwenKey", config.qwenKey);
            config.qwenModel = string(json, "qwenModel", config.qwenModel);
            if (json.has("temperature")) {
                config.temperature = json.get("temperature").getAsDouble();
            }
            if (json.has("maxTokens")) {
                config.maxTokens = json.get("maxTokens").getAsInt();
            }
            if (json.has("thinking")) {
                config.thinking = json.get("thinking").getAsBoolean();
            }
            if (json.has("thinkingLevel")) {
                config.setThinkingLevel(json.get("thinkingLevel").getAsInt());
            } else {
                config.setThinkingLevel(config.thinking ? 2 : 0);
            }
            if (json.has("compressAt")) {
                config.compressAt = json.get("compressAt").getAsInt();
            }
            if (json.has("maxSteps")) {
                config.maxSteps = json.get("maxSteps").getAsInt();
            }
        } catch (RuntimeException ignored) {
            // 配置损坏时退回默认值，界面上仍可重新填写保存。
        }
        return config;
    }

    private static String string(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        String value = json.get(key).getAsString();
        return value == null ? fallback : value;
    }

    /** 界面上显示的 API Key：只露出最后四位。 */
    public String maskedKey() {
        if (this.qwenKey == null || this.qwenKey.isBlank()) {
            return "未填写";
        }
        String key = this.qwenKey.trim();
        if (key.length() <= 4) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }

    public String modelLabel() {
        String model = model();
        return model.length() > 24 ? model.substring(0, 24) + "…" : model;
    }

    public String shortProvider() {
        return qwen() ? "QWEN" : "OLLAMA";
    }

    public void toggleProvider() {
        this.provider = qwen() ? "ollama" : "qwen";
    }

    public String temperatureText() {
        return String.format(Locale.ROOT, "%.1f", this.temperature);
    }
}
