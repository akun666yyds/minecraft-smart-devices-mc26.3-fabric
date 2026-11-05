package com.akun.hajikun.vm;

import com.akun.hajikun.jvm.JavaToolchain;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 一台电脑里的虚拟机内核：文件系统、当前目录与终端命令解释器。
 *
 * <p>所有状态都保存在服务端的方块实体里，客户端只拿到快照并发送命令，
 * 因此换人、换客户端、退出重进后看到的内容完全一致。网络层走宿主机的
 * 真实网络（浏览器在客户端直接取网），其余软件全部运行在这套沙盒里。</p>
 */
public final class VirtualMachine {
    private final VirtualFileSystem fs;
    private final Set<String> paired = new LinkedHashSet<>();
    private String cwd = "/home/user";
    private boolean bluetooth = true;

    public VirtualMachine() {
        this.fs = new VirtualFileSystem();
        this.seed();
    }

    private VirtualMachine(VirtualFileSystem fs, String cwd) {
        this.fs = fs;
        this.cwd = cwd == null || cwd.isBlank() ? "/home/user" : cwd;
    }

    public VirtualFileSystem fs() {
        return this.fs;
    }

    public String cwd() {
        return this.cwd;
    }

    /** 硬关机：清掉运行时上下文（当前目录），磁盘上的文件与蓝牙配对仍然保留。 */
    public void hardReset() {
        this.cwd = "/home/user";
    }

    /** 蓝牙总开关：关闭后不扫描、不配对、不传输、不投屏。 */
    public boolean bluetooth() {
        return this.bluetooth;
    }

    public void setBluetooth(boolean enabled) {
        this.bluetooth = enabled;
    }

    public boolean isPaired(String mac) {
        for (String value : this.paired) {
            if (value.equalsIgnoreCase(mac)) {
                return true;
            }
        }
        return false;
    }

    public void addPaired(String mac) {
        if (mac != null && !mac.isBlank()) {
            this.paired.add(mac.toUpperCase(Locale.ROOT));
        }
    }

    public void removePaired(String mac) {
        this.paired.removeIf(value -> value.equalsIgnoreCase(mac));
    }

    public Set<String> pairedMacs() {
        return new LinkedHashSet<>(this.paired);
    }

    /** 首次开机写入的示例文档，已经存在就不覆盖。 */
    private void seed() {
        this.fs.mkdirs("/home/user/文档");
        this.fs.mkdirs("/home/user/图片");
        this.fs.mkdirs("/home/user/下载");
        this.fs.mkdirs("/系统");
        this.fs.write("/home/user/文档/欢迎.txt",
                "欢迎使用哈基坤智能设备。\n"
                        + "这台电脑是一台保存在世界存档里的虚拟机：名字、MAC 地址、文件与开关机状态都跟着存档走。\n"
                        + "终端里输入 help 可以查看全部命令。\n");
        this.fs.write("/home/user/文档/使用说明.txt",
                "1. 对着电脑按右键，视角会聚焦到屏幕上。\n"
                        + "2. 单击屏幕右下角的圆形电源键开机，长按 Shift 再点它等于硬关机。\n"
                        + "3. 按 Shift 离开电脑。\n"
                        + "4. 浏览器走宿主机的真实网络，可以直接打开 bilibili、央视网等站点。\n");
        this.fs.write("/系统/内核信息.txt",
                "内核：HajikunVM 0.1\n"
                        + "存储：世界存档内嵌\n"
                        + "网络：物理机穿透（只穿透网络层，其余系统完全独立）\n"
                        + "设备身份：MAC 由设备 UUID 派生，不可修改\n");
    }

    public String execute(String line, String hostname, String mac, long uptimeTicks) {
        return this.execute(line, hostname, mac, uptimeTicks, null);
    }

