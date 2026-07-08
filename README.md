# LiveHelper

LiveHelper 是一个面向 Minecraft Fabric 1.20.1 客户端的多机位直播辅助 Mod。

它允许用户通过本地 Web UI 创建镜头片段（Clip）和播放编排（Manager），在 Minecraft 客户端内按时间线渲染虚拟摄像机画面，并通过 Spout2 发送到 OBS Studio。

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
- Manager 时间线支持相邻 Clip 之间的摄像机转场
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
- 时间线片段
- 每个片段进入时的转场时长和缓动曲线

时间线 JSON 示例：

```json
[
  {"clipId": 1, "startOffset": 0, "transitionDuration": 0, "transitionEasing": "linear"},
  {"clipId": 2, "startOffset": 5000, "transitionDuration": 1000, "transitionEasing": "easeInOut"}
]
```

其中：

- `clipId` 是已创建 Clip 的 ID
- `startOffset` 是该 Clip 在 Manager 时间线中的开始时间，单位毫秒
- `transitionDuration` 是进入该 Clip 时，从前一个 Clip 末帧混合到当前 Clip 的时间，单位毫秒
- `transitionEasing` 支持 `linear`、`easeIn`、`easeOut`、`easeInOut`
- 第一个 Clip 没有前一个 Clip，因此转场配置不会生效

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
  {"clipId": 1, "startOffset": 0, "transitionDuration": 0, "transitionEasing": "linear"}
]
```

curl 示例：

```bash
curl -X POST http://localhost:23512/api/managers ^
  -H "Content-Type: application/json" ^
  -d "{\"id\":0,\"name\":\"Main Stream\",\"clips\":[{\"clipId\":1,\"startOffset\":0,\"transitionDuration\":0,\"transitionEasing\":\"linear\"}],\"width\":1280,\"height\":720,\"fps\":30,\"renderDistance\":12}"
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
- OBS 可看到 Minecraft 虚拟摄像机画面
- 当前实现会接管主摄像机，因此推流活跃时本机 Minecraft 视角也会跟随虚拟机位

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

- `STATIC`：相机位置、朝向和 FOV 全程固定，适合做稳定的全景、特写或转场前后停顿画面。
- `ORBIT`：相机围绕 `targetX/targetY/targetZ` 做圆周运动，并持续看向目标点；`radius` 控制环绕距离，`speed` 控制 Clip 播放期间绕行圈数，`elevation` 控制仰角。
- `DOLLY`：相机从 `fromX/fromY/fromZ` 移动到 `toX/toY/toZ`，朝向由移动方向决定；适合向前推进、后拉或斜向穿行镜头。
- `TRUCK`：当前实现继承 `DOLLY`，参数和运动逻辑相同；约定上用于横向平移镜头，通常保持 `fromY/toY` 与 `fromZ/toZ` 接近，只改变 X 或横向坐标。
- `PEDESTAL`：相机固定在 `centerX/centerZ`，从 `fromHeight` 升降到 `toHeight`，朝向由 `rotX/rotY` 固定；适合垂直升起、下降展示场景高度关系。
- `PAN_TILT`：相机位置固定在 `posX/posY/posZ`，只在 `startPan/endPan` 和 `startTilt/endTilt` 之间旋转；适合扫视平台或从一侧转向另一侧。
- `PATH`：相机按 `keyframes` 的 `t` 时间点在多段位置和旋转之间插值；适合复杂路径、绕行、抬升再落下等组合镜头。

转场观察重点：

- 进入某个 Clip 的前 `transitionDuration` 毫秒，会从上一个 Clip 的末帧混合到当前 Clip 的当前帧。
- `linear` 速度恒定；`easeIn` 开始慢、后段快；`easeOut` 开始快、后段慢；`easeInOut` 两端慢、中间快。
- 第一个 Clip 没有前一段，因此不会出现进入转场。
- 当前转场是摄像机参数混合，不是画面淡入淡出；OBS 中应看到机位平滑移动/旋转/FOV 变化，而不是透明度变化。

使用下方 `Platform Template Transition Test` 时，可按时间线逐段检查：

| 时间范围 | Clip | 模板 | 预期画面 |
|---:|---:|---|---|
| `0-4000ms` | 101 | `STATIC` | 从平台北侧上方固定俯看，画面不应移动 |
| `4000-11000ms` | 102 | `ORBIT` | 前 `1200ms` 从静态镜头平滑过渡，然后围绕平台中心半圈环绕 |
| `11000-16000ms` | 103 | `DOLLY` | 前 `900ms` 缓入转场，然后从平台北侧向平台推进 |
| `16000-21000ms` | 104 | `TRUCK` | 前 `900ms` 缓出转场，然后沿平台南侧横向穿过 |
| `21000-26000ms` | 105 | `PEDESTAL` | 前 `1000ms` 匀速转场，然后在平台东北外侧垂直升起 |
| `26000-31000ms` | 106 | `PAN_TILT` | 前 `1200ms` 平滑转场，然后固定位置左右摇摄并略微俯仰 |
| `31000-38000ms` | 107 | `PATH` | 前 `1500ms` 平滑转场，然后沿关键帧绕平台移动 |

### 11.1 使用内置模板 + 转场测试配置

仓库已提供一套模板测试配置：

```text
examples/livehelper/clips.json
examples/livehelper/managers.json
```

测试区域假设是一个小平台：

```text
from -8 -61 -8 to 24 -61 24
```

样例相机围绕平台中心 `(8, -61, 8)` 布置，主要使用 `y=-58..-45` 的观察高度。

测试 Manager：

| ID | 名称 | 总时长 | Sender |
|---:|---|---:|---|
| 201 | `Platform Template Transition Test` | 38000ms | `LiveHelper-Platform Template Transition Test` |

该 Manager 会按顺序播放所有模板，并测试这些转场：

| 进入 Clip | 转场时长 | 缓动 |
|---:|---:|---|
| 101 | 0ms | `linear` |
| 102 | 1200ms | `easeInOut` |
| 103 | 900ms | `easeIn` |
| 104 | 900ms | `easeOut` |
| 105 | 1000ms | `linear` |
| 106 | 1200ms | `easeInOut` |
| 107 | 1500ms | `easeInOut` |

使用方式：

1. 执行 `./gradlew runClient`
2. 进入包含 `-8 -61 -8` 到 `24 -61 24` 平台的测试世界
3. 打开 `http://localhost:23512`
4. 在 Managers 页面启动 `Platform Template Transition Test`
5. OBS 添加 Spout2 Capture 并选择 `LiveHelper-Platform Template Transition Test`

如果正式游戏实例需要同一套配置，可把这两个 JSON 复制到：

```text
.minecraft/config/livehelper/
```

### 12. 停止 Manager

Web UI 点击 `Stop`。

或使用 curl：

```bash
curl -X POST http://localhost:23512/api/managers/1/stop
```

预期：

- 状态变为 `stopped`
- 资源释放
- OBS 画面停止更新或 Sender 消失

### 13. 双机位验证（可选）

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

## 注意事项

- Spout/OBS 输出必须在 Windows + OBS + Spout2 Capture 环境中手动验证。
- API 服务器绑定本机 `23512` 端口，如果端口被占用，Mod 会记录启动失败日志。
- 所有渲染资源创建/销毁已调度到 Minecraft 主线程执行。
- 当前 Web UI 是轻量基础版，侧重可用性，不依赖 npm 或构建工具。

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
