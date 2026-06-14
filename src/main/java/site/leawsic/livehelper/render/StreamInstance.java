package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.mixin.GameRendererAccessor;
import site.leawsic.livehelper.mixin.MinecraftAccessor;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.scheduler.MainScheduler;
import site.leawsic.livehelper.spout.SpoutSender;

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
    private FrameCommand lastCommand;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.dummyCamera = new Camera();
        this.frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / Math.max(1, manager.fps());
        this.renderTarget = new MainTarget(manager.width(), manager.height());
        this.renderTarget.setClearColor(0f, 0f, 0f, 1f);
        this.spoutSender = new SpoutSender("LiveHelper-" + manager.name());

        scheduleNext(System.nanoTime());
    }

    private void scheduleNext(long currentNs) {
        if (stopped) return;
        long nextFrameNs = Math.max(currentNs + frameIntervalNs, System.nanoTime());
        MainScheduler.submitTask(nextFrameNs, (isOutOfMemoryRecovery, taskNs) -> {
            if (stopped) return;
            renderFrame();
            scheduleNext(taskNs);
        });
    }

    private void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        FrameCommand computed = engine.computeFrame();
        if (computed != null) lastCommand = computed;
        FrameCommand cmd = lastCommand;
        if (cmd == null) return;

        MinecraftAccessor minecraftAccessor = (MinecraftAccessor) mc;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) mc.gameRenderer;
        RenderTarget prevTarget = minecraftAccessor.livehelper$getMainRenderTarget();
        Camera prevCamera = gameRendererAccessor.livehelper$getMainCamera();
        boolean prevHideGui = mc.options.hideGui;

        try {
            minecraftAccessor.livehelper$setMainRenderTarget(renderTarget);
            gameRendererAccessor.livehelper$setMainCamera(dummyCamera);
            mc.options.hideGui = true;

            renderTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, renderTarget.width, renderTarget.height);
            renderTarget.clear(Minecraft.ON_OSX);

            // ── Set up virtual camera ──
            CameraSetup.apply(dummyCamera, cmd, config.width(), config.height(), config.renderDistance());

            // ── Build projection matrix for the offscreen target, NOT the window ──
            // GameRenderer.getProjectionMatrix() uses window aspect ratio — that will
            // give wrong frustum culling if the output resolution differs from the window.
            // We construct our own projection matching the Manager's output dimensions.
            float aspect = (float) config.width() / (float) config.height();
            float fovRad = (float) Math.toRadians(cmd.fov());
            float farPlane = config.renderDistance() * 16f;
            Matrix4f projection = new Matrix4f().perspective(fovRad, aspect, 0.05f, farPlane);

            // ── Build model-view matrix ──
            // This mirrors what GameRenderer.renderLevel builds before calling LevelRenderer.renderLevel.
            PoseStack poseStack = new PoseStack();
            poseStack.mulPoseMatrix(projection);

            // ── Render world directly through LevelRenderer ──
            // We bypass GameRenderer.renderLevel() so the projection matrix comes
            // from our virtual camera, not from the window.
            // The frustum culling inside LevelRenderer will use this projection,
            // so terrain at our virtual camera's position will NOT be culled.
            LevelRenderer levelRenderer = mc.levelRenderer;
            GameRenderer gameRenderer = mc.gameRenderer;
            LightTexture lightTexture = gameRenderer.lightTexture();
            lightTexture.updateLightTexture(1.0F);
            levelRenderer.renderLevel(
                poseStack, 1.0F, System.nanoTime(), false,
                dummyCamera, gameRenderer, lightTexture, projection
            );

            // ── Spout send ──
            spoutSender.send(renderTarget.frameBufferId, renderTarget.width, renderTarget.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        } finally {
            minecraftAccessor.livehelper$setMainRenderTarget(prevTarget);
            gameRendererAccessor.livehelper$setMainCamera(prevCamera);
            mc.options.hideGui = prevHideGui;
            prevTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    public void tick() {
        if (stopped) return;
        dummyCamera.tick();
    }

    @Override
    public void close() {
        stopped = true;
        renderTarget.destroyBuffers();
        spoutSender.close();
    }
}
