# LiveHelper 验证流程

从零验证构建、加载、Web UI、API、数据持久化与 OBS Spout 输出。README 只保留功能与用法，这里放逐条验证步骤。

## 1. 构建验证

```bash
./gradlew clean build
```

预期 `BUILD SUCCESSFUL`，产物位于 `build/libs/livehelper-<版本>.jar`。

## 2. 启动开发客户端

```bash
./gradlew runClient
```

预期：Minecraft 1.20.1 客户端启动，日志出现 LiveHelper 初始化信息，API server 显示端口 `23512` 已启动，浏览器自动打开 Web UI。

## 3. 进入世界

创建/进入任意世界。预期客户端不崩溃，无活跃 Manager 时游戏行为正常。

## 4. 验证 Web UI

浏览器打开 `http://localhost:23512`，预期页面正常加载、可切换 `总览 / Clips / Managers / 触发器`、控制台无明显 JS 错误。

## 5. 验证 API

```bash
curl http://localhost:23512/api/templates
curl http://localhost:23512/api/trigger-schema
curl http://localhost:23512/api/clips
curl http://localhost:23512/api/managers
curl http://localhost:23512/api/triggers
curl http://localhost:23512/api/pose
```

预期：`/api/templates` 与 `/api/trigger-schema` 返回带字段定义的 schema；`/api/clips`、`/api/managers`、`/api/triggers` 返回数组；`/api/pose` 在进入世界后返回 `x/y/z/qx/qy/qz/qw`。

## 6. 创建测试 Clip

Web UI 新建一个 ORBIT Clip（`duration: 10000`）：

```json
{
  "targetX": 0, "targetY": 70, "targetZ": 0,
  "radius": 10, "speed": 1, "startAngle": 0, "elevation": 10, "fov": 70
}
```

或用 curl：

```bash
curl -X POST http://localhost:23512/api/clips ^
  -H "Content-Type: application/json" ^
  -d "{\"id\":0,\"name\":\"Orbit Test\",\"duration\":10000,\"template\":\"ORBIT\",\"params\":{\"targetX\":0,\"targetY\":70,\"targetZ\":0,\"radius\":10,\"speed\":1,\"startAngle\":0,\"elevation\":10,\"fov\":70}}"
```

预期返回 `{"id": ...}`，Web UI 列表出现新 Clip。

## 7. 创建测试 Manager

假设上一步 Clip ID 为 `1`：

```bash
curl -X POST http://localhost:23512/api/managers ^
  -H "Content-Type: application/json" ^
  -d "{\"id\":0,\"name\":\"Main Stream\",\"clips\":[{\"clipId\":1,\"startOffset\":0,\"transitionDuration\":0,\"transitionEasing\":\"linear\"}],\"width\":1280,\"height\":720,\"fps\":30,\"renderDistance\":12,\"loop\":true,\"locked\":false}"
```

预期返回 Manager ID。

## 8. 配置 OBS

1. 启动 OBS Studio 并确认已安装 Spout2 Capture 插件
2. 添加来源 `Spout2 Capture`
3. Sender 名称选 **`LiveHelper`**（固定名；若未出现，先执行下一步启动推流）

> 从旧版本升级：原先按 `LiveHelper-<Manager 名>` 选中的源已失效，需重新选 `LiveHelper`。

## 9. 启动推流

```bash
curl -X POST http://localhost:23512/api/managers/1/start
```

预期：Minecraft 不崩溃；日志显示 base manager 已启动；OBS 出现 `LiveHelper` sender 并显示虚拟机位画面；本机视角同步跟随虚拟机位（当前实现接管主摄像机）。

**重点观察 MC 本机窗口**：从启动推流开始，视角应稳定保持在虚拟相机上，**不应在相机视角与玩家视角之间来回闪**。若出现闪烁，通常是 Manager 的 fps 低于游戏渲染帧率时的产出间隔处理问题，或时间线上存在片段空档（`startOffset` 与上一个 Clip 结束时间之间有间隙）——后者会在日志里提示一次 `has no active clip`。

