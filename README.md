# 哈基坤的智能设备 · Hajikun Smart Devices

把台式电脑、笔记本电脑、智能电视与照相机带进 Minecraft 26.3 —— 而且它们不是会发光的装饰方块，
是真正能用的设备：电脑里跑着一台**存档内持久化的独立虚拟机**，有自己的文件系统、终端、编译器，
以及一个能打开真实网页的浏览器。

[English](#english) · [构建](#从源码构建) · [隐私与安全](#隐私与安全) · [许可](#许可与第三方声明)

---

## 这台电脑里到底有什么

| 设备 | 里面装了什么 |
| --- | --- |
| 台式电脑 · 笔记本电脑 | 首次放置时给设备命名，并分配一个**不可更改的世界唯一 MAC 地址**。此后它的硬盘、文档、登录状态全部随存档保存，拆掉重装也还在。 |
| 桌面系统 | 窗口化管理器：最小化 / 最大化 / 关闭、任意位置终端、准确 `cwd` 的文件管理器（双击打开、右键菜单、重命名、删除）。 |
| 浏览器 | 三种内核可选：游戏进程内渲染的 **JavaFX WebKit**、外部 **Electron/Chromium**，以及一套内置虚拟互联网。能真实联网、保存 Cookie、按路径定位、抓帧贴到虚拟机屏幕上。 |
| JavaIDE | 全屏工作区 + 编辑器 + 终端，配套一个**自研的迷你 JDK**：`javac` 与 `java` 都在模组内实现（词法 / 语法解析见 `JavaLexer`、`JavaParser`，字节码执行见 `JavaRunner`），以目录为包的类路径，可直接在 Minecraft 里写 Java 并跑起来。 |
| 智能体 | IDE 内嵌 ReAct 智能体面板，可选本地 **Ollama** 或阿里云百炼 **Qwen**（OpenAI 兼容接口），支持会话、上下文压缩与图片输入。模型地址与 Key 由你在设置里填写。 |
| 蓝牙 | 控制面板可开关、扫描、配对，多台电脑之间互传文件，并把画面投屏到电视。 |
| 智能电视 | 2×1、5×3、16×9 三种尺寸，多方块拼接时屏幕连续无边框网格；支持聚焦正视全屏与实时投屏画面。 |
| 照相机 + 三脚架 | 右键进入拍摄模式（手持相机自动隐藏，不挡视野），自由视角、变焦平滑补帧、物理补光（原生动态光源而非后处理提亮），无损 / 低损照片与录像，胶卷列表与媒体查看器支持缩放平移与播放控制。 |
| P2V 导入 | 把现实电脑里的文件导入虚拟机，并在内置媒体查看器里播放。 |

## 合成

| 产物 | 配方 |
| --- | --- |
| 台式电脑 | 黑曜石 ×8 + 红石 ×1 |
| 笔记本电脑 | 黑曜石 ×6 + 红石 ×1 |
| 智能电视 2×1 | 玻璃 ×2 + 红石 ×1 + 铁锭 ×1 |
| 智能电视 5×3 | 由 2×1 电视 + 玻璃 ×4 + 铁锭 ×3 升级 |
| 智能电视 16×9 | 由 5×3 电视 + 黑曜石 ×3 + 铁锭 ×3 升级 |

所有物品都在创造分栏 **「哈基坤的智能设备」** 里。

## 运行环境

- Minecraft Java Edition **26.3**
- Fabric Loader **≥ 0.19.5**，Fabric API **0.163.0+26.3**（或更高）
- Java **25**
- 内置浏览器内核随包附带的是 `javafx-*-25.0.5-win.jar`，因此**开箱可用的浏览器仅限 Windows**；其他系统仍可正常游玩其余功能，网页内核需自行替换 `libs/javafx/` 里对应平台的构件。

## 从源码构建

```bash
# 需要 JDK 25
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

产物在 `build/libs/hajikun-smart-mod-0.1.0.jar`（已把 JavaFX 的类与原生库直接摊进模组 jar，
所以不需要系统安装 JavaFX，也不会启动额外的浏览器进程）。把它丢进 `.minecraft/mods/` 即可，
记得同目录放一份 Fabric API。

首次构建会联网下载 Minecraft 与 Loom 构件；`gradle.properties` 里已经配好 BMCLAPI 镜像，
国内网络可直接 `./gradlew build --offline`（前提是本机 loom 缓存已存在）。

## 隐私与安全

- **API Key 不入库**：仓库里 `IdeConfig.qwenKey` 的默认值是空字符串，百炼 / DashScope 的 Key 只存在你**游戏实例**的
  `config/hajikun/` 运行期目录里，该目录整体在 `.gitignore` 内，不会被提交。
- **浏览器 Cookie 不入库**：WebKit 的 `cookies.txt`、Electron 的临时目录都写在 `run/config/hajikun/` 下，
  同样已被忽略；仓库中没有任何会话、凭据、`.env`、私钥文件。
- 智能体只把对话发往你自己在设置里填写的端点（默认本地 `http://127.0.0.1:11434`）。
- 若你二次分发这个模组，请把运行期 `config/hajikun/` 目录一并清空，以免带着自己的 Key 与 Cookie 发给别人。

## 已知限制

- 浏览器网页内核依赖 JavaFX（Windows 构件）或外部 Electron；两者都是可选路径，缺任一不影响其余设备功能。
- 三脚架的定点拍摄与胶卷列表仍在完善中。
- 这是一个个人向的玩法模组，没有做服务端多版本兼容矩阵，也没有发布到 Modrinth / CurseForge。

## 许可与第三方声明

本项目采用 **MIT License**，详见 [LICENSE](LICENSE)。版权所有 © 2026 啊坤。

随仓库附带或依赖的第三方组件各自保留原有许可：

- **OpenJFX / JavaFX 25.0.5**（`libs/javafx/*.jar`，GPL-2.0 with Classpath Exception）
- **Fabric Loader** 与 **Fabric API**（Apache-2.0）
- **Minecraft** 与 **Java** 分别是 Mojang Studios / Microsoft 与 Oracle 的商标与版权作品；
  本项目是非官方的粉丝向模组，未获任何官方授权或背书。

---

<a name="english"></a>

## English

A Minecraft **26.3 / Fabric** mod that puts working electronics into the world: desktop PCs, laptops,
multi-size smart TVs, a camera and a tripod. Each computer hosts a **persistent in-save virtual machine**
with its own filesystem, windows, terminal and a real, networked browser. It ships a from-scratch
**mini JDK** (`javac` and `java` implemented inside the mod), a full-screen Java IDE with an embedded
ReAct agent that can talk to local Ollama or Alibaba Bailian Qwen, Bluetooth pairing with file transfer
and screen casting, and a shooting mode with lossless photos, video, zoom frame interpolation and
native dynamic-light fill lighting.

Requirements: Minecraft 26.3, Fabric Loader ≥ 0.19.5, Fabric API, Java 25.
Build with `./gradlew build`; the jar lands in `build/libs/` with JavaFX classes and native libraries
already embedded.

No secrets are committed: model API keys default to empty strings and both the keys and browser cookies
live only in the game instance's runtime `config/hajikun/` directory, which is git-ignored.

Licensed under MIT, © 2026 啊坤. JavaFX (GPL-2.0 w/ Classpath Exception), Fabric Loader / Fabric API
(Apache-2.0) keep their own terms. Minecraft is a trademark of Mojang Studios / Microsoft; this is an
unofficial fan project.
