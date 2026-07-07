# LiveHelper 开发路线文档

> 目标：从零构建一个 Fabric 1.20.1 (Mojang mappings) 的 Minecraft 多机位直播辅助 Mod
> 核心功能：多视角渲染 → Spout2 推流到 OBS + Web 可视化管理界面

---

## 📖 文档索引

| 文档 | 内容 |
|---|---|
| **DEV_ROADMAP.md**（本文件） | 开发路线、分阶段计划、验收标准、测试流程 |

## 目录

1. [项目概述](#1-项目概述)
2. [技术选型](#2-技术选型)
3. [项目结构](#3-项目结构)
4. [Phase 1：项目脚手架与构建系统](#4-phase-1项目脚手架与构建系统)
5. [Phase 2：数据模型与持久化](#5-phase-2数据模型与持久化)
6. [Phase 3：运动模板引擎](#6-phase-3运动模板引擎)
7. [Phase 4：播放引擎 (PlaybackEngine)](#7-phase-4播放引擎-playbackengine)
8. [Phase 5：渲染管线与 Spout 推流](#8-phase-5渲染管线与-spout-推流)
9. [Phase 6：Mixin 注入层](#9-phase-6mixin-注入层)
10. [Phase 7：嵌入式 Web 服务器与 API](#10-phase-7嵌入式-web-服务器与-api)
11. [Phase 8：Web 前端 UI](#11-phase-8web-前端-ui)
12. [验收标准](#12-验收标准)
13. [测试流程](#13-测试流程)
14. [附录：关键参考代码](#14-附录关键参考代码)

---

## 1. 项目概述

### 1.1 定位

一个 Fabric 客户端 Mod，允许直播主在 Minecraft 中创建**多机位虚拟摄像机**，将不同角度的画面通过 **Spout2** 推送到 OBS Studio 等直播软件，并通过 **Web 界面**可视化编排镜头片段（Clip）和播放列表（Manager）。

### 1.2 核心功能

| 功能 | 说明 |
|---|---|
| **多机位渲染** | 同时运行多个摄像机流，每个流渲染到独立的离屏 FBO |
| **Spout2 推流** | 每帧渲染结果通过 Spout2 协议发送到 OBS，每个流一个 Spout Sender |
| **Clip（片段）** | 一个镜头片段，包含时长、运动模板（如静态/环绕/推拉）和参数 |
| **Manager（编排）** | Clip 的有序播放列表，定义每个 Clip 何时开始、持续多久 |
| **运动模板** | 内置多种摄像机运动算法（STATIC/ORBIT/DOLLY/TRUCK 等），用户选择模板 + 调参 |
| **Web GUI** | 基于浏览器的可视化编排界面，创建 Clip、排序 Manager、启停直播流 |
| **自定义帧调度** | 替换 Minecraft 原生帧循环以获得精确的摄像头帧率控制 |

### 1.3 非功能需求

- 仅支持 **Windows 10+**（Spout2 限制）
- 单线程安全（所有渲染操作在 Minecraft 主线程执行）
- Manager 停止时自动清理离屏 FBO 和 Spout Sender
- Web UI 通过 Mod 内嵌的 HTTP 服务器提供服务，无需额外安装

---

## 2. 技术选型

### 2.1 Mod 侧（Java 17）

| 技术 | 版本 | 用途 |
|---|---|---|
| **Minecraft** | 1.20.1 | 游戏版本 |
| **Fabric Loader** | ≥0.15.0 | Mod 加载器 |
| **Fabric API** | 0.92.0+1.20.1 | Fabric 事件/API |
| **Mojang Mappings** | (Loom 内置) | 官方反混淆映射 |
| **Java** | 17 | 语言版本 |
| **Gradle** | 8.x | 构建工具 |
| **Fabric Loom** | 1.6+ | Gradle 插件 |
| **Mixin** | 0.13+ | 字节码注入 |
| **Access Widener** | — | 开放私有字段/方法 |
| **Gson** | 2.10+ | JSON 序列化 (随 Minecraft 自带) |
| **JNA** | 5.14+ | Spout DLL 本地绑定 |
| **OSHI** | 6.4+ | 平台检测 (内含 JNA) |
| **JOML** | — | 矩阵/四元数运算（Minecraft 自带） |

### 2.2 Web UI 侧

- **零构建工具链**——单个 `index.html` + 内联 CSS/JS，或至多 3 个文件
- 使用 **Vanilla JS**，无 React/Vue/Svelte 依赖
- HTTP API 通过 `fetch()` 调用
- CSS 使用简单的 flex/grid 布局，无 Tailwind 等框架
- **不引入 npm/Webpack/Vite**

> 选择理由：Web UI 功能简单（列表 + 表单 + 按钮），不需要框架带来的复杂度和构建步骤。内嵌在 Mod jar 中作为静态资源，零外部依赖。

### 2.3 关键决策说明

| 决策项 | 选择 | 理由 |
|---|---|---|
| **HTTP 服务器** | JDK 内置 `com.sun.net.httpserver.HttpServer` | 零依赖，API 端点 < 15 个，Jooby/Netty 太重 |
| **Spout 本地绑定** | JNA（非 FFM API） | Java 17 不支持 FFM，JNA 最简兼容方案 |
| **持久化** | `config/livehelper/` 下 2 个 JSON 文件 | 取代 5 桶异步文件存储，显著简化 |
| **Mixin** | 标准 Mixin 注解 | 无需 MixinExtras，减少依赖 |
| **ScopedValue** | 替换为 ThreadLocal | Java 17 不支持 ScopedValue |
| **Future API** | 替换 `future.state()`/`resultNow()` | Java 17 不支持，用 `isDone()` + `get()` |
| **Math.clamp** | 替换为手动 `Math.max/min` | Java 17 不支持 Math.clamp |
| **Thread.ofPlatform** | 替换为 `new Thread()` + `setDaemon()` | Java 17 不支持 |

---

## 3. 项目结构

```
livehelper/
├── build.gradle                        # Fabric Loom 构建脚本
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/
│
├── src/main/
│   ├── java/net/example/livehelper/    # 包名可自定义
│   │   ├── LiveHelperMod.java          # @Mod 入口 + ClientModInitializer
│   │   │
│   │   ├── model/                      # 数据模型
│   │   │   ├── Clip.java               # 片段
│   │   │   ├── Manager.java            # 编排
│   │   │   └── FrameCommand.java       # 渲染指令(DTO)
│   │   │
│   │   ├── storage/                    # 持久化
│   │   │   └── StorageManager.java     # JSON 文件读写
│   │   │
│   │   ├── scheduler/                  # 帧调度
│   │   │   └── MainScheduler.java      # 主循环替代
│   │   │
│   │   ├── render/                     # 渲染管线
│   │   │   ├── StreamManager.java      # 多流生命周期管理
│   │   │   ├── StreamInstance.java     # 单流渲染循环
│   │   │   └── CameraSetup.java        # 摄像机反射控制
│   │   │
│   │   ├── engine/                     # 播放引擎
│   │   │   ├── PlaybackEngine.java     # 时间线播放
│   │   │   └── templates/              # 运动模板
│   │   │       ├── MotionTemplate.java     # 接口
│   │   │       ├── StaticTemplate.java     # 固定机位
│   │   │       ├── OrbitTemplate.java      # 环绕
│   │   │       ├── DollyTemplate.java      # 推拉
│   │   │       ├── TruckTemplate.java      # 横移
│   │   │       ├── PedestalTemplate.java   # 升降
│   │   │       ├── PanTiltTemplate.java    # 摇镜/俯仰
│   │   │       └── PathTemplate.java       # 关键帧路径
│   │   │
│   │   ├── spout/                      # Spout2 集成
│   │   │   ├── SpoutBinding.java       # JNA DLL 接口定义
│   │   │   └── SpoutSender.java        # FBO 发送封装
│   │   │
│   │   ├── server/                     # HTTP API 服务器
│   │   │   └── ApiServer.java          # HttpServer + 路由 + Handler
│   │   │
│   │   └── mixin/                      # Mixin 注入
│   │       ├── MinecraftMixin.java     # 替换主循环
│   │       └── GameRendererMixin.java  # 压制 GUI/手部
│   │
│   ├── resources/
│   │   ├── fabric.mod.json
│   │   ├── livehelper.accesswidener
│   │   ├── livehelper.mixins.json
│   │   └── assets/livehelper/
│   │       ├── libSpoutBinding.dll     # Spout2 绑定 DLL
│   │       └── web/                    # Web UI 静态文件
│   │           ├── index.html
│   │           ├── app.js
│   │           └── style.css
│   │
│   └── ...
```

---

## 4. Phase 1：项目脚手架与构建系统

### 4.1 目标

搭建可编译、可运行的 Fabric 1.20.1 空壳 Mod，确认开发环境正常。

### 4.2 技术要求

#### 4.2.1 `settings.gradle`

```groovy
pluginManagement {
    repositories {
        maven { url "https://maven.fabricmc.net/" }
        mavenCentral()
        gradlePluginPortal()
    }
}
```

#### 4.2.2 `build.gradle`

```groovy
plugins {
    id 'fabric-loom' version '1.6-SNAPSHOT'
    id 'java-library'
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

dependencies {
    minecraft "com.mojang:minecraft:1.20.1"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:0.15.11"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.92.0+1.20.1"

    // Spout native binding
    include implementation("net.java.dev.jna:jna:5.14.0")
    // OSHI (platform detection, already includes JNA)
    implementation("com.github.oshi:oshi-core:6.4.0")
}
```

#### 4.2.3 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2G
```

#### 4.2.4 `fabric.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "livehelper",
  "version": "1.0.0",
  "name": "LiveHelper",
  "description": "Multi-camera live streaming helper",
  "authors": ["YourName"],
  "environment": "client",
  "entrypoints": {
    "client": ["net.example.livehelper.LiveHelperMod"]
  },
  "mixins": ["livehelper.mixins.json"],
  "accessWidener": "livehelper.accesswidener",
  "depends": {
    "fabricloader": ">=0.15.0",
    "minecraft": ">=1.20.1",
    "fabric-api": "*"
  }
}
```

#### 4.2.5 `livehelper.mixins.json`

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "net.example.livehelper.mixin",
  "compatibilityLevel": "JAVA_17",
  "client": [
    "MinecraftMixin",
    "GameRendererMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

#### 4.2.6 `livehelper.accesswidener`

```
accessible class net/minecraft/client/Minecraft field mainRenderTarget Lcom/mojang/blaze3d/pipeline/RenderTarget;
accessible class net/minecraft/client/renderer/GameRenderer field mainCamera Lnet/minecraft/client/Camera;
accessible class com/mojang/blaze3d/pipeline/RenderTarget field fbo I
```

#### 4.2.7 `LiveHelperMod.java`（空壳验证）

```java
package net.example.livehelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class LiveHelperMod implements ClientModInitializer {
    public static final String MODID = "livehelper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("LiveHelper initialized");
    }
}
```

### 4.3 验收标准

- [ ] `gradlew build` 编译成功，生成 jar
- [ ] 运行 `gradlew runClient` 启动 Minecraft 1.20.1
- [ ] 游戏内执行 `/fabric list` 看到 `livehelper`
- [ ] 日志打印 `LiveHelper initialized`

---

## 5. Phase 2：数据模型与持久化

### 5.1 目标

定义 Clip 和 Manager 的数据结构，实现 JSON 文件的读写。

### 5.2 Clip 模型

```java
// Clip.java
public record Clip(
    int id,
    String name,
    long duration,               // 时长(毫秒)
    String template,             // 运动模板名称 (STATIC, ORBIT, DOLLY, ...)
    Map<String, Double> params   // 模板参数
) {}
```

**模板参数约定**（各模板需要哪些 key）：

| 模板 | 必需参数 | 可选参数 | 说明 |
|---|---|---|---|
| `STATIC` | `posX`, `posY`, `posZ`, `rotX`, `rotY`, `rotZ`, `fov` | — | 固定机位 |
| `ORBIT` | `targetX`, `targetY`, `targetZ`, `radius`, `speed` | `startAngle`, `elevation`, `fov` | 环绕旋转 |
| `DOLLY` | `fromX`,`fromY`,`fromZ`, `toX`,`toY`,`toZ` | `easing` (linear/easeInOut), `fov` | 推拉镜头 |
| `TRUCK` | same as DOLLY | same as DOLLY | 横移 |
| `PEDESTAL` | `fromHeight`, `toHeight`, `centerX`, `centerZ` | `easing`, `fov`, `rotX`, `rotY` | 升降 |
| `PAN_TILT` | `startPan`,`endPan`,`startTilt`,`endTilt` | `posX`,`posY`,`posZ`, `fov` | 仅旋转 |
| `PATH` | `keyframes` (JSON string) | `fov` | 多关键帧路径 |

> `keyframes` 格式：`[{t:0, x,y,z, rx,ry,rz}, {t:0.5, ...}, {t:1, ...}]`

### 5.3 Manager 模型

```java
// Manager.java
public record Manager(
    int id,
    String name,
    List<ClipSlot> clips,        // 有序片段列表
    int width,                   // 输出宽度
    int height,                  // 输出高度
    int fps,                     // 目标帧率
    int renderDistance           // 渲染距离(区块)
) {}

public record ClipSlot(
    int clipId,                  // 引用 Clip.id
    long startOffset             // 相对 Manager 起始时间的偏移(毫秒)
) {}
```

**Manager 的 Playback 规则**：
- Clip 按 `clips` 列表顺序 + `startOffset` 计算绝对开始时间
- 每个 Clip 的结束时间 = startOffset + clip.duration
- 当前时间落在哪个 Clip 区间就渲染哪个
- 若时间不在任何区间，画面静止（保留最后一帧）

### 5.4 FrameCommand（渲染指令 DTO）

```java
// FrameCommand.java
public record FrameCommand(
    double x, double y, double z,       // 位置
    float qx, float qy, float qz, float qw,  // 朝向四元数
    float fov                           // 视场角(度)
) {}
```

### 5.5 持久化实现

```java
// StorageManager.java
public class StorageManager {
    private final Path storageDir;

    public StorageManager(Path configDir) {
        this.storageDir = configDir.resolve("livehelper");
        Files.createDirectories(storageDir);
    }

    // 存储结构：
    // config/livehelper/
    //   ├── clips.json       // List<Clip>
    //   └── managers.json    // List<Manager>

    public List<Clip> loadClips() { ... }
    public void saveClips(List<Clip> clips) { ... }
    public List<Manager> loadManagers() { ... }
    public void saveManagers(List<Manager> managers) { ... }
}
```

**实现要求**：
- 使用 Gson 序列化/反序列化
- 写入时先写临时文件再 rename（原子写入）

### 5.6 验收标准

- [ ] 创建 Clip 序列化为 JSON，再反序列化回来字段一致
- [ ] Manager 包含 ClipSlot 列表，序列化/反序列化正确
- [ ] 多次保存不丢数据
- [ ] 文件损坏时有友好错误日志（而非 crash）

---

## 6. Phase 3：运动模板引擎

### 6.1 目标

实现一组内置运动模板，每个模板根据参数和时间进度计算出摄像机的位置和朝向。

### 6.2 接口定义

```java
// MotionTemplate.java
public interface MotionTemplate {
    /**
     * @param params  Clip 中用户配置的参数
     * @param progress 片段进度 [0.0, 1.0)
     * @return 当前帧的摄像机位置和朝向
     */
    FrameCommand evaluate(Map<String, Double> params, float progress);
}
```

### 6.3 各模板实现

#### 6.3.1 StaticTemplate

```java
// 固定机位
FrameCommand evaluate(Map<String, Double> params, float progress) {
    return new FrameCommand(
        params.get("posX"), params.get("posY"), params.get("posZ"),
        eulerToQuat(params.get("rotX"), params.get("rotY"), params.get("rotZ")),
        params.getOrDefault("fov", 70.0).floatValue()
    );
}
```

#### 6.3.2 OrbitTemplate

```java
// 环绕目标点旋转
FrameCommand evaluate(Map<String, Double> params, float progress) {
    double angle = params.getOrDefault("startAngle", 0.0)
                 + progress * 2 * Math.PI * params.getOrDefault("speed", 1.0);
    double radius = params.get("radius");
    double x = params.get("targetX") + radius * Math.cos(angle);
    double z = params.get("targetZ") + radius * Math.sin(angle);
    double y = params.get("targetY") + params.getOrDefault("elevation", 0.0);
    // 朝向始终指向 target 中心点
    Quaternionf rot = lookAt(x, y, z, params.get("targetX"), ...);
    return new FrameCommand(x, y, z, rot, params.getOrDefault("fov", 70.0).floatValue());
}
```

#### 6.3.3 DollyTemplate / TruckTemplate

```java
// 线性插值起点→终点，支持缓动
FrameCommand evaluate(Map<String, Double> params, float progress) {
    float eased = ease(progress, params.getOrDefault("easing", "linear"));
    double x = lerp(params.get("fromX"), params.get("toX"), eased);
    double y = lerp(params.get("fromY"), params.get("toY"), eased);
    double z = lerp(params.get("fromZ"), params.get("toZ"), eased);
    // 朝向 = 运动方向
    Quaternionf rot = lookInDirection(dx, dy, dz);
    return new FrameCommand(x, y, z, rot, ...);
}
```

#### 6.3.4 PanTiltTemplate

```java
// 仅旋转，位置不变
FrameCommand evaluate(Map<String, Double> params, float progress) {
    float pan  = lerp(params.get("startPan"), params.get("endPan"), progress);
    float tilt = lerp(params.get("startTilt"), params.get("endTilt"), progress);
    double x = params.getOrDefault("posX", 0.0);
    double y = params.getOrDefault("posY", 0.0);
    double z = params.getOrDefault("posZ", 0.0);
    return new FrameCommand(x, y, z, eulerToQuat(tilt, pan, 0),
        params.getOrDefault("fov", 70.0).floatValue());
}
```

#### 6.3.5 PathTemplate

```java
// 多关键帧路径插值
FrameCommand evaluate(Map<String, Double> params, float progress) {
    List<Keyframe> kfs = parseKeyframes(params.get("keyframes"));
    // 找到当前进度所在的两个关键帧之间插值
    Keyframe a = ..., b = ...;
    float t = (progress - a.t) / (b.t - a.t);
    double x = lerp(a.x, b.x, t);  // 同理 y, z, rx, ry, rz
    return new FrameCommand(x, y, z, eulerToQuat(rx, ry, rz), fov);
}
```

### 6.4 模板注册

```java
public class MotionTemplates {
    private static final Map<String, MotionTemplate> REGISTRY = new LinkedHashMap<>();

    static {
        register("STATIC",   new StaticTemplate());
        register("ORBIT",    new OrbitTemplate());
        register("DOLLY",    new DollyTemplate());
        register("TRUCK",    new TruckTemplate());
        register("PEDESTAL", new PedestalTemplate());
        register("PAN_TILT", new PanTiltTemplate());
        register("PATH",     new PathTemplate());
    }

    public static MotionTemplate get(String name) { ... }
    public static Set<String> getAvailableTemplates() { ... }
}
```

### 6.5 工具函数

```java
// 欧拉角→四元数 (YZX顺序，匹配Minecraft Camera)
public static Quaternionf eulerToQuat(double rotX, double rotY, double rotZ) {
    // 使用 org.joml.Quaternionf.rotationYXZ()
}

// 朝向目标点
public static Quaternionf lookAt(double fromX, double fromY, double fromZ,
                                  double toX, double toY, double toZ) {
    // 使用 org.joml 的 lookAt + 转四元数
}

// 缓动函数
public static float ease(float t, String type) {
    switch (type) {
        case "linear":    return t;
        case "easeInOut": return t < 0.5 ? 2*t*t : -1 + (4-2*t)*t;
        case "easeIn":    return t * t;
        case "easeOut":   return t * (2 - t);
    }
}
```

### 6.6 验收标准

- [ ] 每个模板调用 `evaluate(params, 0)` 和 `evaluate(params, 0.5)` 和 `evaluate(params, 0.999)` 返回合理的值
- [ ] StaticTemplate 在不同 progress 下返回相同的值
- [ ] OrbitTemplate 返回的轨迹是一个圆形
- [ ] 缓动函数映射正确

---

## 7. Phase 4：播放引擎 (PlaybackEngine)

### 7.1 目标

实现 Manager 的时间线播放逻辑：根据当前运行时长确定当前活跃的 Clip，调用运动模板计算 `FrameCommand`。

### 7.2 核心逻辑

```java
// PlaybackEngine.java
public class PlaybackEngine {
    private final Manager manager;
    private final long startTime;       // System.nanoTime() when started
    private final List<Clip> loadedClips;

    /**
     * 计算当前时刻的渲染指令
     */
    public @Nullable FrameCommand computeFrame() {
        long elapsed = (System.nanoTime() - startTime) / 1_000_000;  // ms
        // 遍历 ClipSlots，找到当前时间对应的 Clip
        for (ClipSlot slot : manager.clips()) {
            long clipStart = slot.startOffset();
            long clipEnd   = clipStart + getClip(slot.clipId()).duration();
            if (elapsed >= clipStart && elapsed < clipEnd) {
                float progress = (float)(elapsed - clipStart)
                               / (float)(clipEnd - clipStart);  // [0, 1)
                Clip clip = getClip(slot.clipId());
                MotionTemplate template = MotionTemplates.get(clip.template());
                return template.evaluate(clip.params(), progress);
            }
        }
        return null;  // 无活跃 Clip，保留上一帧
    }

    public boolean isFinished() { ... }  // 所有 Clip 播放完毕
}
```

### 7.3 播放模式

- **单次播放**：播放完所有 Clip 后自动停止
- **循环播放**：播放完最后一个 Clip 后回到第一个（可选功能，可在 Manager 中添加 `loop: boolean` 字段）

### 7.4 验收标准

- [ ] 给定一个 Manager + 3 个 Clip，按时间顺序依次返回每个 Clip 的 FrameCommand
- [ ] 时间在 Clip 间隙时返回 `null`
- [ ] 所有 Clip 播放完毕后 `isFinished()` 返回 `true`

---

## 8. Phase 5：渲染管线与 Spout 推流

### 8.1 目标

实现离屏多机位渲染，每帧将渲染结果通过 Spout2 发送到 OBS。

### 8.2 StreamManager（多流生命周期）

```java
// StreamManager.java
public class StreamManager {
    private final Map<Integer, StreamInstance> activeStreams = new HashMap<>();

    public void start(int managerId, Manager manager, PlaybackEngine engine) { ... }
    public void stop(int managerId) { ... }
    public boolean isRunning(int managerId) { ... }

    // 在 ClientTickEvent.START_CLIENT_TICK 中调用
    public void tickAll() {
        for (StreamInstance stream : activeStreams.values()) {
            stream.tick();
        }
    }
}
```

### 8.3 StreamInstance（单流渲染循环）

```java
// StreamInstance.java
public class StreamInstance {
    private final Manager config;
    private final PlaybackEngine engine;
    private final MainTarget renderTarget;  // 离屏 FBO
    private final SpoutSender spoutSender;  // Spout 发送器
    private final Camera dummyCamera;       // 反射操纵的 Camera

    // 使用 MainScheduler 按帧率调度
    public void scheduleFrame() {
        MainScheduler.submitTask(nextFrameNs, () -> {
            FrameCommand cmd = engine.computeFrame();
            if (cmd != null) {
                render(cmd);       // 离屏渲染 + Spout 发送
            }
            scheduleFrame();       // 调度下一帧
        });
    }

    private void render(FrameCommand cmd) {
        // 1. 保存当前渲染状态
        RenderTarget prevTarget = minecraft.mainRenderTarget;
        Camera prevCamera = minecraft.gameRenderer.mainCamera;
        boolean prevHideGui = minecraft.options.hideGui;

        // 2. 切换到离屏 FBO + 设置 Camera
        minecraft.mainRenderTarget = renderTarget;
        minecraft.gameRenderer.mainCamera = dummyCamera;
        minecraft.options.hideGui = true;

        // 3. 反射设置 Camera 位置/朝向
        CameraSetup.apply(dummyCamera, cmd);

        // 4. 调用 GameRenderer.render()
        //    注意：1.20.1 的 GameRenderer.render() 签名不同
        //    render(float tickDelta, long startNano, MatrixStack matrices)
        renderInternal();

        // 5. 恢复渲染状态
        minecraft.mainRenderTarget = prevTarget;
        minecraft.gameRenderer.mainCamera = prevCamera;
        minecraft.options.hideGui = prevHideGui;

        // 6. Spout 发送
        spoutSender.sendFrameBufferObject(renderTarget.getColorTextureId(),
                                          renderTarget.width, renderTarget.height);
    }
}
```

### 8.4 CameraSetup（反射控制摄像机）

**关键说明**：使用 `MethodHandles` + `VarHandle` 反射写入 Camera 私有字段。使用 **Mojang mappings**，字段名如下：

| 名称 | 类型 | 用途 |
|---|---|---|
| `Camera.initialized` | `boolean` | 标记是否已初始化 |
| `Camera.setPosition(double,double,double)` | method | 设置位置 |
| `Camera.setRotation(float,float,float)` | method | 设置旋转 (yaw, pitch, roll) |
| `Camera.cachedViewRotMatrix` | `Matrix4f` | 视图矩阵缓存 |
| `Camera.prepareCullFrustum(Matrix4fc,Matrix4f,Vec3)` | method | 裁剪视锥体 |
| `Camera.getViewRotationMatrix(Matrix4f) → Matrix4f` | method | 获取视图旋转矩阵 |
| `Camera.setupPerspective(float,float,float,float,float)` | method | 透视投影 |
| `Camera.depthFar` | `float` | 远平面 |
| `Camera.fov` | `float` | 视场角 |
| `Camera.hudFov` | `float` | HUD 视场角 |

完整实现代码见 **`DEV_REFERENCE.md#2-camerasetup摄像机反射控制`**。

### 8.5 MainScheduler（自定义帧调度）

- 直接复用原项目 `MainScheduler.java` 的核心逻辑（见参考代码）
- 需要替换：Java 17 不支持的 API
  - `Task record` → OK（Java 16+）
  - `LockSupport.parkNanos()` → OK（Java 5+）
  - `Thread.onSpinWait()` → OK（Java 9+）
- 添加 `hasActiveStreams()` 方法供 Mixin 判断是否激活自定义调度

### 8.6 Spout2 集成（JNA）

#### 8.6.1 SpoutBinding（JNA 接口）

```java
// SpoutBinding.java
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

public interface SpoutBinding extends Library {
    SpoutBinding INSTANCE = Native.load("libSpoutBinding", SpoutBinding.class);

    Pointer spCreateSpout(String name);
    void spReleaseSpout(Pointer spout);
    int spSendFrameBufferObject(Pointer spout, int fbo, int width, int height);
}
```

#### 8.6.2 SpoutSender（封装）

```java
// SpoutSender.java
public class SpoutSender implements AutoCloseable {
    private final Pointer handle;

    public SpoutSender(String name) {
        if (SystemInfo.getCurrentPlatform() != PlatformEnum.WINDOWS) {
            throw new RuntimeException("Spout requires Windows");
        }
        // DLL 从 jar 资源解压到临时目录
        extractDll();
        this.handle = SpoutBinding.INSTANCE.spCreateSpout(name);
    }

    public void send(int fbo, int width, int height) {
        int result = SpoutBinding.INSTANCE.spSendFrameBufferObject(handle, fbo, width, height);
        if (result == 0) throw new RuntimeException("Spout send failed");
    }

    @Override public void close() {
        SpoutBinding.INSTANCE.spReleaseSpout(handle);
    }
}
```

#### 8.6.3 DLL 提取

```java
// 从 jar 的 /assets/livehelper/libSpoutBinding.dll 提取到临时目录
Path dllPath = Files.createTempFile("libSpoutBinding-", ".dll");
try (InputStream is = getClass().getResourceAsStream("/assets/livehelper/libSpoutBinding.dll")) {
    Objects.requireNonNull(is).transferTo(Files.newOutputStream(dllPath));
}
System.load(dllPath.toString());  // JNA 通过 System.load 加载
```

### 8.7 在 1.20.1 中 Spout 的挂钩策略

原项目通过 Mixin 注入 `GlCommandEncoder.presentTexture()`——这个类在 1.20.1 中**不存在**。

**替代方案**：使用 `MainTarget` 的 FBO ID 直接发送

```java
// StreamInstance.render() 中，在 GameRenderer.render() 完成后：
int fboId = renderTarget.getColorTextureId();  // 或 renderTarget.frameBufferObject
spoutSender.send(fboId, renderTarget.width, renderTarget.height);
```

在 1.20.1 Mojmap 中，`RenderTarget` 的 FBO ID 字段为 `fbo`（int）。Access widener 开放 `RenderTarget.fbo` 后可直接读取。

### 8.8 验收标准

- [ ] StreamManager 能同时启动 2 个不同 Manager，各自独立渲染到自己的离屏 FBO
- [ ] Spout 发送成功后 OBS 端能接收到画面
- [ ] 停止 Stream 后 Spout Sender 自动释放
- [ ] 帧率维持在配置的目标帧率 ±5%
- [ ] CameraSetup 反射设置的位置/朝向在渲染结果中正确体现

---

## 9. Phase 6：Mixin 注入层

### 9.1 目标

通过 Mixin 替换 Minecraft 的主循环并压制摄像机机位的 GUI/手部渲染。

### 9.2 MinecraftMixin

**目标**：替换 Minecraft 主循环，使用 MainScheduler 替代原生帧循环

**要点**：
- 1.20.1 的 `Minecraft.run()` 循环内容与 26.1.2 有所不同
- 需要找到合适的注入点：拦截 `runTick()` 调用，转给 `MainScheduler.tick()`
- 不直接全部 @Overwrite，而是 @Redirect 或 @Inject

```java
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Shadow @Final public GameRenderer gameRenderer;

    // 方案 A：Redirect runTick 调用到 MainScheduler
    @Redirect(
        method = "run",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V")
    )
    private void redirectRunTick(Minecraft instance, boolean bl) {
        if (StreamManager.hasActive()) {
            MainScheduler.tick(bl);
        } else {
            instance.runTick(bl);  // 原生路径
        }
    }

    // 方案 B：在 run 循环中注入 MainScheduler 的初始化
    @Inject(method = "run", at = @At("HEAD"))
    private void onRunHead(CallbackInfo ci) {
        MainScheduler.submitTask(System.nanoTime(), ...);
    }
}
```

**需要确认**：在 1.20.1 的 `Minecraft.run()` 中，`runTick()` 的调用位置和方式。通过反编译确认。

### 9.3 GameRendererMixin

**目标**：当正在渲染摄像机帧时，跳过 GUI 渲染和手部渲染

**要点**：
- 1.20.1 的 `GameRenderer.render(float tickDelta, long startNano, MatrixStack matrices)`——**注意参数不同**
- 没有 `extract()`/`extractGui()`/`GameRenderState`，渲染是过程式的
- 需要在 `renderLevel` 后压制 `renderItemInHand`

```java
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    // 如果当前在摄像头帧渲染中，跳过渲染手部
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void skipHandInCameraFrame(...) {
        if (ActiveRenderContext.isActive()) {
            ci.cancel();
        }
    }

    // 如果当前在摄像头帧渲染中，强制隐藏 GUI
    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(...) {
        if (ActiveRenderContext.isActive()) {
            minecraft.options.hideGui = true;
        }
    }
}
```

> **ActiveRenderContext**：用 ThreadLocal 替代原项目的 ScopedValue，标记当前是否在摄像头帧渲染上下文中。

### 9.4 LiveHelperMod 最终版

```java
@Environment(EnvType.CLIENT)
public class LiveHelperMod implements ClientModInitializer {
    public static final String MODID = "livehelper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override
    public void onInitializeClient() {
        // 初始化存储
        StorageManager.init();
        // 初始化 API 服务器
        ApiServer.start();
        // 注册事件
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            StreamManager.INSTANCE.tickAll();
        });
        // 玩家加入时发送 Web UI 链接
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                    "LiveHelper Web UI: http://localhost:23512"));
            }
        });
        LOGGER.info("LiveHelper initialized");
    }
}
```

### 9.5 验收标准

- [ ] 没有活跃 Stream 时，Minecraft 正常运行，无性能影响
- [ ] 有活跃 Stream 时，主循环切换到 MainScheduler
- [ ] 摄像头帧画面中无 HUD/手部显示
- [ ] 所有 Mixin 不与其他 Mod 冲突

---

## 10. Phase 7：嵌入式 Web 服务器与 API

### 10.1 目标

在 Mod 中嵌入一个 HTTP 服务器，提供 REST API 供 Web UI 调用。

### 10.2 服务器启动

```java
// ApiServer.java
public class ApiServer {
    private static HttpServer server;

    public static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(23512), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LiveHelper-API");
            t.setDaemon(true);
            return t;
        }));

        // 注册路由
        server.createContext("/api/clips",       new ClipsHandler());
        server.createContext("/api/managers",    new ManagersHandler());
        server.createContext("/api/managers/",   new ManagerDetailHandler());
        server.createContext("/api/pose",        new PoseHandler());
        server.createContext("/",               new StaticFileHandler("/assets/livehelper/web/"));

        server.start();
    }
}
```

### 10.3 API 端点

#### 10.3.1 Clips

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| `GET` | `/api/clips` | — | `Clip[]` | 获取所有 Clip |
| `POST` | `/api/clips` | `Clip` (无 id) | `{"id": n}` | 创建 Clip，自动分配 id |
| `PUT` | `/api/clips/{id}` | `Clip` | — | 更新 Clip |
| `DELETE` | `/api/clips/{id}` | — | — | 删除 Clip |

#### 10.3.2 Managers

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| `GET` | `/api/managers` | — | `Manager[]` | 获取所有 Manager |
| `POST` | `/api/managers` | `Manager` (无 id) | `{"id": n}` | 创建 Manager |
| `PUT` | `/api/managers/{id}` | `Manager` | — | 更新 Manager |
| `DELETE` | `/api/managers/{id}` | — | — | 删除 Manager |
| `POST` | `/api/managers/{id}/start` | — | — | 启动 Manager 推流 |
| `POST` | `/api/managers/{id}/stop` | — | — | 停止推流 |
| `GET` | `/api/managers/{id}/status` | — | `{"status":"running"\|"stopped"\|"error"}` | 查询状态 |

#### 10.3.3 其他

| 方法 | 路径 | 响应 | 说明 |
|---|---|---|---|
| `GET` | `/api/pose` | `{"x", "y", "z", "qx", "qy", "qz", "qw"}` | 获取当前玩家相机姿态 |
| `GET` | `/api/templates` | `["STATIC", "ORBIT", ...]` | 获取可用的运动模板列表 |

### 10.4 PoseHandler

```java
// 获取当前玩家 Camera 的位置和朝向
public class PoseHandler implements HttpHandler {
    public void handle(HttpExchange exchange) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getCameraEntity() == null) {
            sendJson(exchange, 503, "{\"error\":\"player not ready\"}");
            return;
        }
        Camera camera = client.gameRenderer.mainCamera;
        // 通过 Access Widener 或反射获取内部状态
        JsonObject pose = new JsonObject();
        pose.addProperty("x", camera.position().x);
        pose.addProperty("y", camera.position().y);
        pose.addProperty("z", camera.position().z);
        // 使用 Player.getYRot() / getXRot() 获取朝向，转为四元数
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        var q = net.example.livehelper.util.AngleConvert.toQuaternion(pitch, yaw, 0f);
        pose.addProperty("qx", q.x);
        pose.addProperty("qy", q.y);
        pose.addProperty("qz", q.z);
        pose.addProperty("qw", q.w);
        ...
        sendJson(exchange, 200, pose.toString());
    }
}
```

### 10.5 静态文件服务

```java
public class StaticFileHandler implements HttpHandler {
    private final String resourceBase;  // "/assets/livehelper/web/"

    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        // 从 classpath 读取资源
        InputStream is = getClass().getResourceAsStream(resourceBase + path);
        if (is == null) {
            exchange.sendResponseHeaders(404, 0);
            return;
        }
        // 设置 Content-Type
        String contentType = guessContentType(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, 0);
        is.transferTo(exchange.getResponseBody());
    }
}
```

### 10.6 验收标准

- [ ] 服务器启动在 23512 端口
- [ ] `GET /api/clips` 返回 JSON 数组
- [ ] `POST /api/clips` 创建成功返回 id
- [ ] `POST /api/managers/{id}/start` 启动该 Manager 的流
- [ ] `GET /api/managers/{id}/status` 返回正确状态
- [ ] `GET /api/pose` 返回当前玩家的位置和朝向
- [ ] `GET /` 返回 index.html

---

## 11. Phase 8：Web 前端 UI

### 11.1 目标

提供浏览器中的可视化编排界面，无需安装任何软件（浏览器访问 `http://localhost:23512` 即可）。

### 11.2 UI 布局

```
┌──────────────────────────────────────────────────────┐
│  LiveHelper  ■ 总览  ■ Clips  ■ Managers            │ ← 导航 Tab
├──────────────────────────────────────────────────────┤
│                                                      │
│  [Tab: Clips]                                        │
│  ┌──────────────────────────────────────────────┐    │
│  │  [+ 新建 Clip]                                │    │
│  │                                               │    │
│  │  ┌──── Clip A ──── [编辑] [删除] ──────────┐ │    │
│  │  │  模板: ORBIT | 时长: 5000ms | 参数: ... │ │    │
│  │  └──────────────────────────────────────────┘ │    │
│  │  ┌──── Clip B ──── [编辑] [删除] ──────────┐ │    │
│  │  │  模板: STATIC | 时长: 3000ms | 参数: ... │ │    │
│  │  └──────────────────────────────────────────┘ │    │
│  └──────────────────────────────────────────────┘    │
│                                                      │
│  [Tab: Managers]                                     │
│  ┌──────────────────────────────────────────────┐    │
│  │  [+ 新建 Manager]  [刷新]                    │    │
│  │                                               │    │
│  │  ┌── My Stream ── [启动/停止] [编辑] [删除] ┐│    │
│  │  │  状态: ● 运行中 | FPS: 60 | 1920x1080   ││    │
│  │  │  片段顺序:                               ││    │
│  │  │   1. Clip A (0s~5s)  ▦ 可拖动           ││    │
│  │  │   2. Clip B (5s~8s)  ▦ 可拖动           ││    │
│  │  └──────────────────────────────────────────┘│    │
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

### 11.3 文件结构

```
web/
├── index.html    # 主页面 HTML + 内联 CSS
├── app.js        # 全部 JS 逻辑 (API 调用 + DOM 操作)
└── style.css     # 全部 CSS 样式（可选内联到 index.html）
```

> 为简单起见，所有前端代码可以全部在一个 `index.html` 中（内联 `<style>` 和 `<script>`）。只有确认体积过大时才拆分为 3 个文件。

### 11.4 前端功能清单

#### 11.4.1 导航栏
- 三个 Tab：总览 / Clips / Managers
- 使用 CSS class 切换显示/隐藏对应的 section

#### 11.4.2 总览页
- 当前活跃流列表（名称、状态、帧率、分辨率）
- 一键停止所有流

#### 11.4.3 Clips 管理
- 列表显示所有 Clip（模板名称、时长、参数摘要）
- 新建 Clip：弹出表单（或内联编辑）
  - 名称、时长、模板选择（下拉框）
  - 根据所选模板动态展示参数输入控件
  - 模板切换时，参数输入区联动变化
- 编辑 Clip：同上
- 删除 Clip：确认后删除
- 使用 `fetch()` 调用 REST API

#### 11.4.4 Managers 管理
- 列表显示所有 Manager
- 新建/编辑 Manager：
  - 名称、输出分辨率、帧率、渲染距离
  - 片段排序：从所有 Clip 中选择并排序（简单上移/下移按钮）
- 启动/停止按钮：
  - 启动后轮询状态接口，实时显示运行状态
- 状态显示：绿色圆点=运行中，灰色=已停止，红色=错误

#### 11.4.5 姿态捕获
- Clip 编辑器中可以点击「捕获当前姿态」按钮
- 调用 `GET /api/pose` 获取当前玩家位置/朝向
- 自动填入位置/旋转参数

### 11.5 前端 API 调用封装

```javascript
// app.js
const API = {
    async getClips()        { return fetch('/api/clips').then(r => r.json()); },
    async createClip(data)  { return fetch('/api/clips', {method:'POST', body:JSON.stringify(data), headers:{'Content-Type':'application/json'}}).then(r => r.json()); },
    async updateClip(id, data) { return fetch(`/api/clips/${id}`, {method:'PUT', body:JSON.stringify(data), ...}); },
    async deleteClip(id)    { return fetch(`/api/clips/${id}`, {method:'DELETE'}); },

    async getManagers()         { return fetch('/api/managers').then(r => r.json()); },
    async createManager(data)   { return fetch('/api/managers', {method:'POST', ...}); },
    async updateManager(id, data) { return fetch(`/api/managers/${id}`, {method:'PUT', ...}); },
    async deleteManager(id)     { return fetch(`/api/managers/${id}`, {method:'DELETE'}); },
    async startManager(id)      { return fetch(`/api/managers/${id}/start`, {method:'POST'}); },
    async stopManager(id)       { return fetch(`/api/managers/${id}/stop`, {method:'POST'}); },
    async getManagerStatus(id)  { return fetch(`/api/managers/${id}/status`).then(r => r.json()); },

    async getPose()             { return fetch('/api/pose').then(r => r.json()); },
    async getTemplates()        { return fetch('/api/templates').then(r => r.json()); },
};
```

### 11.6 模板参数表单联动

```javascript
const TEMPLATE_FIELDS = {
    STATIC:   ['posX','posY','posZ', 'rotX','rotY','rotZ', 'fov'],
    ORBIT:    ['targetX','targetY','targetZ', 'radius', 'speed', 'startAngle', 'elevation', 'fov'],
    DOLLY:    ['fromX','fromY','fromZ', 'toX','toY','toZ', 'easing', 'fov'],
    TRUCK:    ['fromX','fromY','fromZ', 'toX','toY','toZ', 'easing', 'fov'],
    PEDESTAL: ['fromHeight','toHeight','centerX','centerZ', 'easing', 'fov', 'rotX','rotY'],
    PAN_TILT: ['startPan','endPan','startTilt','endTilt', 'posX','posY','posZ', 'fov'],
    PATH:     ['keyframes', 'fov'],
};
```

- 模板下拉切换时，`params` 输入区域根据该模板的字段列表动态渲染
- 字段类型约定：
  - 坐标/旋转/角度：`<input type="number" step="0.1">`
  - 缓动类型：`<select>` (linear/easeInOut/easeIn/easeOut)
  - keyframes：`<textarea>` 输入 JSON 字符串

### 11.7 Manager 片段排序

使用 HTML 原生拖拽或简单的上下箭头按钮实现片段排序（无需 dnd-kit）：

```
┌──────────────────────────────────┐
│  [上移] [下移] Clip A (0ms~5000ms) │
│  [上移] [下移] Clip B (5000ms~...)│
│  [+ 添加片段]                     │
└──────────────────────────────────┘
```

JS 逻辑：
```javascript
function moveClipSlot(manager, fromIndex, toIndex) {
    const slot = manager.clips.splice(fromIndex, 1)[0];
    manager.clips.splice(toIndex, 0, slot);
    renderManagerEditor(manager);
}
```

### 11.8 验收标准

- [ ] 浏览器访问 `http://localhost:23512` 正常加载页面
- [ ] Clips 页面：增删改查正常
- [ ] Managers 页面：增删改查正常
- [ ] Manager 片段排序（上移/下移）功能正常
- [ ] 启动 Manager 后，状态显示正确，OBS 收到画面
- [ ] 停止 Manager 后，状态切换为 stopped
- [ ] 姿态捕获按钮能获取到当前玩家位置
- [ ] 模板切换联动正常
- [ ] 页面样式整洁，响应式布局基本可用

---

## 12. 验收标准

### 12.1 功能验收

| # | 验收项 | 验证方式 | 优先级 |
|---|---|---|---|
| F1 | 编译成功，Fabric 模组加载正常 | `gradlew build` + `runClient` | P0 |
| F2 | 创建一个 Clip，选择 ORBIT 模板，配置参数，保存后重新加载数据不丢失 | Web UI 操作 + 重启游戏 | P0 |
| F3 | 创建一个 Manager，包含 2 个 Clip，启动后在 OBS 中看到 Spout 画面 | OBS 添加 Spout2 Capture 源 | P0 |
| F4 | 停止 Manager，OBS 画面停止更新，资源正确释放 | OBS 观察 + Mod 日志 | P0 |
| F5 | 同时运行 2 个 Manager，OBS 中 2 个 Spout 源各自独立渲染 | OBS 添加 2 个 Spout 源 | P1 |
| F6 | 运动模板在片段时长内正确插值（ORBIT 画面旋转完整一圈） | 视觉验证 | P1 |
| F7 | 姿态捕获功能获取到的位置/朝向正确 | 与游戏内实际 F3 数据对比 | P1 |
| F8 | 低配机器上渲染不掉帧（预期单流 30fps 可维持） | 监控帧率 | P2 |

### 12.2 非功能验收

| # | 验收项 | 验证方式 | 优先级 |
|---|---|---|---|
| NF1 | 无活跃流时 Mod 零性能开销 | FPS 对比（装/不装 Mod） | P0 |
| NF2 | 停止流后所有资源释放（FBO、Spout Sender、线程） | 任务管理器 GPU 内存 + 日志 | P0 |
| NF3 | API 返回的 JSON 格式符合预期 | 手动 curl 测试 | P1 |
| NF4 | 文件写入异常时（磁盘满）不导致游戏崩溃 | 模拟 + 日志检查 | P2 |
| NF5 | Mod 在不支持 Spout 的系统上友好报错 | 非 Windows 运行检查 | P2 |

### 12.3 代码验收

| # | 验收项 | 说明 |
|---|---|---|
| C1 | 无 Java 17 不兼容的 API 调用 | 无 `ScopedValue`、`Math.clamp`、`Thread.ofPlatform`、`Future.state()` |
| C2 | 所有渲染操作在 Minecraft 主线程执行 | 无跨线程 GL 操作 |
| C3 | Mixin 使用标准注解，无 MixinExtras 依赖 | — |
| C4 | Camera 反射字段名需与 Mojang mappings 一致 | 通过 IDE 反编译确认 |
| C5 | Web UI 使用 Vanilla JS，无 npm/构建步骤 | — |

---

## 13. 测试流程

### 13.1 单元测试（JUnit + Mock）

测试不依赖 Minecraft 环境的纯逻辑：

```bash
# Gradle 运行所有单元测试
gradlew test
```

**测试范围**：
- `MotionTemplate` 各模板的数学计算
- `PlaybackEngine` 的时间线逻辑
- `FrameCommand` 数据有效性验证
- 缓动函数计算

**测试示例**：
```java
@Test void staticTemplate_returnsSamePosition() {
    var cmd = new StaticTemplate().evaluate(Map.of(
        "posX", 10.0, "posY", 20.0, "posZ", 30.0,
        "rotX", 0.0, "rotY", 0.0, "rotZ", 0.0,
        "fov", 70.0
    ), 0.5f);
    assertEquals(10.0, cmd.x(), 1e-6);
}

@Test void playbackEngine_switchesClipsAtCorrectTime() throws InterruptedException {
    // 创建 2 个 Clip：ClipA(0~100ms), ClipB(100~200ms)
    // 启动引擎，sleep 50ms 后应返回 ClipA 的 FrameCommand
    // sleep 150ms 后应返回 ClipB 的 FrameCommand
}
```

### 13.2 集成测试（手动）

需要启动 Minecraft 的测试：

**测试清单**：

| 测试项 | 步骤 | 预期 |
|---|---|---|
| Web UI 加载 | 游戏启动后，浏览器打开 `localhost:23512` | 页面正常加载，3 个 Tab 可切换 |
| Clip CRUD | 创建/编辑/删除 Clip | 列表正确更新，刷新后不丢失 |
| Manager CRUD | 创建 Manager，添加 2 个 Clip | 可上移/下移排序 |
| 启动推流 | 点击 Start，OBS 添加 Spout2 Source | 画面出现，帧率稳定 |
| 停止推流 | 点击 Stop | OBS 画面冻结/黑屏 |
| 姿态捕获 | 站在一个位置，点击捕获按钮 | 填入的坐标与 F3 一致 |
| 切换模板 | Clip 模板下拉从 STATIC 切换到 ORBIT | 参数输入区联动变化 |
| 错误处理 | 删除被 Manager 引用的 Clip | API 返回错误，前端显示提示 |
| 双机位 | 创建 2 个 Manager，分别启动 | OBS 添加 2 个 Spout2 源，各自独立运行 |

### 13.3 回归测试

每次修改后运行：
```bash
gradlew build         # 确保编译通过
gradlew runClient     # 启动并手工验证核心流程：启动 Manager → OBS 见画面
```

### 13.4 性能测试

| 指标 | 最低要求 | 目标 |
|---|---|---|
| 单流帧率 (1080p) | 30fps | 60fps |
| 双流帧率 (1080p) | 15fps | 30fps |
| 启动延迟（Start → OBS 见画面） | < 2s | < 1s |
| 停止延迟 | < 500ms | < 100ms |
| Web UI 响应时间 | < 500ms | < 100ms |

### 13.5 兼容性测试

- Minecraft 1.20.1 纯净环境 ✅
- Fabric API 0.92.0 ✅
- 与其他常用直播 Mod（如 MinimalMenu、ReplayMod）不冲突（需验证）
- OBS Studio 30+ ✅
- Windows 10 / Windows 11 ✅

---

## 14. 附录：关键参考代码

### 14.1 MainScheduler（参考自原项目）

```java
// 直接参考 D:\MODS\LiveHelper\src\main\java\net\burningtnt\livehelper\MainScheduler.java
// 核心点：
// 1. PriorityQueue<Task> 按时间排序
// 2. sleepUntil() 自适应 overshoot 补偿
// 3. tick() 从队列取最早任务执行
// 4. submitTask() 插入新任务
// Java 17 兼容：record Task, LockSupport.parkNanos, Thread.onSpinWait 都 OK
```

### 14.2 1.20.1 GameRenderer.render() 签名

在 1.20.1 (Mojmap) 中，`GameRenderer.render()` 的签名是：
```java
public void render(float tickDelta, long startNano, MatrixStack matrices)
```
- `tickDelta`：帧间插值因子（同 `DeltaTracker`）
- `startNano`：帧开始时间
- `matrices`：矩阵栈

需要在 `StreamInstance.renderInternal()` 中模拟调用。

### 14.3 Mojmap 下 Camera 和 RenderTarget 的字段名

所有反射访问的字段名均使用 **Mojang mappings** 的原始名称：
- `Camera.initialized`, `Camera.cachedViewRotMatrix`, `Camera.depthFar`, `Camera.fov`, `Camera.hudFov`（字段）
- `Camera.setPosition()`, `Camera.setRotation()`, `Camera.prepareCullFrustum()`, `Camera.getViewRotationMatrix()`, `Camera.setupPerspective()`（方法）
- `RenderTarget.fbo`（FBO ID 字段，需 accesswidener）
- `Minecraft.mainRenderTarget`, `GameRenderer.mainCamera`（已通过 accesswidener 开放）

### 14.4 原项目参考位置

原项目中以下文件可作为**实现参考**（非复制，仅参考思路）：

| 原项目文件 | 参考价值 |
|---|---|
| `api/ActiveStream.java` | RenderStep 接口设计思路（但 Mix 不需要） |
| `api/ActiveStreamInstanceImpl.java` | 渲染循环流程（setupInternal → renderInternal） |
| `api/ActiveStreamImpl.java` | 流注册表管理 |
| `api/SpoutRenderer.java` | Spout 挂钩思路（但在 1.20.1 中需不同实现） |
| `util/AngleConvert.java` | 四元数/欧拉角转换工具 |
| `mixin/MinecraftMixin.java` | 主循环替换思路（但需适配 1.20.1） |
| `server/executor/ProgramScheduler.java` | Clip/Manager 时间线管理（删掉 WASM 部分） |

---

---

## 14. 附录：关键参考代码

关键模块的完整可编译实现（CameraSetup 反射控制、Spout JNA 绑定、StreamInstance 渲染循环、MainScheduler、Mixin、全部运动模板、PlaybackEngine、ApiServer）请查阅 **[DEV_REFERENCE.md](./DEV_REFERENCE.md)**。

---

> 本文档供 AI Agent 按阶段逐步实现。每个阶段完成后应运行验收标准中的检查项，确认无误再进入下一阶段。