    public String execute(String line, String hostname, String mac, long uptimeTicks, PeerHost host) {
        String input = line == null ? "" : line.trim();
        if (input.isEmpty()) {
            return "";
        }
        if (input.equals("clear") || input.equals("cls")) {
            return "__CLEAR__";
        }
        List<String> tokens = tokenize(input);
        String command = tokens.get(0).toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "help" -> help();
                case "ls", "dir" -> list(tokens);
                case "cd" -> changeDir(tokens);
                case "pwd" -> this.cwd;
                case "cat", "type" -> cat(tokens);
                case "mkdir" -> mkdir(tokens);
                case "touch" -> touch(tokens);
                case "write" -> write(tokens, input);
                case "echo" -> echo(tokens, input);
                case "rm", "del" -> remove(tokens);
                case "mv", "move" -> move(tokens);
                case "hostname" -> hostname == null || hostname.isBlank() ? "未命名设备" : hostname;
                case "mac", "ifconfig" -> "eth0  MAC " + mac + "  (不可修改)";
                case "uptime" -> uptime(uptimeTicks);
                case "whoami" -> "user";
                case "browser" -> "浏览器请点击桌面上的「浏览器」图标。";
                case "javac" -> JavaToolchain.compile(this.fs, this.cwd, tokens.subList(1, tokens.size()));
                case "java" -> JavaToolchain.run(this.fs, this.cwd, tokens.subList(1, tokens.size()));
                case "jdk", "jre", "javadoc" -> JavaToolchain.info(this.fs, this.cwd,
                        tokens.subList(1, tokens.size()));
                case "bt", "bluetooth", "devices" -> bluetooth(tokens, host);
                case "cast", "mirror" -> cast(tokens, host);
                default -> "未知命令：" + command + "，输入 help 查看可用命令。";
            };
        } catch (RuntimeException exception) {
            return "命令执行失败：" + exception.getMessage();
        }
    }

    private String bluetooth(List<String> tokens, PeerHost host) {
        if (host == null) {
            return "蓝牙不可用：虚拟机没有连接到设备网络。";
        }
        if (!this.bluetooth) {
            if (tokens.size() > 1 && tokens.get(1).equalsIgnoreCase("on")) {
                this.bluetooth = true;
                return "蓝牙已开启。";
            }
            return "蓝牙已关闭。先在控制面板里打开蓝牙，或在终端输入 bt on。";
        }
        String sub = tokens.size() < 2 ? "scan" : tokens.get(1).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status", "info" -> {
                if (!this.bluetooth) {
                    return "蓝牙：已关闭。输入 bt on 打开。";
                }
                return "蓝牙：已开启。" + (host == null ? "" : "\n" + host.paired());
            }
            case "on" -> {
                this.bluetooth = true;
                return "蓝牙已开启。";
            }
            case "off" -> {
                this.bluetooth = false;
                return "蓝牙已关闭。";
            }
            case "scan", "devices" -> {
                return host.scan();
            }
            case "find", "search" -> {
                if (tokens.size() < 3) {
                    return "用法：bt find <名称或 MAC>";
                }
                return host.find(tokens.get(2));
            }
            case "pair" -> {
                if (tokens.size() < 3) {
                    return "用法：bt pair <名称或 MAC>";
                }
                String message = host.pair(tokens.get(2));
                if (message.startsWith("已配对")) {
                    this.paired.add(tokens.get(2).toUpperCase(Locale.ROOT));
                }
                return message;
            }
            case "send" -> {
                if (tokens.size() < 4) {
                    return "用法：bt send <名称或 MAC> <文件>";
                }
                String path = VirtualFileSystem.normalize(this.cwd, tokens.get(3));
                String message = host.send(tokens.get(2), path);
                if (message.contains("发送") || message.contains("已传输")) {
                    this.paired.add(tokens.get(2).toUpperCase(Locale.ROOT));
                }
                return message;
            }
            case "list", "paired" -> {
                return host.paired();
            }
            case "unpair" -> {
                if (tokens.size() < 3) {
                    return "用法：bt unpair <名称或 MAC>";
                }
                this.removePaired(tokens.get(2));
                return "已取消配对：" + tokens.get(2);
            }
            default -> {
                return "蓝牙命令：bt status / bt scan / bt find <名称> / bt pair <名称|MAC> / "
                        + "bt send <名称|MAC> <文件> / bt list";
            }
        }
    }

    private String cast(List<String> tokens, PeerHost host) {
        if (host == null) {
            return "投屏不可用：虚拟机没有连接到设备网络。";
        }
        if (!this.bluetooth) {
            return "蓝牙已关闭，无法投屏。";
        }
        if (tokens.size() < 2) {
            return "用法：cast <名称|MAC>，或用 cast all 广播到附近所有电视";
        }
        String target = tokens.get(1);
        if (target.equalsIgnoreCase("all") || target.equals("广播") || target.equals("所有")) {
            return host.castAll();
        }
        return host.cast(target);
    }

    public static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private String help() {
        return String.join("\n",
                "可用命令：",
                "  ls [目录]            列出目录内容",
                "  cd <目录>            切换目录（cd .. 返回上级）",
                "  pwd                  显示当前目录",
                "  cat <文件>           查看文件内容",
                "  mkdir <目录>         新建目录",
                "  touch <文件>         新建空文件",
                "  write <文件> <内容>  写入文本文件",
                "  echo 文本 > 文件     把文本写入文件",
                "  rm <路径>            删除文件或目录",
                "  mv <源> <目标>       移动或重命名",
                "  hostname / mac       查看设备名与 MAC",
                "  uptime               查看本次开机时长",
                "  bt scan              扫描附近的智能设备",
                "  bt status            一句话查看蓝牙状态与已配对设备",
                "  bt find <名称>       按名称或 MAC 查找设备",
                "  bt on / bt off       打开或关闭蓝牙",
                "  bt pair <名称|MAC>   蓝牙配对",
                "  bt send <名称|MAC> <文件> 把文件发送给另一台电脑",
                "  bt list              查看已配对设备",
                "  cast <名称|MAC>      把当前电脑投屏到指定电视",
                "  cast all             广播投屏到附近所有电视",
                "  javac <文件.java>    编译 Java 源码（包 = 目录）",
                "  java <类名>          运行 Java 程序，如 java com.demo.Main",
                "  java -version        查看内置 JDK 版本",
                "  jdk sample           生成 Java 示例工程",
                "  clear                清空终端");
    }

    private String list(List<String> tokens) {
        String target = tokens.size() > 1 ? tokens.get(1) : this.cwd;
        String path = VirtualFileSystem.normalize(this.cwd, target);
        VirtualFileSystem.Node node = this.fs.find(path);
        if (node == null) {
            return "目录不存在：" + path;
        }
        if (!node.isDir()) {
            return VirtualFileSystem.fileName(path);
        }
        if (node.children.isEmpty()) {
            return "(空目录)";
        }
        StringBuilder out = new StringBuilder();
        for (VirtualFileSystem.Node child : this.fs.list(path)) {
            if (out.length() > 0) {
                out.append("    ");
            }
            out.append(child.isDir() ? child.name + "/" : child.name);
        }
        return out.toString();
    }

    private String changeDir(List<String> tokens) {
        if (tokens.size() < 2) {
            this.cwd = "/home/user";
            return "已回到 " + this.cwd;
        }
        String path = VirtualFileSystem.normalize(this.cwd, tokens.get(1));
        VirtualFileSystem.Node node = this.fs.find(path);
        if (node == null || !node.isDir()) {
            return "不是有效目录：" + path;
        }
        this.cwd = path;
        return this.cwd;
    }

    private String cat(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法：cat <文件>";
        }
        String path = VirtualFileSystem.normalize(this.cwd, tokens.get(1));
        String content = this.fs.read(path);
        return content == null ? "无法读取文件：" + path : content;
    }

    private String mkdir(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法：mkdir <目录>";
        }
        String path = VirtualFileSystem.normalize(this.cwd, tokens.get(1));
        String created = this.fs.mkdirs(path);
        return created == null ? "创建失败，已有同名文件：" + path : "已创建目录 " + created;
    }

    private String touch(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法：touch <文件>";
        }
        String path = VirtualFileSystem.normalize(this.cwd, tokens.get(1));
        return this.fs.write(path, "") ? "已创建 " + path : "创建失败：" + path;
    }

    private String write(List<String> tokens, String input) {
        if (tokens.size() < 3) {
            return "用法：write <文件> <内容>";
        }
        String path = VirtualFileSystem.normalize(this.cwd, tokens.get(1));
        String content = input.substring(input.indexOf(tokens.get(2)));
        return this.fs.write(path, content) ? "已写入 " + path : "写入失败：" + path;
    }

    private String echo(List<String> tokens, String input) {
        int arrow = input.indexOf('>');
        if (arrow < 0) {
            String text = input.substring(4).trim();
            return text;
        }
        String text = input.substring(4, arrow).trim();
        String file = input.substring(arrow + 1).trim();
        if (file.isEmpty()) {
            return "用法：echo 文本 > 文件";
        }
        String path = VirtualFileSystem.normalize(this.cwd, file);
        return this.fs.write(path, text) ? "已写入 " + path : "写入失败：" + path;
    }

    private String remove(List<String> tokens) {
        if (tokens.size() < 2) {
            return "用法：rm <路径>";
        }
        String path = VirtualFileSystem.normalize(this.cwd, tokens.get(tokens.size() - 1));
        if (VirtualFileSystem.isProtected(path)) {
            return "系统文件受保护，不能删除：" + path;
        }
        return this.fs.delete(path) ? "已删除 " + path : "删除失败，路径不存在：" + path;
    }

    private String move(List<String> tokens) {
        if (tokens.size() < 3) {
            return "用法：mv <源> <目标>";
        }
        String from = VirtualFileSystem.normalize(this.cwd, tokens.get(1));
        String to = VirtualFileSystem.normalize(this.cwd, tokens.get(2));
        if (VirtualFileSystem.isProtected(from) || VirtualFileSystem.isProtected(to)) {
            return "系统文件受保护，不能重命名或移动。";
        }
        return this.fs.move(from, to) ? "已移动到 " + to : "移动失败：" + from + " -> " + to;
    }

    private static String uptime(long ticks) {
        long seconds = Math.max(0, ticks) / 20L;
        return String.format(Locale.ROOT, "已运行 %d 小时 %d 分 %d 秒",
                seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }

    /** 整台机器（含媒体二进制）压缩成一段字节，供物品携带。 */
    public byte[] encode() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             DataOutputStream out = new DataOutputStream(gzip)) {
            byte[] json = this.toJson().toString().getBytes(StandardCharsets.UTF_8);
            byte[] bundle = this.fs.mediaBundle();
            out.writeInt(0x484B4D31);
            out.writeInt(json.length);
            out.write(json);
            out.writeInt(bundle.length);
            out.write(bundle);
            out.flush();
            gzip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    public static VirtualMachine decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new VirtualMachine();
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != 0x484B4D31) {
                return new VirtualMachine();
            }
            int jsonLength = in.readInt();
            if (jsonLength < 0 || jsonLength > 64 * 1024 * 1024) {
                return new VirtualMachine();
            }
            byte[] json = new byte[jsonLength];
            in.readFully(json);
            int bundleLength = in.readInt();
            if (bundleLength < 0 || bundleLength > 512 * 1024 * 1024) {
                return new VirtualMachine();
            }
            byte[] bundle = new byte[bundleLength];
            in.readFully(bundle);
            VirtualMachine machine = fromJson(JsonParser.parseString(
                    new String(json, StandardCharsets.UTF_8)).getAsJsonObject());
            machine.fs().loadMediaBundle(bundle);
            return machine;
        } catch (IOException | RuntimeException exception) {
            return new VirtualMachine();
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("cwd", this.cwd);
        json.add("fs", this.fs.toJson());
        JsonArray peers = new JsonArray();
        for (String mac : this.paired) {
            peers.add(mac);
        }
        json.add("paired", peers);
        json.addProperty("bt", this.bluetooth);
        return json;
    }

    public static VirtualMachine fromJson(JsonObject json) {
        if (json == null) {
            return new VirtualMachine();
        }
        VirtualFileSystem fs = json.has("fs")
                ? VirtualFileSystem.fromJson(json.getAsJsonObject("fs"))
                : new VirtualFileSystem();
        String cwd = json.has("cwd") ? json.get("cwd").getAsString() : "/home/user";
        VirtualMachine machine = new VirtualMachine(fs, cwd);
        if (json.has("paired") && json.get("paired").isJsonArray()) {
            for (var element : json.getAsJsonArray("paired")) {
                machine.addPaired(element.getAsString());
            }
        }
        machine.setBluetooth(!json.has("bt") || json.get("bt").getAsBoolean());
        if (fs.find("/home/user") == null) {
            machine.seed();
        }
        return machine;
    }
}
