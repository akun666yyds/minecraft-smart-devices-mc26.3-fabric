package com.akun.hajikun.vm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟机的文件系统，纯内存树结构，随方块实体一起写进世界存档。
 *
 * <p>根目录是 {@code /}，路径分隔符固定为 {@code /}。文件与目录都允许中文名，
 * 目录内容按创建顺序保存，方便文件管理器稳定显示。整棵树可以用 JSON 序列化，
 * 客户端拿到快照后也用同一个类还原，保证两端行为一致。</p>
 */
public final class VirtualFileSystem {
    /** 一段二进制媒体：按内容散列存放，同名文件、蓝牙转发都只引用同一份数据。 */
    public static final class Media {
        public final String mime;
        public final byte[] data;

        public Media(String mime, byte[] data) {
            this.mime = mime == null ? "application/octet-stream" : mime;
            this.data = data;
        }
    }

    /** 一个文件或目录。文本放 {@code content}，图片/音频/视频放 {@code media} 散列。 */
    public static final class Node {
        public String name;
        public boolean dir;
        public String content;
        public String mime;
        public String media;
        public long size;
        public final Map<String, Node> children;

        public Node(String name, boolean dir) {
            this.name = name;
            this.dir = dir;
            this.content = "";
            this.mime = "";
            this.media = "";
            this.size = 0L;
            this.children = new LinkedHashMap<>();
        }

        public boolean isDir() {
            return this.dir;
        }

        public boolean isBinary() {
            return !this.dir && this.media != null && !this.media.isBlank();
        }
    }

    private final Node root = new Node("", true);
    private final Map<String, Media> media = new LinkedHashMap<>();

    public Node root() {
        return this.root;
    }

    /** 把相对路径按当前目录展开成绝对路径，处理 {@code .}、{@code ..} 与多余斜杠。 */
    public static String normalize(String cwd, String raw) {
        String base = raw.startsWith("/") ? "" : cwd;
        String combined = raw.startsWith("/") ? raw : base + "/" + raw;
        List<String> stack = new ArrayList<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        return "/" + String.join("/", stack);
    }

    /** 系统目录：只读，界面与终端都不允许删除或重命名。 */
    public static final String SYSTEM_DIR = "/系统";

    /** 根目录与系统目录（含其下全部内容）受保护。 */
    public static boolean isProtected(String absolutePath) {
        String path = normalize("/", absolutePath);
        return path.equals("/") || path.equals(SYSTEM_DIR) || path.startsWith(SYSTEM_DIR + "/");
    }

