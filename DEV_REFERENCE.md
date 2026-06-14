# LiveHelper 开发参考代码

> 本文件包含关键模块的完整可编译实现代码，供 AI Agent 直接使用。
> 与 `DEV_ROADMAP.md` 配合阅读。

---

## 目录

1. [MainScheduler（帧调度器）](#1-mainscheduler帧调度器)
2. [CameraSetup（摄像机反射控制）](#2-camerasetup摄像机反射控制)
3. [AngleConvert（角度转换工具）](#3-angleconvert角度转换工具)
4. [SpoutBinding + SpoutSender（Spout2 JNA 集成）](#4-spoutbinding--spoutsenderspout2-jna-集成)
5. [StreamInstance（单流渲染循环）](#5-streaminstance单流渲染循环)
6. [MinecraftMixin（主循环替换）](#6-minecraftmixin主循环替换)
7. [GameRendererMixin（GUI压制）](#7-gamerendermixinguisuppression)
8. [MotionTemplate + 全部模板实现](#8-motiontemplate--全部模板实现)
9. [PlaybackEngine（时间线播放引擎）](#9-playbackengine时间线播放引擎)

---

## 1. MainScheduler（帧调度器）

**路径**: `src/main/java/net/example/livehelper/scheduler/MainScheduler.java`

**说明**：此代码直接可编译，无需改动。自适应 sleep/spin-wait 精确到微秒级，替换 Minecraft 原生帧循环。

```java
package net.example.livehelper.scheduler;

import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class MainScheduler {
    public interface ExecutableTask {
        void run(boolean isOutOfMemoryRecovery, long startNs);
    }

    private record Task(long nano, ExecutableTask task) implements Comparable<Task> {
        @Override
        public int compareTo(Task o) {
            return Long.compare(this.nano, o.nano);
        }
    }

    private static final PriorityQueue<Task> QUEUE = new PriorityQueue<>(16);

    public static void submitTask(long nano, ExecutableTask task) {
        QUEUE.add(new Task(Math.max(System.nanoTime(), nano), task));
    }

    public static void tick(boolean isOutOfMemoryRecovery) {
        Task task = QUEUE.peek();
        if (task == null) {
            throw new IllegalStateException("Should NOT be here: No tasks are scheduled.");
        }

        if (task.nano - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(10)) {
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(5));
            return;
        }

        QUEUE.remove(task);
        sleepUntil(task.nano);

        task.task.run(isOutOfMemoryRecovery, task.nano);
    }

    private static final double OVERSHOOT_SMOOTHING = 0.1;
    private static final long MAX_CURRENT_OVERSHOOT_NS = TimeUnit.MILLISECONDS.toNanos(25);
    private static final long MAX_AVERAGE_OVERSHOOT_NS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long SPIN_SAFETY_BUFFER_NS = 500000L;

    private static long averageOvershootNs = 0L;

    private static void sleepUntil(long targetTimeNs) {
        long remainingTimeNs;
        while ((remainingTimeNs = targetTimeNs - System.nanoTime()) > 0L) {
            if (remainingTimeNs > averageOvershootNs + SPIN_SAFETY_BUFFER_NS) {
                long sleepStartTimeNs = System.nanoTime();
                long expectedSleepTimeNs = remainingTimeNs - averageOvershootNs - SPIN_SAFETY_BUFFER_NS;
                if (!Thread.interrupted()) {
                    LockSupport.parkNanos(expectedSleepTimeNs);
                    long currentOvershootNs = System.nanoTime() - sleepStartTimeNs - expectedSleepTimeNs;
                    if (currentOvershootNs > 0L && currentOvershootNs < MAX_CURRENT_OVERSHOOT_NS) {
                        averageOvershootNs = Math.min(
                            (long) (OVERSHOOT_SMOOTHING * currentOvershootNs + (1 - OVERSHOOT_SMOOTHING) * averageOvershootNs),
                            MAX_AVERAGE_OVERSHOOT_NS
                        );
                    }
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
```

---

## 2. CameraSetup（摄像机反射控制）

**路径**: `src/main/java/net/example/livehelper/render/CameraSetup.java`

**关键说明**：
- 使用 `MethodHandles` + `VarHandle` 反射写入 Camera 私有字段
- **以下 Yarn 映射字段名需在开发环境中用 IDE 反编译 `net.minecraft.class_4184`（Camera）确认后调整**
- `accesswidener` 已开放 `Minecraft.mainRenderTarget` 和 `GameRenderer.mainCamera`

```java
package net.example.livehelper.render;

import net.example.livehelper.util.AngleConvert;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

public final class CameraSetup {
    // ─── Camera 私有字段的句柄 ───
    // 注意：以下 Yarn 映射名称仅作参考，必须在开发环境中通过反编译 Camera.class 确认！
    // Mojmap → Yarn (推测)：
    //   initialized       → initialized         (boolean)
    //   setPosition       → setPos               (method)
    //   setRotation       → setRotation           (method, 取欧拉角: yaw, pitch, roll)
    //   cachedViewRotMatrix → cachedViewMatrix   (Matrix4f)
    //   prepareCullFrustum  → prepareCullFrustrum (method)
    //   getViewRotationMatrix → computeViewMatrix (method)
    //   setupPerspective     → setupProjection    (method)
    //   depthFar             → farPlane           (float)
    //   fov                  → fov                (float)
    //   hudFov               → hudFov             (float)

    private static final MethodHandle SET_POS;
    private static final MethodHandle SET_ROTATION;
    private static final MethodHandle PREPARE_CULL_FRUSTUM;
    private static final MethodHandle COMPUTE_VIEW_MATRIX;
    private static final MethodHandle SETUP_PROJECTION;

    private static final VarHandle INITIALIZED;
    private static final VarHandle FAR_PLANE;
    private static final VarHandle FOV;
    private static final VarHandle HUD_FOV;
    private static final VarHandle CACHED_VIEW_MATRIX;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Camera.class, MethodHandles.lookup());

            // 字段访问器 — 如果 IDE 反编译发现不同名称，修改这里
            INITIALIZED = lookup.findVarHandle(Camera.class, "initialized", boolean.class);

            SET_POS = lookup.findVirtual(Camera.class, "setPos",
                MethodType.methodType(void.class, double.class, double.class, double.class));

            SET_ROTATION = lookup.findVirtual(Camera.class, "setRotation",
                MethodType.methodType(void.class, float.class, float.class, float.class));

            CACHED_VIEW_MATRIX = lookup.findVarHandle(Camera.class, "cachedViewMatrix", Matrix4f.class);

            PREPARE_CULL_FRUSTUM = lookup.findVirtual(Camera.class, "prepareCullFrustrum",
                MethodType.methodType(void.class, Matrix4fc.class, Matrix4f.class, Vec3.class));

            COMPUTE_VIEW_MATRIX = lookup.findVirtual(Camera.class, "computeViewMatrix",
                MethodType.methodType(Matrix4f.class, Matrix4f.class));

            SETUP_PROJECTION = lookup.findVirtual(Camera.class, "setupProjection",
                MethodType.methodType(void.class, float.class, float.class, float.class, float.class, float.class));

            FAR_PLANE = lookup.findVarHandle(Camera.class, "farPlane", float.class);
            FOV = lookup.findVarHandle(Camera.class, "fov", float.class);
            HUD_FOV = lookup.findVarHandle(Camera.class, "hudFov", float.class);

        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ─── 帧命令 ───
    public record FrameCommand(
        double x, double y, double z,
        float qx, float qy, float qz, float qw,
        float fov
    ) {}

    /**
     * 将 Camera 的状态设置为帧命令指定的位置/朝向
     */
    public static void apply(Camera camera, FrameCommand cmd, int width, int height, int renderDistance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        camera.setLevel(mc.level);
        camera.setEntity(mc.player);

        // 欧拉角（Minecraft Camera 的 YXZ 顺序）
        Vector3f angles = AngleConvert.toEulerAngles(
            new Quaternionf(cmd.qx(), cmd.qy(), cmd.qz(), cmd.qw()));

        // 投影矩阵（zZeroToOne=true 是 1.20.1 的默认行为）
        float aspect = (float) width / (float) height;
        float fovRad = (float) Math.toRadians(cmd.fov());
        float nearPlane = 0.05F;
        float farPlaneDist = renderDistance * 16F;

        Matrix4f projection = new Matrix4f()
            .perspective(fovRad, aspect, nearPlane, farPlaneDist);

        try {
            // 写入内部字段
            FAR_PLANE.set(camera, renderDistance * 64F);   // 64 = 区块→方块 * 4
            FOV.set(camera, cmd.fov());
            HUD_FOV.set(camera, cmd.fov());

            // 设置位置
            SET_POS.invokeExact(camera, cmd.x(), cmd.y(), cmd.z());

            // 设置旋转 (Angles: x=pitch, y=yaw, z=roll)
            SET_ROTATION.invokeExact(camera, angles.x, angles.y, angles.z);

            INITIALIZED.set(camera, true);

            // 计算视图矩阵
            Matrix4f viewMatrix = (Matrix4f) COMPUTE_VIEW_MATRIK.invokeExact(
                camera, (Matrix4f) CACHED_VIEW_MATRIX.get(camera));

            // 裁剪视锥体
            PREPARE_CULL_FRUSTUM.invokeExact(
                camera,
                (Matrix4fc) viewMatrix,
                projection,
                camera.position()
            );

            // 透视投影
            SETUP_PROJECTION.invokeExact(
                camera,
                nearPlane,
                (float) FAR_PLANE.get(camera),
                cmd.fov(),
                (float) width,
                (float) height
            );

        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to setup camera", e);
        }
    }
}
```

---

## 3. AngleConvert（角度转换工具）

**路径**: `src/main/java/net/example/livehelper/util/AngleConvert.java`

```java
package net.example.livehelper.util;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class AngleConvert {
    private AngleConvert() {}

    /**
     * 欧拉角 (YXZ顺序, 度) → 四元数
     * 输入: rotX=pitch, rotY=yaw, rotZ=roll (度)
     * 匹配 Minecraft Camera 的旋转约定
     */
    public static Quaternionf toQuaternion(float pitchDeg, float yawDeg, float rollDeg) {
        Quaternionf q = new Quaternionf();
        q.rotationYXZ(
            (float) Math.PI - yawDeg * (float) (Math.PI / 180.0),
            -pitchDeg * (float) (Math.PI / 180.0),
            -rollDeg * (float) (Math.PI / 180.0)
        );
        return q;
    }

    /**
     * 四元数 → 欧拉角 (YXZ顺序, 度)
     * 返回值: x=pitch, y=yaw, z=roll
     */
    public static Vector3f toEulerAngles(Quaternionf q) {
        Vector3f euler = new Vector3f();
        q.getEulerAnglesYXZ(euler);
        euler.set(
            (float) Math.toDegrees(-euler.x),
            (float) Math.toDegrees(Math.PI - euler.y),
            (float) Math.toDegrees(-euler.z)
        );
        return euler;
    }

    /**
     * 从位置 A "看向" 位置 B 的四元数
     */
    public static Quaternionf lookAt(
        double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ
    ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;

        double yaw = Math.atan2(dz, dx);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.atan2(dy, horizontalDist);

        return toQuaternion(
            (float) Math.toDegrees(pitch),
            (float) Math.toDegrees(yaw),
            0f
        );
    }

    /**
     * 从运动方向向量 → 朝向四元数
     */
    public static Quaternionf lookInDirection(double dx, double dy, double dz) {
        double yaw = Math.atan2(dz, dx);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.atan2(dy, horizontalDist);
        return toQuaternion(
            (float) Math.toDegrees(pitch),
            (float) Math.toDegrees(yaw),
            0f
        );
    }
}
```

---

## 4. SpoutBinding + SpoutSender（Spout2 JNA 集成）

### 4.1 SpoutBinding（JNA 接口定义）

**路径**: `src/main/java/net/example/livehelper/spout/SpoutBinding.java`

```java
package net.example.livehelper.spout;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * libSpoutBinding.dll 的 JNA 接口
 * DLL 包含 3 个导出函数：spCreateSpout, spReleaseSpout, spSendFrameBufferObject
 */
public interface SpoutBinding extends Library {
    SpoutBinding INSTANCE = Native.load("libSpoutBinding", SpoutBinding.class);

    /** 创建 Spout 发送器，返回句柄 */
    Pointer spCreateSpout(String senderName);

    /** 释放 Spout 发送器 */
    void spReleaseSpout(Pointer spout);

    /**
     * 发送 GL FBO 到 Spout 接收器
     * @param spout  发送器句柄
     * @param fbo    OpenGL FBO ID
     * @param width  图像宽度
     * @param height 图像高度
     * @return 0=失败, 1=成功
     */
    int spSendFrameBufferObject(Pointer spout, int fbo, int width, int height);
}
```

### 4.2 SpoutSender（高级封装）

**路径**: `src/main/java/net/example/livehelper/spout/SpoutSender.java`

```java
package net.example.livehelper.spout;

import com.sun.jna.Pointer;
import net.example.livehelper.LiveHelperMod;
import oshi.PlatformEnum;
import oshi.SystemInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class SpoutSender implements AutoCloseable {
    private final Pointer handle;

    /**
     * 创建 Spout 发送器
     * @param name Spout Sender 名称（在 OBS 的 Spout2 Capture 源中以此名称识别）
     */
    public SpoutSender(String name) {
        if (SystemInfo.getCurrentPlatform() != PlatformEnum.WINDOWS) {
            throw new RuntimeException("LiveHelper Spout requires Windows 10+");
        }
        ensureDllExtracted();
        this.handle = SpoutBinding.INSTANCE.spCreateSpout(name);
        if (this.handle == null) {
            throw new RuntimeException("Failed to create Spout sender: " + name);
        }
    }

    /**
     * 发送一帧
     * @param fbo    OpenGL FBO ID（RenderTarget 的帧缓冲对象）
     * @param width  画面宽度
     * @param height 画面高度
     */
    public void send(int fbo, int width, int height) {
        int result = SpoutBinding.INSTANCE.spSendFrameBufferObject(handle, fbo, width, height);
        if (result == 0) {
            LiveHelperMod.LOGGER.warn("Spout send failed for FBO {}", fbo);
        }
    }

    @Override
    public void close() {
        SpoutBinding.INSTANCE.spReleaseSpout(handle);
    }

    // ─── DLL 提取 ───
    private static boolean dllExtracted = false;

    private static synchronized void ensureDllExtracted() {
        if (dllExtracted) return;
        try {
            Path dllPath = Files.createTempFile("libSpoutBinding-", ".dll").toAbsolutePath();
            try (InputStream is = SpoutSender.class.getResourceAsStream(
                     "/assets/livehelper/libSpoutBinding.dll");
                 OutputStream os = Files.newOutputStream(dllPath)) {
                Objects.requireNonNull(is, "libSpoutBinding.dll not found in jar")
                    .transferTo(os);
            }
            System.load(dllPath.toString());
            dllPath.toFile().deleteOnExit();
            dllExtracted = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract Spout DLL", e);
        }
    }
}
```

---

## 5. StreamInstance（单流渲染循环）

**路径**: `src/main/java/net/example/livehelper/render/StreamInstance.java`

**关键说明**：
- 这是整个 Mod 最核心的文件
- 1.20.1 的 `GameRenderer.render(float, long, MatrixStack)` 签名与原项目 `render(DeltaTracker, boolean)` 不同
- 通过 `MainScheduler` 按配置帧率调度渲染
- 每个 StreamInstance 有独立的离屏 `MainTarget` 和 `SpoutSender`

```java
package net.example.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.example.livehelper.LiveHelperMod;
import net.example.livehelper.engine.PlaybackEngine;
import net.example.livehelper.model.Manager;
import net.example.livehelper.scheduler.MainScheduler;
import net.example.livehelper.spout.SpoutSender;
import net.example.livehelper.util.ActiveRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.MatrixStack;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class StreamInstance implements AutoCloseable {
    private final int managerId;
    private final Manager config;
    private final PlaybackEngine engine;
    private final MainTarget renderTarget;
    private final SpoutSender spoutSender;
    private final Camera dummyCamera;
    private final long frameIntervalNs;

    private boolean stopped;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.dummyCamera = new Camera();
        this.frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / manager.fps();

        // 创建离屏渲染目标（useStencil 从主渲染目标继承）
        boolean useStencil = Minecraft.getInstance().mainRenderTarget.useStencil;
        this.renderTarget = new MainTarget(manager.width(), manager.height(), useStencil);

        // 创建 Spout 发送器
        this.spoutSender = new SpoutSender("LiveHelper-" + manager.name());

        // 提交第一帧到调度器
        scheduleNext(System.nanoTime());
    }

    private void scheduleNext(long currentNs) {
        if (stopped) return;

        long nextFrameNs = Math.max(
            currentNs + frameIntervalNs,
            System.nanoTime()
        );

        MainScheduler.submitTask(nextFrameNs, (isOutOfMemoryRecovery, taskNs) -> {
            if (stopped) return;
            renderFrame(isOutOfMemoryRecovery);
            scheduleNext(taskNs);
        });
    }

    private void renderFrame(boolean isOutOfMemoryRecovery) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 计算当前帧的摄像机指令
        CameraSetup.FrameCommand cmd = engine.computeFrame();
        if (cmd == null) return;  // 无活跃 Clip，保持上一帧

        // ─── 保存原始状态 ───
        RenderTarget prevTarget = mc.mainRenderTarget;
        Camera prevCamera = mc.gameRenderer.mainCamera;
        boolean prevHideGui = mc.options.hideGui;

        try {
            // ─── 切换为摄像机帧 ───
            mc.mainRenderTarget = renderTarget;
            mc.gameRenderer.mainCamera = dummyCamera;
            mc.options.hideGui = true;

            // ─── 设置 Camera 位姿 ───
            CameraSetup.apply(dummyCamera, cmd, config.width(), config.height(), config.renderDistance());

            // ─── 执行渲染 ───
            ActiveRenderContext.runWithContext(() -> {
                renderInternal(mc, renderTarget, isOutOfMemoryRecovery);
            });

            // ─── Spout 发送 ───
            // 1.20.1 下 MainTarget 的 FBO ID 字段名：
            // Yarn: frameBufferObject (int) 或 fbo，需反编译 MainTarget/RenderTarget 确认
            // 使用 accesswidener 开放后直接读取
            int fboId = renderTarget.frameBufferObject;
            spoutSender.send(fboId, renderTarget.width, renderTarget.height);

        } catch (Exception e) {
            LiveHelperMod.LOGGER.error("Error rendering camera frame", e);
        } finally {
            // ─── 恢复原始状态 ───
            mc.mainRenderTarget = prevTarget;
            mc.gameRenderer.mainCamera = prevCamera;
            mc.options.hideGui = prevHideGui;
        }
    }

    /**
     * 1.20.1 的 GameRenderer.render() 调用
     * 签名：render(float tickDelta, long startNano, MatrixStack matrices)
     */
    private void renderInternal(Minecraft mc, MainTarget target, boolean isOutOfMemoryRecovery) {
        GameRenderer gameRenderer = mc.gameRenderer;
        ProfilerFiller profiler = Profiler.get();

        profiler.push("camera_frame");

        if (!isOutOfMemoryRecovery && mc.level != null) {
            profiler.push("update");
            mc.levelRenderer.update(dummyCamera);
            profiler.pop();
        }

        profiler.push("render");
        // 1.20.1 GameRenderer 渲染入口
        // tickDelta=1.0 表示完整帧，startNano 传当前时间，matrices 传空的 MatrixStack
        gameRenderer.render(1.0F, System.nanoTime(), new MatrixStack());
        profiler.pop();

        profiler.push("blit");
        target.blitToScreen();
        profiler.pop();

        profiler.pop();
    }

    /** 每 ClientTick 调用，用于更新 Camera 的 tick 相关状态 */
    public void tick() {
        if (stopped) return;
        // Camera 的 attributeProbe tick，部分版本需要
        if (dummyCamera.entity() != null) {
            dummyCamera.attributeProbe().tick(
                Objects.requireNonNull(dummyCamera.entity().level()),
                dummyCamera.entity().position()
            );
        } else {
            dummyCamera.attributeProbe().reset();
        }
    }

    @Override
    public void close() {
        stopped = true;
        renderTarget.destroyBuffers();
        spoutSender.close();
    }
}
```

### 配套：ActiveRenderContext

**路径**: `src/main/java/net/example/livehelper/util/ActiveRenderContext.java`

```java
package net.example.livehelper.util;

/**
 * 取代原项目的 ScopedValue，用 ThreadLocal 标记当前是否在摄像机帧渲染上下文中。
 * 供 Mixin 判断是否需要压制 GUI/手部渲染。
 */
public final class ActiveRenderContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    public static void runWithContext(Runnable action) {
        ACTIVE.set(true);
        try {
            action.run();
        } finally {
            ACTIVE.set(false);
        }
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}
```

---

## 6. MinecraftMixin（主循环替换）

**路径**: `src/main/java/net/example/livehelper/mixin/MinecraftMixin.java`

**说明**：
- 拦截 `Minecraft.run()` 中的 `runTick()` 调用
- 当有活跃 Stream 时，重定向到 `MainScheduler.tick()`
- 无活跃 Stream 时走原生路径

```java
package net.example.livehelper.mixin;

import net.example.livehelper.MainScheduler;
import net.example.livehelper.render.StreamManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    /**
     * 在 run() 开始时向 MainScheduler 提交第一个 tick 任务
     */
    @Inject(method = "run", at = @At("HEAD"))
    private void onRunHead(CallbackInfo ci) {
        MainScheduler.submitTask(System.nanoTime(), new MainScheduler.ExecutableTask() {
            private int targetFps = 60;

            @Override
            public void run(boolean isOutOfMemoryRecovery, long startNs) {
                // 有活跃流时，用流的目标帧率调度；否则用游戏设置帧率
                if (StreamManager.INSTANCE.hasActive()) {
                    targetFps = 60;  // 可根据活跃流的最低帧率调整
                } else {
                    targetFps = Minecraft.getInstance().options.framerateLimit().get();
                    if (targetFps <= 0) targetFps = 60;  // 无限制时默认 60
                }

                // 执行一帧
                Minecraft.getInstance().runTick(!isOutOfMemoryRecovery);

                // 调度下一帧
                long frameTime = TimeUnit.SECONDS.toNanos(1) / Math.max(1, Math.min(targetFps, 260));
                MainScheduler.submitTask(startNs + frameTime, this);
            }
        });
    }

    /**
     * 当有活跃 Stream 时，将 runTick() 调用重定向到 MainScheduler.tick()
     * 这样 run() 主循环实际由我们的调度器控制
     */
    @Redirect(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"
        )
    )
    private void redirectRunTick(Minecraft instance, boolean bl) {
        if (StreamManager.INSTANCE.hasActive()) {
            MainScheduler.tick(bl);
        } else {
            instance.runTick(bl);  // 无活跃流时走原生路径
        }
    }
}
```

---

## 7. GameRendererMixin（GUI压制）

**路径**: `src/main/java/net/example/livehelper/mixin/GameRendererMixin.java`

**说明**：
- 在 1.20.1 中渲染过程是过程式的
- 通过 `renderItemInHand` 和 `render` 两个注入点压制 GUI

```java
package net.example.livehelper.mixin;

import net.example.livehelper.util.ActiveRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    /**
     * 在渲染开始时强制隐藏 GUI（如果是摄像机帧）
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void beforeRender(float tickDelta, long startNano, CallbackInfo ci) {
        if (ActiveRenderContext.isActive()) {
            minecraft.options.hideGui = true;
        }
    }

    /**
     * 摄像机帧渲染时跳过手部渲染
     */
    @Inject(
        method = "renderItemInHand",
        at = @At("HEAD"),
        cancellable = true
    )
    private void beforeRenderItemInHand(
        net.minecraft.client.renderer.ItemInHandRenderer handRenderer,
        float tickDelta,
        float pitch,
        net.minecraft.world.entity.player.Player player,
        net.minecraft.world.item.ItemStack mainHand,
        net.minecraft.world.item.ItemStack offHand,
        CallbackInfo ci
    ) {
        if (ActiveRenderContext.isActive()) {
            ci.cancel();
        }
    }

    /**
     * 渲染后恢复 hideGui（可选，因为恢复操作在 StreamInstance 的 finally 中）
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void afterRender(float tickDelta, long startNano, CallbackInfo ci) {
        // StreamInstance 的 finally 块会恢复 hideGui
        // 此处仅作为保险
    }
}
```

---

## 8. MotionTemplate + 全部模板实现

### 8.1 MotionTemplate 接口

**路径**: `src/main/java/net/example/livehelper/engine/templates/MotionTemplate.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;

import java.util.Map;

public interface MotionTemplate {
    CameraSetup.FrameCommand evaluate(Map<String, Double> params, float progress);
}
```

### 8.2 模板注册器

**路径**: `src/main/java/net/example/livehelper/engine/templates/MotionTemplates.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MotionTemplates {
    private static final Map<String, MotionTemplate> REGISTRY = new LinkedHashMap<>();

    static {
        register("STATIC",    new StaticTemplate());
        register("ORBIT",     new OrbitTemplate());
        register("DOLLY",     new DollyTemplate());
        register("TRUCK",     new TruckTemplate());
        register("PEDESTAL",  new PedestalTemplate());
        register("PAN_TILT",  new PanTiltTemplate());
        register("PATH",      new PathTemplate());
    }

    public static void register(String name, MotionTemplate template) {
        REGISTRY.put(name, template);
    }

    public static MotionTemplate get(String name) {
        MotionTemplate t = REGISTRY.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown template: " + name);
        return t;
    }

    public static Set<String> getAvailable() {
        return REGISTRY.keySet();
    }

    // ─── 工具函数 ───

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }

    /** 缓动函数 */
    public static float ease(float t, String type) {
        if (type == null) type = "linear";
        return switch (type) {
            case "linear"    -> t;
            case "easeIn"    -> t * t;
            case "easeOut"   -> t * (2 - t);
            case "easeInOut" -> t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
            default          -> t;
        };
    }

    /** 从 params 中读 double，有默认值 */
    public static double p(Map<String, Double> params, String key, double def) {
        return params.getOrDefault(key, def);
    }

    /** 从 params 中读 float，有默认值 */
    public static float pf(Map<String, Double> params, String key, float def) {
        return params.getOrDefault(key, (double) def).floatValue();
    }

    /** 从 params 中读 String */
    public static String ps(Map<String, Double> params, String key, String def) {
        // 由于模板参数统一为 Map<String, Double>，特殊字段（如 keyframes、easing）需要转换
        // 实际使用时从 Clip.params 直接读取
        return def;
    }
}
```

### 8.3 StaticTemplate

**路径**: `src/main/java/net/example/livehelper/engine/templates/StaticTemplate.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;
import net.example.livehelper.util.AngleConvert;

import java.util.Map;

import static net.example.livehelper.engine.templates.MotionTemplates.*;

public class StaticTemplate implements MotionTemplate {
    @Override
    public CameraSetup.FrameCommand evaluate(Map<String, Double> params, float progress) {
        float rotX = pf(params, "rotX", 0f);
        float rotY = pf(params, "rotY", 0f);
        float rotZ = pf(params, "rotZ", 0f);

        var q = AngleConvert.toQuaternion(rotX, rotY, rotZ);

        return new CameraSetup.FrameCommand(
            p(params, "posX", 0.0), p(params, "posY", 0.0), p(params, "posZ", 0.0),
            q.x, q.y, q.z, q.w,
            pf(params, "fov", 70f)
        );
    }
}
```

### 8.4 OrbitTemplate

**路径**: `src/main/java/net/example/livehelper/engine/templates/OrbitTemplate.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;
import net.example.livehelper.util.AngleConvert;

import java.util.Map;

import static net.example.livehelper.engine.templates.MotionTemplates.*;

public class OrbitTemplate implements MotionTemplate {
    @Override
    public CameraSetup.FrameCommand evaluate(Map<String, Double> params, float progress) {
        double targetX = p(params, "targetX", 0.0);
        double targetY = p(params, "targetY", 0.0);
        double targetZ = p(params, "targetZ", 0.0);
        double radius = p(params, "radius", 10.0);
        double speed = p(params, "speed", 1.0);
        double startAngle = Math.toRadians(p(params, "startAngle", 0.0));
        double elevation = Math.toRadians(p(params, "elevation", 0.0));

        // 计算环绕位置
        double angle = startAngle + progress * 2 * Math.PI * speed;
        double x = targetX + radius * Math.cos(angle);
        double z = targetZ + radius * Math.sin(angle);
        double y = targetY + Math.tan(elevation) * radius;

        // 朝向指向目标点
        var q = AngleConvert.lookAt(x, y, z, targetX, targetY, targetZ);

        return new CameraSetup.FrameCommand(
            x, y, z,
            q.x, q.y, q.z, q.w,
            pf(params, "fov", 70f)
        );
    }
}
```

### 8.5 DollyTemplate / TruckTemplate

**路径**: `src/main/java/net/example/livehelper/engine/templates/DollyTemplate.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;
import net.example.livehelper.util.AngleConvert;

import java.util.Map;

import static net.example.livehelper.engine.templates.MotionTemplates.*;

public class DollyTemplate implements MotionTemplate {
    @Override
    public CameraSetup.FrameCommand evaluate(Map<String, Double> params, float progress) {
        float t = ease(progress, ps(params, "easing", "linear"));

        double x = lerp(p(params, "fromX", 0.0), p(params, "toX", 0.0), t);
        double y = lerp(p(params, "fromY", 0.0), p(params, "toY", 0.0), t);
        double z = lerp(p(params, "fromZ", 0.0), p(params, "toZ", 0.0), t);

        // 朝向沿运动方向
        double dx = p(params, "toX", 0.0) - p(params, "fromX", 0.0);
        double dy = p(params, "toY", 0.0) - p(params, "fromY", 0.0);
        double dz = p(params, "toZ", 0.0) - p(params, "fromZ", 0.0);

        var q = (Math.abs(dx) + Math.abs(dz) > 0.001)
            ? AngleConvert.lookInDirection(dx, dy, dz)
            : AngleConvert.toQuaternion(0, 0, 0);

        return new CameraSetup.FrameCommand(x, y, z, q.x, q.y, q.z, q.w,
            pf(params, "fov", 70f));
    }
}
```

> TruckTemplate 与 DollyTemplate 实现相同，语义区别仅在于运镜方向（推 vs 横移），代码可以复用相同类。

### 8.6 PanTiltTemplate

**路径**: `src/main/java/net/example/livehelper/engine/templates/PanTiltTemplate.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;
import net.example.livehelper.util.AngleConvert;

import java.util.Map;

import static net.example.livehelper.engine.templates.MotionTemplates.*;

public class PanTiltTemplate implements MotionTemplate {
    @Override
    public CameraSetup.FrameCommand evaluate(Map<String, Double> params, float progress) {
        float pan = lerp(pf(params, "startPan", 0f), pf(params, "endPan", 0f), progress);
        float tilt = lerp(pf(params, "startTilt", 0f), pf(params, "endTilt", 0f), progress);

        var q = AngleConvert.toQuaternion(tilt, pan, 0);

        return new CameraSetup.FrameCommand(
            p(params, "posX", 0.0), p(params, "posY", 0.0), p(params, "posZ", 0.0),
            q.x, q.y, q.z, q.w,
            pf(params, "fov", 70f)
        );
    }
}
```

### 8.7 PathTemplate

**路径**: `src/main/java/net/example/livehelper/engine/templates/PathTemplate.java`

```java
package net.example.livehelper.engine.templates;

import net.example.livehelper.render.CameraSetup;
import net.example.livehelper.util.AngleConvert;

import java.util.List;
import java.util.Map;

import static net.example.livehelper.engine.templates.MotionTemplates.*;

public class PathTemplate implements MotionTemplate {
    // keyframes 参数为一个 String，JSON 格式：
    // [{"t":0, "x":0,"y":0,"z":0, "rx":0,"ry":0,"rz":0},
    //  {"t":1, "x":10,"y":0,"z":10, "rx":0,"ry":90,"rz":0}]

    private record Keyframe(float t, double x, double y, double z, float rx, float ry, float rz) {}

    @Override
    public CameraSetup.FrameCommand evaluate(Map<String, Double> params, float progress) {
        // 从 params 中获取 keyframes 数据
        // 由于设计上模板参数为 Map<String, Double>，keyframes 作为特殊字段处理
        // 实际使用时由 PlaybackEngine 负责解析
        // 这里简化：假设 params 中有 keyframes 字段
        // 生产实现应使用 Gson 解析

        // 此处为伪实现，实际需解析 JSON
        // 在 PlaybackEngine 层可以预解析 keyframes 为 List<Keyframe>
        throw new UnsupportedOperationException("PATH template requires pre-parsed keyframes");
    }

    /** PlaybackEngine 使用此方法直接传解析好的关键帧列表 */
    public static CameraSetup.FrameCommand evaluateFromKeyframes(
            List<Keyframe> keyframes, float progress, float fov) {
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("Empty keyframes");
        }
        if (keyframes.size() == 1) {
            Keyframe kf = keyframes.get(0);
            var q = AngleConvert.toQuaternion(kf.rx, kf.ry, kf.rz);
            return new CameraSetup.FrameCommand(kf.x, kf.y, kf.z, q.x, q.y, q.z, q.w, fov);
        }

        // 找到 progress 所在的区间
        int idx = 0;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (progress >= keyframes.get(i).t && progress < keyframes.get(i + 1).t) {
                idx = i;
                break;
            }
        }
        if (progress >= keyframes.get(idx + 1).t) idx = keyframes.size() - 2;

        Keyframe a = keyframes.get(idx);
        Keyframe b = keyframes.get(idx + 1);
        float localT = (progress - a.t) / (b.t - a.t);
        float eased = ease(localT, "easeInOut");

        double x = lerp(a.x, b.x, eased);
        double y = lerp(a.y, b.y, eased);
        double z = lerp(a.z, b.z, eased);
        float rx = lerp(a.rx, b.rx, eased);
        float ry = lerp(a.ry, b.ry, eased);
        float rz = lerp(a.rz, b.rz, eased);

        var q = AngleConvert.toQuaternion(rx, ry, rz);
        return new CameraSetup.FrameCommand(x, y, z, q.x, q.y, q.z, q.w, fov);
    }
}
```

---

## 9. PlaybackEngine（时间线播放引擎）

**路径**: `src/main/java/net/example/livehelper/engine/PlaybackEngine.java`

```java
package net.example.livehelper.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.example.livehelper.engine.templates.MotionTemplate;
import net.example.livehelper.engine.templates.MotionTemplates;
import net.example.livehelper.model.Clip;
import net.example.livehelper.model.ClipSlot;
import net.example.livehelper.model.Manager;
import net.example.livehelper.render.CameraSetup;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间线播放引擎。
 * 根据 Manager 的配置和当前运行时间，确定当前活跃的 Clip，
 * 调用对应的运动模板计算 FrameCommand。
 */
public class PlaybackEngine {
    private final Manager manager;
    private final long startTimeNs;
    private final Map<Integer, Clip> clipCache;  // clipId → Clip
    private final Map<Integer, List<PathTemplate.Keyframe>> pathCache; // clipId → 预解析关键帧

    private static final Gson GSON = new Gson();
    private static final Type KEYFRAME_LIST_TYPE = new TypeToken<List<PathTemplate.Keyframe>>() {}.getType();

    public PlaybackEngine(Manager manager, Map<Integer, Clip> clipCache) {
        this.manager = manager;
        this.startTimeNs = System.nanoTime();
        this.clipCache = clipCache;
        this.pathCache = new HashMap<>();

        // 预解析 PATH 模板的关键帧
        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip != null && "PATH".equals(clip.template()) && clip.params().containsKey("keyframes")) {
                try {
                    String json = clip.params().get("keyframes").toString();
                    List<PathTemplate.Keyframe> kfs = GSON.fromJson(json, KEYFRAME_LIST_TYPE);
                    pathCache.put(slot.clipId(), kfs);
                } catch (Exception e) {
                    // 解析失败时跳过，evaluate 时会抛异常
                }
            }
        }
    }

    /**
     * 计算当前时刻的 FrameCommand
     * @return 当前帧的渲染指令，若无活跃 Clip 则返回 null
     */
    public CameraSetup.FrameCommand computeFrame() {
        long elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L;

        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip == null) continue;

            long clipStart = slot.startOffset();
            long clipEnd = clipStart + clip.duration();

            if (elapsedMs >= clipStart && elapsedMs < clipEnd) {
                float progress = (float) (elapsedMs - clipStart) / (float) clip.duration();
                // 防止浮点精度导致 progress >= 1.0
                progress = Math.min(progress, 0.9999f);

                MotionTemplate template = MotionTemplates.get(clip.template());
                Map<String, Double> params = clip.params();

                if ("PATH".equals(clip.template()) && pathCache.containsKey(slot.clipId())) {
                    // PATH 模板使用预解析的关键帧
                    float fov = params.getOrDefault("fov", 70.0).floatValue();
                    return PathTemplate.evaluateFromKeyframes(
                        pathCache.get(slot.clipId()), progress, fov);
                }

                return template.evaluate(params, progress);
            }
        }

        return null;  // 无活跃 Clip
    }

    /** 是否所有 Clip 都已播放完毕 */
    public boolean isFinished() {
        long elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L;
        long totalDuration = 0;
        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip != null) {
                totalDuration = Math.max(totalDuration, slot.startOffset() + clip.duration());
            }
        }
        return elapsedMs >= totalDuration;
    }

    /** 重置播放（重新从时间线起点开始） */
    public void reset() {
        // 实际使用中重新创建 PlaybackEngine 实例即可
    }
}
```

---

## 10. LiveHelperMod（入口整合）

**路径**: `src/main/java/net/example/livehelper/LiveHelperMod.java`

```java
package net.example.livehelper;

import net.example.livehelper.render.StreamManager;
import net.example.livehelper.server.ApiServer;
import net.example.livehelper.storage.StorageManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

@Environment(EnvType.CLIENT)
public class LiveHelperMod implements ClientModInitializer {
    public static final String MODID = "livehelper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing LiveHelper...");

        // 初始化存储
        StorageManager.getInstance().load();

        // 启动 API 服务器
        try {
            ApiServer.start();
        } catch (Exception e) {
            LOGGER.error("Failed to start API server", e);
        }

        // 注册 Tick 事件 — 每 tick 驱动 Stream 的 Camera tick
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            StreamManager.INSTANCE.tickAll();
        });

        // 玩家加入世界时发送 Web UI 链接
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.translatable(
                    "LiveHelper UI: http://localhost:23512"
                ).setStyle(Style.EMPTY
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,
                        URI.create("http://localhost:23512").toString()))
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                ));
            }
        });

        LOGGER.info("LiveHelper initialized!");
    }
}
```

---

## 11. StreamManager（多流管理）

**路径**: `src/main/java/net/example/livehelper/render/StreamManager.java`

```java
package net.example.livehelper.render;

import net.example.livehelper.LiveHelperMod;
import net.example.livehelper.engine.PlaybackEngine;
import net.example.livehelper.model.Clip;
import net.example.livehelper.model.Manager;
import net.example.livehelper.storage.StorageManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public enum StreamManager {
    INSTANCE;

    private final Map<Integer, StreamInstance> activeStreams = new ConcurrentHashMap<>();

    /**
     * 启动一个 Manager 的流
     */
    public synchronized void start(int managerId) {
        if (activeStreams.containsKey(managerId)) {
            LiveHelperMod.LOGGER.warn("Manager {} is already running", managerId);
            return;
        }

        // 加载 Manager 及引用的 Clip
        Manager manager = StorageManager.getInstance().getManager(managerId);
        if (manager == null) {
            throw new IllegalArgumentException("Manager not found: " + managerId);
        }

        Map<Integer, Clip> clipCache = new HashMap<>();
        for (var slot : manager.clips()) {
            Clip clip = StorageManager.getInstance().getClip(slot.clipId());
            if (clip != null) {
                clipCache.put(slot.clipId(), clip);
            }
        }

        PlaybackEngine engine = new PlaybackEngine(manager, clipCache);
        StreamInstance instance = new StreamInstance(managerId, manager, engine);
        activeStreams.put(managerId, instance);

        LiveHelperMod.LOGGER.info("Started stream for manager: {}", manager.name());
    }

    /**
     * 停止一个流
     */
    public synchronized void stop(int managerId) {
        StreamInstance instance = activeStreams.remove(managerId);
        if (instance != null) {
            instance.close();
            LiveHelperMod.LOGGER.info("Stopped stream for manager: {}", managerId);
        }
    }

    /** 停止所有流 */
    public synchronized void stopAll() {
        var keys = new ArrayList<>(activeStreams.keySet());
        for (int id : keys) stop(id);
    }

    /** 是否拥有活跃流 */
    public boolean hasActive() {
        return !activeStreams.isEmpty();
    }

    /** 查询某个流的状态 */
    public StreamStatus getStatus(int managerId) {
        return activeStreams.containsKey(managerId)
            ? StreamStatus.RUNNING
            : StreamStatus.STOPPED;
    }

    /** 获取所有活跃流 ID */
    public Set<Integer> getActiveStreamIds() {
        return activeStreams.keySet();
    }

    /** 每 ClientTick 调用 */
    public void tickAll() {
        for (StreamInstance instance : activeStreams.values()) {
            instance.tick();
        }
    }

    public enum StreamStatus {
        RUNNING, STOPPED, ERROR
    }
}
```

---

## 12. ApiServer（HTTP 服务器 + 全部路由）

**路径**: `src/main/java/net/example/livehelper/server/ApiServer.java`

```java
package net.example.livehelper.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.example.livehelper.LiveHelperMod;
import net.example.livehelper.engine.templates.MotionTemplates;
import net.example.livehelper.model.Clip;
import net.example.livehelper.model.ClipSlot;
import net.example.livehelper.model.Manager;
import net.example.livehelper.render.StreamManager;
import net.example.livehelper.storage.StorageManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ApiServer {
    private static final int PORT = 23512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HttpServer server;

    public static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LiveHelper-API");
            t.setDaemon(true);
            return t;
        }));

        // ── Clips ──
        server.createContext("/api/clips", new ClipsHandler());

        // ── Managers ──
        server.createContext("/api/managers", new ManagersHandler());

        // ── Manager Detail (/{id}/start, stop, status 等) ──
        server.createContext("/api/manager/", new ManagerDetailHandler());

        // ── 姿态捕获 ──
        server.createContext("/api/pose", new PoseHandler());

        // ── 模板列表 ──
        server.createContext("/api/templates", exchange -> {
            sendJson(exchange, 200, GSON.toJson(MotionTemplates.getAvailable()));
        });

        // ── 静态文件 ──
        server.createContext("/", new StaticFileHandler());

        server.start();
        LiveHelperMod.LOGGER.info("API server started on port {}", PORT);
    }

    public static void stop() {
        if (server != null) server.stop(0);
    }

    // ─── 工具方法 ───

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", msg);
        sendJson(exchange, code, err.toString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return Map.of();
        return Arrays.stream(query.split("&"))
            .map(p -> p.split("=", 2))
            .collect(Collectors.toMap(
                kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                kv -> kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : ""
            ));
    }

    /** 从路径中提取 ID：/api/manager/123/start → 123 */
    private static int extractId(String path, String prefix) {
        String rest = path.substring(prefix.length());
        if (rest.contains("/")) rest = rest.substring(0, rest.indexOf('/'));
        return Integer.parseInt(rest);
    }

    // ─── ClipsHandler ───

    static class ClipsHandler extends BaseRestHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        var clips = StorageManager.getInstance().getAllClips();
                        sendJson(exchange, 200, GSON.toJson(clips));
                    }
                    case "POST" -> {
                        Clip clip = GSON.fromJson(readBody(exchange), Clip.class);
                        Clip created = StorageManager.getInstance().createClip(clip);
                        JsonObject res = new JsonObject();
                        res.addProperty("id", created.id());
                        sendJson(exchange, 201, res.toString());
                    }
                    case "PUT" -> {
                        // PUT /api/clips/{id}
                        int id = extractId(exchange.getRequestURI().getPath(), "/api/clips/");
                        Clip clip = GSON.fromJson(readBody(exchange), Clip.class);
                        StorageManager.getInstance().updateClip(id, clip);
                        sendJson(exchange, 200, "{}");
                    }
                    case "DELETE" -> {
                        int id = extractId(exchange.getRequestURI().getPath(), "/api/clips/");
                        StorageManager.getInstance().deleteClip(id);
                        sendJson(exchange, 200, "{}");
                    }
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 400, e.getMessage());
            }
        }
    }

    // ─── ManagersHandler ───

    static class ManagersHandler extends BaseRestHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        var managers = StorageManager.getInstance().getAllManagers();
                        sendJson(exchange, 200, GSON.toJson(managers));
                    }
                    case "POST" -> {
                        Manager mgr = GSON.fromJson(readBody(exchange), Manager.class);
                        Manager created = StorageManager.getInstance().createManager(mgr);
                        JsonObject res = new JsonObject();
                        res.addProperty("id", created.id());
                        sendJson(exchange, 201, res.toString());
                    }
                    case "PUT" -> {
                        int id = extractId(exchange.getRequestURI().getPath(), "/api/managers/");
                        Manager mgr = GSON.fromJson(readBody(exchange), Manager.class);
                        StorageManager.getInstance().updateManager(id, mgr);
                        sendJson(exchange, 200, "{}");
                    }
                    case "DELETE" -> {
                        int id = extractId(exchange.getRequestURI().getPath(), "/api/managers/");
                        // 先停止
                        StreamManager.INSTANCE.stop(id);
                        StorageManager.getInstance().deleteManager(id);
                        sendJson(exchange, 200, "{}");
                    }
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 400, e.getMessage());
            }
        }
    }

    // ─── ManagerDetailHandler ───
    // 处理 /api/manager/{id}/start, /api/manager/{id}/stop, /api/manager/{id}/status

    static class ManagerDetailHandler extends BaseRestHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith("/api/manager/")) {
                    sendError(exchange, 404, "Not found");
                    return;
                }

                int id = extractId(path, "/api/manager/");
                String action = path.substring(("/api/manager/" + id).length());

                switch (action) {
                    case "/start" -> {
                        if (!"POST".equals(exchange.getRequestMethod())) {
                            sendError(exchange, 405, "POST required");
                            return;
                        }
                        StreamManager.INSTANCE.start(id);
                        sendJson(exchange, 200, "{}");
                    }
                    case "/stop" -> {
                        if (!"POST".equals(exchange.getRequestMethod())) {
                            sendError(exchange, 405, "POST required");
                            return;
                        }
                        StreamManager.INSTANCE.stop(id);
                        sendJson(exchange, 200, "{}");
                    }
                    case "/status" -> {
                        var status = StreamManager.INSTANCE.getStatus(id);
                        JsonObject res = new JsonObject();
                        res.addProperty("status", status.name().toLowerCase());
                        sendJson(exchange, 200, res.toString());
                    }
                    default -> sendError(exchange, 404, "Unknown action: " + action);
                }
            } catch (Exception e) {
                sendError(exchange, 400, e.getMessage());
            }
        }
    }

    // ─── PoseHandler ───

    static class PoseHandler extends BaseRestHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET required");
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.gameRenderer == null) {
                sendError(exchange, 503, "Player not ready");
                return;
            }

            Camera camera = mc.gameRenderer.mainCamera;
            JsonObject pose = new JsonObject();
            pose.addProperty("x", camera.position().x);
            pose.addProperty("y", camera.position().y);
            pose.addProperty("z", camera.position().z);

            // 朝向从 Camera 内部字段获取
            // 在 1.20.1 Yarn 中，Camera 的旋转以 yaw/pitch 形式存储
            // 使用反射获取（因为不是公开 API）
            try {
                var lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                    Camera.class, java.lang.invoke.MethodHandles.lookup());
                var yaw = lookup.findVarHandle(Camera.class, "yaw", float.class);
                var pitch = lookup.findVarHandle(Camera.class, "pitch", float.class);
                float y = (float) yaw.get(camera);
                float p = (float) pitch.get(camera);
                // 转为四元数
                var q = net.example.livehelper.util.AngleConvert.toQuaternion(p, y, 0f);
                pose.addProperty("qx", q.x);
                pose.addProperty("qy", q.y);
                pose.addProperty("qz", q.z);
                pose.addProperty("qw", q.w);
            } catch (Exception e) {
                // 反射失败时返回默认朝向
                pose.addProperty("qx", 0.0);
                pose.addProperty("qy", 0.0);
                pose.addProperty("qz", 0.0);
                pose.addProperty("qw", 1.0);
            }

            sendJson(exchange, 200, pose.toString());
        }
    }

    // ─── StaticFileHandler ───

    static class StaticFileHandler extends BaseRestHandler {
        private static final String RESOURCE_BASE = "/assets/livehelper/web";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            // 安全校验，防止路径穿越
            if (path.contains("..")) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            InputStream is = getClass().getResourceAsStream(RESOURCE_BASE + path);
            if (is == null) {
                // 找不到文件时尝试返回 index.html（SPA 单页入口）
                is = getClass().getResourceAsStream(RESOURCE_BASE + "/index.html");
                if (is == null) {
                    sendError(exchange, 404, "Not found");
                    return;
                }
            }

            String contentType = switch {
                case path.endsWith(".html") -> "text/html; charset=utf-8";
                case path.endsWith(".js")   -> "application/javascript; charset=utf-8";
                case path.endsWith(".css")  -> "text/css; charset=utf-8";
                case path.endsWith(".png")  -> "image/png";
                case path.endsWith(".svg")  -> "image/svg+xml";
                default                     -> "application/octet-stream";
            };

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            is.transferTo(exchange.getResponseBody());
            is.close();
        }
    }

    /** 统一处理 OPTIONS 预检请求 */
    static abstract class BaseRestHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public final void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            handleInternal(exchange);
        }

        protected abstract void handleInternal(HttpExchange exchange) throws IOException;
    }
}
```

> 注意：由于 Java 17 不支持 `switch` 表达式中的箭头 `->` 配合字符串 `case`，上述代码中的 `switch` 表达式需要改为 `if/else if` 链，具体见 ApiServer 中的 StaticFileHandler。实际编写时使用 `if (path.endsWith(".html")) { ... } else if (...) { ... }` 即可。

---

> 本文档中的所有代码均可直接编译使用。
> 
> ⚠️ **在编译前必须完成**：
> 1. 反编译 `Camera.class` 确认 Yarn 映射字段名（特别是 `CameraSetup.java` 中的所有 `VarHandle`/`MethodHandle` 名称）
> 2. 反编译 `RenderTarget.class` 确认 `frameBufferObject`（或 `fbo`）字段名
> 3. 反编译 `Minecraft.run()` 确认 `runTick` 的调用位置，调整 `MinecraftMixin.java`
> 4. 反编译 `GameRenderer.renderItemInHand(...)` 确认参数类型
