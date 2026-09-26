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
  - `SPLINE`
- 模板参数由 Java 侧 schema 定义并通过 `/api/templates` 下发，Web UI 动态生成表单
- 写入前静态校验（时长、必填参数、枚举取值、关键帧时间单调性）
- 主摄像机接管式虚拟机位推流
- Manager 时间线支持相邻 Clip 之间的摄像机转场，以及 `repeat` / `pingpong` 两种循环方式
- 常驻机位 / 切机位双层机位模型：触发器是「切过去播一小段再回来」，不是「重播到手动停止」
- 触发器：条件命中时自动把推流切到指定 Manager（自动导播），支持延迟与冷却
- Spout2 DLL + JNA 发送主窗口 FBO 到 OBS
- Stream 活跃时阻止失焦自动暂停，手动 ESC 暂停仍保留

## 运镜模板

| 模板 | 运动方式 | 适用场景 |
|---|---|---|
| `STATIC` | 位置、朝向、FOV 全程固定 | 稳定全景、特写、转场前后的停顿画面 |
| `STATIC_TRACK` | 位置固定，每帧重新看向目标实体 | 拍摄移动中的玩家、生物、载具 |
| `ORBIT` | 绕目标点做圆周运动并持续看向它 | 环绕展示、舞台旋转 || `DOLLY` | 从起点推进到终点，朝向随移动方向 | 推进、后拉、斜向穿行 |
| `TRUCK` | 与 `DOLLY` 同逻辑，约定上只改横向坐标 | 横移跟拍 |
| `PEDESTAL` | 固定 X/Z，仅在高度上升降 | 垂直升起、下降展示空间关系 |
| `PAN_TILT` | 位置固定，仅在水平角/俯仰角间旋转 | 扫视平台、从一侧转向另一侧 |
| `PATH` | 关键帧之间直线插值，段内缓动 | 折线路径；关键帧处速度会突变 |
| `SPLINE` | Catmull-Rom 平滑曲线 + 弧长匀速 | 连续长镜头；关键帧处无速度突变 |

`PATH` 与 `SPLINE` 的关键帧格式**完全一致**（`t` / `x` / `y` / `z` / `rx` / `ry` / `rz` / `fov`），已有配置改模板名即可平滑升级。`SPLINE` 的 `orientMode` 可选 `keyframe`（按关键帧姿态插值）或 `tangent`（始终朝向路径切线前方）。

两者的匀速差异可以直接用命令量化：

```text
/livehelper eval <clipId> 0 20
```

输出的 `step` 是相邻采样点之间的世界距离：`SPLINE` 基本均匀，`PATH` 在关键帧处突变。

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
| `/livehelper validate <clipId>` | 对某个 Clip 跑一遍写入前校验，输出错误与警告 |
| `/livehelper eval <clipId> [progress] [samples]` | 离线查看 Clip 在指定进度下的相机参数；`samples` > 1 时同时打印相邻采样点的世界距离 |
| `/livehelper trigger list` | 列出所有触发规则 |
| `/livehelper trigger add <type> <managerId> [名称]` | 新建触发规则 |
| `/livehelper trigger id <id> enable \| disable \| remove \| test` | 启用 / 禁用 / 删除 / 立即试切某条规则 |
| `/livehelper trigger back` | 结束当前切机位，立即返回常驻机位 |
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
- `loopMode` 为 `repeat`（从头重播）或 `pingpong`（往复折返，监控式来回摇机位只需一个 Manager）；缺省为 `repeat`
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
| `GET` | `/api/templates` | 获取模板列表**及其参数字段 schema**（Web UI 据此动态渲染表单） |
| `GET` | `/api/triggers` | 获取触发规则列表 |
| `POST` | `/api/triggers` | 新建触发规则 |
| `PUT` | `/api/triggers/{id}` | 更新触发规则 |
| `DELETE` | `/api/triggers/{id}` | 删除触发规则 |
| `GET` | `/api/trigger-schema` | 获取触发类型的 conditions 字段 schema |
| `GET` | `/api/pose` | 获取当前玩家位置与朝向四元数 |
| `GET` | `/` | 打开 Web UI |

写入 `POST` / `PUT` Clip 时会先做静态校验，不通过返回 `400` 并附带错误列表（未知模板、非法缓动名、关键帧 `t` 非递增、FOV 越界等）。触发规则同样有校验。

## 机位模型：常驻与切机位

直播和录播脚本的根本差别在于「镜头要不要回来」。因此这里有两个不同的操作：

| 操作 | 语义 | 谁调用 |
|---|---|---|
| **常驻机位**（base） | 一直推流，直到手动停止或被新的常驻机位替换 | `/livehelper start` |
| **切机位**（cue） | 临时接管镜头，**时间线播完自动回常驻机位** | 触发器、`/livehelper trigger id <id> test` |

`/livehelper status` 会分别显示当前两者，例如：

```text
机位: 常驻=#1 Main Stream | 切机位=#2 Kill Cam
```

### 自动返回的前提

