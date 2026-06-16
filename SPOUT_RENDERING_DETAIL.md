# Spout 离屏渲染技术分析

## 问题根源一句话

**新项目离屏渲染不走 `GameRenderer.render()`，而是直接调 `LevelRenderer.renderLevel()`，跳过了游戏渲染管线的完整初始化过程。**

原项目从 `GameRenderer.render()` → `renderLevel()` → `LevelRenderer.renderLevel()` 全链路执行，只在外部 swap 了 RenderTarget 和 Camera。新项目只做了最后一步，前两步的初始化全部丢失。

---

## 1. 整体架构对比

### 原项目（NeoForge, 正常工作）
```
┌─────────────────────────────────────────────────────┐
│ ActiveStreamInstanceImpl.render()                   │
│  1. swap mainRenderTarget (→ offscreen MainTarget)  │
│  2. swap mainCamera (→ dummy Camera)                │
│  3. setupInternal()  ← 通过反射完全初始化 Camera    │
│  4. runWith(ScopedValue) ← 绑定上下文给 Mixin 用    │
│  5.   renderInternal():                             │
│  6.     levelRenderer.update(camera)                │
│  7.     gameRenderer.extract()     ← 全管线提取     │
│  8.     gameRenderer.render()      ← 全管线渲染     │
│  9.     renderTarget.blitToScreen() ← 解析多重采样   │
│ 10. restore mainRenderTarget, mainCamera             │
│                                                      │
│ Spout 发送在 GlCommandEncoderMixin 中:               │
│   presentTexture() hook → 替换 drawFbo → Spout send  │
└─────────────────────────────────────────────────────┘
```

### 新项目（Fabric 1.20.1, 全黑）
```
┌─────────────────────────────────────────────────────┐
│ StreamInstance.renderFrame()                         │
│  1. swap mainRenderTarget                            │
│  2. swap mainCamera (→ dummy Camera)                 │
│  3. CameraSetup.apply():                             │
│     - camera.setup() ────→ 只初始化了玩家位置        │
│     - setPosition/setRotation ← 覆盖坐标             │
│     ✗ 没有初始化 depthFar/fov/hudFov                 │
│     ✗ 没有调用 prepareCullFrustum                    │
│     ✗ 没有调用 setupPerspective                      │
│     ✗ 没有调用 getViewRotationMatrix                 │
│  4. 直接调用 levelRenderer.renderLevel():            │
│     ✗ 没有先调 levelRenderer.update(camera)         │
│     ✗ 没有走 gameRenderer.extract()                  │
│     ✗ 没有走 gameRenderer.render()                   │
│     ✗ 没有内部 Camera.setup() 过程                   │
│  5. 直接读 renderTarget.fbo → Spout send             │
│  restore                                              │
└─────────────────────────────────────────────────────┘
```

---

## 2. Camera 初始化：最关键的差异

原项目通过反射**完整初始化了 Camera 的 11 个私有字段/方法**。

### 原项目设置 Camera 的完整顺序

```java
// ActiveStreamInstanceImpl.setupInternal()
// ① 设置位置
SET_POSITION.invokeExact(camera, frame.x(), frame.y(), frame.z());

// ② 设置旋转
SET_ROTATION.invokeExact(camera, angles.y, angles.z, angles.z);
// 注意：第三个参数应该是 angles.x (roll)，原项目有 bug 复制了 angles.z

// ③ 标记为已初始化
INITIALIZED.set(camera, true);

// ④ 设置投影相关参数
DEPTH_FAR.set(camera, config.renderDistance() * 64);  // 注意乘 64 不是 16
FOV.set(camera, frame.fov());
HUD_FOV.set(camera, frame.fov());

// ⑤ 计算视图旋转矩阵（关键！）
Matrix4f viewRot = (Matrix4f) GET_VIEW_ROTATION_MATRIX.invokeExact(
    camera, (Matrix4f) CACHED_VIEW_ROT_MATRIX.get(camera)
);

// ⑥ 准备裁剪视锥体（关键！）
PREPARE_CULL_FRUSTUM.invokeExact(camera, viewRot, projection, camera.position());

// ⑦ 设置透视投影（关键！）
SETUP_PERSPECTIVE.invokeExact(camera, 0.05F, depthFar, fov, width, height);
```

