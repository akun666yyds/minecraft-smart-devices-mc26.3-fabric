package com.akun.hajikun.client.ide;

import com.akun.hajikun.client.HajikunSmartClient;
import com.akun.hajikun.client.ide.IdeAgent;
import com.akun.hajikun.client.ide.IdeConfig;
import com.akun.hajikun.client.ide.IdeMessage;
import com.akun.hajikun.client.ide.LlmChat;
import com.akun.hajikun.client.media.MediaCache;
import com.akun.hajikun.client.media.P2VImport;
import com.akun.hajikun.computer.DeviceSnapshot;
import com.akun.hajikun.vm.VirtualFileSystem;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import com.akun.hajikun.client.render.Canvas;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;

/**
 * JavaIDE：长在虚拟机屏幕里的集成开发环境。
 *
 * <p>它不是游戏弹窗，而是一块普通的电脑窗口：界面按电脑画布的像素坐标排布，
 * 由方块渲染器贴到显示器表面，鼠标射线和键盘焦点都从电脑界面转进来。退出聚焦
 * 后电脑上仍留着打开的 IDE，和真实的桌面程序一样。</p>
 *
 * <p>左侧是项目文件树，中间是带语法高亮、保存、编译与运行的编辑器，右侧是
 * 智能体面板。智能体走「计划 → 工具执行 → 观察 → 再推理」的 ReAct 循环，可接
 * 本地 Ollama 或阿里云百炼 Qwen；项目记忆、技能与会话都保存在当前工作目录的
 * {@code .ide/} 里，跟着电脑一起存进世界存档。</p>
 */
public final class IdeApp {
    // ------------------------------------------------------------------ 配色
    private static final int BG = 0xFF1E1E1E;
    private static final int PANEL = 0xFF252526;
    private static final int TAB_OFF = 0xFF2D2D30;
    private static final int BORDER = 0xFF3C3C3C;
    private static final int TEXT = 0xFFD4D4D4;
    private static final int MUTED = 0xFF8A8A8A;
    private static final int DIM = 0xFF6A6A6A;
    private static final int ACCENT = 0xFF007ACC;
    private static final int HOVER = 0x22FFFFFF;
    private static final int SELECT = 0x553A6DA5;
    private static final int GREEN = 0xFF4EC9B0;
    private static final int YELLOW = 0xFFDCDCAA;
    private static final int ORANGE = 0xFFCE9178;
    private static final int BLUE = 0xFF569CD6;
    private static final int PURPLE = 0xFFC586C0;
    private static final int RED = 0xFFF14C4C;
    private static final int INPUT_BG = 0xFF3C3C3C;

    private static final int HEAD = 18;
    private static final int FOOT = 11;
    private static final int TREE_HEAD = 26;
    private static final int TAB_H = 14;
    private static final int ROW = 12;
    private static final int TREE_ROW = 12;
    private static final int LINE_H = 10;

