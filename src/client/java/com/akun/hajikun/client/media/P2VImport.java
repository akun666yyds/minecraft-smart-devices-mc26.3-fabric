package com.akun.hajikun.client.media;

import com.akun.hajikun.client.HajikunSmartClient;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * P2V（physical to virtual）导入：把物理机上的文件转成虚拟机可用的格式再上传。
 *
 * <p>图片保留原样或转成 PNG，音频统一转成 22.05kHz 单声道 WAV，视频转成
 * {@link HkvVideo} 容器；文本直接按 UTF-8 写入虚拟机。文件选择器在 Swing
 * 线程弹出，转换与分块上传在后台线程执行，不会卡住游戏画面。</p>
 */
public final class P2VImport {
    private static final int CHUNK = 24 * 1024;
    private static final long MAX_SOURCE = 96L * 1024 * 1024;
    private static final long MAX_TEXT = 2L * 1024 * 1024;
    private static final int MAX_FRAMES = 2400;
    private static final int MAX_HOST_ENTRIES = 240;
    /** 硬盘浏览只列这些扩展名，避免把构建产物和缓存目录也画进列表。 */
    private static final String[] ALLOWED_EXTENSIONS = {
            "png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff", "avif", "ico",
            "mp3", "wav", "flac", "ogg", "oga", "m4a", "aac", "opus", "wma", "aiff", "aif",
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "mpg", "mpeg", "ts", "3gp",
            "txt", "md", "markdown", "mdx", "json", "json5", "jsonc", "xml", "html", "htm",
            "xhtml", "csv", "tsv", "log", "java", "kt", "kts", "scala", "groovy", "clj",
            "cljs", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "py", "pyw", "rb", "php",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte", "lua", "rs", "go", "swift",
            "dart", "m", "mm", "ex", "exs", "erl", "hrl", "hs", "jl", "pl", "pm", "r", "tex",
            "bib", "asm", "s", "v", "sv", "vhd", "vhdl", "sql", "css", "scss", "sass", "less",
            "yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "gradle", "mk", "cmake",
            "bat", "cmd", "ps1", "sh", "bash", "zsh", "fish", "mcmeta", "glsl", "fsh", "vsh",
            "env", "gitignore", "gitattributes", "editorconfig", "npmrc", "dockerfile",
            "makefile", "ipynb", "lock"
    };

    /** 没有后缀但确定是文本的常见文件名（小写比较）。 */
    private static final Set<String> TEXT_NAMES = Set.of(
            "dockerfile", "makefile", "gnumakefile", "license", "licence", "readme", "changelog",
            "authors", "notice", "copying", "todo", "procfile", "gemfile", "rakefile", "vagrantfile",
            "justfile", "cmakelists", "brewfile", "hosts", "passwd", "fstab", "crontab", "bashrc",
            "zshrc", "profile", "gitignore", "gitattributes", "editorconfig", "npmrc", "yarnrc",
            "env", "htaccess");

    private record Converted(String name, String mime, byte[] data) {
    }

    /** 物理机上的一个条目，供 P2V 窗口列表使用。 */
    public record HostEntry(String name, String path, boolean directory, boolean supported,
                            String kind, long size) {
    }

    /** 一次成功传入：source 是物理机路径，path 是虚拟机里的最终路径。 */
    public record Imported(String source, String path, String name, String mime) {
    }

    private static File lastDirectory;
    private static Boolean ffmpegReady;

    private P2VImport() {
    }

