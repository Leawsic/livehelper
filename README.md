# LiveHelper

LiveHelper 是一个面向 Minecraft Fabric 1.20.1 客户端的多机位直播辅助 Mod。

它允许用户通过本地 Web UI 创建镜头片段（Clip）和播放编排（Manager），在 Minecraft 客户端内按时间线渲染虚拟摄像机画面，并通过 Spout2 发送到 OBS Studio。

灵感来源：https://github.com/burningtnt/LiveHelper

## 功能概览

- Fabric 1.20.1 客户端 Mod
- 内置 HTTP API 服务器：`http://localhost:23512`
- 内嵌 Web UI：无需 npm、无需前端构建工具
- Clip/Manager JSON 持久化：`config/livehelper/`
- 多种运镜模板：
  - `STATIC`
  - `STATIC_TRACK`
  - `ORBIT`
  - `DOLLY`
  - `TRUCK`
  - `PEDESTAL`
  - `PAN_TILT`
  - `PATH`
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

## 游戏内客户端命令

进入世界或停留在客户端内时，可在聊天栏使用客户端命令快速调试 LiveHelper：

| 命令 | 说明 |
|---|---|
| `/livehelper` | 显示总体状态，等同于 `/livehelper status` |
| `/livehelper status` | 显示 Clip 数量、Manager 数量和当前活跃 Manager ID |
| `/livehelper open` | 打开 Web UI：`http://localhost:23512/` |
| `/livehelper reload` | 重新从 `config/livehelper/` 加载 JSON 配置 |
| `/livehelper pose` | 显示当前玩家眼睛坐标、方块坐标、pitch/yaw |
| `/livehelper entities [radius]` | 列出附近实体的运行时 ID、名称、UUID 和位置，默认半径 32 格 |
| `/livehelper list clips` | 列出所有 Clip 的 ID、名称、模板和时长 |
| `/livehelper list managers` | 列出所有 Manager 的 ID、名称、时长和运行状态 |
| `/livehelper start <managerId>` | 启动指定 Manager 推流 |
| `/livehelper stop <managerId>` | 停止指定 Manager 推流 |
| `/livehelper stop-all` | 停止所有活跃 Manager |

这些命令直接调用客户端内的 `StorageManager` 和 `StreamManager`，不依赖浏览器或 curl，适合调试坐标、快速重载配置和控制 OBS Sender。

### 创建 Clip

在 `Clips` 页面点击 `新建 Clip`，填写：

- 名称
- 时长（毫秒）
- 模板
- 模板参数

Clip 参数编辑器提供“玩家坐标辅助”：进入世界后，站到想要取点的位置/视角，再点击对应按钮即可写入当前参数表单。

| 按钮 | 会尝试写入的参数 | 适用场景 |
|---|---|---|
| `填入机位位置/朝向` | `posX/posY/posZ`、`rotX/rotY/rotZ` | `STATIC`、`PAN_TILT` 的相机位置，或任何有固定机位的模板 |
| `填入目标点` | `targetX/targetY/targetZ`、`centerX/centerZ` | `ORBIT` 目标点，`PEDESTAL` 固定 X/Z |
| `填入起点` | `fromX/fromY/fromZ`、`fromHeight`、`startPan/startTilt` | `DOLLY/TRUCK` 起点，`PEDESTAL` 起始高度，`PAN_TILT` 起始角度 |
| `填入终点` | `toX/toY/toZ`、`toHeight`、`endPan/endTilt` | `DOLLY/TRUCK` 终点，`PEDESTAL` 结束高度，`PAN_TILT` 结束角度 |
| `追加 PATH 关键帧` | 向 `keyframes` 追加当前 `x/y/z/rx/ry/rz/fov` | `PATH` 多点路径采样 |

这些按钮只会填充当前模板里实际存在的参数字段；不适用的字段会自动跳过。`PATH` 追加关键帧后会自动把所有关键帧的 `t` 均分到 `0..1`，便于连续站点采样。PATH 编辑器支持逐点设置 `fov`，可在移动时同步拉近/拉远镜头，制作类似希区柯克变焦的效果。

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

#### STATIC_TRACK

固定机位，实时锁定一个实体。实体查找优先级为 `entityId`、`entityUuid`、`entityName`；找不到实体时回退到 `rotX/rotY/rotZ`。
`trackSpeed` 控制追踪平滑速度：`0` 表示即时锁定，`4-12` 通常比较适合拍摄，数值越小越丝滑但跟随延迟越明显。

可先在游戏内执行：

```text
/livehelper entities 64
```

然后把目标实体的 ID 填入 `entityId`。

```json
{
  "posX": 8,
  "posY": -50,
  "posZ": -18,
  "entityId": 123,
  "entityUuid": "",
  "entityName": "",
  "targetYOffset": 0,
  "trackSpeed": 8,
  "rotX": 20,
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
    {"t": 0.0, "x": 0,  "y": 80, "z": 0,  "rx": 0, "ry": 0,  "rz": 0, "fov": 75},
    {"t": 0.5, "x": 10, "y": 82, "z": 10, "rx": 0, "ry": 90, "rz": 0, "fov": 45},
    {"t": 1.0, "x": 0,  "y": 80, "z": 20, "rx": 0, "ry": 180,"rz": 0, "fov": 75}
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
- Loop：时间线播放到末尾后从头循环
- Locked：启动其它 Manager 时不自动停止当前 Manager
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

Manager 级别字段：

- `loop` 为 `true` 时，时间线播放到总时长后会从头继续播放；默认为 `false`
- `locked` 为 `true` 时，启动其它 Manager 不会自动停止它；默认为 `false`
- 启动一个新的未 locked Manager 时，会自动停止其它未 locked 的活跃 Manager，便于保持单主机位推流
- 为避免快速切换时 OBS 短暂黑屏，旧 Manager 会先暂停调度并保留上一帧输出，直到新 Manager 首帧发送成功后再释放旧 Spout sender

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
- loop: `true`
- locked: `false`
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
  -d "{\"id\":0,\"name\":\"Main Stream\",\"clips\":[{\"clipId\":1,\"startOffset\":0,\"transitionDuration\":0,\"transitionEasing\":\"linear\"}],\"width\":1280,\"height\":720,\"fps\":30,\"renderDistance\":12,\"loop\":true,\"locked\":false}"
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
- `STATIC_TRACK`：相机位置固定，但每帧重新看向目标实体眼睛位置；适合拍摄运动中的玩家、生物、载具或演出对象。
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
2. 将需要并行保留的 Manager 设置为 `locked: true`
3. 启动两个 Manager
4. OBS 添加两个 Spout2 Capture 源

预期：

- 出现两个 sender：

```text
LiveHelper-<Manager A Name>
LiveHelper-<Manager B Name>
```

- 两个画面独立更新
- 未设置 `locked` 的旧 Manager 会在新 Manager 启动时自动停止

## 注意事项

- Spout/OBS 输出必须在 Windows + OBS + Spout2 Capture 环境中手动验证。
- API 服务器绑定本机 `23512` 端口，如果端口被占用，Mod 会记录启动失败日志。
- 所有渲染资源创建/销毁已调度到 Minecraft 主线程执行。
- 当前 Web UI 是轻量基础版，侧重可用性，不依赖 npm 或构建工具。

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
