package com.akun.hajikun.client.ide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 智能体可调用的工具清单。
 *
 * <p>参数结构沿用 OpenAI function calling，Ollama 与 DashScope 都能直接吃。
 * 工具本体在 {@link IdeAgent} 里执行，全部落到当前电脑的虚拟机文件系统。</p>
 */
public final class AgentTools {
    private AgentTools() {
    }

    public static JsonArray schema() {
        JsonArray tools = new JsonArray();
        tools.add(tool("list_files", "列出目录内容。目录名以 / 结尾。路径可相对于项目根目录，也可用绝对路径。",
                props("path", "目录路径，省略表示项目根目录")));
        tools.add(tool("read_file", "读取一个文本文件的完整内容。",
                props("path", "文件路径")));
        tools.add(tool("write_file", "新建或覆盖写入一个文本文件。会自动创建父目录。",
                props("path", "文件路径", "content", "完整文件内容")));
        tools.add(tool("create_dir", "新建目录，已存在时不会报错。",
                props("path", "目录路径")));
        tools.add(tool("move_path", "移动或重命名文件 / 目录，用于整理项目结构。",
                props("from", "源路径", "to", "目标路径")));
        tools.add(tool("delete_path", "删除文件或目录。只在用户明确要求时使用。",
                props("path", "要删除的路径")));
        tools.add(tool("search_files", "在项目里按关键字搜索文件名与文本内容。",
                props("query", "关键字", "path", "搜索起点，省略表示项目根目录")));
        tools.add(tool("write_report", "把一份 Markdown 报告写到 /home/user/文档，供用户直接打开。",
                props("name", "报告名，如 项目结构报告", "content", "报告正文（Markdown）")));
        tools.add(tool("run_command", "在虚拟机终端执行一条命令，返回命令输出。内置 javac/java，cwd 与用户终端一致。",
                props("command", "命令，例如 pwd 或 javac Hello.java")));
        tools.add(tool("web_search", "联网搜索真实网页，返回标题、地址与摘要。需要最新信息时先搜索。",
                props("query", "搜索词")));
        tools.add(tool("fetch_url", "抓取一个网页并转成纯文本，用于阅读全文。只支持 http/https。",
                props("url", "网页地址")));
        tools.add(tool("bluetooth_status", "一句话查看虚拟机蓝牙开关、已配对设备与附近可发现的设备数量。", props()));
        tools.add(tool("bluetooth_scan", "扫描 64 格内的电脑与电视，返回名称、类型、MAC 与开关机状态。", props()));
        tools.add(tool("bluetooth_pair", "按设备名或 MAC 精准配对一台电脑。",
                props("target", "设备名或 MAC")));
        tools.add(tool("bluetooth_send", "按设备名或 MAC 把虚拟机里的一个文件蓝牙发送给另一台电脑。",
                props("target", "设备名或 MAC", "path", "要发送的文件路径")));
        tools.add(tool("bluetooth_cast", "把当前电脑投屏到电视。target 填设备名 / MAC 表示精准投屏，填 all 表示广播到附近所有电视。",
                props("target", "电视名、MAC，或 all")));
        tools.add(tool("list_skills", "列出项目里可用的技能（.ide/skills/*.md）。", props()));
        tools.add(tool("read_skill", "读取一个技能的完整说明。",
                props("name", "技能文件名或名称")));
        tools.add(tool("read_memory", "读取当前项目记忆。", props()));
        tools.add(tool("remember", "把一条长期有用的信息写入项目记忆，下次会话仍会带上。",
                props("text", "要记住的内容")));
        return tools;
    }

    private static JsonObject tool(String name, String description, JsonObject properties) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        for (String key : properties.keySet()) {
            required.add(key);
        }
        if (properties.size() > 0) {
            parameters.add("required", required);
        }
        function.add("parameters", parameters);
        JsonObject json = new JsonObject();
        json.addProperty("type", "function");
        json.add("function", function);
        return json;
    }

    /** 用「名、说明、名、说明…」拼属性表；所有列出的参数都是必填。 */
    private static JsonObject props(String... pairs) {
        JsonObject properties = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "string");
            property.addProperty("description", pairs[i + 1]);
            properties.add(pairs[i], property);
        }
        return properties;
    }

    /** 给系统提示用的工具速查表。 */
    public static String describe() {
        return "list_files(path) 列目录；read_file(path) 读文件；write_file(path, content) 写文件；\n"
                + "create_dir(path) 建目录；move_path(from, to) 移动/重命名；delete_path(path) 删除；\n"
                + "search_files(query, path) 搜索；write_report(name, content) 写 Markdown 报告；\n"
                + "run_command(command) 执行终端命令（javac / java / pwd / bt …）；\n"
                + "web_search(query) 联网搜索；fetch_url(url) 读取网页正文；\n"
                + "bluetooth_status() 蓝牙状态；bluetooth_scan() 扫描设备；bluetooth_pair(target) 配对；\n"
                + "bluetooth_send(target, path) 互传文件；bluetooth_cast(target) 投屏（target=all 广播）；\n"
                + "list_skills() / read_skill(name) 查看技能；read_memory() / remember(text) 读写项目记忆。";
    }
}
