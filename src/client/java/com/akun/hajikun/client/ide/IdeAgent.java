package com.akun.hajikun.client.ide;

import com.akun.hajikun.client.net.WebTools;
import com.akun.hajikun.vm.VirtualFileSystem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * JavaIDE 的智能体链：计划 → 思考 → 工具执行 → 观察 → 再推理，直到给出答复。
 *
 * <p>运行在客户端，但所有文件与命令都通过虚拟机执行。上下文超长时会先把旧消息
 * 压缩成摘要；项目记忆与技能都来自当前工作目录下的 {@code .ide/}。整个过程
 * 由界面帧驱动，网络回调统一回到客户端线程，避免和渲染线程抢数据。</p>
 */
public final class IdeAgent {
    public enum Phase { IDLE, PLANNING, RUNNING, DONE, ERROR, STOPPED }

    public static final class Step {
        public final String kind;
        public final String title;
        public String detail;
        public boolean done;

        private Step(String kind, String title, String detail) {
            this.kind = kind;
            this.title = title;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** 智能体对工作区的最小依赖，方便界面注入虚拟机操作。 */
    public interface Host {
        String projectRoot();

        VirtualFileSystem fs();

        void writeFile(String path, String content);

        void createDir(String path, Consumer<String> done);

        void runCommand(String command, Consumer<String> done);

        String memory();

        void appendMemory(String text);

        /** 技能名 → 完整说明。 */
        Map<String, String> skills();
    }

    private static final int TOOL_OUTPUT_LIMIT = 6000;

    private final Host host;
    public final IdeConfig config;
    public final List<IdeMessage> messages = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();
    private final Deque<IdeMessage.ToolCall> toolQueue = new ArrayDeque<>();

    public String sessionId = "";
    public String title = "";
    public long updatedAt;
    public String activeSkill = "";
    public long version;

    private Phase phase = Phase.IDLE;
    private String status = "";
    private String plan = "";
    private int loop;
    private volatile boolean cancelled;
    private boolean compressNotice;
    private LlmChat chat;
    private Consumer<String> toolDone;

    public IdeAgent(Host host, IdeConfig config) {
        this.host = host;
        this.config = config;
    }

    // ------------------------------------------------------------------ 对外状态

    public Phase phase() {
        return this.phase;
    }

    public String status() {
        return this.status;
    }

    public String plan() {
        return this.plan;
    }

    public boolean busy() {
        return this.phase == Phase.PLANNING || this.phase == Phase.RUNNING;
    }

    public String phaseLabel() {
        return switch (this.phase) {
            case PLANNING -> "规划中";
            case RUNNING -> "执行中";
            case DONE -> "已完成";
            case ERROR -> "出错";
            case STOPPED -> "已停止";
            default -> "就绪";
        };
    }

    public int estimateTokens() {
        int chars = 0;
        for (IdeMessage message : this.messages) {
            chars += message.content == null ? 0 : message.content.length();
            chars += message.reasoning == null ? 0 : message.reasoning.length();
            for (IdeMessage.ToolCall call : message.toolCalls) {
                chars += call.arguments == null ? 0 : call.arguments.length();
            }
            chars += message.images.size() * 900;
        }
        return Math.max(1, (int) Math.round(chars / 3.2D));
    }

    // ------------------------------------------------------------------ 会话

    public void newSession() {
        this.sessionId = newSessionId();
        this.title = "新会话";
        this.updatedAt = System.currentTimeMillis();
        this.messages.clear();
        this.steps.clear();
        this.plan = "";
        this.loop = 0;
        this.cancelled = false;
        this.phase = Phase.IDLE;
        this.status = "";
        this.bump();
    }

    private static String newSessionId() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return stamp + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    public boolean empty() {
        return this.messages.isEmpty();
    }

    public void loadSession(JsonObject json) {
        this.sessionId = json.has("id") ? json.get("id").getAsString() : newSessionId();
        this.title = json.has("title") ? json.get("title").getAsString() : "未命名会话";
        this.updatedAt = json.has("updated") ? json.get("updated").getAsLong() : System.currentTimeMillis();
        this.messages.clear();
        if (json.has("messages")) {
            for (JsonElement element : json.getAsJsonArray("messages")) {
                this.messages.add(IdeMessage.fromJson(element.getAsJsonObject()));
            }
        }
        this.steps.clear();
        this.steps.add(step("session", "已载入会话", this.title));
        this.phase = Phase.IDLE;
        this.status = "";
        this.plan = "";
        this.loop = 0;
        this.cancelled = false;
        this.bump();
    }

    public void saveSession() {
        if (this.sessionId.isEmpty()) {
            this.sessionId = newSessionId();
        }
        if (this.title == null || this.title.isBlank()) {
            this.title = "新会话";
        }
        this.updatedAt = System.currentTimeMillis();
        JsonObject json = new JsonObject();
        json.addProperty("id", this.sessionId);
        json.addProperty("title", this.title);
        json.addProperty("updated", this.updatedAt);
        json.addProperty("model", this.config.model());
        JsonArray array = new JsonArray();
        for (IdeMessage message : this.messages) {
            if (message.emptyAssistant()) {
                continue;
            }
            array.add(message.toJson());
        }
        json.add("messages", array);
        this.host.writeFile(this.sessionsDir() + "/" + this.sessionId + ".json", json.toString());
    }

    public String sessionsDir() {
        return this.host.projectRoot() + "/.ide/sessions";
    }

    // ------------------------------------------------------------------ 发送

    public void send(String text, List<String> images) {
        if (busy()) {
            return;
        }
        String prompt = text == null ? "" : text.trim();
        if (prompt.isEmpty() && (images == null || images.isEmpty())) {
            return;
        }
        if (this.sessionId.isEmpty()) {
            this.sessionId = newSessionId();
        }
        if (this.messages.isEmpty()) {
            this.title = prompt.isBlank() ? "图片会话" : (prompt.length() > 28
                    ? prompt.substring(0, 28) + "…" : prompt);
        }
        this.steps.clear();
        this.plan = "";
        this.loop = 0;
        this.cancelled = false;
        this.compressNotice = false;
        IdeMessage user = new IdeMessage("user", prompt);
        if (images != null) {
            user.images.addAll(images);
        }
        this.messages.add(user);
        this.phase = Phase.PLANNING;
        this.status = "正在制定计划…";
        this.steps.add(step("plan", "计划", "等待模型给出执行步骤…"));
        this.bump();
        this.compressThen(this::runPlan);
    }

    public void cancel() {
        if (!busy()) {
            return;
        }
        this.cancelled = true;
        this.phase = Phase.STOPPED;
        this.status = "已停止本次任务。";
        this.steps.add(step("error", "已停止", "用户中止了智能体。"));
        this.saveSession();
        this.bump();
    }

    public void attachSkill(String skill) {
        this.activeSkill = skill == null ? "" : skill;
    }

    // ------------------------------------------------------------------ 上下文压缩

    private void compressThen(Runnable next) {
        int tokens = estimateTokens();
        if (tokens <= Math.max(1000, this.config.compressAt) || this.messages.size() < 8) {
            next.run();
            return;
        }
        int keepFrom = Math.max(4, this.messages.size() - 4);
        List<IdeMessage> old = new ArrayList<>(this.messages.subList(0, keepFrom));
        Step step = step("compress", "上下文压缩",
                "估算 " + tokens + " tokens，正在把 " + old.size() + " 条旧消息压缩成摘要…");
        StringBuilder transcript = new StringBuilder();
        for (IdeMessage message : old) {
            if (message.images.size() > 0 && message.content.isBlank()) {
                continue;
            }
            String text = message.content == null ? "" : message.content;
            if (text.length() > 1200) {
                text = text.substring(0, 1200) + "…";
            }
            transcript.append('[').append(message.role).append("] ").append(text).append('\n');
        }
        List<IdeMessage> request = new ArrayList<>();
        request.add(new IdeMessage("system",
                "你负责压缩对话上下文。请把下面的对话整理成要点，保留：用户目标、已确认的事实、"
                        + "改过的文件、执行的命令与结果、未完成的事项。只输出摘要正文，不要客套。"));
        request.add(new IdeMessage("user", transcript.toString()));
        this.status = "上下文过长，正在压缩…";
        this.bump();
        LlmChat.forConfig(this.config).chat(this.config, request, null, new LlmChat.Sink() {
            @Override
            public void delta(String text, boolean reasoning) {
                // 摘要不需要逐字显示。
            }

            @Override
            public void turn(LlmChat.Turn turn) {
                execute(() -> {
                    if (IdeAgent.this.cancelled) {
                        return;
                    }
                    List<IdeMessage> current = new ArrayList<>(IdeAgent.this.messages);
                    int keep = Math.max(4, current.size() - 4);
                    List<IdeMessage> tail = new ArrayList<>(current.subList(keep, current.size()));
                    IdeAgent.this.messages.clear();
                    IdeMessage summary = new IdeMessage("system",
                            "【上下文摘要】\n" + turn.content().strip());
                    IdeAgent.this.messages.add(summary);
                    IdeAgent.this.messages.addAll(tail);
                    step.detail = "已压缩 " + old.size() + " 条消息，估算 "
                            + IdeAgent.this.estimateTokens() + " tokens。";
                    step.done = true;
                    IdeAgent.this.compressNotice = true;
                    IdeAgent.this.bump();
                    next.run();
                });
            }

            @Override
            public void error(String message) {
                execute(() -> {
                    if (IdeAgent.this.cancelled) {
                        return;
                    }
                    step.detail = "压缩失败，继续使用原上下文：" + message;
                    step.done = true;
                    IdeAgent.this.bump();
                    next.run();
                });
            }
        });
    }

    // ------------------------------------------------------------------ 计划阶段

    private void runPlan() {
        if (this.cancelled) {
            return;
        }
        this.phase = Phase.PLANNING;
        this.status = "正在规划…";
        Step planStep = this.steps.isEmpty() ? step("plan", "计划", "") : this.steps.get(0);
        planStep.detail = "";
        planStep.done = false;
        List<IdeMessage> request = new ArrayList<>();
        request.add(new IdeMessage("system", this.systemPrompt(true)));
        for (IdeMessage message : compactHistory()) {
            request.add(message);
        }
        this.chat = LlmChat.forConfig(this.config);
        this.bump();
        this.chat.chat(this.config, request, null, new LlmChat.Sink() {
            @Override
            public void delta(String text, boolean reasoning) {
                execute(() -> {
                    if (IdeAgent.this.cancelled) {
                        return;
                    }
                    planStep.detail = planStep.detail + text;
                    planStep.done = false;
                    IdeAgent.this.bump();
                });
            }

            @Override
            public void turn(LlmChat.Turn turn) {
                execute(() -> {
                    if (IdeAgent.this.cancelled) {
                        return;
                    }
                    if (turn.content().isBlank()) {
                        planStep.detail = "（模型没有给出文字计划，直接开始执行）";
                    } else {
                        planStep.detail = turn.content().strip();
                    }
                    planStep.done = true;
                    if (!turn.reasoning().isBlank()) {
                        IdeAgent.this.steps.add(step("think", "思考", shorten(turn.reasoning(), 900)));
                    }
                    IdeAgent.this.plan = turn.content().strip();
                    IdeAgent.this.phase = Phase.RUNNING;
                    IdeAgent.this.status = "开始执行…";
                    IdeAgent.this.bump();
                    IdeAgent.this.callExec();
                });
            }

            @Override
            public void error(String message) {
                execute(() -> fail(message));
            }
        });
    }

    // ------------------------------------------------------------------ 执行阶段

    private void callExec() {
        if (this.cancelled) {
            return;
        }
        if (this.phase == Phase.IDLE || this.phase == Phase.ERROR || this.phase == Phase.STOPPED) {
            return;
        }
        if (this.loop >= Math.max(1, this.config.maxSteps)) {
            this.phase = Phase.DONE;
            this.status = "已达到最大工具步数（" + this.config.maxSteps + "），先停在这里。";
            this.steps.add(step("error", "步数上限", this.status));
            this.saveSession();
            this.bump();
            return;
        }
        this.phase = Phase.RUNNING;
        this.status = "智能体正在执行第 " + (this.loop + 1) + " 轮…";
        List<IdeMessage> request = new ArrayList<>();
        request.add(new IdeMessage("system", this.systemPrompt(false)));
        for (IdeMessage message : compactHistory()) {
            request.add(message);
        }
        final IdeMessage current = new IdeMessage("assistant", "");
        this.messages.add(current);
        this.chat = LlmChat.forConfig(this.config);
        this.bump();
        this.chat.chat(this.config, request, AgentTools.schema(), new LlmChat.Sink() {
            @Override
            public void delta(String text, boolean reasoning) {
                execute(() -> {
                    if (IdeAgent.this.cancelled) {
                        return;
                    }
                    if (reasoning) {
                        current.reasoning = current.reasoning + text;
                    } else {
                        current.content = current.content + text;
                    }
                    IdeAgent.this.bump();
                });
            }

            @Override
            public void turn(LlmChat.Turn turn) {
                execute(() -> onExecTurn(current, turn));
            }

            @Override
            public void error(String message) {
                execute(() -> {
                    if (failedAssistant(current)) {
                        IdeAgent.this.messages.remove(current);
                    }
                    fail(message);
                });
            }
        });
    }

    private void onExecTurn(IdeMessage current, LlmChat.Turn turn) {
        if (this.cancelled) {
            return;
        }
        if (!turn.content().isBlank() || current.content.isBlank()) {
            current.content = turn.content().isBlank() ? current.content : turn.content();
        }
        if (!turn.reasoning().isBlank()) {
            current.reasoning = turn.reasoning();
        }
        current.toolCalls.clear();
        current.toolCalls.addAll(turn.toolCalls());
        if (!current.reasoning.isBlank()) {
            this.steps.add(step("think", "思考", shorten(current.reasoning.strip(), 700)));
        }
        if (turn.hasTools()) {
            this.loop++;
            StringBuilder title = new StringBuilder();
            for (IdeMessage.ToolCall call : turn.toolCalls()) {
                if (title.length() > 0) {
                    title.append("、");
                }
                title.append(call.name);
            }
            this.steps.add(step("tool", "执行工具", title.toString()));
            this.toolQueue.clear();
            this.toolQueue.addAll(turn.toolCalls());
            this.runNextTool();
            this.bump();
            return;
        }
        if (current.content.isBlank()) {
            this.messages.remove(current);
            fail("模型没有返回内容。检查模型是否支持工具调用，或换一个模型。");
            return;
        }
        this.phase = Phase.DONE;
        this.status = "";
        this.saveSession();
        this.bump();
    }

    private void runNextTool() {
        if (this.cancelled) {
            return;
        }
        IdeMessage.ToolCall call = this.toolQueue.poll();
        if (call == null) {
            if (this.loop >= Math.max(1, this.config.maxSteps)) {
                this.phase = Phase.DONE;
                this.status = "已达到最大工具步数。";
                this.steps.add(step("error", "步数上限", this.status));
                this.saveSession();
                this.bump();
                return;
            }
            this.callExec();
            return;
        }
        this.status = "执行工具 " + call.name + "…";
        this.bump();
        this.executeTool(call, result -> {
            if (this.cancelled) {
                return;
            }
            this.messages.add(IdeMessage.toolResult(call.id, call.name, shorten(result, TOOL_OUTPUT_LIMIT)));
            this.steps.add(step("result", "工具结果 · " + call.name, shorten(result, 500)));
            this.bump();
            this.runNextTool();
        });
    }

    // ------------------------------------------------------------------ 工具实现

    private void executeTool(IdeMessage.ToolCall call, Consumer<String> done) {
        JsonObject args = new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(call.arguments == null || call.arguments.isBlank()
                    ? "{}" : call.arguments);
            if (parsed.isJsonObject()) {
                args = parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // 参数坏了就按空参数执行，错误会作为工具结果回给模型。
        }
        switch (call.name) {
            case "list_files" -> done.accept(this.listFiles(arg(args, "path")));
            case "read_file" -> done.accept(this.readFile(arg(args, "path")));
            case "write_file" -> done.accept(this.writeFile(arg(args, "path"), arg(args, "content")));
            case "create_dir" -> {
                String path = this.resolve(arg(args, "path"));
                if (VirtualFileSystem.isProtected(path)) {
                    done.accept("系统目录受保护，不能新建。");
                } else {
                    this.host.createDir(path, output ->
                            done.accept(output.isBlank() ? "已创建目录 " + path : output));
                }
            }
            case "move_path" -> {
                String from = this.resolve(arg(args, "from"));
                String to = this.resolve(arg(args, "to"));
                if (from.isEmpty() || to.isEmpty() || from.equals("/") || to.equals("/")) {
                    done.accept("移动失败：源路径和目标路径都不能为空。");
                } else if (VirtualFileSystem.isProtected(from) || VirtualFileSystem.isProtected(to)) {
                    done.accept("系统目录受保护，不能移动或重命名。");
                } else {
                    this.host.runCommand("mv " + quote(from) + " " + quote(to), done);
                }
            }
            case "delete_path" -> {
                String path = this.resolve(arg(args, "path"));
                if (path.isEmpty() || path.equals("/")) {
                    done.accept("删除失败：缺少路径。");
                } else if (VirtualFileSystem.isProtected(path)) {
                    done.accept("系统目录受保护，不能删除。");
                } else {
                    this.host.runCommand("rm " + quote(path), done);
                }
            }
            case "write_report" -> done.accept(this.writeReport(arg(args, "name"), arg(args, "content")));
            case "web_search" -> WebTools.search(arg(args, "query"), done);
            case "fetch_url" -> WebTools.fetch(arg(args, "url"), done);
            case "bluetooth_status" -> this.host.runCommand("bt status", done);
            case "bluetooth_scan" -> this.host.runCommand("bt scan", done);
            case "bluetooth_pair" -> {
                String target = arg(args, "target").trim();
                if (target.isEmpty()) {
                    done.accept("缺少设备名或 MAC。");
                } else {
                    this.host.runCommand("bt pair " + quote(target), done);
                }
            }
            case "bluetooth_send" -> {
                String target = arg(args, "target").trim();
                String path = this.resolve(arg(args, "path"));
                if (target.isEmpty() || path.isEmpty()) {
                    done.accept("缺少目标设备或文件路径。");
                } else {
                    this.host.runCommand("bt send " + quote(target) + " " + quote(path), done);
                }
            }
            case "bluetooth_cast" -> {
                String target = arg(args, "target").trim();
                this.host.runCommand("cast " + quote(target.isEmpty() ? "all" : target), done);
            }
            case "search_files" -> done.accept(this.searchFiles(arg(args, "query"), arg(args, "path")));
            case "run_command" -> {
                String command = arg(args, "command").trim();
                if (command.isEmpty()) {
                    done.accept("命令为空。");
                } else {
                    this.status = "运行命令：" + shorten(command, 60);
                    this.bump();
                    this.host.runCommand(command, done);
                }
            }
            case "list_skills" -> done.accept(this.listSkills());
            case "read_skill" -> done.accept(this.readSkill(arg(args, "name")));
            case "read_memory" -> {
                String memory = this.host.memory();
                done.accept(memory == null || memory.isBlank() ? "（项目记忆为空）" : memory);
            }
            case "remember" -> {
                String text = arg(args, "text").trim();
                if (text.isEmpty()) {
                    done.accept("没有可记住的内容。");
                } else {
                    this.host.appendMemory(text.replace('\n', ' '));
                    done.accept("已写入项目记忆。");
                }
            }
            default -> done.accept("未知工具：" + call.name);
        }
    }

    private String listFiles(String raw) {
        String path = this.resolve(raw);
        VirtualFileSystem.Node node = this.host.fs().find(path);
        if (node == null) {
            return "路径不存在：" + path;
        }
        if (!node.isDir()) {
            return path + " 是文件，用 read_file 读取。";
        }
        List<VirtualFileSystem.Node> children = this.host.fs().list(path);
        if (children.isEmpty()) {
            return "(空目录)";
        }
        StringBuilder out = new StringBuilder(path).append('\n');
        int count = 0;
        for (VirtualFileSystem.Node child : children) {
            if (count++ >= 200) {
                out.append("…（超过 200 项，已截断）\n");
                break;
            }
            out.append(child.isDir() ? "[D] " : "[F] ").append(child.name);
            if (!child.isDir()) {
                out.append("  ").append(child.size).append(" B");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String readFile(String raw) {
        String path = this.resolve(raw);
        VirtualFileSystem.Node node = this.host.fs().find(path);
        if (node == null) {
            return "文件不存在：" + path;
        }
        if (node.isDir()) {
            return path + " 是目录。";
        }
        if (node.isBinary()) {
            return "这是二进制文件（" + node.mime + "），不能按文本读取。";
        }
        String content = this.host.fs().read(path);
        if (content == null) {
            return "读取失败：" + path;
        }
        return shorten(content, 12000);
    }

    private String writeFile(String raw, String content) {
        String path = this.resolve(raw);
        if (path.equals("/") || path.isEmpty()) {
            return "缺少文件路径。";
        }
        if (VirtualFileSystem.isProtected(path)) {
            return "系统目录受保护，不能写入。";
        }
        this.host.writeFile(path, content);
        return "已写入 " + path + "（" + content.length() + " 字符）。";
    }

    private String searchFiles(String query, String raw) {
        if (query == null || query.isBlank()) {
            return "缺少搜索关键字。";
        }
        String base = this.resolve(raw);
        VirtualFileSystem.Node node = this.host.fs().find(base);
        if (node == null) {
            return "搜索起点不存在：" + base;
        }
        List<String> hits = new ArrayList<>();
        this.searchNode(node, base, query.toLowerCase(Locale.ROOT), hits);
        if (hits.isEmpty()) {
            return "没有找到包含「" + query + "」的文件。";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(80, hits.size()); i++) {
            out.append(hits.get(i)).append('\n');
        }
        if (hits.size() > 80) {
            out.append("…（共 ").append(hits.size()).append(" 条，已截断）");
        }
        return out.toString();
    }

    private void searchNode(VirtualFileSystem.Node node, String path, String query, List<String> hits) {
        if (hits.size() >= 200 || node == null) {
            return;
        }
        if (node.isDir()) {
            for (VirtualFileSystem.Node child : this.host.fs().list(path)) {
                this.searchNode(child, path.equals("/") ? "/" + child.name : path + "/" + child.name,
                        query, hits);
                if (hits.size() >= 200) {
                    return;
                }
            }
            return;
        }
        if (node.name.toLowerCase(Locale.ROOT).contains(query)) {
            hits.add(path + "  （文件名匹配）");
        }
        if (!node.isBinary() && node.size <= 512 * 1024) {
            String content = this.host.fs().read(path);
            if (content == null) {
                return;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            int index = lower.indexOf(query);
            if (index < 0) {
                return;
            }
            int line = 1;
            for (int i = 0; i < index; i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                }
            }
            int lineStart = content.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
            int lineEnd = content.indexOf('\n', index);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            hits.add(path + ":" + line + "  "
                    + content.substring(lineStart, Math.min(content.length(), lineEnd)).trim());
        }
    }

    private String listSkills() {
        Map<String, String> skills = this.host.skills();
        if (skills.isEmpty()) {
            return "（还没有技能，可在 .ide/skills 里放 Markdown 文件）";
        }
        StringBuilder out = new StringBuilder("可用技能：\n");
        for (Map.Entry<String, String> entry : skills.entrySet()) {
            String first = entry.getValue() == null ? "" : entry.getValue().lines()
                    .filter(line -> !line.isBlank()).findFirst().orElse("");
            out.append("- ").append(entry.getKey()).append("：").append(shorten(first, 120)).append('\n');
        }
        return out.toString();
    }

    private String readSkill(String name) {
        Map<String, String> skills = this.host.skills();
        for (Map.Entry<String, String> entry : skills.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                    || entry.getKey().toLowerCase(Locale.ROOT)
                    .contains(name.toLowerCase(Locale.ROOT))) {
                return shorten(entry.getValue(), 9000);
            }
        }
        return "没有找到技能：" + name + "。可先调用 list_skills。";
    }

    private String writeReport(String rawName, String content) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (name.isEmpty()) {
            name = "报告-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            name = name + ".md";
        }
        String path = "/home/user/文档/" + name;
        String body = content == null ? "" : content;
        this.host.writeFile(path, body);
        return "报告已写入 " + path + "（" + body.length() + " 字符）。";
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\\\"")) + "\"";
    }