**步骤 ⑤-⑦ 是新项目完全缺失的。** 这三个步骤的作用：

| 步骤 | 作用 | 如果缺失 |
|------|------|----------|
| `getViewRotationMatrix()` | 根据 yaw/pitch 计算出 Camera 的方向四元数，存入 `cachedViewRotMatrix` | frustum 用的方向矩阵是默认值（玩家朝向） |
| `prepareCullFrustum()` | 根据方向矩阵构建裁剪视锥体的六个平面 | 视锥体裁剪用默认相机位置，可能把地形全裁掉 |
| `setupPerspective()` | 更新 Camera 内部的近远平面/FOV/宽高比，这些被 `LevelRenderer.renderLevel()` 内部读取 | renderLevel 内部获取到的投影参数与实际渲染不一致 |

### 新项目只做了

```java
// CameraSetup.apply()
camera.setup(mc.level, mc.player, false, false, 1.0F);  // ← 基于玩家的位置
applyAfterSetup(camera, cmd);  // ← 覆盖位置和旋转

// CameraSetup.applyAfterSetup()
setPosition(x, y, z);
setRotation(yaw, pitch);
// ✗ 没有初始化 depthFar
// ✗ 没有初始化 fov / hudFov
// ✗ 没有调用 getViewRotationMatrix
// ✗ 没有调用 prepareCullFrustum
// ✗ 没有调用 setupPerspective
```

**`Camera.setup()` 里做了什么？**

```java
// Camera.java 1.20.1 (Mojang)
public void setup(BlockGetter level, Entity entity, boolean detached,
                  boolean thirdPerson, float partialTick) {
    this.initialized = true;
    this.level = level;
    this.entity = entity;
    // 计算玩家眼睛位置
    double x = ... 基于 entity.getEyePosition(partialTick);
    double y = ...
    double z = ...
    this.setPosition(x, y, z);        // 设为玩家位置
    this.setRotation(yaw, pitch);     // 设为玩家朝向
    
    // 注意：setup() 内部也会调用 setPosition/setRotation
    // 但 setup() 不会调用 prepareCullFrustum 或 setupPerspective
    // 这些是由 GameRenderer.renderLevel() 在外部调用的！
}
```

**关键发现：** `Camera.setup()` 只初始化位置 + 旋转 + 标记。`prepareCullFrustum()` 和 `setupPerspective()` 是 `GameRenderer.renderLevel()` 调用的。新项目跳过了 `GameRenderer.renderLevel()`，所以这些调用也跳过了。

---

## 3. LevelRenderer.renderLevel() 内部依赖什么

`LevelRenderer.renderLevel()` 内部会读取 Camera 的以下状态：

```java
// LevelRenderer.java 1.20.1 (简化)
public void renderLevel(PoseStack poseStack, float partialTick, long nanos,
                        boolean renderBlockOutline, Camera camera,
                        GameRenderer gameRenderer, LightTexture lightTexture,
                        Matrix4f projectionMatrix) {
    
    // ① 用 Camera 的视图矩阵构建裁剪视锥体
    Frustum frustum = new Frustum(
        poseStack.last().pose(),  // 模型视图矩阵
        projectionMatrix           // 投影矩阵
    );
    // 注意：这里 poseStack.last().pose() 应该 = 单位矩阵（从 GameRenderer 传入时）
    // 但如果用户往 poseStack 里 mul 了投影矩阵，frustum = projection × projection，全裁掉
    
    // ② 用 camera.position() 获取视点原点
    //    如果 camera.position() 没被正确设置（setup 后被覆盖了但矩阵没更新），
    //    可能返回玩家位置而不是虚拟相机位置
    
    // ③ 用 lightTexture.updateLightTexture() 更新光照纹理
    //    这个在新项目里单独调用了，没问题
    
    // ④ 渲染区块
    //    - 遍历已构建的区块
    //    - 用 frustum 测试每个区块是否可见
    //    - 如果 frustum 是错的，所有区块都被裁掉
    
    // ⑤ 渲染实体
    //    - 用 frustum 测试实体可见性
    
    // ⑥ 渲染云（不经过 frustum 测试！）
    renderClouds(poseStack, projectionMatrix, partialTick, camera.position());
    // 这就是云可见而其他东西不可见的原因
}
```

