# 哈基坤的智能设备 · Hajikun Smart Devices

把台式电脑、笔记本电脑、智能电视与照相机带进 Minecraft 26.3 —— 而且它们不是会发光的装饰方块，
是真正能用的设备：电脑里跑着一台**存档内持久化的独立虚拟机**，有自己的文件系统、终端、编译器，
以及一个能打开真实网页的浏览器。

[English](#english) · [合成](#合成--全部配方) · [构建](#从源码构建) · [隐私与安全](#隐私与安全) · [许可](#许可与第三方声明)

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

## 合成 · 全部配方

七件设备都有工作台合成配方，配方书的归类是：**电脑与电视在「红石」页签，照相机与三脚架在「装备」页签**。
材料按现实职责选型，看懂这六个角色就能记住全部配方：

| 材料 | 在设备里承担什么 |
| --- | --- |
| 玻璃 | 屏幕面板、照相机镜头 —— 一切「透光显示」的部分 |
| 黑曜石 | 机箱外壳与底座 —— 原版最硬的常规方块，呼应「保护存档里那台虚拟机不被苦力怕炸掉」 |
| 铁锭 | 骨架、转轴、云台等结构件 |
| 红石 | 电路与信号 |
| 金锭 | 处理器与高精度触点（沿用原版「金 + 红石 = 时钟 / 比较器」的语义） |
| 木棍 | 三脚架的三条腿 |

下面每张表就是一张 3×3 工作台：**行 = 上/中/下三排，列 = 左/中/右三格**，「空」表示该格不放东西。

### 台式电脑

| 工作台 | 左 | 中 | 右 |
| --- | :-: | :-: | :-: |
| **上排** | 玻璃 | 玻璃 | 玻璃 |
| **中排** | 黑曜石 | 金锭 | 黑曜石 |
| **下排** | 红石 | 铁锭 | 红石 |

> 玻璃 ×3 + 黑曜石 ×2 + 金锭 ×1 + 红石 ×2 + 铁锭 ×1 —— 三格玻璃是那块大显示器，中间的金锭是 CPU，
> 两侧黑曜石是立起来的机箱，下排红石 + 铁锭是主板与键盘底座。

### 笔记本电脑

| 工作台 | 左 | 中 | 右 |
| --- | :-: | :-: | :-: |
| **上排** | 玻璃 | 金锭 | 玻璃 |
| **中排** | 铁锭 | 红石 | 铁锭 |
| **下排** | 黑曜石 | 红石 | 黑曜石 |

> 玻璃 ×2 + 金锭 ×1 + 铁锭 ×2 + 红石 ×2 + 黑曜石 ×2 —— 和台式机同样是 9 件材料，但屏缩成两格，
> 金锭挪到上排与屏同层（SoC 焊在屏幕总成上），骨架由 1 个铁锭加倍到 2 个，转轴位置留红石走线，
> 底壳两角是黑曜石护角。整机从「一块大屏 + 一个机箱」变成「一块屏扣在机身上」的合盖造型，体现薄、硬、便携。

### 智能电视 2×1（入门款）

| 工作台 | 左 | 右 |
| --- | :-: | :-: |
| **上排** | 玻璃 | 玻璃 |
| **下排** | 红石 | 铁锭 |

> 玻璃 ×2 + 红石 ×1 + 铁锭 ×1 —— 唯一的 2×2 配方，4 件材料就能做出第一块屏幕，是三档电视里唯一
> 不需要前置产物的入门款。

### 智能电视 5×3（由 2×1 升级）

| 工作台 | 左 | 中 | 右 |
| --- | :-: | :-: | :-: |
| **上排** | 玻璃 | 玻璃 | 玻璃 |
| **中排** | 玻璃 | **电视 2×1** | 玻璃 |
| **下排** | 铁锭 | 铁锭 | 铁锭 |

> 玻璃 ×5 + 智能电视 2×1 ×1 + 铁锭 ×3 —— 把小屏包进新一圈玻璃，再用铁锭做底座。

### 智能电视 16×9（由 5×3 升级）

| 工作台 | 左 | 中 | 右 |
| --- | :-: | :-: | :-: |
| **上排** | 玻璃 | 玻璃 | 玻璃 |
| **中排** | 玻璃 | **电视 5×3** | 玻璃 |
| **下排** | 黑曜石 | 黑曜石 | 黑曜石 |

> 玻璃 ×5 + 智能电视 5×3 ×1 + 黑曜石 ×3 —— 升级节奏完全一致：每档都是「玻璃 ×5 + 结构件 ×3」，
> 只是底座从铁锭换成黑曜石，尺寸越大越难炸。三档累计：2×1 = 玻璃2 红石1 铁1；
> 5×3 = 玻璃7 红石1 铁4；16×9 = 玻璃12 红石1 铁4 黑曜石3。

### 照相机

| 工作台 | 左 | 中 | 右 |
| --- | :-: | :-: | :-: |
| **上排** | 铁锭 | 铁锭 | 铁锭 |
| **中排** | 金锭 | 玻璃 | 金锭 |
| **下排** | 空 | 红石 | 空 |

> 铁锭 ×3 + 玻璃 ×1 + 金锭 ×2 + 红石 ×1 —— 上排是金属顶盖，中排那格玻璃就是镜头、
> 两侧金锭是触点与镜圈，下排红石是快门。参考原版时钟（金锭 ×4 + 红石）的价位，属于中等成本。

### 三脚架

| 工作台 | 左 | 中 | 右 |
| --- | :-: | :-: | :-: |
| **上排** | 空 | 铁锭 | 空 |
| **中排** | 木棍 | 空 | 木棍 |
| **下排** | 空 | 木棍 | 空 |

> 铁锭 ×1 + 木棍 ×3 —— 中间铁锭是快装云台，三条木棍就是正面看过去的三条腿。
> 它只是配件，所以和入门电视一样只要 4 件材料，随时能在地上补一个。

所有物品同时都能在创造分栏 **「哈基坤的智能设备」** 里直接拿取；配方只决定生存模式下的获取成本，
不影响功能。已经放置过的设备被拆下来时**必定完整掉落**，物品上带着虚拟机的存档数据，重新放下去还是原来那台机器。

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

**Crafting** (top → bottom row, left → right; `-` = empty slot; full 3×3 grids are in the section above):

| Result | Grid | Ingredients |
| --- | --- | --- |
| Desktop PC | `GGG` / `OAO` / `RIR` | 3 Glass, 2 Obsidian, 1 Gold Ingot, 2 Redstone, 1 Iron Ingot |
| Laptop | `GAG` / `IRI` / `ORO` | 2 Glass, 1 Gold Ingot, 2 Iron Ingot, 2 Redstone, 2 Obsidian |
| Smart TV 2×1 | `GG` / `RI` | 2 Glass, 1 Redstone, 1 Iron Ingot |
| Smart TV 5×3 | `GGG` / `GTG` / `III` | 5 Glass, 1 Smart TV 2×1, 3 Iron Ingot |
| Smart TV 16×9 | `GGG` / `GTG` / `OOO` | 5 Glass, 1 Smart TV 5×3, 3 Obsidian |
| Camera | `III` / `AGA` / `-R-` | 3 Iron Ingot, 1 Glass, 2 Gold Ingot, 1 Redstone |
| Tripod | `-I-` / `S-S` / `-S-` | 1 Iron Ingot, 3 Stick |

Each TV tier costs the same shape: five new glass panes plus three frame pieces, with the frame material
upgrading from iron to obsidian. Computers and TVs file under the **Redstone** recipe-book tab,
the camera and tripod under **Equipment**.

No secrets are committed: model API keys default to empty strings and both the keys and browser cookies
live only in the game instance's runtime `config/hajikun/` directory, which is git-ignored.

Licensed under MIT, © 2026 啊坤. JavaFX (GPL-2.0 w/ Classpath Exception), Fabric Loader / Fabric API
(Apache-2.0) keep their own terms. Minecraft is a trademark of Mojang Studios / Microsoft; this is an
unofficial fan project.