    private String resolve(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return this.host.projectRoot();
        }
        return VirtualFileSystem.normalize(this.host.projectRoot(), value);
    }

    private static String arg(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        JsonElement element = json.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    // ------------------------------------------------------------------ 提示词

    private String systemPrompt(boolean planning) {
        StringBuilder out = new StringBuilder();
        out.append("你是「哈基坤 OS」JavaIDE 内置的智能体，既能写代码，也能聊天、查资料、整理文件、控制蓝牙与投屏。\n");
        out.append("工作区根目录：").append(this.host.projectRoot()).append('\n');
        out.append("终端当前目录：").append(this.host.projectRoot())
                .append("（run_command 与用户看到的终端是同一个虚拟机，pwd 结果一致；不确定时先执行 pwd）\n");
        out.append("内置 JDK 支持 javac / java，可直接编译运行 Java 源码。\n\n");
        out.append("允许的能力只有这五类：\n");
        out.append("1. 联网搜索与阅读：web_search 搜真实网页，fetch_url 读正文；回答里保留来源地址。\n");
        out.append("2. Java 软件开发全流程：建工程、写代码、编译、运行、测试、排错；用 run_command 执行 javac/java。\n");
        out.append("3. 闲聊：普通聊天直接回答，不需要为了寒暄调用工具。\n");
        out.append("4. 整个虚拟机的文件与项目管理：列目录、读写、移动、删除、搜索、整理，并可用 write_report 生成 Markdown 报告。\n");
        out.append("   /home/user 可读写，/系统 只读；一切路径都可以用绝对路径。\n");
        out.append("5. 蓝牙与投屏：bluetooth_status 查状态，bluetooth_scan 找设备，bluetooth_pair 配对，\n");
        out.append("   bluetooth_send 互传文件，bluetooth_cast 精准或广播投屏；用户一句话描述就自己选对应工具。\n");
        out.append("   设备名和 MAC 都可用于配对、发送与投屏；cast 的 target=all 表示附近所有电视。\n\n");
        out.append("工具：\n").append(AgentTools.describe()).append('\n');
        if (this.config.thinking()) {
            out.append("\n思考：已开启（档位：").append(this.config.thinkingLabel()).append("）。")
                    .append(this.config.thinkingHint()).append('\n');
        } else {
            out.append("\n思考：已关闭。直接给出结论与行动，不要输出冗长的推理过程。\n");
        }
        out.append("规则：\n");
        out.append("1. 先用工具查看真实文件与真实页面，不要凭空猜测内容或引用假链接。\n");
        out.append("2. 修改代码后必须编译或运行验证；失败就读错误原文继续修。\n");
        out.append("3. 不要写入或删除 /系统；删除用户文件前必须先得到明确要求。\n");
        out.append("4. 终端命令里的空格路径要加引号；发送/投屏前先确认设备在线且文件存在。\n");
        out.append("5. 回答用简体中文，简洁说明做了什么、结果如何；需要长期保留的事实用 remember 写入项目记忆。\n");
        if (planning) {
            out.append("\n当前是计划阶段：只输出 2-6 条可执行步骤，每条一行，不要调用工具，不要写代码。\n");
        } else {
            out.append("\n当前是执行阶段：可以调用工具。每一步先想清楚再行动，能一次完成就不要拆太碎。\n");
            if (!this.plan.isBlank()) {
                out.append("\n已经确定的计划：\n").append(shorten(this.plan, 1400)).append('\n');
            }
        }
        String memory = this.host.memory();
        if (memory != null && !memory.isBlank()) {
            out.append("\n项目记忆（.ide/memory.md）：\n").append(shorten(memory, 3000)).append('\n');
        }
        Map<String, String> skills = this.host.skills();
        if (!skills.isEmpty()) {
            out.append("\n可用技能：");
            boolean first = true;
            for (String name : skills.keySet()) {
                if (!first) {
                    out.append("、");
                }
                out.append(name);
                first = false;
            }
            out.append("。需要细节时调用 read_skill。\n");
        }
        if (!this.activeSkill.isBlank()) {
            String content = skills.get(this.activeSkill);
            if (content != null) {
                out.append("\n当前挂载的技能「").append(this.activeSkill).append("」：\n")
                        .append(shorten(content, 6000)).append('\n');
            }
        }
        return out.toString();
    }

    /** 送给模型的历史：去掉空占位，工具结果过长时截断，图片只保留标记。 */
    private List<IdeMessage> compactHistory() {
        List<IdeMessage> list = new ArrayList<>();
        for (IdeMessage message : this.messages) {
            if (message.emptyAssistant()) {
                continue;
            }
            IdeMessage copy = new IdeMessage(message.role, shorten(message.content, 6000));
            copy.reasoning = "";
            copy.toolCallId = message.toolCallId;
            copy.toolName = message.toolName;
            copy.toolCalls.addAll(message.toolCalls);
            if (message.role.equals("tool")) {
                copy.content = shorten(message.content, 2500);
            }
            if (!message.images.isEmpty()) {
                copy.images.addAll(message.images);
                if (copy.content.isBlank()) {
                    copy.content = "（随消息附上了图片）";
                }
            }
            list.add(copy);
        }
        return list;
    }

    // ------------------------------------------------------------------ 杂项

    private void fail(String message) {
        this.phase = Phase.ERROR;
        this.status = message == null || message.isBlank() ? "智能体执行失败。" : message;
        this.steps.add(step("error", "出错", this.status));
        this.saveSession();
        this.bump();
    }

    private static boolean failedAssistant(IdeMessage message) {
        return message != null && message.emptyAssistant();
    }

    private Step step(String kind, String title, String detail) {
        Step step = new Step(kind, title, detail);
        this.steps.add(step);
        return step;
    }

    private void bump() {
        this.version++;
    }

    private static void execute(Runnable runnable) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(runnable);
        }
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "\n…（已截断）";
    }
}