**为什么只有云可见：**
- 区块和实体渲染**经过 frustum 裁减**，如果 camera 的视图矩阵/投影矩阵没正确设置，frutum 把所有内容裁掉
- 云渲染**不经过 frustum 裁减**，直接用 world position 画大四边形，不受面试锥体影响
- 因此当 camera 状态不完整时，只有云能显示

---

## 4. 原项目 vs 新项目：渲染管线入口对比

### 原项目：`gameRenderer.render()`

```
gameRenderer.render(deltaTracker, !isOutOfMemoryRecovery)
├── gameRenderer.render(DeltaTracker, boolean)  // NeoForge 覆写过的方法
│   ├── gameRenderer.extract(deltaTracker, shouldRenderLevel)
│   │   ├── extractCamera()            ← 计算 mainCamera 状态
│   │   ├── extractGui()               ← 被 Mixin 抑制
│   │   ├── extractWindow()            ← 被 Mixin 强制设为 stream 宽高
│   │   └── ...
│   ├── RenderSystem.executePendingTasks()
│   ├── ClientHooks.fireRenderFramePre()
│   ├── gameRenderer.render(deltaTracker, boolean)  // 内部版本
│   │   ├── renderLevel()
│   │   │   ├── resetPoseStack()       ← 重置 PoseStack
│   │   │   ├── mainCamera.setup()     ← 被 Mixin 拦截后 override 位置
│   │   │   ├── buildProjectionMatrix()
│   │   │   ├── prepareCullFrustum()   ← 自动调用了！
│   │   │   ├── setupPerspective()     ← 自动调用了！
│   │   │   └── levelRenderer.renderLevel()  ← 最终调这里
│   │   ├── renderItemInHand()         ← 被 Mixin 抑制
│   │   └── ...
│   └── ClientHooks.fireRenderFramePost()
```

**原项目的三个 Mixins 拦截点：**

| Mixin | 拦截位置 | 做了什么 |
|-------|----------|----------|
| `GameRendererMixin` | `extractGui()` | 跳过 GUI 渲染 |
| `GameRendererMixin` | `extractWindow()` | 覆盖窗口宽高为 stream 配置 |
| `GameRendererMixin` | `renderItemInHand()` | 跳过手中物品渲染 |
| `GlCommandEncoderMixin` | `presentTexture()` | 替换 drawFbo 并发送 Spout |

**注意：** 原项目**没有**跳过 `GameRenderer.render()` 的任何核心步骤。它只是让全管线正常执行，但在外部 swap 了 RenderTarget 和 Camera。Mixins 只抑制了 GUI / 手模型这些不必要的输出。

### 新项目：直接 `levelRenderer.renderLevel()`

```java
// 跳过了 GameRenderer.render() →
//   ✗ extractCamera()         → Camera 没被正常更新
//   ✗ extractWindow()         → 窗口状态与离屏目标不一致
//   ✗ renderLevel() 内部做了:
//     ✗ resetPoseStack()      → PoseStack 可能残留投影矩阵
//     ✗ mainCamera.setup()    → dummyCamera.setup() 走的是玩家数据 
//     ✗ buildProjectionMatrix() → 外部创建的，但不匹配 Camera 内部状态
//     ✗ prepareCullFrustum()   → 没调用！
//     ✗ setupPerspective()    → 没调用！
```

---

## 5. 为什么新项目"之前只有云可见，现在全黑"

### 第一阶段：只有云可见

问题：

1. `poseStack.mulPoseMatrix(projection)` — 造成 frustum = projection × projection，所有地形/实体被裁掉
2. Camera 的 `prepareCullFrustum` / `setupPerspective` 没调用 — frustum 使用默认（未正确初始化）的 Camera 状态
3. `levelRenderer.update(camera)` 没调用 — 区块可能没在虚拟相机位置加载

**为什么云还在：** 云不经过 frustum 测试。

### 第二阶段：全黑

这可能是因为某个变化导致：