切机位用的是「时间线走完就回」，所以**切机位 Manager 应该关闭 `loop`**：

- `loop: false` → 播完自动返回常驻机位（推荐用于击杀特写、进区域镜头）
- `loop: true` → 永不自动返回，会一直停在那个画面，直到下一次触发或手动 `/livehelper trigger back`

常驻机位反过来通常应开 `loop: true`，否则它自己会先走完。

### 恢复时的行为

切机位期间常驻机位是**暂停**而不是销毁：时间线冻结在暂停处，恢复后从原处继续，不会跳到中间。因此循环的全景机位切回来时画面是连续的。

### 几种退化情况

| 情况 | 行为 |
|---|---|
| 没有常驻机位 | 切机位退化为常驻启动，**不会自动返回**（没有可回的目标），日志提示一次 |
| 常驻机位是 `locked` | 那是「多机位并行推流、OBS 各占一个源」的语义，没有「当前画面是谁」可言，退化为普通启动 |
| 切机位目标就是常驻机位 | 等价于结束当前切机位并返回 |

触发器连续命中不同片段时，切机位只保留一层（不做栈），新的切机位会顶掉上一个，返回目标始终是常驻机位。

## 触发器（自动导播）

触发规则让推流在特定事件发生时**自动切到指定 Manager**（见上一节的机位模型），适合击杀切 kill cam、进区域切机位、受伤切特写这类直播节奏。规则在 Web UI 的「触发器」页配置，存于 `config/livehelper/triggers.json`。

### 支持的触发类型

| 类型 | 说明 | 常用条件 |
|---|---|---|
| `entity_kill` | 击杀生物 | `target` 生物类型 |
| `damage` | 自身受伤 | `min_damage` 最小伤害量 |
| `entity_attack` | 攻击实体 | `target` |
| `entity_interact` | 与实体交互 | `target`、`item` |
| `block_interact` | 与方块交互 | `target` 方块、`item` |
| `item_on_interact` | 持物交互 | `item`、`target`、`target_type` |
| `item_use` / `item_consume` / `item_release` | 开始使用 / 用完 / 中途松手 | `item` |
| `dimension_change` | 切换维度 | `dimension` |
| `location` | 进入区域 | `position` + `radius`，或 `corner1` + `corner2` |
| `advancement` | 获得进度 | `advancement` |
| `xp` | 获得经验 | `level` 或 `total` |
| `observation` | 注视目标 | `target`、`target_type` |

标识写法：`zombie` 等价于 `minecraft:zombie`；`*` 与 `minecraft:*` 为通配；留空表示不限。空手交互把 `item` 留空即可。

### 规则字段

| 字段 | 默认 | 说明 |
|---|---|---|
| `targetManager` | 必填 | 命中后启动的 Manager id |
| `enabled` | `true` | 是否启用 |
| `repeatable` | `true` | 是否可重复触发；`false` 时命中一次即失效，直到重新加载配置 |
| `delayMs` | `0` | 命中后延迟多久再切机位。击杀后稍等 0.3~0.5s 再给 kill cam 观感更好 |
| `cooldownMs` | `0` | 两次命中的最小间隔，防止同一事件反复切镜 |
| `onEnter` | `false` | 位置类：只在**进入**区域时触发，已在区域内不重复 |
| `exitBuffer` | `0` | 位置类：离开原区域多少格后才算已离开，防止边界抖动反复触发 |

`delayMs` 与 `cooldownMs` 的判定精度是 1 tick（50ms）。

### 关于多人服务器

判定完全基于**本地客户端**：受伤、升级、切维度、击杀等都从本地玩家状态读出，交互与攻击由客户端交互层捕获。因此多人服同样可用——推流画面本来就来自本机，只要主播这边看到的时机对，画面就是对的。

其中击杀是近似的：客户端拿不到服务端判定结果，采用「我攻击过它 + 它随后死亡或从世界消失」。若目标在攻击后超过 5 秒才死，则不计为击杀。

## 注意事项

- Spout/OBS 输出必须在 Windows + OBS + Spout2 Capture 环境中手动验证，完整步骤见 [TESTING.md](./TESTING.md)。
- API 服务器绑定本机 `23512` 端口，如果端口被占用，Mod 会记录启动失败日志。
- 所有渲染资源创建/销毁已调度到 Minecraft 主线程执行。
- `rotZ` / 关键帧 `rz`（画面滚转）目前**尚未进入渲染链路**：`CameraSetup` 只应用 yaw 与 pitch，roll 会被丢弃。相关字段保留在格式中但暂不生效。
- 源码含中文注释，构建依赖 `build.gradle` 里的 `options.encoding = 'UTF-8'`；若移除该项，javac 会回落到系统默认编码导致编译失败。
- `gradle.properties` 开启了构建缓存，验证单测需加 `--rerun-tasks --no-build-cache`，否则 `:test` 显示 `FROM-CACHE` 且不打印结果。
- 当前 Web UI 是轻量基础版，侧重可用性，不依赖 npm 或构建工具。

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