    public static String parent(String path) {
        if (path.equals("/") || path.isEmpty()) {
            return "/";
        }
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    public static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** 查找路径对应的节点；不存在返回 null。 */
    public Node find(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty() || absolutePath.equals("/")) {
            return this.root;
        }
        Node current = this.root;
        for (String part : absolutePath.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            current = current.children.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** 逐级创建目录，返回完整路径；路径冲突（同名文件）时返回 null。 */
    public String mkdirs(String absolutePath) {
        String path = normalize("/", absolutePath);
        if (path.equals("/")) {
            return "/";
        }
        Node current = this.root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            Node child = current.children.get(part);
            if (child == null) {
                child = new Node(part, true);
                current.children.put(part, child);
            } else if (!child.dir) {
                return null;
            }
            current = child;
        }
        return path;
    }

    /** 写入文本文件，自动创建父目录。父路径是文件时返回 false。 */
    public boolean write(String absolutePath, String content) {
        Node node = this.fileNode(absolutePath);
        if (node == null) {
            return false;
        }
        node.content = content == null ? "" : content;
        node.mime = "text/plain";
        node.media = "";
        node.size = node.content.getBytes(StandardCharsets.UTF_8).length;
        return true;
    }

    /**
     * 写入二进制媒体（P2V 导入、照相机照片、视频录制都走这里）。
     *
     * <p>数据按 SHA-256 去重后存进媒体表，节点只保留散列；这样快照与同步
     * 报文里永远不会夹带图片本身，客户端需要预览时再单独拉取。</p>
     *
     * @return 完整路径；路径非法或父目录是文件时返回 null
     */
    public String writeMedia(String absolutePath, String mime, byte[] data) {
        if (data == null) {
            return null;
        }
        String path = normalize("/", absolutePath);
        if (path.equals("/")) {
            return null;
        }
        Node node = this.fileNode(path);
        if (node == null) {
            return null;
        }
        String hash = sha256(data);
        if (mime != null && mime.startsWith("text/")) {
            // 文本类导入仍然写成普通文本文件，记事本和终端都能直接读写。
            return this.write(path, new String(data, StandardCharsets.UTF_8)) ? path : null;
        }
        this.media.putIfAbsent(hash, new Media(mime, data));
        node.content = "";
        node.mime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
        node.media = hash;
        node.size = data.length;
        return path;
    }

    private Node fileNode(String absolutePath) {
        String path = normalize("/", absolutePath);
        if (path.equals("/")) {
            return null;
        }
        Node parent = this.find(parent(path));
        if (parent == null) {
            if (this.mkdirs(parent(path)) == null) {
                return null;
            }
            parent = this.find(parent(path));
        }
        if (parent == null || !parent.dir) {
            return null;
        }
        String name = fileName(path);
        Node node = parent.children.get(name);
        if (node != null && node.dir) {
            return null;
        }
        if (node == null) {
            node = new Node(name, false);
            parent.children.put(name, node);
        }
        return node;
    }

    public String read(String absolutePath) {
        Node node = this.find(normalize("/", absolutePath));
        if (node == null || node.dir) {
            return null;
        }
        if (node.isBinary()) {
            return null;
        }
        return node.content;
    }

    /** 读取文件原始字节：文本转 UTF-8，二进制直接取媒体表。 */
    public byte[] readBytes(String absolutePath) {
        Node node = this.find(normalize("/", absolutePath));
        if (node == null || node.dir) {
            return null;
        }
        if (node.isBinary()) {
            Media blob = this.media.get(node.media);
            return blob == null ? null : blob.data;
        }
        return (node.content == null ? "" : node.content).getBytes(StandardCharsets.UTF_8);
    }

    public String mimeOf(String absolutePath) {
        Node node = this.find(normalize("/", absolutePath));
        if (node == null || node.dir) {
            return "";
        }
        return node.mime == null ? "" : node.mime;
    }

    public long sizeOf(String absolutePath) {
        Node node = this.find(normalize("/", absolutePath));
        if (node == null || node.dir) {
            return 0L;
        }
        if (node.size > 0L) {
            return node.size;
        }
        return node.content == null ? 0L : node.content.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 媒体表：保存时写进方块实体的独立字节数组。 */
    public Map<String, Media> media() {
        return this.media;
    }

    public boolean hasMedia(String hash) {
        return hash != null && this.media.containsKey(hash);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 删除文件或整棵目录；根目录不可删除。 */
    public boolean delete(String absolutePath) {
        String path = normalize("/", absolutePath);
        if (path.equals("/")) {
            return false;
        }
        Node parent = this.find(parent(path));
        boolean removed = parent != null && parent.children.remove(fileName(path)) != null;
        if (removed) {
            this.pruneMedia();
        }
        return removed;
    }

    /** 删除文件后释放不再被任何节点引用的媒体数据。 */
    private void pruneMedia() {
        List<String> referenced = new ArrayList<>();
        collectMedia(this.root, referenced);
        this.media.keySet().removeIf(hash -> !referenced.contains(hash));
    }

    private static void collectMedia(Node node, List<String> out) {
        if (!node.dir && node.isBinary()) {
            out.add(node.media);
        }
        for (Node child : node.children.values()) {
            collectMedia(child, out);
        }
    }

    /** 重命名或移动；目标已存在时失败，避免悄悄覆盖。 */
    public boolean move(String from, String to) {
        String source = normalize("/", from);
        String target = normalize("/", to);
        Node node = this.find(source);
        if (node == null || source.equals("/") || this.find(target) != null) {
            return false;
        }
        Node targetParent = this.find(parent(target));
        if (targetParent == null || !targetParent.dir) {
            return false;
        }
        Node sourceParent = this.find(parent(source));
        if (sourceParent == null) {
            return false;
        }
        sourceParent.children.remove(fileName(source));
        node.name = fileName(target);
        targetParent.children.put(node.name, node);
        return true;
    }

    /** 列出目录内容，目录在前、名称排序，便于阅读。 */
    public List<Node> list(String absolutePath) {
        Node node = this.find(normalize("/", absolutePath));
        List<Node> result = new ArrayList<>();
        if (node == null || !node.dir) {
            return result;
        }
        node.children.values().stream()
                .sorted((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareTo(b.name))
                .forEach(result::add);
        return result;
    }

    /** 只含元数据的快照：二进制文件记录散列与大小，绝不内嵌图片本身。 */
    public JsonObject toJson() {
        return nodeToJson(this.root);
    }

    private static JsonObject nodeToJson(Node node) {
        JsonObject json = new JsonObject();
        json.addProperty("name", node.name);
        json.addProperty("dir", node.dir);
        if (node.dir) {
            JsonArray children = new JsonArray();
            for (Node child : node.children.values()) {
                children.add(nodeToJson(child));
            }
            json.add("children", children);
        } else {
            json.addProperty("mime", node.mime == null ? "" : node.mime);
            if (node.isBinary()) {
                json.addProperty("media", node.media);
                json.addProperty("size", node.size);
            } else {
                json.addProperty("content", node.content);
                json.addProperty("size", node.size);
            }
        }
        return json;
    }

    public static VirtualFileSystem fromJson(JsonObject json) {
        VirtualFileSystem fs = new VirtualFileSystem();
        if (json != null) {
            readNode(json, fs.root);
        }
        return fs;
    }

    private static void readNode(JsonObject json, Node node) {
        node.dir = !json.has("dir") || json.get("dir").getAsBoolean();
        if (node.dir) {
            if (json.has("children")) {
                for (var element : json.getAsJsonArray("children")) {
                    JsonObject childJson = element.getAsJsonObject();
                    Node child = new Node(childJson.has("name") ? childJson.get("name").getAsString() : "?",
                            !childJson.has("dir") || childJson.get("dir").getAsBoolean());
                    readNode(childJson, child);
                    node.children.put(child.name, child);
                }
            }
        } else {
            node.mime = json.has("mime") ? json.get("mime").getAsString() : "";
            node.media = json.has("media") ? json.get("media").getAsString() : "";
            node.size = json.has("size") ? json.get("size").getAsLong() : 0L;
            if (node.isBinary()) {
                node.content = "";
            } else {
                node.content = json.has("content") ? json.get("content").getAsString() : "";
                if (node.size == 0L) {
                    node.size = node.content.getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
    }

    /** 媒体二进制打包：写入方块实体或物品时使用，避开 JSON 字符串的长度上限。 */
    public byte[] mediaBundle() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(this.media.size());
            for (Map.Entry<String, Media> entry : this.media.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue().mime);
                out.writeInt(entry.getValue().data.length);
                out.write(entry.getValue().data);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    public void loadMediaBundle(byte[] bundle) {
        if (bundle == null || bundle.length < 4) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bundle))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String hash = in.readUTF();
                String mime = in.readUTF();
                int length = in.readInt();
                if (length < 0 || length > 256 * 1024 * 1024) {
                    return;
                }
                byte[] data = new byte[length];
                in.readFully(data);
                this.media.put(hash, new Media(mime, data));
            }
        } catch (IOException ignored) {
            // 损坏的媒体包直接忽略，文件仍保留元数据，界面会显示为不可预览。
        }
    }
}
