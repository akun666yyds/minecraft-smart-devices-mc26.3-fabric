package com.akun.hajikun.jvm;

import com.akun.hajikun.vm.VirtualFileSystem;
import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟机自带的迷你 JDK：javac 把 .java 编译成 .class，java 解释执行 main。
 *
 * <p>类路径就是虚拟文件系统：包名与目录一一对应，{@code package com.demo;}
 * 的文件应当放在 {@code com/demo/} 目录里；跨文件的类会按包路径惰性加载。
 * 编译产物是带 {@value #CLASS_HEADER} 头的文本，丢失源码也能重新解释。</p>
 */
public final class JavaToolchain {
    public static final String CLASS_HEADER = "HJVM1\n";
    public static final String VERSION = "21.0.4";
    private static final String LEVEL = "Java 8 / 11 / 17 / 21 常用语法";

    private JavaToolchain() {
    }

    // ------------------------------------------------------------------ javac

    public static String compile(VirtualFileSystem fs, String cwd, List<String> args) {
        if (args.isEmpty()) {
            return "用法: javac <文件.java> [更多文件] [-d 输出目录]\n"
                    + "例如: javac Hello.java\n"
                    + "      javac com/demo/Main.java com/demo/Greeter.java\n"
                    + "版本: javac " + VERSION + "（javac -version 查看）";
        }
        if (args.get(0).equals("-version") || args.get(0).equals("--version")) {
            return "javac " + VERSION;
        }
        String outputDir = null;
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-d")) {
                if (i + 1 >= args.size()) {
                    return "javac: 错误: -d 后面需要目录";
                }
                outputDir = args.get(++i);
                continue;
            }
            if (arg.equals("-encoding") || arg.equals("-source") || arg.equals("-target")
                    || arg.equals("--release")) {
                if (i + 1 < args.size()) {
                    i++;
                }
                continue;
            }
            if (arg.startsWith("-")) {
                return "javac: 错误: 不支持的选项 " + arg;
            }
            sources.add(arg);
        }
        if (sources.isEmpty()) {
            return "javac: 错误: 没有指定源文件";
        }
        StringBuilder out = new StringBuilder();
        int files = 0;
        int classes = 0;
        for (String source : sources) {
            String path = VirtualFileSystem.normalize(cwd, source);
            if (!path.endsWith(".java")) {
                out.append("javac: 错误: ").append(source).append(" 不是 .java 文件\n");
                continue;
            }
            String text = fs.read(path);
            if (text == null) {
                out.append("javac: 错误: 找不到源文件 ").append(path).append("\n");
                continue;
            }
            String file = VirtualFileSystem.fileName(path);
            String base = file.substring(0, file.length() - 5);
            try {
                JavaAst.Unit unit = JavaParser.parse(text);
                boolean named = unit.types().stream().anyMatch(type -> type.name().equals(base));
                if (!named) {
                    StringBuilder names = new StringBuilder();
                    for (JavaAst.ClassDecl type : unit.types()) {
                        if (names.length() > 0) {
                            names.append(", ");
                        }
                        names.append(type.name());
                    }
                    out.append(path).append(": 错误: 文件名必须与公共类名一致，文件里的类有：")
                            .append(names).append("\n");
                    continue;
                }
                if (!unit.pkg().isBlank()) {
                    String expected = unit.pkg().replace('.', '/');
                    if (!VirtualFileSystem.parent(path).endsWith(expected)) {
                        out.append("警告: 包 ").append(unit.pkg()).append(" 建议放在 ").append(expected)
                                .append("/ 目录里（目录就是包）\n");
                    }
                }
                String classPath;
                if (outputDir != null) {
                    String root = VirtualFileSystem.normalize(cwd, outputDir);
                    String pkgPath = unit.pkg().isBlank() ? "" : unit.pkg().replace('.', '/') + "/";
                    classPath = root + "/" + pkgPath + base + ".class";
                    fs.mkdirs(VirtualFileSystem.parent(classPath));
                } else {
                    classPath = path.substring(0, path.length() - 5) + ".class";
                }
                fs.write(classPath, CLASS_HEADER + text);
                files++;
                classes += unit.types().size();
                out.append("javac: ").append(path).append(" -> ").append(classPath).append("\n");
                if (sources.size() == 1 && !unit.pkg().isBlank()) {
                    out.append("       运行: java ").append(unit.pkg()).append('.').append(base)
                            .append("（从包含 ").append(unit.pkg().replace('.', '/'))
                            .append(" 的目录执行）\n");
                }
            } catch (JavaSyntaxException error) {
                out.append(path).append(':').append(error.line()).append(':').append(error.col())
                        .append(": 错误: ").append(error.getMessage()).append("\n");
            }
        }
        if (files > 0) {
            out.append("已编译 ").append(files).append(" 个源文件，生成 ").append(classes)
                    .append(" 个类（javac ").append(VERSION).append("）");
        }
        return out.toString().stripTrailing();
    }

    // ------------------------------------------------------------------ java

    public static String run(VirtualFileSystem fs, String cwd, List<String> args) {
        if (args.isEmpty()) {
            return versionBlock() + "\n\n" + usage();
        }
        String first = args.get(0);
        if (first.equals("-version") || first.equals("--version")) {
            return versionBlock();
        }
        if (first.equals("-help") || first.equals("--help") || first.equals("?")) {
            return usage();
        }
        int classIndex = -1;
        for (int i = 0; i < args.size(); i++) {
            if (!args.get(i).startsWith("-")) {
                classIndex = i;
                break;
            }
        }
        if (classIndex < 0) {
            return "java: 错误: 没有指定主类\n" + usage();
        }
        String className = args.get(classIndex);
        List<String> programArgs = new ArrayList<>(args.subList(classIndex + 1, args.size()));
        String path = resolveClassFile(fs, cwd, className);
        if (path == null) {
            return "java: 错误: 找不到类 " + className + "\n"
                    + "提示: 先运行 javac 编译；包里的类请从包含包目录的文件夹运行，例如\n"
                    + "      cd /home/user/项目\n"
                    + "      java com.akun.demo.Main\n"
                    + "当前目录: " + cwd;
        }
        String text = fs.read(path);
        if (text == null) {
            return "java: 错误: 无法读取 " + path;
        }
        if (text.startsWith(CLASS_HEADER)) {
            text = text.substring(CLASS_HEADER.length());
        }
        JavaAst.Unit unit;
        try {
            unit = JavaParser.parse(text);
        } catch (JavaSyntaxException error) {
            return "java: 错误: " + path + " 无法解析（第 " + error.line() + " 行: "
                    + error.getMessage() + "）";
        }
        return new JavaRunner(fs, classpathRoots(cwd, path, unit.pkg()))
                .run(unit, className, programArgs);
    }

    /**
     * 组装类路径根目录：包根（由主类所在路径反推）、当前目录及其全部祖先。
     *
     * <p>这样无论玩家站在包根、包内部的子目录，还是别处的祖先目录，
     * {@code java com.akun.demo.Main} 都能找到同包的 {@code Greeter}。</p>
     */
    private static List<String> classpathRoots(String cwd, String classFile, String pkg) {
        List<String> roots = new ArrayList<>();
        String suffix = (pkg == null || pkg.isBlank() ? "" : "/" + pkg.replace('.', '/'))
                + "/" + VirtualFileSystem.fileName(classFile);
        String packageRoot = classFile.endsWith(suffix)
                ? classFile.substring(0, classFile.length() - suffix.length())
                : VirtualFileSystem.parent(classFile);
        roots.add(packageRoot.isEmpty() ? "/" : packageRoot);
        roots.add(VirtualFileSystem.parent(classFile));
        String directory = cwd;
        while (true) {
            roots.add(directory);
            if (directory.equals("/")) {
                break;
            }
            directory = VirtualFileSystem.parent(directory);
        }
        List<String> unique = new ArrayList<>();
        for (String root : roots) {
            if (root != null && !root.isBlank() && !unique.contains(root)) {
                unique.add(root);
            }
        }
        return unique;
    }

    /** 按当前目录、祖先目录与 /home/user 下的包目录寻找 .class。 */
    private static String resolveClassFile(VirtualFileSystem fs, String cwd, String className) {
        String relative = className.replace('.', '/') + ".class";
        String directory = cwd;
        while (true) {
            String candidate = VirtualFileSystem.normalize(directory, relative);
            if (fs.find(candidate) != null && !fs.find(candidate).isDir()) {
                return candidate;
            }
            if (directory.equals("/")) {
                break;
            }
            directory = VirtualFileSystem.parent(directory);
        }
        String simple = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1) : className;
        List<String> candidates = new ArrayList<>();
        collectClassFiles(fs, "/home/user", simple + ".class", candidates, 0);
        collectClassFiles(fs, "/", simple + ".class", candidates, 0);
        if (className.contains(".")) {
            String pkg = className.substring(0, className.lastIndexOf('.'));
            for (String candidate : candidates) {
                String text = fs.read(candidate);
                if (text == null) {
                    continue;
                }
                if (text.startsWith(CLASS_HEADER)) {
                    text = text.substring(CLASS_HEADER.length());
                }
                try {
                    if (JavaParser.parse(text).pkg().equals(pkg)) {
                        return candidate;
                    }
                } catch (JavaSyntaxException ignored) {
                    // 损坏的 class 文件直接跳过。
                }
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static void collectClassFiles(VirtualFileSystem fs, String directory, String fileName,
                                          List<String> found, int depth) {
        if (depth > 8 || found.size() >= 8) {
            return;
        }
        for (VirtualFileSystem.Node node : fs.list(directory)) {
            String path = (directory.equals("/") ? "" : directory) + "/" + node.name;
            if (node.isDir()) {
                if (node.name.equals("系统")) {
                    continue;
                }
                collectClassFiles(fs, path, fileName, found, depth + 1);
            } else if (node.name.equals(fileName) && !found.contains(path)) {
                found.add(path);
            }
        }
    }

    // ------------------------------------------------------------------ jdk

    public static String info(VirtualFileSystem fs, String cwd, List<String> args) {
        String sub = args.isEmpty() ? "" : args.get(0).toLowerCase(java.util.Locale.ROOT);
        return switch (sub) {
            case "sample", "demo" -> writeSample(fs);
            case "docs", "help", "?" -> docs(fs);
            default -> versionBlock() + "\n\n" + usage();
        };
    }

    private static String versionBlock() {
        return "Hajikun JDK " + VERSION + "\n"
                + "语言级别: " + LEVEL + "\n"
                + "运行时: HajikunVM 1.0（AST 解释执行，不生成 JVM 字节码）\n"
                + "类路径: 当前目录；包名与目录一一对应\n"
                + "安全限制: 300 万步 / 2.4 万字符输出 / 200 层调用";
    }

    private static String usage() {
        return "用法:\n"
                + "  javac <文件.java>      编译，生成同名 .class\n"
                + "  javac -d <目录> <文件> 编译到指定根目录（按包建子目录）\n"
                + "  java -version          查看版本\n"
                + "  java <类名> [参数...]  运行 main（包里的类用点号，如 com.demo.Main）\n"
                + "  jdk sample             生成两个示例工程到 /home/user/项目\n"
                + "  jdk docs               生成语法速查文档\n"
                + "支持: class/extends/interface、字段与方法、构造器、数组、for/while/if、\n"
                + "      String/ArrayList/HashMap/StringBuilder、Math、System.out、常用包装类。\n"
                + "不支持: switch、lambda、try/catch、enum、record、内部类、泛型擦除后的类型检查。";
    }

    private static String docs(VirtualFileSystem fs) {
        String path = "/home/user/文档/Java 虚拟机速查.txt";
        fs.write(path, "Hajikun JDK " + VERSION + " 速查\n"
                + "====================\n"
                + "1. 包就是目录\n"
                + "   package com.demo; 的文件应放在 com/demo/ 下。\n"
                + "   运行 java com.demo.Main 时，从包含 com/ 的目录执行。\n\n"
                + "2. 编译与运行\n"
                + "   javac Hello.java\n"
                + "   java Hello\n\n"
                + "3. 支持的核心语法\n"
                + "   class / extends / interface，字段、方法、构造器、this、super\n"
                + "   int long double float boolean char String 与数组\n"
                + "   if / else、for、for-each、while、do-while、break、continue、return\n"
                + "   一元/二元/三元运算、强制转换、字符串拼接\n"
                + "   System.out.print / println / printf、Math、String、ArrayList、HashMap\n"
                + "   StringBuilder、Arrays.toString / sort、Integer / Double / Character\n\n"
                + "4. 与真实 JDK 的差别\n"
                + "   不生成字节码，不支持多线程、反射、文件 IO 和第三方库；\n"
                + "   不检查泛型类型，也不连接真实 JVM。\n\n"
                + "5. 安全上限\n"
                + "   300 万步执行、2.4 万字符输出、200 层调用，防止死循环拖慢服务器。\n");
        return "已生成 " + path + "，可用 cat 查看。";
    }

    private static String writeSample(VirtualFileSystem fs) {
        fs.mkdirs("/home/user/项目");
        fs.write("/home/user/项目/Hello.java", ""
                + "public class Hello {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello, Minecraft!\");\n"
                + "        int total = 0;\n"
                + "        for (int i = 1; i <= 100; i++) {\n"
                + "            total += i;\n"
                + "        }\n"
                + "        System.out.println(\"1 到 100 的和 = \" + total);\n"
                + "        System.out.println(\"圆周率约等于 \" + Math.round(Math.PI * 100) / 100.0);\n"
                + "    }\n"
                + "}\n");
        fs.mkdirs("/home/user/项目/com/akun/demo");
        fs.write("/home/user/项目/com/akun/demo/Main.java", ""
                + "package com.akun.demo;\n"
                + "\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        Greeter greeter = new Greeter(\"哈基坤\");\n"
                + "        greeter.sayHello();\n"
                + "\n"
                + "        List<String> langs = new ArrayList<>();\n"
                + "        langs.add(\"Java\");\n"
                + "        langs.add(\"Kotlin\");\n"
                + "        langs.add(\"C++\");\n"
                + "        for (String lang : langs) {\n"
                + "            System.out.println(\"掌握语言: \" + lang);\n"
                + "        }\n"
                + "\n"
                + "        String[] words = {\"包\", \"就是\", \"目录\"};\n"
                + "        System.out.println(\"数组长度 \" + words.length);\n"
                + "        System.out.println(String.join(\" → \", words));\n"
                + "        System.out.printf(\"圆周率约等于 %.2f%n\", Math.PI);\n"
                + "    }\n"
                + "}\n");
        fs.write("/home/user/项目/com/akun/demo/Greeter.java", ""
                + "package com.akun.demo;\n"
                + "\n"
                + "public class Greeter {\n"
                + "    private final String name;\n"
                + "\n"
                + "    public Greeter(String name) {\n"
                + "        this.name = name;\n"
                + "    }\n"
                + "\n"
                + "    public void sayHello() {\n"
                + "        System.out.println(\"你好，\" + name + \"！这里是虚拟机里的 Java。\");\n"
                + "    }\n"
                + "}\n");
        fs.write("/home/user/项目/README.txt", ""
                + "两个示例：\n"
                + "1. Hello.java（默认包）\n"
                + "   cd /home/user/项目\n"
                + "   javac Hello.java\n"
                + "   java Hello\n\n"
                + "2. com/akun/demo/Main.java（包 = 目录）\n"
                + "   cd /home/user/项目\n"
                + "   javac com/akun/demo/Main.java com/akun/demo/Greeter.java\n"
                + "   java com.akun.demo.Main\n");
        return "已生成示例工程：\n"
                + "  /home/user/项目/Hello.java\n"
                + "  /home/user/项目/com/akun/demo/Main.java\n"
                + "  /home/user/项目/com/akun/demo/Greeter.java\n"
                + "依次运行：\n"
                + "  cd /home/user/项目\n"
                + "  javac Hello.java\n"
                + "  java Hello\n"
                + "  javac com/akun/demo/Main.java com/akun/demo/Greeter.java\n"
                + "  java com.akun.demo.Main";
    }
}