1. 修复了 poseStack 问题（移除了 `mulPoseMatrix`），但 camera 状态问题依然存在
2. frustum 变成 identity × projection（看起来正确），但 camera 内部状态不一致导致渲染完全失败
3. Spout 发送了一个完全黑色的 frame（渲染没产生有效像素）

或者 Spout 通信出了问题——`renderTarget.frameBufferId` 字段名不匹配（Yarn vs Mojang），导致：
- 如果编译通过了但 `frameBufferId` 不存在 → 可能用了 Yarn 映射而不是 Mojang（与 build.gradle 矛盾）
- 如果用 Mojang，`renderTarget.frameBufferId` 编译不通过 → 修改后用了 `fbo`，但可能值不对

---

## 6. 关键差异表格

| 方面 | 原项目 | 新项目 | 影响 |
|------|--------|--------|------|
| **渲染入口** | `GameRenderer.render()` | `LevelRenderer.renderLevel()` 直接 | 丢失 extract/render 全流程初始化 |
| **Camera 初始化** | 反射设置 11 个字段/方法 | 只设了位置+旋转（4 个字段） | frustum 不可用 |
| **prepareCullFrustum** | 反射调用 | 未调用 | 视锥体未构建 |
| **setupPerspective** | 反射调用 | 未调用 | 投影参数未同步到 Camera 内部 |
| **depthFar** | `renderDistance × 64` | 未设置 | 默认值可能不对 |
| **fov / hudFov** | 反射设置 | 未设置 | Camera 内部 fov 与投影矩阵不一致 |
| **getViewRotationMatrix** | 反射调用并缓存结果 | 未调用 | 视图旋转矩阵未计算 |
| **levelRenderer.update** | 渲染前调用 | 未调用 | 区块可能未加载到正确位置 |
| **RenderTarget 切换** | `mainRenderTarget = target` | `mainRenderTarget = target` | 一致 |
| **Camera 切换** | `mainCamera = dummyCamera` | `mainCamera = dummyCamera` | 一致 |
| **GUI 抑制** | Mixin 拦截 extractGui | Mixin 拦截 hideGui | hideGui 方式也可行 |
| **FBO 发送** | Mixin 拦截 presentTexture | 直接读 fbo | 新方法更直接，但缺少 blitToScreen |
| **多重采样** | blitToScreen() 解析 | 未解析 | 可能有残留 MSAA 问题 |
| **Spout 上下文** | ScopedValue 绑定 | 无绑定 | 新项目没用到 Mixin 改 FBO |
| **帧率控制** | 活跃流时主游戏降低到 15fps | 默认 60fps | 性能影响 |

---

## 7. Camera 私有 API 在各版本的差异

**1.16-1.20.1（Yarn 命名 → Mojang 命名）：**

| 方法/字段 | Yarn 1.16-1.20 | Mojang 1.20.1 | 可见性 | 参数 |
|-----------|----------------|----------------|--------|------|
| 位置设置 | `setPosition(x, y, z)` | `setPosition(x, y, z)` | private | (double, double, double) |
| 旋转设置 | `setRotation(yaw, pitch)` | `setRotation(yaw, pitch)` | private | (float, float) |
| 初始化标记 | `initialized` | `initialized` | private | boolean |
| 远平面 | `farPlane` | `depthFar` | private | float |
| FOV | `fov` | `fov` | private | float |
| HUD FOV | `hudFov` | `hudFov` | private | float |
| 视图旋转矩阵 | `cachedViewRotMatrix` | `cachedViewRotMatrix` | private | Matrix4f |
| 视图旋转矩阵获取 | `getViewRotationMatrix(Matrix4f)` | `getViewRotationMatrix(Matrix4f)` | private | →Matrix4f |
| 视锥体准备 | `prepareCullFrustum(Matrix4fc, Matrix4f, Vec3)` | `prepareCullFrustum(Matrix4fc, Matrix4f, Vec3)` | private | void |
| 透视设置 | `setupPerspective(float,float,float,float,float)` | `setupPerspective(float,float,float,float,float)` | private | void |

**1.21+（NeoForge 原项目）：**
- `setRotation` 变为 3 个参数 (yaw, pitch, roll)，新增 roll 轴
- Camera 内部架构重构，原项目使用 Java 25 FFM API 做反射

---

