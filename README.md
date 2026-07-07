# LiveHelper

LiveHelper 是一个面向 Minecraft Fabric 1.20.1 客户端的多机位直播辅助 Mod。

它允许用户通过本地 Web UI 创建镜头片段（Clip）和播放编排（Manager），在 Minecraft 客户端内按时间线渲染虚拟摄像机画面，并通过 Spout2 发送到 OBS Studio。

> 当前实现包名以现有项目为准：`site.leawsic.livehelper`。

## 功能概览

- Fabric 1.20.1 客户端 Mod
- 内置 HTTP API 服务器：`http://localhost:23512`
- 内嵌 Web UI：无需 npm、无需前端构建工具
- Clip/Manager JSON 持久化：`config/livehelper/`
- 多种运镜模板：
  - `STATIC`
  - `ORBIT`
  - `DOLLY`
  - `TRUCK`
  - `PEDESTAL`
  - `PAN_TILT`
  - `PATH`
- 自定义主线程帧调度器 `MainScheduler`
- 主摄像机接管式虚拟机位推流
- Spout2 DLL + JNA 发送主窗口 FBO 到 OBS
- Stream 活跃时阻止失焦自动暂停，手动 ESC 暂停仍保留

## 环境要求

- Windows 10/11
- Java 17
- Minecraft 1.20.1
- Fabric Loader
- Fabric API
- OBS Studio
- OBS Spout2 Capture 插件

Spout2 仅支持 Windows。非 Windows 环境下启动推流会报错。

## 构建

在项目根目录执行：

```bash
./gradlew build
```

构建产物：

```text
build/libs/livehelper-1.0.0.jar
build/libs/livehelper-1.0.0-sources.jar
```

已验证当前代码可通过：

```text
BUILD SUCCESSFUL
```

## 运行开发客户端

```bash
./gradlew runClient
```

游戏启动后，Mod 会：

1. 初始化 `StorageManager`
2. 启动本地 API 服务器
3. 注册客户端 tick 回调
4. 加载 Web UI 静态资源
5. 自动打开 Web UI：`http://localhost:23512/`

## Web UI 使用

启动 Minecraft 客户端并进入世界后，用浏览器打开：

```text
http://localhost:23512
```

页面包含：

- 总览
- Clips
- Managers
- 世界连接状态提示
- Clip ID 点击复制

### 创建 Clip

在 `Clips` 页面点击 `新建 Clip`，填写：

- 名称
- 时长（毫秒）
- 模板
- 模板参数

常用模板示例：

#### STATIC

```json
{
  "posX": 0,
  "posY": 80,
  "posZ": 0,
  "rotX": 0,
  "rotY": 0,
  "rotZ": 0,
  "fov": 70
}
```

#### ORBIT

```json
{
  "targetX": 0,
  "targetY": 70,
  "targetZ": 0,
  "radius": 10,
  "speed": 1,
  "startAngle": 0,
  "elevation": 15,
  "fov": 70
}
```

#### PATH

```json
{
  "fov": 70,
  "keyframes": [
    {"t": 0.0, "x": 0,  "y": 80, "z": 0,  "rx": 0, "ry": 0,  "rz": 0},
    {"t": 0.5, "x": 10, "y": 82, "z": 10, "rx": 0, "ry": 90, "rz": 0},
    {"t": 1.0, "x": 0,  "y": 80, "z": 20, "rx": 0, "ry": 180,"rz": 0}
  ]
}
```

### 创建 Manager

在 `Managers` 页面点击 `新建 Manager`，填写：

- 名称
- 输出宽度
- 输出高度
- FPS
- 渲染距离
- Clips JSON

`Clips JSON` 示例：

```json
[
  {"clipId": 1, "startOffset": 0},
  {"clipId": 2, "startOffset": 5000}
]
```

其中：

- `clipId` 是已创建 Clip 的 ID
- `startOffset` 是该 Clip 在 Manager 时间线中的开始时间，单位毫秒

## HTTP API

默认端口：`23512`

### Clips

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/clips` | 获取所有 Clip |
| `POST` | `/api/clips` | 创建 Clip |
| `PUT` | `/api/clips/{id}` | 更新 Clip |
| `DELETE` | `/api/clips/{id}` | 删除 Clip |

### Managers

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/managers` | 获取所有 Manager |
| `POST` | `/api/managers` | 创建 Manager |
| `PUT` | `/api/managers/{id}` | 更新 Manager |
| `DELETE` | `/api/managers/{id}` | 删除 Manager |
| `POST` | `/api/managers/{id}/start` | 启动推流 |
| `POST` | `/api/managers/{id}/stop` | 停止推流 |
| `GET` | `/api/managers/{id}/status` | 获取状态 |

兼容路径：

```text
/api/manager/{id}/start
/api/manager/{id}/stop
/api/manager/{id}/status
```