    /** 弹出物理机文件选择器；选完后逐个转换并上传到这台电脑的 dir 目录。 */
    public static void choose(BlockPos pos, String dir, Set<String> existing, Consumer<String> status) {
        if (GraphicsEnvironment.isHeadless()) {
            status.accept("P2V 不可用：当前环境没有图形文件选择器。");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("P2V · 从物理机导入文件");
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            // 允许显示无后缀文件（如 README、Dockerfile），真正的类型判定在 convert 里。
            chooser.setAcceptAllFileFilterUsed(true);
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "图片 / 文本 / 音频 / 视频", ALLOWED_EXTENSIONS));
            if (lastDirectory != null && lastDirectory.isDirectory()) {
                chooser.setCurrentDirectory(lastDirectory);
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File[] files = chooser.getSelectedFiles();
            if (files == null || files.length == 0) {
                return;
            }
            lastDirectory = chooser.getCurrentDirectory();
            Thread worker = new Thread(() -> upload(files, pos, dir, existing, status, null),
                    "P2V import");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** 从 P2V 窗口传入物理机文件；转码与分块上传都在后台线程完成。 */
    public static void importFiles(BlockPos pos, String dir, Set<String> existing,
                                   Consumer<String> status, Consumer<Imported> imported,
                                   List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        List<File> list = new ArrayList<>(files);
        Thread worker = new Thread(() -> upload(list.toArray(new File[0]), pos, dir, existing,
                status, imported), "P2V import");
        worker.setDaemon(true);
        worker.start();
    }

    /** 列出物理机目录；空路径表示磁盘/盘符列表。目录在前，其余按名称排序。 */
    public static List<HostEntry> listHost(String directory) {
        List<HostEntry> entries = new ArrayList<>();
        if (directory == null || directory.isBlank()) {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    if (root != null) {
                        entries.add(new HostEntry(root.getPath(), root.getAbsolutePath(),
                                true, false, "磁盘", 0L));
                    }
                }
            }
            return entries;
        }
        File[] files = new File(directory).listFiles();
        if (files == null) {
            return entries;
        }
        for (File file : files) {
            if (entries.size() >= MAX_HOST_ENTRIES) {
                break;
            }
            String name = file.getName();
            if (name.isEmpty()) {
                continue;
            }
            boolean isDir = file.isDirectory();
            String kind = isDir ? "文件夹" : typeLabel(file);
            entries.add(new HostEntry(name, file.getAbsolutePath(), isDir,
                    !isDir && !kind.isEmpty(), kind, isDir ? 0L : file.length()));
        }
        entries.sort((a, b) -> {
            if (a.directory() != b.directory()) {
                return a.directory() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return entries;
    }

    /** 只允许虚拟机能播放或打开的格式；其余文件在 P2V 列表里显示为不支持。 */
    public static String typeLabel(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        String extension = extension(file.getName());
        if (isImage(extension)) {
            return "图片";
        }
        if (isAudio(extension)) {
            return "音频";
        }
        if (isVideo(extension)) {
            return "录像";
        }
        if (isText(extension)) {
            return "文本";
        }
        // 无后缀或未知后缀：只有内容确属文本才放行，二进制一律拒收。
        if (extension.isEmpty() || TEXT_NAMES.contains(file.getName().toLowerCase(Locale.ROOT))) {
            return looksLikeText(file) ? "文本" : "";
        }
        return "";
    }

    public static boolean supported(File file) {
        return !typeLabel(file).isEmpty();
    }

    private static void upload(File[] files, BlockPos pos, String dir, Set<String> existing,
                               Consumer<String> status, Consumer<Imported> imported) {
        Set<String> used = existing == null ? new HashSet<>() : new HashSet<>(existing);
        int ok = 0;
        for (File file : files) {
            report(status, "P2V：正在处理 " + file.getName() + " …");
            try {
                Converted converted = convert(file);
                if (converted == null) {
                    report(status, "P2V：跳过 " + file.getName() + "（空文件）");
                    continue;
                }
                String name = unique(converted.name(), used);
                used.add(name);
                send(pos, dir, name, converted.mime(), converted.data());
                ok++;
                report(status, "P2V：已上传 " + name + "（" + size(converted.data().length) + "）");
                if (imported != null) {
                    report(imported, new Imported(file.getAbsolutePath(), join(dir, name), name,
                            converted.mime()));
                }
            } catch (Exception exception) {
                report(status, "P2V 导入失败：" + file.getName() + "（"
                        + (exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage()) + "）");
            }
        }
        report(status, "P2V 传输完成：成功 " + ok + " / " + files.length + " 个文件。");
    }

    private static void send(BlockPos pos, String dir, String name, String mime, byte[] data) {
        int total = Math.max(1, (data.length + CHUNK - 1) / CHUNK);
        String transfer = UUID.randomUUID().toString();
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK;
            int end = Math.min(data.length, start + CHUNK);
            byte[] chunk = Arrays.copyOfRange(data, start, end);
            HajikunSmartClient.upload(pos, transfer, dir, name, mime, i, total, chunk);
        }
    }

    private static Converted convert(File file) throws IOException, InterruptedException {
        if (!file.isFile()) {
            throw new IOException("不是普通文件");
        }
        if (file.length() > MAX_SOURCE) {
            throw new IOException("超过 96 MiB");
        }
        String name = sanitize(file.getName());
        String extension = extension(name);
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (isImage(extension)) {
            return convertImage(file, name, extension);
        }
        if (isAudio(extension)) {
            return convertAudio(file, base);
        }
        if (isVideo(extension)) {
            return convertVideo(file, base);
        }
        if (isText(extension) || extension.isEmpty()
                || TEXT_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            byte[] bytes = readText(file);
            String target = extension.isEmpty() && !name.contains(".") ? name + ".txt" : name;
            return bytes.length == 0 ? null : new Converted(target, "text/plain", bytes);
        }
        // 其余类型一律拒收：虚拟机的查看器/播放器只认图片、文本、WAV 与 hkv 录像。
        throw new IOException("不支持的文件类型（仅图片/文本/音频/视频）");
    }

    private static Converted convertImage(File file, String name, String extension)
            throws IOException, InterruptedException {
        if (extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg")
                || extension.equals("bmp")) {
            return new Converted(name, extension.equals("png") ? "image/png"
                    : extension.equals("bmp") ? "image/bmp" : "image/jpeg", Files.readAllBytes(file.toPath()));
        }
        Path png = transcode(file, "png", List.of("-frames:v", "1"));
        if (png == null) {
            throw new IOException("缺少 ffmpeg 或转码失败，无法把 " + extension.toUpperCase(Locale.ROOT)
                    + " 转成 PNG");
        }
        return new Converted(baseName(name) + ".png", "image/png", readAndDelete(png));
    }

    private static Converted convertAudio(File file, String base) throws IOException, InterruptedException {
        Path wav = transcode(file, "wav", List.of("-vn", "-ac", "1", "-ar", "22050", "-c:a", "pcm_s16le"));
        if (wav == null) {
            throw new IOException("缺少 ffmpeg 或转码失败，无法把 "
                    + extension(file.getName()).toUpperCase(Locale.ROOT) + " 转成 WAV");
        }
        return new Converted(base + ".wav", "audio/wav", readAndDelete(wav));
    }

    private static Converted convertVideo(File file, String base) throws IOException, InterruptedException {
        if (!ffmpeg()) {
            throw new IOException("缺少 ffmpeg，无法把视频转成虚拟机录像");
        }
        Path work = Files.createTempDirectory("hajikun-p2v-");
        try {
            Path framesDir = work.resolve("frames");
            Files.createDirectories(framesDir);
            Process process = new ProcessBuilder(ffmpegPath(), "-y", "-t", "300", "-i", file.getAbsolutePath(),
                    "-an", "-vf", "fps=8,scale=320:-2", "-q:v", "6",
                    framesDir.resolve("f%05d.jpg").toString())
                    .redirectErrorStream(true)
                    .start();
            drain(process);
            if (process.waitFor() != 0) {
                throw new IOException("视频转码失败");
            }
            List<Path> frameFiles = new ArrayList<>();
            try (var stream = Files.list(framesDir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".jpg"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(MAX_FRAMES)
                        .forEach(frameFiles::add);
            }
            if (frameFiles.isEmpty()) {
                throw new IOException("视频没有可用画面");
            }
            int width = 320;
            int height = 180;
            var image = ImageIO.read(frameFiles.get(0).toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
            List<byte[]> frames = new ArrayList<>(frameFiles.size());
            for (Path frame : frameFiles) {
                frames.add(Files.readAllBytes(frame));
            }
            byte[] audio = new byte[0];
            Path wav = transcode(file, "wav", List.of("-vn", "-t", "300", "-ac", "1", "-ar", "22050",
                    "-c:a", "pcm_s16le"));
            if (wav != null && Files.size(wav) > 44L) {
                audio = Files.readAllBytes(wav);
            }
            if (wav != null) {
                Files.deleteIfExists(wav);
            }
            byte[] hkv = HkvVideo.write(width, height, 8, frames, audio);
            if (hkv == null || hkv.length > 96 * 1024 * 1024) {
                throw new IOException("录像体积过大");
            }
            return new Converted(base + ".hkv", "video/hkv", hkv);
        } finally {
            deleteTree(work);
        }
    }

    /** 用 ffmpeg 把任意源转成指定后缀，返回临时文件；没有 ffmpeg 时返回 null。 */
    private static Path transcode(File source, String suffix, List<String> options)
            throws IOException, InterruptedException {
        if (!ffmpeg()) {
            return null;
        }
        Path output = Files.createTempFile("hajikun-p2v-", "." + suffix);
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(source.getAbsolutePath());
        command.addAll(options);
        if (suffix.equals("png")) {
            command.add("-update");
            command.add("1");
        }
        command.add(output.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        drain(process);
        if (process.waitFor() != 0 || Files.size(output) == 0L) {
            Files.deleteIfExists(output);
            return null;
        }
        return output;
    }

    private static synchronized boolean ffmpeg() {
        if (ffmpegReady != null) {
            return ffmpegReady;
        }
        for (String candidate : candidates()) {
            try {
                Process process = new ProcessBuilder(candidate, "-version")
                        .redirectErrorStream(true).start();
                drain(process);
                if (process.waitFor() == 0) {
                    ffmpegPath = candidate;
                    ffmpegReady = true;
                    return true;
                }
            } catch (IOException | InterruptedException ignored) {
            }
        }
        ffmpegReady = false;
        return false;
    }

    private static String ffmpegPath = "ffmpeg";

    private static String ffmpegPath() {
        return ffmpegPath;
    }

    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        String env = System.getenv("FFMPEG");
        if (env != null && !env.isBlank()) {
            list.add(env);
        }
        list.add("ffmpeg");
        list.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
        list.add("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe");
        list.add("C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe");
        return list;
    }

    private static void drain(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // 丢弃 ffmpeg 日志，避免后台线程写控制台拖慢游戏。
                }
            } catch (IOException ignored) {
            }
        }, "P2V ffmpeg log");
        thread.setDaemon(true);
        thread.start();
    }

    private static <T> void report(Consumer<T> consumer, T value) {
        Minecraft.getInstance().execute(() -> consumer.accept(value));
    }

    private static String join(String dir, String name) {
        String base = dir == null || dir.isBlank() ? "/home/user/下载" : dir;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + name;
    }

    private static String unique(String name, Set<String> used) {
        if (!used.contains(name)) {
            return name;
        }
        String base = name;
        String suffix = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            suffix = name.substring(dot);
        }
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String sanitize(String name) {
        StringBuilder builder = new StringBuilder();
        for (char c : name.toCharArray()) {
            builder.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String clean = builder.toString().trim();
        return clean.isEmpty() ? "导入文件" : clean;
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 只读探测：文件内容像文本才返回 true；含 NUL 或控制字符过多一律判为二进制。 */
    private static boolean looksLikeText(File file) {
        if (file == null || !file.isFile() || file.length() == 0L || file.length() > MAX_TEXT) {
            return false;
        }
        try (var in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(8192);
            if (head.length == 0) {
                return false;
            }
            int control = 0;
            for (byte b : head) {
                int value = b & 0xFF;
                if (value == 0) {
                    return false;
                }
                if (value < 9 || (value > 13 && value < 32) || value == 0x7F) {
                    control++;
                }
            }
            return control * 100 <= head.length;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 按 UTF-8 读取文本，失败时按 GBK 兜底并统一转成 UTF-8；
     * 两种编码都不成立（含 NUL 的二进制）直接拒收。全程只读。
     */
    private static byte[] readText(File file) throws IOException {
        if (file.length() > MAX_TEXT) {
            throw new IOException("文本超过 2 MiB");
        }
        byte[] raw = Files.readAllBytes(file.toPath());
        for (byte b : raw) {
            if (b == 0) {
                throw new IOException("二进制文件，P2V 只接收文本");
            }
        }
        for (java.nio.charset.Charset charset : new java.nio.charset.Charset[]{
                StandardCharsets.UTF_8, java.nio.charset.Charset.forName("GBK")}) {
            try {
                var decoder = charset.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                String text = decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
                if (text.indexOf('\u0000') >= 0) {
                    throw new IOException("二进制文件，P2V 只接收文本");
                }
                return text.getBytes(StandardCharsets.UTF_8);
            } catch (java.nio.charset.CharacterCodingException ignored) {
            }
        }
        throw new IOException("不是可识别的文本编码");
    }

    /** 读取临时产物后立即删除，宿主机上不留下任何属于这台虚拟机的中间文件。 */
    private static byte[] readAndDelete(Path path) throws IOException {
        try {
            return Files.readAllBytes(path);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /**
     * 清理历史遗留的 P2V 临时目录/文件（只动本模组前缀且超过 1 小时的条目）。
     * 客户端启动时调用一次，确保宿主机不累积虚拟机残留。
     */
    public static void cleanupScratch() {
        Thread worker = new Thread(() -> {
            long deadline = System.currentTimeMillis() - 60L * 60L * 1000L;
            for (String dir : new String[]{System.getProperty("java.io.tmpdir"), "/tmp"}) {
                if (dir == null) {
                    continue;
                }
                File[] entries = new File(dir).listFiles(
                        file -> file.getName().startsWith("hajikun-p2v-"));
                if (entries == null) {
                    continue;
                }
                for (File entry : entries) {
                    if (entry.lastModified() > deadline) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        deleteTree(entry.toPath());
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        entry.delete();
                    }
                }
            }
        }, "P2V scratch cleanup");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isImage(String extension) {
        return switch (extension) {
            case "png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff", "avif", "ico" -> true;
            default -> false;
        };
    }

    private static boolean isAudio(String extension) {
        return switch (extension) {
            case "mp3", "wav", "flac", "ogg", "oga", "m4a", "aac", "opus", "wma", "aiff", "aif" -> true;
            default -> false;
        };
    }

    private static boolean isVideo(String extension) {
        return switch (extension) {
            case "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "mpg", "mpeg",
                 "ts", "3gp" -> true;
            default -> false;
        };
    }

    private static boolean isText(String extension) {
        return TEXT_EXTENSIONS.contains(extension);
    }

    /** 白名单里的文本/源码后缀；与 {@link #ALLOWED_EXTENSIONS} 保持同源。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "mdx", "json", "json5", "jsonc", "xml", "html", "htm",
            "xhtml", "csv", "tsv", "log", "java", "kt", "kts", "scala", "groovy", "clj", "cljs",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "py", "pyw", "rb", "php", "js", "mjs",
            "cjs", "jsx", "ts", "tsx", "vue", "svelte", "lua", "rs", "go", "swift", "dart", "m",
            "mm", "ex", "exs", "erl", "hrl", "hs", "jl", "pl", "pm", "r", "tex", "bib", "asm", "s",
            "v", "sv", "vhd", "vhdl", "sql", "css", "scss", "sass", "less", "yml", "yaml", "toml",
            "ini", "conf", "cfg", "properties", "gradle", "mk", "cmake", "bat", "cmd", "ps1", "sh",
            "bash", "zsh", "fish", "mcmeta", "glsl", "fsh", "vsh", "env", "gitignore",
            "gitattributes", "editorconfig", "npmrc", "dockerfile", "makefile", "ipynb", "lock");

    private static String size(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