## 8. AngleConvert 角度转换细节

### Minecraft 相机坐标系

- **Yaw：** 0=南方(+Z)，逆时针增加，90=西方(-X)，180=北方(-Z)，270=东方(+X)
- **Pitch：** 0=水平，正=向下看，负=向上看
- **Roll：** Minecraft 正常游戏不用，但 Camera.setRotation 从 1.21 开始支持

### 原项目的角度转换

```java
// 四元数 → 欧拉角 (Quaternion → Minecraft YXZ 欧拉)
public static Vector3f convert(Quaternionf source, Vector3f target) {
    source.getEulerAnglesYXZ(target);           // target = (pitch, yaw, roll) 弧度
    target.set(
        Math.toDegrees(-target.x),              // pitch: 取反 → 度
        Math.toDegrees(Math.PI - target.y),     // yaw: π - yaw → 度（Minecraft 约定）
        Math.toDegrees(-target.z)               // roll: 取反 → 度
    );
    return target;  // 返回值 = (pitchDeg, yawDeg, rollDeg)
}

// Application in setupInternal:
Vector3f angles = AngleConvert.convert(quaternion, new Vector3f());
// angles.x = pitch, angles.y = yaw, angles.z = roll
SET_ROTATION.invokeExact(camera, angles.y, angles.z, angles.z);  // yaw, roll, roll ← BUG?
```

### 新项目的角度转换

```java
// AngleConvert.java (新项目)
public static Vector3f toEulerAngles(Quaternionf q) {
    Vector3f euler = new Vector3f();
    q.getEulerAnglesYXZ(euler);                 // euler = (pitch, yaw, roll) 弧度
    euler.set(
        (float) Math.toDegrees(-euler.x),       // pitch
        (float) Math.toDegrees(Math.PI - euler.y), // yaw
        (float) Math.toDegrees(-euler.z)        // roll
    );
    return euler;  // (pitch, yaw, roll)
}

// Application in CameraSetup:
Vector3f angles = AngleConvert.toEulerAngles(...);
// angles.x = pitch, angles.y = yaw, angles.z = roll
accessor.livehelper$setRotation(angles.y, angles.x);  // setRotation(yaw, pitch)
```

1.20.1 的 `setRotation` 只接受 2 参数 (yaw, pitch)，所以抛弃 roll，用 `angles.y, angles.x` 是合理的。

---

## 9. 正确的渲染方式（两种方案）

### 方案 A：原项目的做法（推荐）

**不要绕过 `GameRenderer.render()`。** 交换 RenderTarget 和 Camera，然后调用完整渲染管线：

```java
void renderFrame() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return;
    
    // ① 计算帧命令
    FrameCommand cmd = engine.computeFrame();
    if (cmd == null) return;
    
    // ② 保存原始状态
    // 原项目用 access transformer，Fabric 用 access widener
    RenderTarget prevTarget = mc.mainRenderTarget;
    Camera prevCamera = mc.gameRenderer.mainCamera;
    boolean prevHideGui = mc.options.hideGui;
    
    try {
        // ③ Swap 离屏目标
        mc.mainRenderTarget = renderTarget;
        mc.gameRenderer.mainCamera = dummyCamera;
        mc.options.hideGui = true;
        
        // ④ 完整初始化 Camera
        CameraSetup.apply(dummyCamera, cmd, width, height, renderDistance);
        // 这一步内部必须调用 prepareCullFrustum 和 setupPerspective！
        
        // ⑤ 更新区块（让 chunk builder 知道新相机位置）
        mc.levelRenderer.update(dummyCamera);
        
        // ⑥ 用右键上下文跑完整渲染管线
        try (var ctx = activateRenderContext(cmd, width, height, renderDistance)) {
            mc.gameRenderer.extract(mc.getDeltaTracker(), false);
            RenderSystem.executePendingTasks();
            mc.gameRenderer.render(mc.getDeltaTracker(), false);
        }
        // extract 和 render 内部会被 Mixin 拦截：
        // - extractGui / renderItemInHand 被抑制
        // - extractWindow 强制设为 stream 分辨率
        
        // ⑦ blitToScreen 解析多重采样
        renderTarget.blitToScreen();
        
        // ⑧ Spout 发送
        spoutSender.send(renderTarget.fbo, renderTarget.width, renderTarget.height);
        
    } catch (Exception e) {
        LOGGER.error("Render error", e);
    } finally {
        // ⑨ 恢复
        mc.mainRenderTarget = prevTarget;
        mc.gameRenderer.mainCamera = prevCamera;
        mc.options.hideGui = prevHideGui;
        prevTarget.bindWrite(true);
    }
}
```