```bash
curl http://localhost:23512/api/managers/1/status
```

预期 `{"status":"running"}`。

## 10. 验证运镜效果

各模板的运动方式与适用场景见 README「运镜模板」一节。要点：

- 转场是**摄像机参数混合**，不是画面淡入淡出；OBS 中应看到机位平滑移动/旋转/FOV 变化。
- 第一个 Clip 没有前一段，因此不会出现进入转场。
- `STATIC_TRACK` 的目标丢失时镜头会**保持最后一帧并持续重找**，不会硬切回预设朝向——观察目标死亡瞬间是否平滑。
- `PATH` 与 `SPLINE` 的匀速差异用命令直接验证：

```text
/livehelper eval <clipId> 0 20
```

`step` 为相邻采样点的世界距离：`SPLINE` 基本均匀，`PATH` 在关键帧处突变。

## 11. 停止

```bash
curl -X POST http://localhost:23512/api/managers/1/stop
```

预期状态变为 `stopped`，资源释放，OBS 画面停止更新或 Sender 消失。

## 12. 多个 Manager 同时运行（可选）

1. 创建第二个 Clip 和 Manager，将需要保留的那个设为 `locked: true`
2. 启动两个 Manager

预期：日志提示一次「只有当前机位会被推送」。OBS 里仍然只有 `LiveHelper` 一个 sender，画面是当前输出拥有者（切机位优先，其次常驻机位）。未设 `locked` 的旧 Manager 会在新 Manager 启动时自动停止。

## 13. 触发器与切机位

1. 先 `/livehelper start 1` 启动一个**常驻机位**（建议 `loop: true`）
2. 建第二个**关闭 `loop`** 的 Manager 作为切机位片段
3. 新建触发规则：类型如 `entity_kill` / `damage`，目标指向第二个 Manager
4. 用 `/livehelper cue 2` 手动试切（不依赖触发器），或 `/livehelper trigger id <id> test` 试某条规则

预期：

- `/livehelper status` 显示 `常驻=... | 切机位=... | 正在推送=...`
- 切过去的片段播完后**自动回到常驻机位**，OBS 画面全程停在同一个 `LiveHelper` 源上、内容自动变化
- `/livehelper cue back` 可立即结束当前切机位并返回
- 没有常驻机位时，触发器会退化为常驻启动并在日志里提示一次

## 14. 停止后镜头必须交还玩家

1. 启动推流，确认 MC 本机视角已被虚拟相机接管
2. 执行 `/livehelper stop-all`

预期：MC 视角**立即回到玩家实际视角**（不再被相机控制），OBS 的 `LiveHelper` sender 消失或画面停止更新，第一人称手臂恢复显示。

若停止后视角仍被相机锁住，或手臂始终不出现，说明渲染上下文没有被清干净——这是回归，请查看日志并反馈。

## 14b. HUD 与手臂的可见性

本模组接管的是**主摄像机**，输出与玩家看到的是同一块 framebuffer，所以**不可能**做到「OBS 里没有 HUD、而自己看得到」。模组因此**不接管 HUD 可见性**，`options.hideGui` 完全由你自己的 F1 决定。

- 推流中：HUD 与聊天**正常显示**，因此推流时执行指令能看到回显；第一人称手臂与手持物**不显示**（`renderItemInHand` 被拦截）。
- 需要干净的推流画面：自行按 **F1** 隐藏 HUD。

预期：推流中手臂消失、停止推流后手臂**立即**恢复。手臂不恢复即为回归——`renderHand` 没有逐帧还原路径，任何对它的一次性赋值都会导致该症状。

## 15. 单元测试

```bash
./gradlew test --rerun-tasks --no-build-cache
```

> `gradle.properties` 里开启了 `org.gradle.caching=true`，不加 `--rerun-tasks --no-build-cache` 时 `:test` 会显示 `FROM-CACHE` 且不打印任何用例结果，容易误判为「0 个测试通过」。