### 其他

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/templates` | 获取模板列表 |
| `GET` | `/api/pose` | 获取当前玩家位置与朝向四元数 |
| `GET` | `/` | 打开 Web UI |

## 实际测试完整流程

以下流程用于从零验证构建、加载、Web UI、API、数据持久化和 OBS Spout 输出。

### 1. 构建验证

在项目根目录执行：

```bash
./gradlew clean build
```

预期结果：

```text
BUILD SUCCESSFUL
```

确认产物存在：

```text
build/libs/livehelper-1.0.0.jar
```

### 2. 启动 Minecraft 客户端

执行：

```bash
./gradlew runClient
```

预期：

- Minecraft 1.20.1 客户端正常启动
- 日志中出现 LiveHelper 初始化信息
- API server 日志显示端口 `23512` 已启动

### 3. 进入单人世界或服务器

创建/进入任意世界。

预期：

- 客户端不崩溃
- 无活跃 Manager 时，游戏正常运行
- FPS 和操作响应基本正常

### 4. 验证 Web UI

浏览器打开：

```text
http://localhost:23512
```

预期：

- 页面正常加载
- 可以切换 `总览 / Clips / Managers`
- 控制台无明显 JS 错误

### 5. 验证 API 基础接口

可使用浏览器或 curl：

```bash
curl http://localhost:23512/api/templates
curl http://localhost:23512/api/clips
curl http://localhost:23512/api/managers
curl http://localhost:23512/api/pose
```

预期：

- `/api/templates` 返回模板列表
- `/api/clips` 返回数组
- `/api/managers` 返回数组
- `/api/pose` 在玩家已进入世界后返回 `x/y/z/qx/qy/qz/qw`

### 6. 创建测试 Clip

通过 Web UI 创建一个 ORBIT Clip：

- name: `Orbit Test`
- duration: `10000`
- template: `ORBIT`
- params:

```json
{
  "targetX": 0,
  "targetY": 70,
  "targetZ": 0,
  "radius": 10,
  "speed": 1,
  "startAngle": 0,
  "elevation": 10,
  "fov": 70
}
```

也可以用 curl：

```bash
curl -X POST http://localhost:23512/api/clips ^
  -H "Content-Type: application/json" ^
  -d "{\"id\":0,\"name\":\"Orbit Test\",\"duration\":10000,\"template\":\"ORBIT\",\"params\":{\"targetX\":0,\"targetY\":70,\"targetZ\":0,\"radius\":10,\"speed\":1,\"startAngle\":0,\"elevation\":10,\"fov\":70}}"
```

预期：

- 返回 `{"id": ...}`
- Web UI Clips 列表出现新 Clip

### 7. 创建测试 Manager

假设上一步创建出的 Clip ID 为 `1`。

创建 Manager：

- name: `Main Stream`
- width: `1280`
- height: `720`
- fps: `30`
- renderDistance: `12`
- clips:

```json
[
  {"clipId": 1, "startOffset": 0}
]
```

curl 示例：

```bash
curl -X POST http://localhost:23512/api/managers ^
  -H "Content-Type: application/json" ^
  -d "{\"id\":0,\"name\":\"Main Stream\",\"clips\":[{\"clipId\":1,\"startOffset\":0}],\"width\":1280,\"height\":720,\"fps\":30,\"renderDistance\":12}"
```

预期：

- 返回 Manager ID
- Web UI Managers 列表出现新 Manager

### 8. 配置 OBS Spout2 Capture

1. 启动 OBS Studio
2. 确保已安装 Spout2 Capture 插件
3. 添加来源：`Spout2 Capture`
4. 准备选择 Sender 名称：

```text
LiveHelper-Main Stream
```

如果名称尚未出现，先执行下一步启动 Manager。

### 9. 启动 Manager 推流

通过 Web UI 点击 Manager 的 `Start`。

或使用 curl，假设 Manager ID 为 `1`：

```bash
curl -X POST http://localhost:23512/api/managers/1/start
```

预期：

- Minecraft 不崩溃
- 日志显示 Manager 已启动
- OBS 的 Spout2 Capture 中出现 `LiveHelper-Main Stream`
- OBS 可看到 Minecraft 离屏摄像机画面
- 摄像机画面应隐藏 GUI 和手部

### 10. 验证状态接口

```bash
curl http://localhost:23512/api/managers/1/status
```

预期：

```json
{"status":"running"}
```

### 11. 验证运镜效果

观察 OBS 画面：

- ORBIT 模板应围绕目标点运动
- STATIC 模板应保持固定画面
- PATH 模板应按关键帧插值移动
- PAN_TILT 模板应固定位置旋转

### 12. 停止 Manager

Web UI 点击 `Stop`。

或使用 curl：

```bash
curl -X POST http://localhost:23512/api/managers/1/stop
```

预期：

- 状态变为 `stopped`
- 资源释放，无持续报错
- OBS 画面停止更新或 Sender 消失

### 13. 验证持久化

1. 创建 Clip 和 Manager
2. 关闭 Minecraft
3. 重新执行：

```bash
./gradlew runClient
```

4. 打开：

```text
http://localhost:23512
```

预期：

- 之前创建的 Clip 和 Manager 仍然存在
- 数据文件位于：

```text
.minecraft/config/livehelper/clips.json
.minecraft/config/livehelper/managers.json
```

开发运行目录通常是：

```text
run/config/livehelper/
```

### 14. 双机位验证（可选）

1. 创建第二个 Clip 和 Manager
2. 启动两个 Manager
3. OBS 添加两个 Spout2 Capture 源

预期：

- 出现两个 sender：

```text
LiveHelper-<Manager A Name>
LiveHelper-<Manager B Name>
```

- 两个画面独立更新

## 当前已完成的自动验证

本仓库当前已经完成：

```bash
./gradlew build
```

并确认：

- Java 编译通过
- Mixin 配置参与构建
- `livehelper.accesswidener` 打包进 jar
- `libSpoutBinding.dll` 打包进 jar
- Web UI 静态文件打包进 jar
- JNA jar 被 include 到 `META-INF/jars/`

## 注意事项

- Spout/OBS 输出必须在 Windows + OBS + Spout2 Capture 环境中手动验证。
- API 服务器绑定本机 `23512` 端口，如果端口被占用，Mod 会记录启动失败日志。
- 所有渲染资源创建/销毁已调度到 Minecraft 主线程执行。
- 当前 Web UI 是轻量基础版，侧重可用性，不依赖 npm 或构建工具。

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