### 方案 B：修复目前的直接调用方式（不推荐，但改动小）

如果不愿改完整管线，必须补齐 Camera 初始化：

```java
public static void applyFull(Camera camera, FrameCommand cmd,
                              int width, int height, int renderDistance) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return;
    
    camera.setup(mc.level, mc.player, false, false, 1.0F);
    
    // 先用反射/Invoker 设置基础值
    // (CameraAccessor) camera).livehelper$setPosition(cmd.x(), cmd.y(), cmd.z());
    // (CameraAccessor) camera).livehelper$setRotation(yaw, pitch);
    
    // 然后必须补上这三个步骤：
    // ① 从 cachedViewRotMatrix 获取当前矩阵，调用 getViewRotationMatrix 刷新
    //    反射: camera.cachedViewRotMatrix → getViewRotationMatrix(cachedViewRotMatrix)
    
    // ② 构建投影矩阵
    //    Matrix4f proj = new Matrix4f().perspective(fovRad, aspect, 0.05f, far);
    
    // ③ prepareCullFrustum(viewRot, proj, camera.position())
    //    反射调用 Camera.prepareCullFrustum
    
    // ④ setupPerspective(0.05f, far, fov, width, height)
    //    反射调用 Camera.setupPerspective
    
    // ⑤ 设置 depthFar = renderDistance * 64 (不是 16)
    // ⑥ 设置 fov / hudFov = cmd.fov()
}
```

---

## 10. 其他需要注意的细节

### 10.1 `depthFar = renderDistance × 64`

原项目设置 `Camera.depthFar = renderDistance * 64`。但在新项目里用的是 `far = renderDistance × 16`。Minecraft 的渲染距离单位是"区块"（16 blocks），而相机内部 `depthFar` 是方块为单位。

```java
// 原项目
DEPTH_FAR.set(camera, config.renderDistance() * 64);   // 64 blocks × 渲染距离?
// 新项目
float farPlane = config.renderDistance() * 16f;         // 16 blocks × 渲染距离（正确）
```

注意：1.20.1 的 `LevelRenderer` 内部用的是 `renderDistance * 16`。原项目的 ×64 可能是 bug 或是版本差异。1.20.1 应该用 ×16。

但关键是新项目**根本没有设置** `Camera.depthFar`，这个字段默认是 0。

### 10.2 `levelRenderer.update(camera)` 的作用

在渲染前调用 `levelRenderer.update(camera)` 会触发：

1. 更新 `ViewArea` 的相机位置
2. 重新计算哪些区块应该被加载/构建
3. 使 `ViewArea` 内部的 `chunksToRebuild` 等集合与当前相机对齐

如果不调用，chunk 构建中心依然在**玩家位置**。如果你的虚拟相机离玩家很远，那些位置没有构建好的区块，渲染内容为空。

```java
// 原项目在 renderInternal 开头
if (shouldRenderLevel) {
    minecraft.levelRenderer.update(camera);  // ← 必调！
}
```

### 10.3 `blitToScreen()` 的作用

`RenderTarget.blitToScreen()` 做两件事：

1. 如果有多重采样 FBO（`useStencil` 为 true），把 MSAA FBO resolve 到颜色纹理
2. `_blitToScreen(width, height)` — 把颜色纹理 blit 到当前绑定的 FBO

如果不调 `blitToScreen()`，直接读 `renderTarget.fbo`：
- 如果没有多重采样：读的就是颜色纹理所在的 FBO → 可以
- 如果有多重采样：读的是 MSAA FBO 的 ID，`glReadPixels` / Spout 读出的是未 resolve 的数据（可能坏掉）

新项目应该加一行 `renderTarget.blitToScreen()`。

### 10.4 `angleConvert` 的 yaw 符号