    private static final String[] AGENT_TABS = {"+新会话", "会话", "技能", "图片"};

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue",
            "default", "do", "else", "enum", "extends", "final", "finally", "for", "goto",
            "if", "implements", "import", "instanceof", "interface", "native", "new",
            "package", "private", "protected", "public", "record", "return", "sealed",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "var", "void", "volatile", "while", "yield",
            "non-sealed", "permits");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final String MEMORY_TEMPLATE = """
            # 项目记忆

            智能体会把长期有效的项目事实追加到这里，例如目录结构、构建方式与约定。

            - 所有路径都相对于当前项目根目录。
            - 编译用内置 javac，运行用内置 java；不要修改 /系统。
            """;

    private static final String JAVA_SKILL = """
            # Java 编写技能

            1. 改代码前先用 list_files / read_file 查看真实文件，不要凭印象修改。
            2. 公共类名必须与文件名一致；包名与目录结构一致。
            3. 用 javac 编译验证，再按提示运行主类，把真实输出贴回对话。
            4. 只改与任务相关的代码，保留原有中文注释，保持缩进一致。
            """;

    private static final String DEBUG_SKILL = """
            # 排错技能

            1. 先复现：执行能触发问题的最小命令。
            2. 读错误原文：文件名、行号、列号、异常类型缺一不可。
            3. 只改一个可疑点，然后重新编译验证；不要一次改一大片。
            4. 把根因和修复方式写进项目记忆，避免下个会话重复踩坑。
            """;

    private static final String AGENT_SKILL = """
            # 智能体协作技能

            1. 先给 2–4 步计划，再逐步执行；每步都要有可验证的结果。
            2. 文件操作后立即读回确认，命令执行后检查退出信息。
            3. 上下文里只保留结论，过程细节用工具结果概括。
            4. 需要长期保留的事实用 remember 工具写入 .ide/memory.md。
            """;

    // ------------------------------------------------------------------ 会话状态
    private final BlockPos pos;
    private int width = 320;
    private int height = 220;
    private final Font font;
    private boolean typing;
    private boolean disposed;
    private Runnable closeListener;
    private DeviceSnapshot snapshot;
    private VirtualFileSystem fs;
    private String root;
    private IdeConfig config;
    private IdeAgent agent;
    private AgentHost host;
    private Tips tips;

    /** 宿主注入的悬停气泡回调；只在文字被截断时上报。 */
    public interface Tips {
        void show(int x, int y, int width, int height, String text);
    }

    public void setTips(Tips tips) {
        this.tips = tips;
    }

    private final List<OpenFile> openFiles = new ArrayList<>();
    private OpenFile activeFile;
    private final Set<String> collapsed = new HashSet<>();
    private String selected = "";
    private int treeScroll;
    private long lastTreeClickAt;
    private String lastTreeClickPath = "";

    private final Deque<String> consoleLines = new ArrayDeque<>();
    private boolean consoleOpen;
    private int consoleScroll;
    private boolean running;
    /** 用户直接在 IDE 控制台里输入的命令、历史游标与最近命令。 */
    private String consoleInput = "";
    private final List<String> consoleHistory = new ArrayList<>();
    private int consoleHistoryAt = -1;

    private String statusLine = "就绪";
    private long statusAt;
    private String chatInput = "";
    private final List<String> attachments = new ArrayList<>();
    private boolean readingImages;
    private String pendingText = "";
    private long pendingSince;
    private final Deque<Pending> commands = new ArrayDeque<>();
    private String lastMessage = "";

    private Field focus = Field.NONE;
    private Mode mode = Mode.NONE;
    private PromptKind promptKind = PromptKind.NONE;
    private String promptTitle = "";
    private String promptValue = "";
    private String promptBase = "";

    private final String[] drafts = new String[11];
    private int settingField = -1;
    private String settingsStatus = "";

    private int sessionScroll;
    private int imageScroll;
    private int skillScroll;
    private int overlayX;
    private int overlayY;
    private int overlayW;
    private int overlayH;
    private int overlayListY;
    private int overlayListH;
    private int overlayRowH = 22;

    private long lastVersion = -1L;
    private boolean stickChat = true;
    private int chatScroll;
    private int chatMaxScroll;
    private int treeMaxScroll;
    /** 默认折叠的思考内容与步骤详情，点击标题展开。 */
    private final Set<Integer> expandedThoughts = new HashSet<>();
    private final Set<Integer> expandedSteps = new HashSet<>();

    // 布局缓存（鼠标事件要用）
    private int leftW;
    private int rightW;
    private int midX;
    private int midW;
    private int panelTop;
    private int panelBottom;
    private int treeX;
    private int treeY;
    private int treeH;
    private int codeX;
    private int codeY;
    private int codeW;
    private int codeH;
    private int codeGutter;
    private int codeRows;
    private int consoleY;
    private int consoleH;
    private int chatX;
    private int chatW;
    private int transcriptX;
    private int transcriptY;
    private int transcriptW;
    private int transcriptH;
    private int chatInputX;
    private int chatInputY;
    private int chatInputW;
    private int chatInputH;
    private int attachY;
    private long lastBlink;
    /** 两侧面板在窄屏上可以折成窄条，把宽度让给编辑器。 */
    private boolean treeCollapsed;
    private boolean agentCollapsed;
    /** 拖动分隔条后的自定义栏宽（逻辑像素）；-1 表示沿用按比例算出的默认宽度。 */
    private int leftWanted = -1;
    private int rightWanted = -1;
    /** 0 未拖动，1 拖项目栏，2 拖智能体栏。 */
    private int splitDrag;
    private long lastSplitClickAt;
    private int lastSplitSide;

    private final List<Hit> hits = new ArrayList<>();

    public IdeApp(BlockPos pos, DeviceSnapshot snapshot) {
        this.pos = pos.immutable();
        Minecraft client = Minecraft.getInstance();
        this.font = client == null ? null : client.font;
        if (snapshot != null) {
            this.applySnapshot(snapshot);
        }
        this.open();
    }

    public BlockPos pos() {
        return this.pos;
    }

    /** 窗口被关掉、电脑关机或方块消失时释放会话。 */
    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.typing = false;
        if (this.agent != null && !this.agent.empty()) {
            this.agent.saveSession();
        }
    }

    /** 窗口关闭或最小化时保留会话：写入 .ide/sessions，实例与进度都还在。 */
    public void preserve() {
        if (this.disposed) {
            return;
        }
        this.typing = false;
        if (this.agent != null && !this.agent.empty()) {
            this.agent.saveSession();
        }
    }

    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }

    /** 关闭按钮的统一出口：交给宿主把窗口从桌面上摘掉。 */
    public void onClose() {
        if (this.closeListener != null) {
            this.closeListener.run();
        }
    }

    /** 当前是否真的在收键盘字符（输入法只在这时打开）。 */
    public boolean typing() {
        return this.typing && !this.disposed;
    }

    // ------------------------------------------------------------------ 生命周期

    private void open() {
        if (this.fs == null) {
            this.fs = new VirtualFileSystem();
        }
        if (this.root == null || this.root.isBlank() || this.fs.find(this.root) == null) {
            this.root = this.snapshot != null && this.snapshot.cwd != null
                    && !this.snapshot.cwd.isBlank() ? this.snapshot.cwd : "/home/user";
            this.fs.mkdirs(this.root);
        }
        if (this.host == null) {
            this.host = new AgentHost();
        }
        if (this.config == null) {
            this.config = this.loadConfig();
            this.seedProject();
            this.agent = new IdeAgent(this.host, this.config);
            this.agent.newSession();
            this.addConsole("JavaIDE 就绪 · 项目 " + this.root);
            this.addConsole("Ctrl+S 保存，Ctrl+Enter 运行，Ctrl+` 控制台。");
        }
        this.collapsed.add(this.root + "/.ide");
        this.syncTextInput();
    }


    private void applySnapshot(DeviceSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.snapshot = snapshot;
        this.fs = snapshot.fs;
        if (snapshot.cwd != null && !snapshot.cwd.isBlank()) {
            // 项目根在打开时固定；只有原目录消失才跟随当前工作目录。
            if (this.root == null || this.root.isBlank() || this.fs.find(this.root) == null) {
                this.root = snapshot.cwd;
            }
        }
    }

    /** 电脑收到新的设备快照：同步文件系统，并把命令输出交回等待者。 */
    public void updateSnapshot(DeviceSnapshot snapshot) {
        this.applySnapshot(snapshot);
        String message = snapshot.message == null ? "" : snapshot.message;
        if (message.isBlank() || message.equals(this.lastMessage)) {
            return;
        }
        this.lastMessage = message;
        if (isInternalMessage(message)) {
            return;
        }
        Pending pending = this.commands.poll();
        if (pending != null) {
            pending.done.accept(message);
        }
    }

    private static boolean isInternalMessage(String message) {
        return message.startsWith("已保存 ")
                || message.startsWith("已删除 ")
                || message.startsWith("已重命名 ")
                || message.startsWith("删除失败")
                || message.startsWith("重命名失败")
                || message.startsWith("写入失败")
                || message.startsWith("名称不能为空")
                || message.startsWith("名称没有变化")
                || message.startsWith("当前目录下已有同名")
                || message.startsWith("系统文件受保护");
    }

    private IdeConfig loadConfig() {
        VirtualFileSystem.Node node = this.fs == null ? null : this.fs.find(this.root + "/.ide/settings.json");
        return node == null ? new IdeConfig() : IdeConfig.parse(node.content);
    }

    private void seedProject() {
        String ide = this.root + "/.ide";
        if (this.fs.find(ide + "/settings.json") == null) {
            this.host.writeFile(ide + "/settings.json", this.config.toJson().toString());
        }
        this.ensureFile(ide + "/memory.md", MEMORY_TEMPLATE);
        this.ensureFile(ide + "/skills/java.md", JAVA_SKILL);
        this.ensureFile(ide + "/skills/debug.md", DEBUG_SKILL);
        this.ensureFile(ide + "/skills/agent.md", AGENT_SKILL);
        this.fs.mkdirs(ide + "/sessions");
        this.fs.mkdirs(ide + "/images");
    }

    private void ensureFile(String path, String content) {
        if (this.fs.find(path) == null) {
            this.host.writeFile(path, content);
        }
    }

    /** 由电脑界面每帧调用：处理命令超时、图片发送与光标闪烁。 */
    public void tick() {
        if (this.disposed) {
            return;
        }
        long now = System.currentTimeMillis();
        Pending pending = this.commands.peek();
        if (pending != null && now - pending.at > 30000L) {
            this.commands.poll();
            pending.done.accept("命令超时（30 秒）：" + pending.command);
        }
        if (this.readingImages) {
            this.finishImageSend(now);
        }
        if (now - this.lastBlink > 500L) {
            this.lastBlink = now;
        }
        this.syncTextInput();
    }

    // ------------------------------------------------------------------ 绘制

    /** 在电脑屏幕上的一块画布内绘制整个 IDE；坐标都以窗口左上角为原点。 */
    public void render(Canvas canvas, int w, int h, int mouseX, int mouseY) {
        if (w < 60 || h < 50 || this.disposed) {
            return;
        }
        this.width = w;
        this.height = h;
        Gfx graphics = new Gfx(canvas);
        this.tick();
        this.hits.clear();
        this.computeLayout();
        graphics.fill(0, 0, this.width, this.height, BG);
        this.renderTop(graphics, mouseX, mouseY);
        this.renderTree(graphics, mouseX, mouseY);
        this.renderEditor(graphics, mouseX, mouseY);
        this.renderAgent(graphics, mouseX, mouseY);
        this.renderFooter(graphics);

        long version = this.agent == null ? 0L : this.agent.version;
        if (version != this.lastVersion) {
            this.lastVersion = version;
            this.stickChat = true;
        }
        if (this.mode != Mode.NONE) {
            this.renderOverlay(graphics, mouseX, mouseY);
        }
    }

    private void computeLayout() {
        int w = this.width;
        int h = this.height;
        this.panelTop = HEAD;
        this.panelBottom = h - FOOT;

        // 逻辑分辨率是屏幕像素的两倍：默认按比例分栏，拖过分隔条就用记住的宽度。
        int lw = this.treeCollapsed ? 26
                : (this.leftWanted > 0 ? this.leftWanted : Math.max(128, Math.min(208, w * 22 / 100)));
        int rw = this.agentCollapsed ? 26
                : (this.rightWanted > 0 ? this.rightWanted : Math.max(176, Math.min(300, w * 34 / 100)));
        if (!this.treeCollapsed && !this.agentCollapsed && w - lw - rw < 236) {
            rw = Math.max(176, w - lw - 236);
        }
        this.leftW = Math.max(this.treeCollapsed ? 26 : 120, Math.min(lw, w / 3));
        this.rightW = Math.max(this.agentCollapsed ? 26 : 168, Math.min(rw, w - this.leftW - 192));
        this.midX = this.leftW;
        this.midW = Math.max(120, w - this.leftW - this.rightW);

        this.treeX = 0;
        this.treeY = HEAD + TREE_HEAD;
        this.treeH = Math.max(24, this.panelBottom - this.treeY);

        int contentTop = HEAD + (this.openFiles.isEmpty() ? 0 : TAB_H);
        this.consoleOpen = this.consoleOpen && this.midW > 200;
        this.consoleH = this.consoleOpen ? Math.min(84, Math.max(52, (this.panelBottom - contentTop) / 3)) : 0;
        this.consoleY = this.panelBottom - this.consoleH;
        this.codeGutter = this.midW < 200 ? 24 : 32;
        this.codeX = this.midX + this.codeGutter + 4;
        this.codeW = Math.max(30, this.midW - this.codeGutter - 12);
        this.codeY = contentTop;
        this.codeH = Math.max(18, (this.consoleOpen ? this.consoleY - 1 : this.panelBottom) - this.codeY);
        this.codeRows = Math.max(1, this.codeH / LINE_H);

        this.chatX = w - this.rightW;
        this.chatW = this.rightW;
        this.transcriptX = this.chatX + 5;
        this.transcriptW = Math.max(60, this.chatW - 10);
        this.transcriptY = HEAD + 21 + this.agentTabRows(this.transcriptW) * 11 + 2;
        this.chatInputH = 22;
        this.chatInputX = this.chatX + 5;
        this.chatInputW = Math.max(60, this.chatW - 10);
        this.chatInputY = this.panelBottom - 10 - 2 - this.chatInputH;
        this.attachY = this.chatInputY - 10;
        this.transcriptH = Math.max(12, this.attachY - 2 - this.transcriptY);
    }

    /** 智能体工具栏最多折成两行，按当前宽度计算行数。 */
    private int agentTabRows(int avail) {
        int x = 0;
        int rows = 1;
        for (String label : AGENT_TABS) {
            int bw = this.font.width(label) + 6;
            if (x > 0 && x + bw > avail) {
                rows++;
                x = 0;
            }
            x += bw + 2;
        }
        return Math.min(2, rows);
    }

    /** 按像素宽度截断文本，超长补省略号。 */
    private String fit(String text, int width) {
        if (text == null) {
            return "";
        }
        int w = Math.max(8, width);
        if (this.font.width(text) <= w) {
            return text;
        }
        String cut = this.font.plainSubstrByWidth(text, Math.max(0, w - this.font.width("…")));
        return cut + "…";
    }

    /** 画一行可能被截断的文本；截断时登记悬停气泡，坐标为本机逻辑坐标。 */
    private void label(Gfx graphics, String text, int x, int y, int width, int color) {
        int room = Math.max(8, width);
        graphics.text(this.font, this.fit(text, room), x, y, color, false);
        if (this.tips != null && text != null && !text.isEmpty() && this.font.width(text) > room) {
            this.tips.show(x, y - 1, room, 10, text);
        }
    }

    private void renderTop(Gfx graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, this.width, HEAD, 0xFF181818);
        graphics.fill(0, HEAD - 1, this.width, HEAD, BORDER);

        // 先排右侧按钮，再按剩余宽度画左侧标题，避免互相压字。
        // 关闭窗口统一用系统标题栏的 ✕，IDE 顶栏不再重复一个「关闭」。
        int right = this.width - 6;
        right = this.topChip(graphics, right, "控制台", mouseX, mouseY, () -> {
            this.consoleOpen = !this.consoleOpen;
            if (this.consoleOpen) {
                this.focus = Field.CONSOLE;
                this.syncTextInput();
            }
            this.status(this.consoleOpen ? "已打开控制台，可直接输入命令" : "已收起控制台");
        }) - 3;
        right = this.topChip(graphics, right, this.running ? "运行中" : "运行",
                this.running ? 0xFF2F5E3A : 0xFF2A2A2A, mouseX, mouseY, this::runActive) - 3;
        right = this.topChip(graphics, right, "保存", mouseX, mouseY, this::saveActive) - 3;
        int chipsLeft = this.topChip(graphics, right, "新建", mouseX, mouseY,
                () -> this.openPrompt(PromptKind.NEW_FILE, this.selectedBase(), "新建 Java 文件", "Main.java"));

        graphics.fill(5, 4, 15, 14, ACCENT);
        graphics.text(this.font, "J", 7, 4, 0xFFFFFFFF, false);
        graphics.text(this.font, "JavaIDE", 17, 5, 0xFFFFFFFF, false);
        int textX = 17 + this.font.width("JavaIDE") + 8;
        int room = chipsLeft - 8 - textX;
        if (room > 18) {
            this.label(graphics, this.root, textX, 5, room, MUTED);
        }
    }

    private int topChip(Gfx graphics, int right, String label, int mouseX, int mouseY,
                        Runnable action) {
        return this.topChip(graphics, right, label, 0xFF2A2A2A, mouseX, mouseY, action);
    }

    private int topChip(Gfx graphics, int right, String label, int base, int mouseX, int mouseY,
                        Runnable action) {
        int w = this.font.width(label) + 10;
        int x = right - w;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= 3 && mouseY < 15;
        graphics.fill(x, 3, x + w, 15, hover ? 0xFF2F6F9E : base);
        graphics.outline(x, 3, x + w, 15, hover ? 0xFF55AADD : BORDER);
        graphics.text(this.font, label, x + 5, 4, hover ? 0xFFFFFFFF : TEXT, false);
        this.hits.add(new Hit(x, 3, w, 12, 1, action, false));
        return x;
    }

    private void renderTree(Gfx graphics, int mouseX, int mouseY) {
        if (this.treeCollapsed) {
            graphics.fill(0, HEAD, this.leftW, this.panelBottom, PANEL);
            graphics.fill(this.leftW - 1, HEAD, this.leftW, this.panelBottom, BORDER);
            boolean toggleHover = mouseX >= 1 && mouseX < 11 && mouseY >= HEAD + 2 && mouseY < HEAD + 14;
            graphics.text(this.font, "»", 4, HEAD + 3, toggleHover ? 0xFFFFFFFF : MUTED, false);
            this.hits.add(new Hit(1, HEAD + 2, 10, 12, 1, () -> {
                this.treeCollapsed = false;
                this.status("已展开项目树");
            }, false));
            graphics.enableScissor(0, this.treeY, this.leftW - 1, this.panelBottom);
            graphics.text(this.font, "项", 3, this.treeY + 1, MUTED, false);
            graphics.text(this.font, "目", 3, this.treeY + 10, MUTED, false);
            graphics.disableScissor();
            this.hits.add(new Hit(1, this.treeY, 11, Math.max(12, this.panelBottom - this.treeY), 1, () -> {
                this.treeCollapsed = false;
                this.status("已展开项目树");
            }, false));
            return;
        }
        graphics.fill(0, HEAD, this.leftW, this.panelBottom, PANEL);
        graphics.fill(this.leftW - 1, HEAD, this.leftW, this.panelBottom, BORDER);
        graphics.text(this.font, "项目", 5, HEAD + 2, TEXT, false);

        boolean refreshHover = mouseX >= this.leftW - 39 && mouseX < this.leftW - 13
                && mouseY >= HEAD + 2 && mouseY < HEAD + 14;
        if (refreshHover) {
            graphics.fill(this.leftW - 39, HEAD + 2, this.leftW - 13, HEAD + 14, HOVER);
        }
        graphics.text(this.font, "刷新", this.leftW - 37, HEAD + 3,
                refreshHover ? 0xFFFFFFFF : MUTED, false);
        this.hits.add(new Hit(this.leftW - 39, HEAD + 2, 26, 12, 1,
                () -> HajikunSmartClient.request(this.pos, "refresh", ""), false));

        boolean collapseHover = mouseX >= this.leftW - 12 && mouseX < this.leftW - 1
                && mouseY >= HEAD + 2 && mouseY < HEAD + 14;
        if (collapseHover) {
            graphics.fill(this.leftW - 12, HEAD + 2, this.leftW - 1, HEAD + 14, HOVER);
        }
        graphics.text(this.font, "«", this.leftW - 9, HEAD + 3,
                collapseHover ? 0xFFFFFFFF : MUTED, false);
        this.hits.add(new Hit(this.leftW - 12, HEAD + 2, 11, 12, 1, () -> {
            this.treeCollapsed = true;
            this.status("已收起项目树");
        }, false));

        int bx = 3;
        bx = this.leftChip(graphics, bx, HEAD + 13, "+文件", mouseX, mouseY,
                () -> this.openPrompt(PromptKind.NEW_FILE, this.selectedBase(),
                        "新建文件", "Main.java")) + 3;
        this.leftChip(graphics, bx, HEAD + 13, "+目录", mouseX, mouseY,
                () -> this.openPrompt(PromptKind.NEW_FOLDER, this.selectedBase(),
                        "新建文件夹", "src"));

        List<TreeRow> rows = new ArrayList<>();
        this.collectRows(this.root, 0, rows);
        int visible = Math.max(1, this.treeH / TREE_ROW);
        this.treeMaxScroll = Math.max(0, rows.size() - visible);
        this.treeScroll = Math.max(0, Math.min(this.treeScroll, this.treeMaxScroll));

        graphics.enableScissor(0, this.treeY, this.leftW - 1, this.treeY + this.treeH);
        for (int i = 0; i < visible; i++) {
            int index = this.treeScroll + i;
            if (index >= rows.size()) {
                break;
            }
            TreeRow row = rows.get(index);
            int y = this.treeY + i * TREE_ROW;
            boolean hover = mouseX >= 0 && mouseX < this.leftW - 2 && mouseY >= y && mouseY < y + TREE_ROW;
            boolean active = row.path.equals(this.selected);
            if (active) {
                graphics.fill(0, y, this.leftW - 1, y + TREE_ROW, SELECT);
            } else if (hover) {
                graphics.fill(0, y, this.leftW - 1, y + TREE_ROW, HOVER);
            }
            int indent = 4 + row.depth * 9;
            if (row.dir) {
                graphics.text(this.font, row.expanded ? "v" : ">", indent, y + 3, MUTED, false);
                graphics.fill(indent + 6, y + 5, indent + 14, y + 11, YELLOW);
                graphics.fill(indent + 8, y + 3, indent + 12, y + 6, YELLOW);
            } else {
                int color = this.fileColor(row.name);
                graphics.fill(indent + 6, y + 5, indent + 12, y + 11, color);
                graphics.fill(indent + 7, y + 3, indent + 11, y + 5, color);
            }
            int nameX = indent + 16;
            this.label(graphics, row.name, nameX, y + 3,
                    Math.max(10, this.leftW - nameX - 8), row.dir ? TEXT : 0xFFCCCCCC);
            OpenFile open = this.findOpen(row.path);
            if (open != null && open.dirty) {
                graphics.fill(this.leftW - 7, y + 5, this.leftW - 4, y + 8, ORANGE);
            }
            String path = row.path;
            boolean dir = row.dir;
            this.hits.add(new Hit(0, y, this.leftW - 2, TREE_ROW, 1, () -> {
                long now = System.currentTimeMillis();
                boolean doubled = path.equals(this.lastTreeClickPath) && now - this.lastTreeClickAt < 400L;
                this.lastTreeClickAt = now;
                this.lastTreeClickPath = path;
                this.selected = path;
                if (dir) {
                    if (doubled) {
                        this.openAsFile(path);
                        return;
                    }
                    if (this.collapsed.contains(path)) {
                        this.collapsed.remove(path);
                    } else {
                        this.collapsed.add(path);
                    }
                } else {
                    this.openFile(path);
                }
            }, false));
        }
        graphics.disableScissor();

        if (this.treeMaxScroll > 0) {
            int barH = Math.max(18, this.treeH * visible / Math.max(1, rows.size()));
            int barY = this.treeY + (this.treeH - barH) * this.treeScroll / this.treeMaxScroll;
            graphics.fill(this.leftW - 4, barY, this.leftW - 2, barY + barH, 0xFF555555);
        }
        // 分隔条：悬停或拖动时高亮，提示这里可以拖宽窄。
        boolean splitHover = Math.abs(mouseX - this.leftW) <= 4
                && mouseY >= HEAD && mouseY < this.panelBottom;
        if (splitHover || this.splitDrag == 1) {
            graphics.fill(this.leftW - 2, HEAD, this.leftW + 2, this.panelBottom,
                    this.splitDrag == 1 ? 0xFF4A9BF5 : 0x664A9BF5);
        }
    }

    private int leftChip(Gfx graphics, int x, int y, String label, int mouseX, int mouseY,
                         Runnable action) {
        int w = this.font.width(label) + 8;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
        if (hover) {
            graphics.fill(x, y, x + w, y + 12, HOVER);
        }
        graphics.text(this.font, label, x + 4, y + 2, hover ? 0xFFFFFFFF : MUTED, false);
        this.hits.add(new Hit(x, y, w, 12, 1, action, false));
        return x + w;
    }

    private int smallButton(Gfx graphics, int right, int y, String label,
                            int mouseX, int mouseY, Runnable action) {
        int w = this.font.width(label) + 8;
        int x = right - w;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
        if (hover) {
            graphics.fill(x, y, x + w, y + 12, HOVER);
        }
        graphics.text(this.font, label, x + 4, y + 2, hover ? 0xFFFFFFFF : MUTED, false);
        this.hits.add(new Hit(x, y, w, 12, 1, action, false));
        return x;
    }

    private void openAsFile(String path) {
        if (this.fs == null) {
            return;
        }
        VirtualFileSystem.Node node = this.fs.find(path);
        if (node != null && !node.isDir()) {
            this.openFile(path);
        }
    }

    private void renderEditor(Gfx graphics, int mouseX, int mouseY) {
        graphics.fill(this.midX, HEAD, this.midX + this.midW, this.panelBottom, BG);
        graphics.fill(this.midX, HEAD, this.midX + 1, this.panelBottom, BORDER);
        graphics.fill(this.midX + this.midW - 1, HEAD, this.midX + this.midW, this.panelBottom, BORDER);

        int tabX = this.midX + 1;
        int tabY = HEAD;
        int tabW = Math.max(0, this.midW - 2);
        if (!this.openFiles.isEmpty()) {
            graphics.fill(tabX, tabY, tabX + tabW, tabY + TAB_H, TAB_OFF);
            for (OpenFile file : new ArrayList<>(this.openFiles)) {
                String name = this.fileName(file.path);
                int w = Math.min(112, this.font.width(name) + 26);
                if (tabX + w > this.midX + this.midW - 2) {
                    break;
                }
                boolean active = file == this.activeFile;
                boolean hover = mouseX >= tabX && mouseX < tabX + w && mouseY >= tabY && mouseY < tabY + TAB_H;
                graphics.fill(tabX, tabY, tabX + w, tabY + TAB_H, active ? BG : hover ? 0xFF333333 : TAB_OFF);
                if (active) {
                    graphics.fill(tabX, tabY, tabX + w, tabY + 2, ACCENT);
                }
                this.label(graphics, name, tabX + 5, tabY + 3, w - 20,
                        active ? 0xFFFFFFFF : MUTED);
                if (file.dirty) {
                    graphics.fill(tabX + w - 14, tabY + 6, tabX + w - 11, tabY + 9, ORANGE);
                }
                int closeX = tabX + w - 9;
                boolean closeHover = mouseX >= closeX - 2 && mouseX < closeX + 7
                        && mouseY >= tabY + 2 && mouseY < tabY + 12;
                graphics.text(this.font, "x", closeX, tabY + 3, closeHover ? 0xFFFFFFFF : DIM, false);
                this.hits.add(new Hit(tabX, tabY, w - 11, TAB_H, 1, () -> {
                    this.activeFile = file;
                    this.focus = Field.EDITOR;
                    this.ensureCaretVisible(file);
                }, false));
                this.hits.add(new Hit(closeX - 2, tabY + 2, 10, 10, 1, () -> this.closeFile(file), false));
                tabX += w + 1;
            }
        }

        if (this.activeFile == null) {
            this.renderWelcome(graphics, mouseX, mouseY);
        } else {
            this.renderCode(graphics, mouseX, mouseY);
        }
        if (this.consoleOpen) {
            this.renderConsole(graphics, mouseX, mouseY);
        }
    }

    private void renderWelcome(Gfx graphics, int mouseX, int mouseY) {
        int cx = this.midX + this.midW / 2;
        int y = HEAD + Math.max(10, this.midH() / 2 - 34);
        String title = "JavaIDE";
        graphics.text(this.font, title, cx - this.font.width(title) / 2, y, 0xFFEAEAEA, false);
        String hint = this.fit("双击文件打开，或新建 Java 类", this.midW - 16);
        graphics.text(this.font, hint, cx - this.font.width(hint) / 2, y + 14, MUTED, false);
        String hint2 = this.fit("Ctrl+S 保存 · Ctrl+Enter 运行", this.midW - 16);
        graphics.text(this.font, hint2, cx - this.font.width(hint2) / 2, y + 25, DIM, false);

        String primary = "新建 Main.java";
        String secondary = "让智能体新建";
        int w1 = this.font.width(primary) + 14;
        int w2 = this.font.width(secondary) + 14;
        int by = y + 42;
        if (w1 + w2 + 6 <= this.midW - 12) {
            int bx = cx - (w1 + w2 + 6) / 2;
            bx = this.actionChip(graphics, bx, by, primary, mouseX, mouseY,
                    () -> this.openPrompt(PromptKind.NEW_FILE, this.root, "新建 Java 文件", "Main.java")) + 6;
            this.actionChip(graphics, bx, by, secondary, mouseX, mouseY, this::askAgentNewProject);
        } else {
            int bx = cx - w1 / 2;
            int nextY = this.actionChip(graphics, bx, by, primary, mouseX, mouseY,
                    () -> this.openPrompt(PromptKind.NEW_FILE, this.root, "新建 Java 文件", "Main.java"));
            this.actionChip(graphics, cx - w2 / 2, nextY + 4, secondary, mouseX, mouseY,
                    this::askAgentNewProject);
        }
    }

    private void askAgentNewProject() {
        this.chatInput = "请在这个项目里创建一个可运行的 Java 入门项目，并编译运行验证。";
        this.focus = Field.CHAT;
        this.syncTextInput();
    }

    private int midH() {
        return this.panelBottom - HEAD;
    }

    private void renderCode(Gfx graphics, int mouseX, int mouseY) {
        OpenFile file = this.activeFile;
        int lineH = LINE_H;
        int rows = Math.max(1, this.codeH / lineH);
        int maxScroll = Math.max(0, file.lines.size() - rows + 1);
        file.scroll = Math.max(0, Math.min(file.scroll, maxScroll));
        boolean focused = this.focus == Field.EDITOR;

        graphics.fill(this.midX + 1, this.codeY, this.midX + this.midW - 1, this.codeY + this.codeH, BG);
        graphics.fill(this.midX + 1, this.codeY, this.midX + this.codeGutter - 6, this.codeY + this.codeH, 0xFF1E1E1E);

        if (focused) {
            int cy = this.codeY + (file.row - file.scroll) * lineH;
            if (cy >= this.codeY && cy < this.codeY + this.codeH) {
                graphics.fill(this.midX + 1, cy - 1, this.midX + this.midW - 1, cy + lineH - 1, 0xFF2A2D2E);
            }
        }

        int gutterW = this.font.width(String.valueOf(Math.max(1, file.lines.size())));
        graphics.enableScissor(this.midX + 1, this.codeY, this.midX + this.midW - 1, this.codeY + this.codeH);
        for (int i = 0; i < rows; i++) {
            int index = file.scroll + i;
            if (index >= file.lines.size()) {
                break;
            }
            int y = this.codeY + i * lineH;
            String number = String.valueOf(index + 1);
            graphics.text(this.font, number, this.midX + this.codeGutter - 8 - this.font.width(number),
                    y, index == file.row ? 0xFFC6C6C6 : 0xFF858585, false);
            int lineX = this.codeX - file.hscroll;
            String line = file.lines.get(index);
            this.drawCodeLine(graphics, line, lineX, y, this.codeX + this.codeW);
        }
        graphics.disableScissor();

        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            String line = file.lines.get(Math.min(file.row, file.lines.size() - 1));
            int col = Math.min(file.col, line.length());
            int cx = this.codeX - file.hscroll + this.font.width(line.substring(0, col));
            int cy = this.codeY + (file.row - file.scroll) * lineH;
            if (cx >= this.codeX && cx <= this.codeX + this.codeW
                    && cy >= this.codeY && cy < this.codeY + this.codeH) {
                graphics.fill(cx, cy, cx + 1, cy + lineH - 1, 0xFFAEAFAD);
            }
        }

        if (file.lines.size() > rows) {
            int barH = Math.max(16, this.codeH * rows / Math.max(1, file.lines.size()));
            int barY = this.codeY + (this.codeH - barH) * file.scroll / Math.max(1, maxScroll);
            graphics.fill(this.midX + this.midW - 5, barY, this.midX + this.midW - 2, barY + barH, 0xFF4A4A4A);
        }

    }

    private void renderConsole(Gfx graphics, int mouseX, int mouseY) {
        int x = this.midX + 1;
        graphics.fill(x, this.consoleY, this.midX + this.midW - 1, this.panelBottom, 0xFF181818);
        graphics.fill(x, this.consoleY, this.midX + this.midW - 1, this.consoleY + 1, BORDER);
        graphics.text(this.font, "控制台", x + 6, this.consoleY + 3, TEXT, false);
        int bx = this.midX + this.midW - 4;
        bx = this.smallButton(graphics, bx, this.consoleY + 2, "清空", mouseX, mouseY, () -> {
            this.consoleLines.clear();
            this.consoleScroll = 0;
        }) - 2;
        this.smallButton(graphics, bx, this.consoleY + 2, "收起", mouseX, mouseY,
                () -> this.consoleOpen = false);

        // 底部固定一行用户输入：点一下就能直接敲命令，和智能体共用同一个 shell。
        int inputY = this.panelBottom - 12;
        int listY = this.consoleY + 16;
        int listH = Math.max(8, inputY - listY - 1);
        int rows = Math.max(1, listH / LINE_H);
        List<String> lines = new ArrayList<>(this.consoleLines);
        int maxScroll = Math.max(0, lines.size() - rows);
        this.consoleScroll = Math.max(0, Math.min(this.consoleScroll, maxScroll));
        graphics.enableScissor(x, listY, this.midX + this.midW - 1, listY + listH);
        for (int i = 0; i < rows; i++) {
            int index = this.consoleScroll + i;
            if (index >= lines.size()) {
                break;
            }
            String line = lines.get(index);
            int color = line.startsWith("错误") || line.contains("错误:") || line.contains("Exception")
                    ? 0xFFFFA0A0 : line.startsWith(">") ? GREEN
                    : line.startsWith(this.consolePrompt()) ? BLUE : 0xFFBBBBBB;
            this.label(graphics, line, x + 6, listY + i * LINE_H, this.midW - 16, color);
        }
        graphics.disableScissor();

        boolean focused = this.focus == Field.CONSOLE;
        graphics.fill(x, inputY - 1, this.midX + this.midW - 1, inputY, BORDER);
        graphics.fill(x + 1, inputY, this.midX + this.midW - 2, this.panelBottom, 0xFF101010);
        String prompt = this.consolePrompt();
        graphics.text(this.font, prompt, x + 4, inputY + 2, GREEN, false);
        int textX = x + 4 + this.font.width(prompt);
        String shown = this.consoleInput;
        int room = Math.max(10, this.midX + this.midW - 6 - textX);
        graphics.enableScissor(textX, inputY, this.midX + this.midW - 3, this.panelBottom);
        graphics.text(this.font, shown, textX, inputY + 2, focused ? 0xFFFFFFFF : TEXT, false);
        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int caret = Math.min(textX + this.font.width(shown), this.midX + this.midW - 4);
            graphics.fill(caret, inputY + 1, caret + 1, this.panelBottom - 1, 0xFFCCCCCC);
        }
        graphics.disableScissor();
        if (!focused && shown.isBlank()) {
            this.label(graphics, "点这里输入命令，和智能体共用终端", textX, inputY + 2, room, DIM);
        }
        this.hits.add(new Hit(x, inputY - 1, this.midW - 2, this.panelBottom - inputY + 1, 1, () -> {
            this.focus = Field.CONSOLE;
            this.syncTextInput();
        }, false));
    }

    /** 控制台提示符：显示虚拟机当前目录，用户和智能体看到的是同一套路径。 */
    private String consolePrompt() {
        String cwd = this.snapshot == null || this.snapshot.cwd == null || this.snapshot.cwd.isBlank()
                ? this.root : this.snapshot.cwd;
        String path = cwd == null ? "/home/user" : cwd.replace('/', '\\');
        return "C:" + (path.equals("\\") ? "" : path) + "> ";
    }

    /** 用户在控制台回车：本地记录一行命令，再交给电脑执行，输出回到同一个列表。 */
    private void runConsoleInput() {
        String command = this.consoleInput.trim();
        if (command.isEmpty()) {
            return;
        }
        this.consoleInput = "";
        if (this.consoleHistory.isEmpty()
                || !this.consoleHistory.get(this.consoleHistory.size() - 1).equals(command)) {
            this.consoleHistory.add(command);
            while (this.consoleHistory.size() > 100) {
                this.consoleHistory.remove(0);
            }
        }
        this.consoleHistoryAt = -1;
        this.addConsole(this.consolePrompt() + command);
        this.exec(command, output -> {
            if (output == null || output.isBlank()) {
                this.addConsole("(没有输出)");
            } else {
                this.addConsole(output);
            }
        });
    }

    private void renderAgent(Gfx graphics, int mouseX, int mouseY) {
        if (this.agentCollapsed) {
            int x = this.chatX;
            graphics.fill(x, HEAD, this.width, this.panelBottom, PANEL);
            graphics.fill(x, HEAD, x + 1, this.panelBottom, BORDER);
            boolean toggleHover = mouseX >= x + 2 && mouseX < x + 12 && mouseY >= HEAD + 2 && mouseY < HEAD + 14;
            graphics.text(this.font, "«", x + 4, HEAD + 3, toggleHover ? 0xFFFFFFFF : MUTED, false);
            this.hits.add(new Hit(x + 1, HEAD + 2, 10, 12, 1, () -> {
                this.agentCollapsed = false;
                this.status("已展开智能体");
            }, false));
            graphics.enableScissor(x, this.treeY, this.width, this.panelBottom);
            graphics.text(this.font, "智", x + 3, this.treeY + 1, MUTED, false);
            graphics.text(this.font, "能", x + 3, this.treeY + 10, MUTED, false);
            graphics.text(this.font, "体", x + 3, this.treeY + 19, MUTED, false);
            graphics.disableScissor();
            this.hits.add(new Hit(x + 1, this.treeY, 11, Math.max(12, this.panelBottom - this.treeY), 1, () -> {
                this.agentCollapsed = false;
                this.status("已展开智能体");
            }, false));
            return;
        }
        graphics.fill(this.chatX, HEAD, this.width, this.panelBottom, PANEL);
        graphics.fill(this.chatX, HEAD, this.chatX + 1, this.panelBottom, BORDER);
        graphics.text(this.font, "智能体", this.chatX + 5, HEAD + 2, 0xFFEAEAEA, false);

        int right = this.chatX + this.chatW - 1;
        boolean collapseHover = mouseX >= right - 11 && mouseX < right
                && mouseY >= HEAD + 2 && mouseY < HEAD + 13;
        if (collapseHover) {
            graphics.fill(right - 11, HEAD + 2, right, HEAD + 13, HOVER);
        }
        graphics.text(this.font, "»", right - 8, HEAD + 2, collapseHover ? 0xFFFFFFFF : MUTED, false);
        this.hits.add(new Hit(right - 11, HEAD + 2, 11, 11, 1, () -> {
            this.agentCollapsed = true;
            this.status("已收起智能体 · Ctrl+B 切换");
        }, false));

        String gear = "设置";
        int gearW = this.font.width(gear);
        int gearX = right - 14 - gearW;
        boolean gearHover = mouseX >= gearX - 2 && mouseX < gearX + gearW + 2
                && mouseY >= HEAD + 2 && mouseY < HEAD + 13;
        if (gearHover) {
            graphics.fill(gearX - 2, HEAD + 2, gearX + gearW + 2, HEAD + 13, HOVER);
        }
        graphics.text(this.font, gear, gearX, HEAD + 2, gearHover ? 0xFFFFFFFF : MUTED, false);
        this.hits.add(new Hit(gearX - 2, HEAD + 2, gearW + 4, 11, 1, this::openSettings, false));
        if (this.agent != null) {
            int phaseColor = switch (this.agent.phase()) {
                case RUNNING, PLANNING -> GREEN;
                case ERROR, STOPPED -> RED;
                case DONE -> BLUE;
                default -> MUTED;
            };
            int phaseX = this.chatX + 5 + this.font.width("智能体") + 5;
            this.label(graphics, this.agent.phaseLabel(), phaseX, HEAD + 2,
                    gearX - 4 - phaseX, phaseColor);
            String model = this.config.shortProvider() + " · " + this.config.modelLabel();
            this.label(graphics, model, this.chatX + 5, HEAD + 11, this.chatW - 12, DIM);
        }

        // 会话工具条：轻量文字标签，按宽度自动折行，最多两行
        int tx = this.chatX + 5;
        int ty = HEAD + 21;
        for (int i = 0; i < AGENT_TABS.length; i++) {
            String label = AGENT_TABS[i];
            if (i == 3 && !this.attachments.isEmpty()) {
                label = label + this.attachments.size();
            }
            int tw = this.font.width(label) + 6;
            if (tx > this.chatX + 5 && tx + tw > this.transcriptX + this.transcriptW) {
                tx = this.chatX + 5;
                ty += 11;
            }
            boolean hover = mouseX >= tx - 2 && mouseX < tx + tw - 2 && mouseY >= ty && mouseY < ty + 11;
            if (hover) {
                graphics.fill(tx - 2, ty, tx + tw - 2, ty + 11, HOVER);
            }
            graphics.text(this.font, label, tx + 2, ty + 1, hover ? 0xFFFFFFFF : TEXT, false);
            final int tab = i;
            this.hits.add(new Hit(tx - 2, ty, tw, 11, 1, () -> this.onAgentTab(tab), false));
            tx += tw + 2;
        }
        if (this.agent != null && this.agent.busy()) {
            String stop = "■ 停止";
            int sw = this.font.width(stop) + 8;
            if (tx + sw <= this.chatX + this.chatW - 4) {
                boolean hover = mouseX >= tx - 2 && mouseX < tx + sw - 2 && mouseY >= ty && mouseY < ty + 11;
                if (hover) {
                    graphics.fill(tx - 2, ty, tx + sw - 2, ty + 11, HOVER);
                }
                graphics.text(this.font, stop, tx + 2, ty + 1, hover ? 0xFFFFFFFF : RED, false);
                this.hits.add(new Hit(tx - 2, ty, sw, 11, 1, () -> this.agent.cancel(), false));
            }
        }

        List<ChatLine> lines = new ArrayList<>();
        this.buildTranscript(lines);
        int lineH = LINE_H;
        int totalH = lines.size() * lineH;
        this.chatMaxScroll = Math.max(0, totalH - this.transcriptH);
        if (this.stickChat) {
            this.chatScroll = this.chatMaxScroll;
        }
        this.chatScroll = Math.max(0, Math.min(this.chatScroll, this.chatMaxScroll));

        graphics.enableScissor(this.transcriptX, this.transcriptY,
                this.transcriptX + this.transcriptW, this.transcriptY + this.transcriptH);
        int start = Math.max(0, this.chatScroll / lineH - 1);
        int end = Math.min(lines.size(), start + this.transcriptH / lineH + 3);
        for (int i = start; i < end; i++) {
            ChatLine line = lines.get(i);
            int ly = this.transcriptY + i * lineH - this.chatScroll;
            boolean hover = line.action != null
                    && mouseX >= this.transcriptX && mouseX < this.transcriptX + this.transcriptW
                    && mouseY >= ly && mouseY < ly + lineH;
            if (line.action != null) {
                if (hover) {
                    graphics.fill(this.transcriptX, ly, this.transcriptX + this.transcriptW,
                            ly + lineH, HOVER);
                }
                final Runnable action = line.action;
                this.hits.add(new Hit(this.transcriptX, ly, this.transcriptW, lineH, 1,
                        action, false));
            }
            if (!line.text.isEmpty()) {
                this.label(graphics, line.text, this.transcriptX + line.indent, ly,
                        this.transcriptW - line.indent, line.color);
            }
        }
        graphics.disableScissor();

        if (this.chatMaxScroll > 0) {
            int barH = Math.max(16, this.transcriptH * this.transcriptH / Math.max(1, totalH));
            int barY = this.transcriptY + (this.transcriptH - barH) * this.chatScroll / this.chatMaxScroll;
            graphics.fill(this.chatX + this.chatW - 3, barY, this.chatX + this.chatW - 2, barY + barH, 0xFF555555);
        }

        // 附件
        int ax = this.chatX + 6;
        for (String path : new ArrayList<>(this.attachments)) {
            String label = this.fileName(path);
            int w = Math.min(this.chatW - 20, this.font.width(label) + 18);
            if (ax + w > this.chatX + this.chatW - 4) {
                break;
            }
            graphics.fill(ax, this.attachY, ax + w, this.attachY + 10, 0xFF2F4A3A);
            this.label(graphics, label, ax + 3, this.attachY + 1, w - 14, GREEN);
            graphics.text(this.font, "x", ax + w - 7, this.attachY + 1, MUTED, false);
            String remove = path;
            this.hits.add(new Hit(ax, this.attachY, w, 10, 1,
                    () -> this.attachments.remove(remove), false));
            ax += w + 3;
        }

        // 输入框
        graphics.fill(this.chatInputX, this.chatInputY, this.chatInputX + this.chatInputW,
                this.chatInputY + this.chatInputH, INPUT_BG);
        graphics.outline(this.chatInputX, this.chatInputY, this.chatInputX + this.chatInputW,
                this.chatInputY + this.chatInputH, this.focus == Field.CHAT ? ACCENT : BORDER);
        int sendW = Math.max(26, this.font.width("发送") + 10);
        int inputRight = this.chatInputX + this.chatInputW - sendW - 10;
        int inputTextW = Math.max(30, inputRight - this.chatInputX - 8);
        if (this.chatInput.isEmpty()) {
            this.label(graphics, "描述要改什么…", this.chatInputX + 5, this.chatInputY + 7,
                    inputTextW, DIM);
        } else {
            List<String> inputLines = this.wrapInput(this.chatInput, inputTextW);
            int startLine = Math.max(0, inputLines.size() - 2);
            int textY = inputLines.size() - startLine > 1 ? this.chatInputY + 2 : this.chatInputY + 7;
            for (int i = startLine; i < inputLines.size(); i++) {
                this.label(graphics, inputLines.get(i), this.chatInputX + 5,
                        textY + (i - startLine) * LINE_H, inputTextW, 0xFFEAEAEA);
            }
        }
        boolean busy = this.agent != null && this.agent.busy();
        int sendX = this.chatInputX + this.chatInputW - sendW - 4;
        int sendY = this.chatInputY + (this.chatInputH - 12) / 2;
        this.button(graphics, sendX, sendY, sendW, 12, busy ? "停止" : "发送",
                busy ? RED : ACCENT, mouseX, mouseY, busy ? () -> this.agent.cancel() : this::sendChat);

        String footer = this.config.providerLabel() + " · " + (this.agent == null ? 0 : this.agent.estimateTokens())
                + "/" + this.config.compressAt + " tokens";
        if (this.agent != null && this.agent.estimateTokens() > this.config.compressAt) {
            footer += " · 自动压缩";
        }
        this.label(graphics, footer, this.chatX + 6, this.panelBottom - 9, this.chatW - 12, DIM);

        // 智能体栏左边缘同样可拖动。
        boolean splitHover = Math.abs(mouseX - this.chatX) <= 4
                && mouseY >= HEAD && mouseY < this.panelBottom;
        if (splitHover || this.splitDrag == 2) {
            graphics.fill(this.chatX - 2, HEAD, this.chatX + 2, this.panelBottom,
                    this.splitDrag == 2 ? 0xFF4A9BF5 : 0x664A9BF5);
        }
    }

    /** 命中拖拽分隔条：1 = 项目栏右边缘，2 = 智能体栏左边缘，0 = 不在分隔条上。 */
    private int splitAt(int mx, int my) {
        if (my < HEAD || my >= this.panelBottom) {
            return 0;
        }
        if (!this.treeCollapsed && Math.abs(mx - this.leftW) <= 4) {
            return 1;
        }
        if (!this.agentCollapsed && Math.abs(mx - this.chatX) <= 4) {
            return 2;
        }
        return 0;
    }

    private void onAgentTab(int tab) {
        switch (tab) {
            case 0 -> {
                if (this.agent != null) {
                    this.agent.newSession();
                    this.attachments.clear();
                    this.chatInput = "";
                    this.stickChat = true;
                    this.status("已新建会话");
                }
            }
            case 1 -> this.openSessions();
            case 2 -> this.openSkills();
            case 3 -> this.openImages();
            default -> this.openSettings();
        }
    }

    private int actionChip(Gfx graphics, int x, int y, String label, int mouseX, int mouseY,
                           Runnable action) {
        int w = this.font.width(label) + 14;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 16;
        graphics.fill(x, y, x + w, y + 16, hover ? 0xFF2F6F9E : 0xFF2A2A2A);
        graphics.outline(x, y, x + w, y + 16, hover ? 0xFF55AADD : BORDER);
        graphics.text(this.font, label, x + 7, y + 4, hover ? 0xFFFFFFFF : TEXT, false);
        this.hits.add(new Hit(x, y, w, 16, 1, action, false));
        return x + w;
    }

    private void renderFooter(Gfx graphics) {
        int y = this.height - FOOT;
        graphics.fill(0, y, this.width, this.height, ACCENT);
        graphics.fill(0, y, this.width, y + 1, 0xFF1B8BD0);
        int leftMax = this.width * 55 / 100 - 10;
        this.label(graphics, this.statusLine, 5, y + 1, leftMax, 0xFFFFFFFF);
        String right = "Java · UTF-8 · " + this.config.providerLabel() + " · "
                + (this.agent == null ? "就绪" : this.agent.phaseLabel());
        if (this.activeFile != null) {
            right = "行 " + (this.activeFile.row + 1) + " 列 " + (this.activeFile.col + 1) + " · " + right;
        }
        int rightMax = this.width - leftMax - 16;
        String shown = this.fit(right, rightMax);
        graphics.text(this.font, shown, this.width - 5 - this.font.width(shown), y + 1, 0xFFFFFFFF, false);
        if (this.tips != null && this.font.width(right) > Math.max(8, rightMax)) {
            this.tips.show(this.width - 5 - this.font.width(shown), y, rightMax, 10, right);
        }
    }

    // ------------------------------------------------------------------ 代码渲染

    private void drawCodeLine(Gfx graphics, String line, int x, int y, int clipRight) {
        if (line.isEmpty() || x >= clipRight) {
            return;
        }
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
            graphics.text(this.font, line, x, y, 0xFF6A9955, false);
            return;
        }
        int comment = commentStart(line);
        String code = comment >= 0 ? line.substring(0, comment) : line;
        int cx = x;
        int i = 0;
        while (i < code.length() && cx < clipRight) {
            char ch = code.charAt(i);
            int j = i + 1;
            int color = TEXT;
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                j = i + 1;
                while (j < code.length()) {
                    char c = code.charAt(j);
                    if (c == '\\' && j + 1 < code.length()) {
                        j += 2;
                        continue;
                    }
                    if (c == quote) {
                        j++;
                        break;
                    }
                    j++;
                }
                color = ORANGE;
            } else if (Character.isDigit(ch)) {
                while (j < code.length() && (Character.isLetterOrDigit(code.charAt(j))
                        || code.charAt(j) == '.' || code.charAt(j) == '_')) {
                    j++;
                }
                color = 0xFFB5CEA8;
            } else if (Character.isJavaIdentifierStart(ch)) {
                while (j < code.length() && Character.isJavaIdentifierPart(code.charAt(j))) {
                    j++;
                }
                String word = code.substring(i, j);
                if (KEYWORDS.contains(word)) {
                    color = BLUE;
                } else if (word.equals("true") || word.equals("false") || word.equals("null")) {
                    color = PURPLE;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    color = GREEN;
                } else {
                    color = TEXT;
                }
            } else if (ch == '@' && i + 1 < code.length()
                    && Character.isJavaIdentifierStart(code.charAt(i + 1))) {
                while (j < code.length() && Character.isJavaIdentifierPart(code.charAt(j))) {
                    j++;
                }
                color = YELLOW;
            }
            String token = code.substring(i, j);
            if (cx + this.font.width(token) > clipRight) {
                int fit = Math.max(0, clipRight - cx);
                token = this.font.plainSubstrByWidth(token, fit);
                if (token.isEmpty()) {
                    break;
                }
            }
            graphics.text(this.font, token, cx, y, color, false);
            cx += this.font.width(token);
            i = j;
        }
        if (comment >= 0) {
            String tail = line.substring(comment);
            if (cx < clipRight) {
                graphics.text(this.font, this.font.plainSubstrByWidth(tail, clipRight - cx),
                        cx, y, 0xFF6A9955, false);
            }
        }
    }

    private static int commentStart(String line) {
        boolean string = false;
        boolean chr = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (string) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    string = false;
                }
            } else if (chr) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    chr = false;
                }
            } else if (c == '"') {
                string = true;
            } else if (c == '\'') {
                chr = true;
            } else if (c == '/' && line.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 智能体面板内容

    private record ChatLine(String text, int color, int indent, Runnable action) {
        private ChatLine(String text, int color, int indent) {
            this(text, color, indent, null);
        }
    }

    private void buildTranscript(List<ChatLine> out) {
        if (this.agent == null) {
            return;
        }
        int width = Math.max(60, this.transcriptW - 6);
        List<IdeMessage> messages = this.agent.messages;
        int start = Math.max(0, messages.size() - 40);
        if (start > 0) {
            out.add(new ChatLine("… 更早的 " + start + " 条消息已折叠，用新会话可减轻上下文 …", DIM, 0));
            out.add(new ChatLine("", TEXT, 0));
        }
        for (int m = start; m < messages.size(); m++) {
            IdeMessage message = messages.get(m);
            switch (message.role) {
                case "user" -> {
                    out.add(new ChatLine("你", ACCENT, 0));
                    String text = message.content == null ? "" : message.content;
                    if (!text.isBlank()) {
                        this.wrapInto(out, this.cap(text, 4000), width - 8, TEXT, 6);
                    }
                    if (!message.images.isEmpty()) {
                        out.add(new ChatLine("[附带 " + message.images.size() + " 张图片]", GREEN, 6));
                    }
                }
                case "assistant" -> {
                    if (message.reasoning != null && !message.reasoning.isBlank()) {
                        final int key = m;
                        final boolean open = this.expandedThoughts.contains(key);
                        String head = open
                                ? "▾ 思考（点击收起）"
                                : "▸ 思考（" + message.reasoning.length() + " 字，点击展开）";
                        out.add(new ChatLine(head, YELLOW, 0, () -> {
                            if (open) {
                                this.expandedThoughts.remove(key);
                            } else {
                                this.expandedThoughts.add(key);
                            }
                        }));
                        if (open) {
                            this.wrapInto(out, this.cap(message.reasoning, 8000), width - 8, MUTED, 6);
                        }
                    }
                    if (message.content != null && !message.content.isBlank()) {
                        out.add(new ChatLine("JavaIDE", GREEN, 0));
                        this.wrapInto(out, this.cap(message.content, 6000), width - 8, TEXT, 6);
                    }
                    for (IdeMessage.ToolCall call : message.toolCalls) {
                        out.add(new ChatLine("→ " + call.summary(), BLUE, 6));
                    }
                }
                case "tool" -> {
                    out.add(new ChatLine("← " + (message.toolName.isBlank() ? "工具结果" : message.toolName),
                            DIM, 0));
                    this.wrapInto(out, this.cap(message.content, 900), width - 8, MUTED, 6);
                }
                case "system" -> {
                    out.add(new ChatLine("上下文摘要", PURPLE, 0));
                    this.wrapInto(out, this.cap(message.content, 3000), width - 8, MUTED, 6);
                }
                default -> this.wrapInto(out, this.cap(message.content, 2000), width - 8, TEXT, 6);
            }
            out.add(new ChatLine("", TEXT, 0));
        }

        if (!this.agent.plan().isBlank()) {
            out.add(new ChatLine("计划", ACCENT, 0));
            this.wrapInto(out, this.cap(this.agent.plan(), 2400), width - 8, TEXT, 6);
            out.add(new ChatLine("", TEXT, 0));
        }

        int stepCount = 0;
        for (IdeAgent.Step step : this.agent.steps) {
            if (stepCount > 30) {
                out.add(new ChatLine("… 其余步骤已折叠 …", DIM, 0));
                break;
            }
            final int key = stepCount++;
            int color = step.done ? GREEN : step.kind.equals("error") ? RED : YELLOW;
            boolean think = step.kind.equals("think");
            boolean hasDetail = step.detail != null && !step.detail.isBlank();
            final boolean open = this.expandedSteps.contains(key);
            String suffix = think && hasDetail ? (open ? "  ▾ 详情" : "  ▸ 详情") : "";
            out.add(new ChatLine((step.done ? "✓ " : "● ") + step.title + suffix, color, 0,
                    think && hasDetail ? () -> {
                        if (open) {
                            this.expandedSteps.remove(key);
                        } else {
                            this.expandedSteps.add(key);
                        }
                    } : null));
            if (hasDetail && (!think || open)) {
                this.wrapInto(out, this.cap(step.detail, 900), width - 8, MUTED, 6);
            }
        }
        if (this.agent.busy() && !this.agent.status().isBlank()) {
            out.add(new ChatLine(this.agent.status(), GREEN, 0));
        }
        if (out.isEmpty()) {
            out.add(new ChatLine("描述你的目标，例如：新建一个 HelloWorld 并运行。", TEXT, 0));
            out.add(new ChatLine("", TEXT, 0));
            out.add(new ChatLine("可用工具：列目录、读文件、写文件、建目录、搜索、运行命令、", MUTED, 0));
            out.add(new ChatLine("读写项目记忆、加载技能。", MUTED, 0));
            out.add(new ChatLine("", TEXT, 0));
            out.add(new ChatLine("项目根：" + this.root, DIM, 0));
            out.add(new ChatLine("记忆：.ide/memory.md · 技能：.ide/skills/ · 会话：.ide/sessions/", DIM, 0));
            out.add(new ChatLine("", TEXT, 0));
            out.add(new ChatLine("先到「设置」里选择本地 Ollama 或百炼 Qwen 并测试连接。", YELLOW, 0));
        }
    }

    private void wrapInto(List<ChatLine> out, String text, int width, int color, int indent) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] paragraphs = text.replace("\r", "").split("\n", -1);
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                out.add(new ChatLine("", color, indent));
                continue;
            }
            String rest = para;
            int guard = 0;
            while (!rest.isEmpty() && guard++ < 300 && out.size() < 6000) {
                String piece = this.font.plainSubstrByWidth(rest, Math.max(12, width - indent));
                if (piece.isEmpty()) {
                    break;
                }
                out.add(new ChatLine(piece, color, indent));
                rest = rest.substring(piece.length());
            }
        }
    }

    private static String cap(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private List<String> wrapInput(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String para : text.replace("\r", "").split("\n", -1)) {
            String rest = para;
            if (rest.isEmpty()) {
                lines.add("");
                continue;
            }
            int guard = 0;
            while (!rest.isEmpty() && guard++ < 100) {
                String piece = this.font.plainSubstrByWidth(rest, Math.max(20, width));
                if (piece.isEmpty()) {
                    break;
                }
                lines.add(piece);
                rest = rest.substring(piece.length());
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ 覆盖层

    private void renderOverlay(Gfx graphics, int mouseX, int mouseY) {
        this.hits.clear();
        graphics.fill(0, 0, this.width, this.height, 0xAA000000);
        switch (this.mode) {
            case SETTINGS -> this.renderSettings(graphics, mouseX, mouseY);
            case SESSIONS -> this.renderSessions(graphics, mouseX, mouseY);
            case SKILLS -> this.renderSkills(graphics, mouseX, mouseY);
            case IMAGES -> this.renderImages(graphics, mouseX, mouseY);
            case PROMPT -> this.renderPrompt(graphics, mouseX, mouseY);
            default -> {
            }
        }
    }

    private void overlayFrame(Gfx graphics, int w, int h, String title) {
        this.overlayW = Math.min(w, this.width - 16);
        this.overlayH = Math.min(h, this.height - 16);
        this.overlayX = (this.width - this.overlayW) / 2;
        this.overlayY = (this.height - this.overlayH) / 2;
        graphics.fill(this.overlayX + 3, this.overlayY + 3, this.overlayX + this.overlayW + 3,
                this.overlayY + this.overlayH + 3, 0x66000000);
        graphics.fill(this.overlayX, this.overlayY, this.overlayX + this.overlayW,
                this.overlayY + this.overlayH, PANEL);
        graphics.outline(this.overlayX, this.overlayY, this.overlayX + this.overlayW,
                this.overlayY + this.overlayH, 0xFF555555);
        graphics.fill(this.overlayX, this.overlayY, this.overlayX + this.overlayW,
                this.overlayY + 20, 0xFF2D2D30);
        this.label(graphics, title, this.overlayX + 7, this.overlayY + 6,
                this.overlayW - 70, 0xFFFFFFFF);
        int closeW = this.font.width("关闭") + 8;
        this.button(graphics, this.overlayX + this.overlayW - closeW - 3, this.overlayY + 3,
                closeW, 14, "关闭", 0xFF3A3A3A, this.lastMouseX, this.lastMouseY, () -> this.mode = Mode.NONE);
        this.overlayListY = this.overlayY + 23;
        this.overlayListH = this.overlayH - 28;
    }

    /** 在浮层标题栏右侧（关闭按钮左边）放一个小按钮，避免固定坐标重叠。 */
    private int overlayAction(Gfx graphics, int fromRight, String label, int color,
                              Runnable action) {
        int closeW = this.font.width("关闭") + 8;
        int w = this.font.width(label) + 10;
        int x = this.overlayX + this.overlayW - 3 - closeW - 4 - fromRight - w;
        this.button(graphics, x, this.overlayY + 3, w, 14, label, color,
                this.lastMouseX, this.lastMouseY, action);
        return x;
    }

    private int lastMouseX;
    private int lastMouseY;

    private void renderSettings(Gfx graphics, int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.overlayFrame(graphics, 292, 204, "智能体 · Ollama / 百炼");
        int x = this.overlayX + 9;
        int w = this.overlayW - 18;
        String[] labels = {"提供方", "Ollama 地址", "Ollama 模型", "百炼地址", "百炼模型",
                "百炼 Key", "温度", "输出上限", "上下文 tokens", "工具步数", "思考程度"};
        int rowH = Math.max(11, Math.min(14, (this.overlayH - 44) / labels.length));
        int y = this.overlayY + 23;
        int boxX = x + 62;
        int boxW = w - 66;
        for (int i = 0; i < labels.length; i++) {
            int ry = y + i * rowH;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH - 1;
            if (hover) {
                graphics.fill(x, ry, x + w, ry + rowH - 1, HOVER);
            }
            graphics.text(this.font, labels[i], x + 3, ry + 2, TEXT, false);
            if (i == 0) {
                this.button(graphics, boxX, ry + 1, 92, 12, this.config.providerLabel(), 0xFF2F4A5E,
                        mouseX, mouseY, () -> this.config.toggleProvider());
                if (boxW > 126) {
                    graphics.text(this.font, "点击切换", boxX + 98, ry + 3, DIM, false);
                }
            } else if (i == 10) {
                this.button(graphics, boxX, ry + 1, 40, 12, this.config.thinkingLabel(),
                        this.config.thinking() ? 0xFF2F5E3A : 0xFF3A3A3A, mouseX, mouseY,
                        this.config::cycleThinking);
                this.label(graphics, "关闭 / 低 / 中 / 高 · 点击切换", boxX + 45, ry + 3,
                        boxW - 48, DIM);
            } else {
                boolean focused = this.settingField == i;
                int boxH = rowH - 2;
                graphics.fill(boxX, ry + 1, boxX + boxW, ry + 1 + boxH, focused ? 0xFF1F1F1F : 0xFF333333);
                graphics.outline(boxX, ry + 1, boxX + boxW, ry + 1 + boxH, focused ? ACCENT : BORDER);
                String value = i == 5 && !focused ? this.maskedDraft() : this.draft(i);
                if (value.isBlank()) {
                    value = i == 5 ? "未填写" : "未设置";
                }
                this.label(graphics, value, boxX + 4, ry + 2, boxW - 8,
                        focused ? 0xFFFFFFFF : 0xFFD0D0D0);
                int fi = i;
                this.hits.add(new Hit(boxX, ry + 1, boxW, boxH, 1, () -> {
                    this.settingField = fi;
                    this.focus = Field.SETTINGS;
                    this.syncTextInput();
                }, true));
            }
        }
        int by = this.overlayY + this.overlayH - 17;
        int bx = this.overlayX + 9;
        bx = this.button(graphics, bx, by, 46, 13, "保存", ACCENT, mouseX, mouseY, this::saveSettings) + 4;
        bx = this.button(graphics, bx, by, 58, 13, "测试连接", 0xFF2F5E3A, mouseX, mouseY, this::testSettings) + 4;
        if (!this.settingsStatus.isBlank()) {
            this.label(graphics, this.settingsStatus, bx, by + 2,
                    this.overlayX + this.overlayW - 8 - bx,
                    this.settingsStatus.contains("失败") ? RED : GREEN);
        } else {
            this.label(graphics, "Key 存在 .ide/settings.json", bx, by + 2,
                    this.overlayX + this.overlayW - 8 - bx, DIM);
        }
    }

    private void renderSessions(Gfx graphics, int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.overlayFrame(graphics, 300, 196, "会话 · .ide/sessions");
        this.overlayAction(graphics, 0, "新建会话", 0xFF2F4A5E, () -> {
            if (this.agent != null) {
                this.agent.newSession();
                this.mode = Mode.NONE;
                this.status("已新建会话");
            }
        });
        List<SessionInfo> sessions = this.sessions();
        int x = this.overlayX + 8;
        int w = this.overlayW - 16;
        int rowH = 16;
        int rows = Math.max(1, this.overlayListH / rowH);
        int maxScroll = Math.max(0, sessions.size() - rows);
        this.sessionScroll = Math.max(0, Math.min(this.sessionScroll, maxScroll));
        graphics.enableScissor(x, this.overlayListY, x + w, this.overlayListY + this.overlayListH);
        for (int i = 0; i < rows; i++) {
            int index = this.sessionScroll + i;
            if (index >= sessions.size()) {
                break;
            }
            SessionInfo info = sessions.get(index);
            int ry = this.overlayListY + i * rowH;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH - 2;
            boolean current = this.agent != null && info.id.equals(this.agent.sessionId);
            graphics.fill(x, ry, x + w, ry + rowH - 2, current ? SELECT : hover ? HOVER : 0xFF2A2A2A);
            // 行尾两个小按钮：改名与删除；先登记它们，避免被整行的「载入」命中区挡住。
            int delX = x + w - 13;
            int renX = delX - 15;
            boolean delHover = mouseX >= delX && mouseX < delX + 12 && mouseY >= ry && mouseY < ry + rowH - 2;
            boolean renHover = mouseX >= renX && mouseX < renX + 12 && mouseY >= ry && mouseY < ry + rowH - 2;
            graphics.fill(renX, ry + 1, renX + 12, ry + rowH - 3, renHover ? 0xFF3A6A9A : 0xFF333333);
            graphics.text(this.font, "改", renX + 2, ry + 3, renHover ? 0xFFFFFFFF : MUTED, false);
            graphics.fill(delX, ry + 1, delX + 12, ry + rowH - 3, delHover ? 0xFF8A3A3A : 0xFF333333);
            graphics.text(this.font, "删", delX + 2, ry + 3, delHover ? 0xFFFFFFFF : MUTED, false);
            this.hits.add(new Hit(renX, ry + 1, 12, rowH - 3, 1,
                    () -> this.openPrompt(PromptKind.SESSION_RENAME, info.path,
                            "重命名会话", info.title), true));
            this.hits.add(new Hit(delX, ry + 1, 12, rowH - 3, 1,
                    () -> this.deleteSession(info), true));
            String meta = info.stamp + " · " + info.count + " 条";
            int metaW = this.font.width(meta);
            int metaX = Math.max(x + 6, renX - 4 - metaW);
            this.label(graphics, info.title, x + 5, ry + 3,
                    Math.max(20, metaX - (x + 5) - 2), current ? 0xFFFFFFFF : TEXT);
            this.label(graphics, meta, metaX, ry + 3, Math.max(0, renX - 4 - metaX), DIM);
            this.hits.add(new Hit(x, ry, Math.max(20, renX - x - 2), rowH - 2, 1,
                    () -> this.loadSession(info), true));
        }
        graphics.disableScissor();
        if (sessions.isEmpty()) {
            this.label(graphics, "还没有会话。发送第一条消息后会自动保存。", x + 5,
                    this.overlayListY + 5, w - 10, MUTED);
        }
        if (maxScroll > 0) {
            int barH = Math.max(14, this.overlayListH * rows / Math.max(1, sessions.size()));
            int barY = this.overlayListY + (this.overlayListH - barH) * this.sessionScroll / maxScroll;
            graphics.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF666666);
        }
    }

    private void renderSkills(Gfx graphics, int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.overlayFrame(graphics, 300, 196, "技能 · .ide/skills");
        this.overlayAction(graphics, 0, "新建技能", 0xFF2F4A5E, () ->
                this.openPrompt(PromptKind.NEW_SKILL, "", "新建技能", "my-skill.md"));
        Map<String, String> skills = this.host == null ? Map.of() : this.host.skills();
        List<String> names = new ArrayList<>(skills.keySet());
        names.sort(String::compareTo);
        int listW = Math.min(112, this.overlayW / 3);
        int x = this.overlayX + 8;
        int rowH = 16;
        int rows = Math.max(1, this.overlayListH / rowH);
        int maxScroll = Math.max(0, names.size() - rows);
        this.skillScroll = Math.max(0, Math.min(this.skillScroll, maxScroll));
        graphics.enableScissor(x, this.overlayListY, x + listW, this.overlayListY + this.overlayListH);
        for (int i = 0; i < rows; i++) {
            int index = this.skillScroll + i;
            if (index >= names.size()) {
                break;
            }
            String name = names.get(index);
            int ry = this.overlayListY + i * rowH;
            boolean active = this.agent != null && name.equals(this.agent.activeSkill);
            boolean hover = mouseX >= x && mouseX < x + listW && mouseY >= ry && mouseY < ry + rowH - 2;
            graphics.fill(x, ry, x + listW, ry + rowH - 2, active ? SELECT : hover ? HOVER : 0xFF2A2A2A);
            this.label(graphics, (active ? "● " : "○ ") + name, x + 4, ry + 3,
                    listW - 34, active ? 0xFFFFFFFF : TEXT);
            graphics.text(this.font, "编辑", x + listW - 26, ry + 3, DIM, false);
            String editPath = this.root + "/.ide/skills/" + name + ".md";
            this.hits.add(new Hit(x + listW - 28, ry, 26, rowH - 2, 1, () -> {
                this.mode = Mode.NONE;
                this.openFile(editPath);
            }, true));
            this.hits.add(new Hit(x, ry, listW - 30, rowH - 2, 1, () -> {
                if (this.agent == null) {
                    return;
                }
                this.agent.attachSkill(active ? "" : name);
                this.status(active ? "已取消技能 " + name : "已加载技能 " + name + "，下一条消息生效");
            }, true));
        }
        graphics.disableScissor();

        int previewX = x + listW + 6;
        int previewW = this.overlayW - listW - 22;
        graphics.fill(previewX, this.overlayListY, previewX + previewW,
                this.overlayListY + this.overlayListH, 0xFF1F1F1F);
        String active = this.agent == null || this.agent.activeSkill.isBlank()
                ? (names.isEmpty() ? "" : names.get(0)) : this.agent.activeSkill;
        String body = skills.get(active);
        if (body == null || body.isBlank()) {
            this.label(graphics, names.isEmpty() ? "还没有技能文件。" : "点击左侧技能加载。",
                    previewX + 6, this.overlayListY + 5, previewW - 12, MUTED);
        } else {
            List<String> lines = this.previewLines(active + "\n\n" + body, previewW - 12,
                    Math.max(1, this.overlayListH / LINE_H));
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(this.font, lines.get(i), previewX + 6,
                        this.overlayListY + 4 + i * LINE_H, i == 0 ? GREEN : 0xFFBBBBBB, false);
            }
        }
        this.label(graphics, "技能随项目保存在电脑里；也支持 P2V 导入 .md", x,
                this.overlayY + this.overlayH - 11, this.overlayW - 16, DIM);
    }

    private void renderImages(Gfx graphics, int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.overlayFrame(graphics, 300, 196, "图片附件 · 最多 4 张");
        this.overlayAction(graphics, 0, "P2V 导入", 0xFF2F4A5E, () -> {
            this.mode = Mode.NONE;
            P2VImport.choose(this.pos, this.root + "/.ide/images", new HashSet<>(), message ->
                    Minecraft.getInstance().execute(() -> this.status(message)));
        });
        List<ImageInfo> images = this.collectImages();
        int x = this.overlayX + 8;
        int w = this.overlayW - 16;
        int rowH = 16;
        int rows = Math.max(1, this.overlayListH / rowH);
        int maxScroll = Math.max(0, images.size() - rows);
        this.imageScroll = Math.max(0, Math.min(this.imageScroll, maxScroll));
        graphics.enableScissor(x, this.overlayListY, x + w, this.overlayListY + this.overlayListH);
        for (int i = 0; i < rows; i++) {
            int index = this.imageScroll + i;
            if (index >= images.size()) {
                break;
            }
            ImageInfo info = images.get(index);
            int ry = this.overlayListY + i * rowH;
            boolean chosen = this.attachments.contains(info.path);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH - 2;
            graphics.fill(x, ry, x + w, ry + rowH - 2, chosen ? SELECT : hover ? HOVER : 0xFF2A2A2A);
            graphics.fill(x + 4, ry + 4, x + 10, ry + rowH - 6, chosen ? GREEN : BLUE);
            String size = info.size < 1024 ? info.size + " B" : (info.size / 1024) + " KB";
            int sizeW = this.font.width(size);
            this.label(graphics, this.relative(info.path), x + 14, ry + 3,
                    w - 22 - sizeW, chosen ? 0xFFFFFFFF : TEXT);
            graphics.text(this.font, size, x + w - 4 - sizeW, ry + 3, DIM, false);
            this.hits.add(new Hit(x, ry, w, rowH - 2, 1, () -> {
                if (chosen) {
                    this.attachments.remove(info.path);
                } else if (this.attachments.size() >= 4) {
                    this.status("最多同时附加 4 张图片");
                } else {
                    this.attachments.add(info.path);
                }
            }, true));
        }
        graphics.disableScissor();
        if (images.isEmpty()) {
            graphics.text(this.font, "项目里还没有图片。可用 P2V 从物理机导入。",
                    x + 5, this.overlayListY + 6, MUTED, false);
        }
        graphics.text(this.font, "选中 " + this.attachments.size() + " / 4 · 发送后转 Base64",
                x, this.overlayY + this.overlayH - 11, DIM, false);
    }

    private void renderPrompt(Gfx graphics, int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.overlayFrame(graphics, 280, 88, this.promptTitle);
        int x = this.overlayX + 9;
        int w = this.overlayW - 18;
        this.label(graphics, this.promptBase.isBlank() ? "输入名称，例如 Main.java"
                : "位置：" + this.relative(this.promptBase), x, this.overlayY + 24, w, DIM);
        int iy = this.overlayY + 33;
        graphics.fill(x, iy, x + w, iy + 14, 0xFF1F1F1F);
        graphics.outline(x, iy, x + w, iy + 14, ACCENT);
        graphics.text(this.font, this.font.plainSubstrByWidth(this.promptValue, w - 10),
                x + 4, iy + 3, 0xFFFFFFFF, false);
        int by = this.overlayY + this.overlayH - 17;
        this.button(graphics, this.overlayX + this.overlayW - 3 - 40 - 4 - 40, by, 40, 13, "取消",
                0xFF3A3A3A, mouseX, mouseY, () -> this.mode = Mode.NONE);
        this.button(graphics, this.overlayX + this.overlayW - 3 - 40, by, 40, 13, "确定",
                ACCENT, mouseX, mouseY, this::confirmPrompt);
        graphics.text(this.font, this.fit("Enter 确认 · Esc 取消", w - 94), x, by + 2, MUTED, false);
    }

    private List<String> previewLines(String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        for (String para : text.replace("\r", "").split("\n", -1)) {
            if (lines.size() >= maxLines) {
                break;
            }
            if (para.isEmpty()) {
                lines.add("");
                continue;
            }
            String rest = para;
            int guard = 0;
            while (!rest.isEmpty() && guard++ < 100 && lines.size() < maxLines) {
                String piece = this.font.plainSubstrByWidth(rest, Math.max(20, width));
                if (piece.isEmpty()) {
                    break;
                }
                lines.add(piece);
                rest = rest.substring(piece.length());
            }
        }
        return lines;
    }

    private int button(Gfx graphics, int x, int y, int w, int h, String label, int color,
                       int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, hover ? this.lighten(color) : color);
        graphics.outline(x, y, x + w, y + h, hover ? 0xFF66BBEE : BORDER);
        int colorText = color == RED ? 0xFFFFFFFF : 0xFFEAEAEA;
        graphics.text(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2, colorText, false);
        this.hits.add(new Hit(x, y, w, h, 1, action, this.mode != Mode.NONE));
        return x + w;
    }

    private int lighten(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 24);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 24);
        int b = Math.min(255, (argb & 0xFF) + 24);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ 鼠标与键盘

    public boolean mouseClicked(int mx, int my, int button) {
        if (this.mode != Mode.NONE) {
            for (Hit hit : this.hits) {
                if (hit.overlay && hit.button == button && hit.contains(mx, my)) {
                    hit.action.run();
                    return true;
                }
            }
            if (this.mode != Mode.PROMPT
                    && (mx < this.overlayX || mx >= this.overlayX + this.overlayW
                    || my < this.overlayY || my >= this.overlayY + this.overlayH)) {
                this.mode = Mode.NONE;
                this.focus = Field.NONE;
                this.syncTextInput();
            }
            return true;
        }
        // 分隔条优先于下面板内容：单击开始拖动，双击恢复按比例的默认栏宽。
        int side = this.splitAt(mx, my);
        if (side != 0) {
            if (button == 2) {
                if (side == 1) {
                    this.leftWanted = -1;
                } else {
                    this.rightWanted = -1;
                }
                this.splitDrag = 0;
                this.computeLayout();
                this.status(side == 1 ? "项目栏宽已恢复自动" : "智能体栏宽已恢复自动");
                return true;
            }
            long now = System.currentTimeMillis();
            if (side == this.lastSplitSide && now - this.lastSplitClickAt < 360L) {
                if (side == 1) {
                    this.leftWanted = -1;
                } else {
                    this.rightWanted = -1;
                }
                this.splitDrag = 0;
                this.computeLayout();
                this.status(side == 1 ? "项目栏宽已恢复自动" : "智能体栏宽已恢复自动");
            } else {
                this.splitDrag = side;
                this.status(side == 1 ? "左右拖动调整项目栏宽，双击恢复" : "左右拖动调整智能体栏宽，双击恢复");
            }
            this.lastSplitClickAt = now;
            this.lastSplitSide = side;
            return true;
        }
        this.splitDrag = 0;
        for (Hit hit : this.hits) {
            if (hit.button == button && hit.contains(mx, my)) {
                hit.action.run();
                return true;
            }
        }
        if (button != 1) {
            // 右键只在菜单与命中区域上生效，不改变编辑器/输入框焦点。
            return true;
        }
        if (this.activeFile != null && mx >= this.midX && mx < this.midX + this.midW
                && my >= this.codeY && my < this.codeY + this.codeH) {
            this.editorClick(mx, my);
            this.focus = Field.EDITOR;
            this.syncTextInput();
            return true;
        }
        if (mx >= this.chatInputX && mx < this.chatInputX + this.chatInputW
                && my >= this.chatInputY && my < this.chatInputY + this.chatInputH) {
            this.focus = Field.CHAT;
            this.syncTextInput();
            return true;
        }
        if (mx >= this.transcriptX && mx < this.transcriptX + this.transcriptW
                && my >= this.transcriptY && my < this.transcriptY + this.transcriptH) {
            this.focus = Field.NONE;
            this.syncTextInput();
            return true;
        }
        // 控制台整块可点：点标签或输出区都聚焦到底部命令行。
        if (this.consoleOpen && mx >= this.midX && mx < this.midX + this.midW
                && my >= this.consoleY && my < this.panelBottom) {
            this.focus = Field.CONSOLE;
            this.syncTextInput();
            return true;
        }
        if (this.focus == Field.EDITOR || this.focus == Field.CHAT || this.focus == Field.CONSOLE) {
            this.focus = Field.NONE;
            this.syncTextInput();
        }
        return true;
    }

    /** 拖动分隔条调整栏宽；逻辑像素与屏幕像素差两倍，调用方已换算好。 */
    public void mouseDragged(int mx, int my) {
        if (this.splitDrag == 0) {
            return;
        }
        if (this.splitDrag == 1) {
            int max = Math.min(360, this.width - this.rightW - 240);
            this.leftWanted = Math.max(120, Math.min(max, mx));
            this.status("项目栏宽 " + Math.round(this.leftWanted / 2.0F) + "px");
        } else if (this.splitDrag == 2) {
            int max = Math.min(440, this.width - this.leftW - 240);
            this.rightWanted = Math.max(168, Math.min(max, this.width - mx));
            this.status("智能体栏宽 " + Math.round(this.rightWanted / 2.0F) + "px");
        }
        this.computeLayout();
    }

    public void mouseReleased() {
        this.splitDrag = 0;
    }

    public boolean mouseScrolled(int mouseX, int mouseY, int scrollY) {
        int delta = Integer.signum(scrollY);
        if (delta == 0) {
            return true;
        }
        if (this.mode != Mode.NONE) {
            switch (this.mode) {
                case SESSIONS -> this.sessionScroll = Math.max(0, this.sessionScroll - delta);
                case SKILLS -> this.skillScroll = Math.max(0, this.skillScroll - delta);
                case IMAGES -> this.imageScroll = Math.max(0, this.imageScroll - delta);
                default -> {
                }
            }
            return true;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (mx < this.leftW) {
            this.treeScroll = Math.max(0, this.treeScroll - delta);
        } else if (mx >= this.chatX) {
            this.chatScroll = Math.max(0, this.chatScroll - delta * 3);
            this.stickChat = false;
        } else if (this.consoleOpen && my >= this.consoleY) {
            this.consoleScroll = Math.max(0, this.consoleScroll - delta * 3);
        } else if (this.activeFile != null) {
            this.activeFile.scroll = Math.max(0, this.activeFile.scroll - delta * 3);
        }
        return true;
    }

    private void editorClick(int mx, int my) {
        OpenFile file = this.activeFile;
        if (file == null) {
            return;
        }
        int row = file.scroll + Math.max(0, (my - this.codeY) / LINE_H);
        file.row = Math.max(0, Math.min(file.lines.size() - 1, row));
        String line = file.lines.get(file.row);
        int target = Math.max(0, mx - this.codeX + file.hscroll);
        int col = 0;
        int width = 0;
        for (int i = 0; i <= line.length(); i++) {
            int w = this.font.width(line.substring(0, i));
            if (w <= target) {
                col = i;
                width = w;
            } else {
                break;
            }
        }
        file.col = col;
        file.hscroll = Math.max(0, Math.min(file.hscroll, Math.max(0, width - 4)));
    }

    public boolean keyPressed(int key, boolean shift, boolean ctrl) {
        if (this.mode == Mode.PROMPT) {
            if (key == InputConstants.KEY_ESCAPE) {
                this.mode = Mode.NONE;
                this.focus = Field.NONE;
                this.syncTextInput();
                return true;
            }
            if (key == InputConstants.KEY_RETURN) {
                this.confirmPrompt();
                return true;
            }
            if (key == InputConstants.KEY_BACKSPACE) {
                if (!this.promptValue.isEmpty()) {
                    this.promptValue = this.promptValue.substring(0, this.promptValue.length() - 1);
                }
                return true;
            }
            return true;
        }
        if (this.mode == Mode.SETTINGS) {
            if (key == InputConstants.KEY_ESCAPE) {
                this.mode = Mode.NONE;
                this.settingField = -1;
                this.focus = Field.NONE;
                this.syncTextInput();
                return true;
            }
            if (this.settingField >= 0) {
                if (key == InputConstants.KEY_BACKSPACE) {
                    String draft = this.draft(this.settingField);
                    if (!draft.isEmpty()) {
                        this.drafts[this.settingField] = draft.substring(0, draft.length() - 1);
                    }
                    return true;
                }
                if (key == InputConstants.KEY_RETURN) {
                    this.settingField = this.nextSettingField(this.settingField);
                    this.syncTextInput();
                    return true;
                }
            }
            return true;
        }
        if (this.mode != Mode.NONE) {
            if (key == InputConstants.KEY_ESCAPE) {
                this.mode = Mode.NONE;
                this.focus = Field.NONE;
                this.syncTextInput();
            }
            return true;
        }

        if (key == InputConstants.KEY_ESCAPE) {
            if (this.focus == Field.CHAT) {
                this.focus = Field.NONE;
                this.syncTextInput();
                return true;
            }
            // 编辑器或面板有焦点时先取消焦点；再按一次才交给外层离开电脑。
            if (this.focus != Field.NONE) {
                this.focus = Field.NONE;
                this.syncTextInput();
                return true;
            }
            return false;
        }
        if (ctrl && key == InputConstants.KEY_S) {
            this.saveActive();
            return true;
        }
        if (ctrl && key == InputConstants.KEY_RETURN) {
            this.runActive();
            return true;
        }
        if (ctrl && key == InputConstants.KEY_GRAVE) {
            this.consoleOpen = !this.consoleOpen;
            if (this.consoleOpen) {
                this.focus = Field.CONSOLE;
            }
            this.syncTextInput();
            return true;
        }
        if (ctrl && key == InputConstants.KEY_B) {
            if (shift) {
                this.treeCollapsed = !this.treeCollapsed;
                this.status(this.treeCollapsed ? "已收起项目树" : "已展开项目树");
            } else {
                this.agentCollapsed = !this.agentCollapsed;
                this.status(this.agentCollapsed ? "已收起智能体 · Ctrl+B 切换" : "已展开智能体");
            }
            return true;
        }

        if (this.focus == Field.CONSOLE) {
            switch (key) {
                case InputConstants.KEY_BACKSPACE -> {
                    if (!this.consoleInput.isEmpty()) {
                        this.consoleInput = this.consoleInput.substring(0, this.consoleInput.length() - 1);
                    }
                }
                case InputConstants.KEY_RETURN -> this.runConsoleInput();
                case InputConstants.KEY_UP -> {
                    if (!this.consoleHistory.isEmpty()) {
                        if (this.consoleHistoryAt < 0) {
                            this.consoleHistoryAt = this.consoleHistory.size();
                        }
                        this.consoleHistoryAt = Math.max(0, this.consoleHistoryAt - 1);
                        this.consoleInput = this.consoleHistory.get(this.consoleHistoryAt);
                    }
                }
                case InputConstants.KEY_DOWN -> {
                    if (this.consoleHistoryAt >= 0) {
                        this.consoleHistoryAt++;
                        if (this.consoleHistoryAt >= this.consoleHistory.size()) {
                            this.consoleHistoryAt = -1;
                            this.consoleInput = "";
                        } else {
                            this.consoleInput = this.consoleHistory.get(this.consoleHistoryAt);
                        }
                    }
                }
                default -> {
                    return true;
                }
            }
            return true;
        }

        if (this.focus == Field.CHAT) {
            switch (key) {
                case InputConstants.KEY_BACKSPACE -> {
                    if (!this.chatInput.isEmpty()) {
                        this.chatInput = this.chatInput.substring(0, this.chatInput.length() - 1);
                    }
                }
                case InputConstants.KEY_RETURN -> {
                    if (shift) {
                        this.chatInput += "\n";
                    } else {
                        this.sendChat();
                    }
                }
                default -> {
                    return true;
                }
            }
            return true;
        }
        if (this.focus == Field.EDITOR && this.activeFile != null) {
            return this.editKey(this.activeFile, key, ctrl, shift);
        }
        return true;
    }

    private int nextSettingField(int from) {
        int[] numeric = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int i = 0; i < numeric.length; i++) {
            if (numeric[i] == from) {
                return numeric[(i + 1) % numeric.length];
            }
        }
        return 1;
    }

    public boolean charTyped(int codepoint) {
        if (codepoint < 32 || !Character.isValidCodePoint(codepoint)) {
            return true;
        }
        String text = new String(Character.toChars(codepoint));
        if (this.mode == Mode.PROMPT) {
            if (this.promptValue.length() < 180) {
                this.promptValue += text;
            }
            return true;
        }
        if (this.mode == Mode.SETTINGS) {
            if (this.settingField >= 0) {
                String draft = this.draft(this.settingField);
                if (draft.length() < 300) {
                    this.drafts[this.settingField] = draft + text;
                }
            }
            return true;
        }
        if (this.mode != Mode.NONE) {
            return true;
        }
        if (this.focus == Field.CONSOLE) {
            if (this.consoleInput.length() < 300) {
                this.consoleInput += text;
            }
            return true;
        }
        if (this.focus == Field.CHAT) {
            if (this.chatInput.length() < 4000) {
                this.chatInput += text;
            }
            return true;
        }
        if (this.focus == Field.EDITOR && this.activeFile != null) {
            this.insert(this.activeFile, text);
            return true;
        }
        return true;
    }

    private boolean editKey(OpenFile file, int key, boolean ctrl, boolean shift) {
        switch (key) {
            case InputConstants.KEY_LEFT -> {
                if (file.col > 0) {
                    file.col--;
                } else if (file.row > 0) {
                    file.row--;
                    file.col = file.lines.get(file.row).length();
                }
            }
            case InputConstants.KEY_RIGHT -> {
                String line = file.lines.get(file.row);
                if (file.col < line.length()) {
                    file.col++;
                } else if (file.row < file.lines.size() - 1) {
                    file.row++;
                    file.col = 0;
                }
            }
            case InputConstants.KEY_UP -> {
                if (file.row > 0) {
                    file.row--;
                    file.col = Math.min(file.col, file.lines.get(file.row).length());
                }
            }
            case InputConstants.KEY_DOWN -> {
                if (file.row < file.lines.size() - 1) {
                    file.row++;
                    file.col = Math.min(file.col, file.lines.get(file.row).length());
                }
            }
            case InputConstants.KEY_HOME -> file.col = 0;
            case InputConstants.KEY_END -> file.col = file.lines.get(file.row).length();
            case InputConstants.KEY_PAGEUP -> file.row = Math.max(0, file.row - Math.max(1, this.codeRows - 1));
            case InputConstants.KEY_PAGEDOWN -> file.row = Math.min(file.lines.size() - 1,
                    file.row + Math.max(1, this.codeRows - 1));
            case InputConstants.KEY_BACKSPACE -> this.backspace(file);
            case InputConstants.KEY_DELETE -> this.deleteForward(file);
            case InputConstants.KEY_RETURN -> this.newline(file);
            case InputConstants.KEY_TAB -> {
                if (shift) {
                    this.outdent(file);
                } else {
                    this.insert(file, "    ");
                }
            }
            default -> {
                return true;
            }
        }
        file.col = Math.min(file.col, file.lines.get(file.row).length());
        this.ensureCaretVisible(file);
        return true;
    }

    private void insert(OpenFile file, String text) {
        String line = file.lines.get(file.row);
        String next = line.substring(0, file.col) + text + line.substring(file.col);
        file.lines.set(file.row, next);
        file.col += text.length();
        file.dirty = true;
        this.ensureCaretVisible(file);
    }

    private void backspace(OpenFile file) {
        if (file.col > 0) {
            String line = file.lines.get(file.row);
            file.lines.set(file.row, line.substring(0, file.col - 1) + line.substring(file.col));
            file.col--;
        } else if (file.row > 0) {
            String prev = file.lines.get(file.row - 1);
            String current = file.lines.get(file.row);
            file.lines.set(file.row - 1, prev + current);
            file.lines.remove(file.row);
            file.row--;
            file.col = prev.length();
        }
        file.dirty = true;
        this.ensureCaretVisible(file);
    }

    private void deleteForward(OpenFile file) {
        String line = file.lines.get(file.row);
        if (file.col < line.length()) {
            file.lines.set(file.row, line.substring(0, file.col) + line.substring(file.col + 1));
        } else if (file.row < file.lines.size() - 1) {
            file.lines.set(file.row, line + file.lines.get(file.row + 1));
            file.lines.remove(file.row + 1);
        }
        file.dirty = true;
        this.ensureCaretVisible(file);
    }

    private void newline(OpenFile file) {
        String line = file.lines.get(file.row);
        String left = line.substring(0, file.col);
        String right = line.substring(file.col);
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < left.length(); i++) {
            char c = left.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(' ');
            } else {
                break;
            }
        }
        String trimmed = left.stripTrailing();
        if (trimmed.endsWith("{")) {
            indent.append("    ");
        }
        file.lines.set(file.row, left);
        file.lines.add(file.row + 1, indent + right);
        file.row++;
        file.col = indent.length();
        file.dirty = true;
        this.ensureCaretVisible(file);
    }

    private void outdent(OpenFile file) {
        String line = file.lines.get(file.row);
        int remove = 0;
        while (remove < 4 && remove < file.col && line.charAt(remove) == ' ') {
            remove++;
        }
        if (remove == 4) {
            file.lines.set(file.row, line.substring(4));
            file.col = Math.max(0, file.col - 4);
            file.dirty = true;
        }
    }

    private void ensureCaretVisible(OpenFile file) {
        int rows = Math.max(1, this.codeRows);
        if (file.row < file.scroll) {
            file.scroll = file.row;
        } else if (file.row >= file.scroll + rows) {
            file.scroll = file.row - rows + 1;
        }
        if (this.codeW <= 0) {
            return;
        }
        String line = file.lines.get(file.row);
        int px = this.font.width(line.substring(0, Math.min(file.col, line.length())));
        if (px - file.hscroll > this.codeW - 20) {
            file.hscroll = Math.max(0, px - this.codeW + 20);
        }
        if (px < file.hscroll) {
            file.hscroll = Math.max(0, px - 20);
        }
        this.syncTextInput();
    }

    /**
     * 26.3 的输入法由外层设备界面统一开关；IDE 只报告“现在是否在收字符”，
     * 候选框位置由聚焦层放在屏幕中央。
     */
    private void syncTextInput() {
        this.typing = !this.disposed && (this.mode == Mode.PROMPT
                || (this.mode == Mode.SETTINGS && this.settingField >= 0)
                || (this.mode == Mode.NONE
                && (this.focus == Field.CHAT || this.focus == Field.CONSOLE)));
    }

    // ------------------------------------------------------------------ 文件操作

    private void collectRows(String path, int depth, List<TreeRow> rows) {
        VirtualFileSystem.Node node = this.fs.find(path);
        if (node == null) {
            return;
        }
        String name = depth == 0 ? (path.equals("/") ? "/" : this.fileName(path)) : node.name;
        boolean expanded = depth == 0 || !this.collapsed.contains(path);
        rows.add(new TreeRow(path, name, node.isDir(), depth, expanded));
        if (!node.isDir() || !expanded || depth > 12 || rows.size() > 900) {
            return;
        }
        List<VirtualFileSystem.Node> children = new ArrayList<>(node.children.values());
        children.sort(Comparator.comparing((VirtualFileSystem.Node child) -> !child.isDir())
                .thenComparing(child -> child.name.toLowerCase(Locale.ROOT)));
        for (VirtualFileSystem.Node child : children) {
            if (depth == 0 && child.name.equals(".ide")) {
                // 仍然展示，但默认折叠
            }
            String childPath = (path.equals("/") ? "" : path) + "/" + child.name;
            this.collectRows(childPath, depth + 1, rows);
        }
    }

    private void openFile(String path) {
        if (this.fs == null) {
            return;
        }
        VirtualFileSystem.Node node = this.fs.find(path);
        if (node == null || node.isDir()) {
            this.status("找不到文件：" + path);
            return;
        }
        if (node.isBinary()) {
            this.status("二进制文件请在文件管理器中打开：" + this.fileName(path));
            return;
        }
        OpenFile open = this.findOpen(path);
        if (open == null) {
            open = new OpenFile(path);
            open.lines.addAll(this.splitLines(node.content));
            if (this.openFiles.size() >= 7) {
                this.openFiles.removeIf(file -> !file.dirty);
            }
            this.openFiles.add(open);
        }
        this.activeFile = open;
        this.focus = Field.EDITOR;
        this.selected = path;
        this.expandParents(path);
        this.ensureCaretVisible(open);
        this.syncTextInput();
    }

    private List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        String text = content == null ? "" : content.replace("\r", "");
        for (String line : text.split("\n", -1)) {
            lines.add(line.replace("\t", "    "));
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private OpenFile findOpen(String path) {
        for (OpenFile file : this.openFiles) {
            if (file.path.equals(path)) {
                return file;
            }
        }
        return null;
    }

    private void closeFile(OpenFile file) {
        this.openFiles.remove(file);
        if (this.activeFile == file) {
            this.activeFile = this.openFiles.isEmpty() ? null : this.openFiles.get(this.openFiles.size() - 1);
        }
        this.status("已关闭 " + this.fileName(file.path) + (file.dirty ? "（未保存的改动已丢弃）" : ""));
    }

    private void saveActive() {
        if (this.activeFile == null) {
            this.status("没有打开的文件");
            return;
        }
        this.saveFile(this.activeFile);
    }

    private void saveFile(OpenFile file) {
        String content = String.join("\n", file.lines);
        this.fs.write(file.path, content);
        HajikunSmartClient.request(this.pos, "write", file.path + "\n" + content);
        file.dirty = false;
        this.status("已保存 " + this.relative(file.path));
    }

    private String readFile(String path) {
        VirtualFileSystem.Node node = this.fs == null ? null : this.fs.find(path);
        return node == null || node.isDir() ? "" : node.content;
    }

    private void expandParents(String path) {
        String parent = VirtualFileSystem.parent(path);
        while (parent != null && parent.length() >= this.root.length()) {
            this.collapsed.remove(parent);
            if (parent.equals(this.root) || parent.equals("/")) {
                break;
            }
            parent = VirtualFileSystem.parent(parent);
        }
    }

    private String selectedBase() {
        if (!this.selected.isBlank()) {
            VirtualFileSystem.Node node = this.fs.find(this.selected);
            if (node != null && node.isDir()) {
                return this.selected;
            }
            String parent = VirtualFileSystem.parent(this.selected);
            if (parent != null && !parent.isBlank()) {
                return parent;
            }
        }
        return this.root;
    }

    private void openPrompt(PromptKind kind, String base, String title, String initial) {
        this.promptKind = kind;
        this.promptBase = base == null ? "" : base;
        this.promptTitle = title;
        this.promptValue = initial == null ? "" : initial;
        this.mode = Mode.PROMPT;
        this.focus = Field.PROMPT;
        this.syncTextInput();
    }

    private void confirmPrompt() {
        if (this.promptKind == PromptKind.NONE) {
            return;
        }
        String value = this.promptValue.trim();
        if (value.isEmpty()) {
            return;
        }
        switch (this.promptKind) {
            case NEW_FILE -> {
                String name = value;
                String base = this.promptBase.isBlank() ? this.root : this.promptBase;
                int slash = value.lastIndexOf('/');
                if (slash >= 0) {
                    name = value.substring(slash + 1);
                    String directory = value.substring(0, slash);
                    if (!directory.isEmpty()) {
                        base = VirtualFileSystem.normalize(base, directory);
                    }
                }
                if (!name.contains(".")) {
                    name += ".java";
                }
                String path = (base.endsWith("/") ? base : base + "/") + name;
                if (this.fs.find(path) != null) {
                    this.status("已存在：" + this.relative(path));
                    return;
                }
                String className = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
                String content = name.endsWith(".java") ? """
                        public class %s {
                            public static void main(String[] args) {
                                System.out.println("Hello from JavaIDE");
                            }
                        }
                        """.formatted(className) : "";
                this.host.writeFile(path, content);
                this.fs.write(path, content);
                this.selected = path;
                this.expandParents(path);
                this.mode = Mode.NONE;
                this.openFile(path);
                this.status("已创建 " + this.relative(path));
            }
            case NEW_FOLDER -> {
                String path = VirtualFileSystem.normalize(this.promptBase, value);
                if (this.fs.find(path) != null) {
                    this.status("已存在：" + this.relative(path));
                    return;
                }
                this.fs.mkdirs(path);
                this.exec("mkdir \"" + path.replace("\"", "\\\"") + "\"", ignored -> {
                });
                this.selected = path;
                this.expandParents(path);
                this.mode = Mode.NONE;
                this.status("已创建目录 " + this.relative(path));
            }
            case NEW_SKILL -> {
                String name = value.endsWith(".md") ? value : value + ".md";
                String path = this.root + "/.ide/skills/" + name;
                if (this.fs.find(path) != null) {
                    this.status("技能已存在：" + name);
                    return;
                }
                String content = "# " + (name.endsWith(".md") ? name.substring(0, name.length() - 3) : name)
                        + "\n\n描述这个技能适用的场景与步骤。\n\n1. 先读文件，确认现状。\n2. 小步修改并验证。\n3. 把结论写入项目记忆。\n";
                this.host.writeFile(path, content);
                this.fs.write(path, content);
                this.mode = Mode.SKILLS;
                this.status("已创建技能 " + name);
            }
            case SESSION_RENAME -> {
                String path = this.promptBase;
                if (path == null || path.isBlank() || this.fs.find(path) == null) {
                    this.status("会话文件不存在");
                    return;
                }
                if (value.length() > 60) {
                    this.status("会话名称最多 60 个字符");
                    return;
                }
                try {
                    JsonObject json = JsonParser.parseString(this.readFile(path)).getAsJsonObject();
                    json.addProperty("title", value);
                    String text = json.toString();
                    this.host.writeFile(path, text);
                    this.fs.write(path, text);
                    if (this.agent != null && json.has("id")
                            && json.get("id").getAsString().equals(this.agent.sessionId)) {
                        this.agent.title = value;
                    }
                    this.mode = Mode.SESSIONS;
                    this.status("已重命名会话：" + value);
                } catch (RuntimeException error) {
                    this.status("会话文件损坏，无法重命名");
                }
            }
            case RENAME -> {
                String path = this.promptBase;
                if (path == null || path.isBlank()) {
                    return;
                }
                if (value.contains("/") || value.contains("\\")) {
                    this.status("名称里不能包含路径分隔符");
                    return;
                }
                String parent = VirtualFileSystem.parent(path);
                String target = (parent.equals("/") ? "" : parent) + "/" + value;
                if (this.fs.find(target) != null) {
                    this.status("当前目录下已有同名文件或文件夹");
                    return;
                }
                HajikunSmartClient.request(this.pos, "fs_rename", path + "\n" + value);
                this.fs.move(path, target);
                for (OpenFile file : this.openFiles) {
                    if (file.path.equals(path)) {
                        file.path = target;
                    }
                }
                this.selected = target;
                this.mode = Mode.NONE;
                this.status("已重命名为 " + value);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 运行

    private void runActive() {
        if (this.running) {
            this.status("正在运行，请等待当前任务结束");
            return;
        }
        if (this.activeFile == null) {
            this.status("先打开一个 .java 文件");
            return;
        }
        if (!this.activeFile.path.endsWith(".java")) {
            this.status("只有 .java 文件可以编译运行");
            return;
        }
        this.saveFile(this.activeFile);
        String path = this.activeFile.path;
        String pkg = this.packageOf(this.activeFile.lines);
        String name = this.fileName(path);
        String className = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
        String mainClass = pkg.isBlank() ? className : pkg + "." + className;
        this.running = true;
        this.consoleOpen = true;
        this.consoleLines.clear();
        this.consoleScroll = 0;
        this.addConsole("> cd " + this.root);
        this.exec("cd \"" + this.root.replace("\"", "\\\"") + "\"", first -> {
            this.addConsole("> javac -d . " + this.relative(path));
            this.exec("javac -d \"" + this.root.replace("\"", "\\\"") + "\" \""
                    + path.replace("\"", "\\\"") + "\"", compile -> {
                this.appendOutput(compile);
                if (compile.contains("错误") || compile.contains("error:")) {
                    this.running = false;
                    this.status("编译失败，见控制台");
                    return;
                }
                this.addConsole("> java " + mainClass);
                this.exec("java " + mainClass, run -> {
                    this.appendOutput(run);
                    this.running = false;
                    this.status("运行结束：" + this.relative(path));
                });
            });
        });
    }

    private static String packageOf(List<String> lines) {
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("package ")) {
                int end = trimmed.indexOf(';');
                if (end > 8) {
                    return trimmed.substring(8, end).trim();
                }
            }
            if (trimmed.startsWith("import ") || trimmed.startsWith("public ")
                    || trimmed.startsWith("class ")) {
                break;
            }
        }
        return "";
    }

    private void addConsole(String line) {
        for (String part : line.replace("\r", "").split("\n", -1)) {
            this.consoleLines.addLast(part);
        }
        while (this.consoleLines.size() > 400) {
            this.consoleLines.removeFirst();
        }
        this.consoleScroll = Math.max(0, this.consoleLines.size() - 1);
    }

    private void appendOutput(String text) {
        if (text == null || text.isBlank()) {
            this.addConsole("(命令没有输出)");
            return;
        }
        this.addConsole(text);
    }

    private void exec(String command, Consumer<String> done) {
        this.commands.addLast(new Pending(command, done, System.currentTimeMillis()));
        HajikunSmartClient.request(this.pos, "shell", command);
    }

    // ------------------------------------------------------------------ 发送消息

    private void sendChat() {
        if (this.agent == null || this.agent.busy() || this.readingImages) {
            return;
        }
        String text = this.chatInput.trim();
        if (text.isEmpty() && this.attachments.isEmpty()) {
            return;
        }
        this.chatInput = "";
        if (!this.attachments.isEmpty()) {
            this.pendingText = text;
            this.pendingSince = System.currentTimeMillis();
            this.readingImages = true;
            for (String path : this.attachments) {
                VirtualFileSystem.Node node = this.fs.find(path);
                if (node != null) {
                    MediaCache.ensure(this.pos, path, node.media);
                }
            }
            this.status("正在读取 " + this.attachments.size() + " 张图片…");
            return;
        }
        this.agent.send(text, List.of());
        this.stickChat = true;
        this.focus = Field.NONE;
        this.syncTextInput();
    }

    private void finishImageSend(long now) {
        List<String> images = new ArrayList<>();
        List<String> stillLoading = new ArrayList<>();
        for (String path : this.attachments) {
            MediaCache.Blob blob = MediaCache.blob(this.pos, path);
            if (blob == null) {
                stillLoading.add(path);
                continue;
            }
            if (blob.data().length > 4 * 1024 * 1024) {
                this.status("跳过大图 " + this.fileName(path) + "（超过 4 MB）");
                continue;
            }
            images.add(Base64.getEncoder().encodeToString(blob.data()));
        }
        if (!stillLoading.isEmpty() && now - this.pendingSince < 12000L) {
            return;
        }
        this.readingImages = false;
        this.attachments.clear();
        if (this.agent != null) {
            this.agent.send(this.pendingText, images);
            this.stickChat = true;
            this.focus = Field.NONE;
            this.syncTextInput();
        }
        if (!stillLoading.isEmpty()) {
            this.status("有 " + stillLoading.size() + " 张图片读取超时，已忽略");
        } else {
            this.status("已发送 " + images.size() + " 张图片");
        }
        this.pendingText = "";
    }

    // ------------------------------------------------------------------ 覆盖层数据

    private record SessionInfo(String id, String title, long updated, String stamp, int count, String path) {
    }

    private List<SessionInfo> sessions() {
        List<SessionInfo> out = new ArrayList<>();
        if (this.fs == null) {
            return out;
        }
        VirtualFileSystem.Node dir = this.fs.find(this.root + "/.ide/sessions");
        if (dir == null || !dir.isDir()) {
            return out;
        }
        for (VirtualFileSystem.Node node : dir.children.values()) {
            if (node.isDir() || !node.name.endsWith(".json")) {
                continue;
            }
            try {
                JsonObject json = JsonParser.parseString(node.content).getAsJsonObject();
                String id = json.has("id") ? json.get("id").getAsString() : node.name;
                String title = json.has("title") ? json.get("title").getAsString() : "未命名会话";
                long updated = json.has("updated") ? json.get("updated").getAsLong() : 0L;
                int count = json.has("messages") ? json.getAsJsonArray("messages").size() : 0;
                String stamp = updated <= 0L ? "--" : LocalDateTime.ofInstant(Instant.ofEpochMilli(updated),
                        ZoneId.systemDefault()).format(STAMP);
                out.add(new SessionInfo(id, title, updated, stamp, count,
                        this.root + "/.ide/sessions/" + node.name));
            } catch (RuntimeException ignored) {
                // 损坏的会话文件跳过，不影响其它会话。
            }
        }
        out.sort(Comparator.comparingLong(SessionInfo::updated).reversed());
        return out;
    }

    private void loadSession(SessionInfo info) {
        try {
            JsonObject json = JsonParser.parseString(this.readFile(info.path)).getAsJsonObject();
            if (this.agent != null) {
                this.agent.loadSession(json);
            }
            this.attachments.clear();
            this.chatInput = "";
            this.stickChat = true;
            this.mode = Mode.NONE;
            this.status("已载入会话：" + info.title);
        } catch (RuntimeException error) {
            this.status("会话文件损坏：" + info.id);
        }
    }

    /** 删除一条历史会话；如果删的正是当前会话，同时开一段新会话避免它被再次保存。 */
    private void deleteSession(SessionInfo info) {
        if (this.fs != null) {
            this.fs.delete(info.path);
        }
        HajikunSmartClient.request(this.pos, "fs_delete", info.path);
        if (this.agent != null && info.id.equals(this.agent.sessionId)) {
            this.agent.newSession();
        }
        this.status("已删除会话：" + info.title);
    }

    private record ImageInfo(String path, long size) {
    }

    private List<ImageInfo> collectImages() {
        List<ImageInfo> out = new ArrayList<>();
        if (this.fs == null) {
            return out;
        }
        this.collectImages(this.root, 0, out);
        out.sort(Comparator.comparing(ImageInfo::path));
        return out;
    }

    private void collectImages(String path, int depth, List<ImageInfo> out) {
        if (depth > 6 || out.size() > 300 || this.fs == null) {
            return;
        }
        VirtualFileSystem.Node node = this.fs.find(path);
        if (node == null) {
            return;
        }
        if (!node.isDir()) {
            String mime = node.mime == null ? "" : node.mime.toLowerCase(Locale.ROOT);
            if (mime.startsWith("image/")) {
                out.add(new ImageInfo(path, node.size));
            }
            return;
        }
        for (VirtualFileSystem.Node child : node.children.values()) {
            String childPath = (path.equals("/") ? "" : path) + "/" + child.name;
            this.collectImages(childPath, depth + 1, out);
        }
    }

    private void openSessions() {
        this.mode = Mode.SESSIONS;
        this.sessionScroll = 0;
        this.focus = Field.NONE;
        this.syncTextInput();
    }

    private void openSkills() {
        this.mode = Mode.SKILLS;
        this.skillScroll = 0;
        this.focus = Field.NONE;
        this.syncTextInput();
    }

    private void openImages() {
        this.mode = Mode.IMAGES;
        this.imageScroll = 0;
        this.focus = Field.NONE;
        this.syncTextInput();
    }

    private void openSettings() {
        this.mode = Mode.SETTINGS;
        this.settingField = -1;
        this.settingsStatus = "";
        this.fillDrafts();
        this.focus = Field.NONE;
        this.syncTextInput();
    }

    private void fillDrafts() {
        this.drafts[1] = this.config.ollamaUrl;
        this.drafts[2] = this.config.ollamaModel;
        this.drafts[3] = this.config.qwenUrl;
        this.drafts[4] = this.config.qwenModel;
        this.drafts[5] = this.config.qwenKey;
        this.drafts[6] = this.config.temperatureText();
        this.drafts[7] = String.valueOf(this.config.maxTokens);
        this.drafts[8] = String.valueOf(this.config.compressAt);
        this.drafts[9] = String.valueOf(this.config.maxSteps);
    }

    private String draft(int index) {
        if (index < 0 || index >= this.drafts.length || this.drafts[index] == null) {
            return "";
        }
        return this.drafts[index];
    }

    private String maskedDraft() {
        String key = this.draft(5);
        if (key == null || key.isBlank()) {
            return "未填写";
        }
        return key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
    }

    private void applyDrafts() {
        this.config.ollamaUrl = this.draft(1).trim();
        this.config.ollamaModel = this.draft(2).trim();
        this.config.qwenUrl = this.draft(3).trim();
        this.config.qwenModel = this.draft(4).trim();
        this.config.qwenKey = this.draft(5).trim();
        this.config.temperature = this.parseDouble(this.draft(6), this.config.temperature, 0.0D, 2.0D);
        this.config.maxTokens = this.parseInt(this.draft(7), this.config.maxTokens, 128, 32768);
        this.config.compressAt = this.parseInt(this.draft(8), this.config.compressAt, 1000, 200000);
        this.config.maxSteps = this.parseInt(this.draft(9), this.config.maxSteps, 1, 24);
    }

    private static double parseDouble(String text, double fallback, double min, double max) {
        try {
            double value = Double.parseDouble(text.trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String text, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(text.trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void saveSettings() {
        this.applyDrafts();
        this.host.writeFile(this.root + "/.ide/settings.json", this.config.toJson().toString());
        this.settingsStatus = "已保存到 .ide/settings.json";
        this.status("设置已保存");
        this.settingField = -1;
        this.syncTextInput();
    }

    private void testSettings() {
        this.applyDrafts();
        this.settingsStatus = "正在测试连接…";
        IdeConfig test = this.config;
        LlmChat.test(test, message -> Minecraft.getInstance().execute(() -> {
            this.settingsStatus = message;
            this.status(message);
        }));
    }

    // ------------------------------------------------------------------ 工具方法

    private void status(String text) {
        this.statusLine = text == null ? "" : text;
        this.statusAt = System.currentTimeMillis();
    }

    private String relative(String path) {
        if (path == null) {
            return "";
        }
        if (path.equals(this.root)) {
            return ".";
        }
        if (path.startsWith(this.root + "/")) {
            return path.substring(this.root.length() + 1);
        }
        return path;
    }

    private String fileName(String path) {
        return VirtualFileSystem.fileName(path == null ? "" : path);
    }

    private String language(String path) {
        if (path.endsWith(".java")) {
            return "Java";
        }
        if (path.endsWith(".md")) {
            return "Markdown";
        }
        if (path.endsWith(".json")) {
            return "JSON";
        }
        return "文本";
    }

    private int fileColor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return 0xFFE0A44A;
        }
        if (lower.endsWith(".md")) {
            return 0xFF8AA8C8;
        }
        if (lower.endsWith(".json")) {
            return 0xFFD7BA7D;
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")) {
            return 0xFFC586C0;
        }
        return 0xFF9AA0A6;
    }

    private record TreeRow(String path, String name, boolean dir, int depth, boolean expanded) {
    }

    private record Pending(String command, Consumer<String> done, long at) {
    }

    private record Hit(int x, int y, int w, int h, int button, Runnable action, boolean overlay) {
        private boolean contains(int px, int py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }

    private enum Field { NONE, EDITOR, CHAT, CONSOLE, PROMPT, SETTINGS }

    private enum Mode { NONE, SETTINGS, SESSIONS, SKILLS, IMAGES, PROMPT }

    private enum PromptKind { NONE, NEW_FILE, NEW_FOLDER, NEW_SKILL, RENAME, SESSION_RENAME }

    private static final class OpenFile {
        private String path;
        private final List<String> lines = new ArrayList<>();
        private boolean dirty;
        private int row;
        private int col;
        private int scroll;
        private int hscroll;

        private OpenFile(String path) {
            this.path = path;
        }
    }

    /** 智能体访问虚拟机与界面的桥。 */
    private final class AgentHost implements IdeAgent.Host {
        @Override
        public String projectRoot() {
            return IdeApp.this.root;
        }

        @Override
        public VirtualFileSystem fs() {
            return IdeApp.this.fs;
        }

        @Override
        public void writeFile(String path, String content) {
            if (path == null || path.isBlank() || VirtualFileSystem.isProtected(path)) {
                return;
            }
            if (IdeApp.this.fs != null) {
                IdeApp.this.fs.write(path, content);
            }
            HajikunSmartClient.request(IdeApp.this.pos, "write", path + "\n"
                    + (content == null ? "" : content));
        }

        @Override
        public void createDir(String path, Consumer<String> done) {
            if (path == null || path.isBlank() || VirtualFileSystem.isProtected(path)) {
                done.accept("不允许创建受保护目录：" + path);
                return;
            }
            if (IdeApp.this.fs != null) {
                IdeApp.this.fs.mkdirs(path);
            }
            IdeApp.this.exec("mkdir \"" + path.replace("\"", "\\\"") + "\"", done);
        }

        @Override
        public void runCommand(String command, Consumer<String> done) {
            IdeApp.this.exec(command, done);
        }

        @Override
        public String memory() {
            return IdeApp.this.readFile(IdeApp.this.root + "/.ide/memory.md");
        }

        @Override
        public void appendMemory(String text) {
            String stamp = LocalDateTime.now().format(STAMP);
            String line = "- " + stamp + " " + (text == null ? "" : text.strip());
            String old = this.memory();
            String next = (old == null || old.isBlank() ? "# 项目记忆\n\n" : old) + line + "\n";
            IdeApp.this.host.writeFile(IdeApp.this.root + "/.ide/memory.md", next);
        }

        @Override
        public Map<String, String> skills() {
            Map<String, String> out = new LinkedHashMap<>();
            if (IdeApp.this.fs == null) {
                return out;
            }
            VirtualFileSystem.Node dir = IdeApp.this.fs.find(IdeApp.this.root + "/.ide/skills");
            if (dir == null || !dir.isDir()) {
                return out;
            }
            List<VirtualFileSystem.Node> nodes = new ArrayList<>(dir.children.values());
            nodes.sort(Comparator.comparing(node -> node.name));
            for (VirtualFileSystem.Node node : nodes) {
                if (node.isDir() || !node.name.endsWith(".md")) {
                    continue;
                }
                out.put(node.name.substring(0, node.name.length() - 3), node.content);
            }
            return out;
        }
    }
}