注意 `AngleConvert.toEulerAngles` 中的 `π - yaw`：

```java
euler.set(
    Math.toDegrees(-euler.x),           // pitch
    Math.toDegrees(Math.PI - euler.y),  // yaw ← π - yaw
    Math.toDegrees(-euler.z)            // roll
);
```

这个 `π - yaw` 是因为：

- `getEulerAnglesYXZ()` 返回的 yaw 是数学坐标系（逆时针为正，0 朝 +Z）
- Minecraft yaw 是 `(π - 数学角度)` 的约定

如果角度不对，相机朝向会歪掉，摄像机对着没有区块/实体的方向。

### 10.5 Spout 发送的 FBO ID

新项目直接用 `renderTarget.fbo`（Mojang 命名）或 `renderTarget.frameBufferId`（Yarn 命名）发送给 Spout。

但在原项目中，`renderTarget.blitToScreen()` 会把颜色纹理 blit 到另一个 FBO，然后通过 `GlCommandEncoderMixin` 拦截 `presentTexture()` 来发送。

新项目的做法：只要确保 `renderTarget.fbo` 是颜色纹理的有效 FBO ID，就可以直接发给 Spout。

注意：`MainTarget` 在构造函数中创建了两个 FBO：
- `fbo` = 主 FBO（渲染目标）
- `colorTextureId` = 颜色纹理 ID
- 如果 `useStencil = true`，还有一个深度模板缓冲区

发送 `renderTarget.fbo` 是正确的。

### 10.6 线程安全

`GameRenderer.render()` 必须在**渲染线程**调用。Minecraft 的渲染线程就是主线程（`Minecraft.run()` 运行的线程）。

新项目通过 `MainScheduler.tick()` + 任务队列在**主线程**执行 `renderFrame()`，所以线程上没问题。

但需要确保 `dummyCamera.tick()` 在每 tick 被调用（`StreamManager.tickAll()` 中调），否则 Camera 的 `attributeProbe` 不更新可能导致内部错误。

---

## 11. 给新项目的修复路线图

### 第一步：修正 Camera 初始化

```
CameraSetup.java 增加：
- 设置 depthFar, fov, hudFov
- 调用 getViewRotationMatrix (如果需要)
- 调用 prepareCullFrustum
- 调用 setupPerspective
```

### 第二步：走 GameRenderer 完整管线

```
StreamInstance.java:
- 不再直接调 levelRenderer.renderLevel()
- 改为 swap 后调 gameRenderer.render() (+ extract 先)
```

### 第三步：补充 Mixin

```
确保 GameRendererMixin 覆盖 extractWindow（窗口分辨率）:
  @Inject(method = "extract", ...) 拦截 extractWindow
  把 windowState.width/height 设为 stream 的配置

GameRendererMixin.afterCameraSetup 完善:
  保证 afterCameraSetup inject 能正确 override 相机位置
```

### 第四步：levelRenderer.update + blitToScreen

```
renderInternal():
  mc.levelRenderer.update(dummyCamera);
  // ... render ...
  renderTarget.blitToScreen();
  spoutSender.send(renderTarget.fbo, ...);
```

---

## 12. 常见排查清单

如果修复后依然有问题，按以下顺序排查：

| # | 检查项 | 方法 |
|---|--------|------|
| 1 | `renderTarget.fbo` 是否正确 | 在 Spout send 前打印 `LOGGER.info("FBO: {}", renderTarget.fbo)` |
| 2 | Camera 位置是否正确 | `LOGGER.info("Camera: {} {} {}", x, y, z)` |
| 3 | Camera 朝向是否正确 | 打印 `camera.getViewRotationMatrix()` 的值 |
| 4 | Frustum 是否裁掉了所有内容 | 临时设置 `renderDistance = 2` 看能否看到近处方块 |
| 5 | levelRenderer.update 是否执行 | 在 update 前后打印 chunk 数量 |
| 6 | Spout DLL 是否正常加载 | 看启动日志是否有 Spout 加载成功/失败信息 |
| 7 | 编译映射是否正确 | 确认 `frameBufferId` → `fbo` |
| 8 | OBS Spout2 插件版本 | 要求 v1.9.0+ 且和 OBS 版本匹配 |
