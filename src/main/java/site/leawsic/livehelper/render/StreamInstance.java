package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.mixin.CameraAccessor;
import site.leawsic.livehelper.mixin.GameRendererAccessor;
import site.leawsic.livehelper.mixin.LightTextureAccessor;
import site.leawsic.livehelper.mixin.MinecraftAccessor;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.scheduler.MainScheduler;
import site.leawsic.livehelper.spout.SpoutSender;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.concurrent.TimeUnit;

public class StreamInstance implements AutoCloseable {
    private final int managerId;
    private final Manager config;
    private final PlaybackEngine engine;
    private final MainTarget renderTarget;
    private final SpoutSender spoutSender;
    private final long frameIntervalNs;

    private boolean stopped;
    private FrameCommand lastCommand;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
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

        MinecraftAccessor mcAccessor = (MinecraftAccessor) mc;
        GameRendererAccessor grAccessor = (GameRendererAccessor) mc.gameRenderer;
        RenderTarget prevTarget = mcAccessor.livehelper$getMainRenderTarget();
        Camera prevCamera = grAccessor.livehelper$getMainCamera();

        try {
            // ── Swap to offscreen target ──
            mcAccessor.livehelper$setMainRenderTarget(renderTarget);
            renderTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, renderTarget.width, renderTarget.height);
            renderTarget.clear(Minecraft.ON_OSX);

            // ── Create virtual camera and position via FrameCommand ──
            Camera camera = new Camera();
            camera.setup(mc.level, mc.player, false, false, 1.0F);

            CameraAccessor camAccessor = (CameraAccessor) camera;
            camAccessor.livehelper$setPosition(cmd.x(), cmd.y(), cmd.z());
            Vector3f angles = AngleConvert.toEulerAngles(new Quaternionf(cmd.qx(), cmd.qy(), cmd.qz(), cmd.qw()));
            camAccessor.livehelper$setRotation(angles.y, angles.x);

            // ── Swap mainCamera so shaders/uniforms read our camera ──
            grAccessor.livehelper$setMainCamera(camera);

            // ── Build view matrix ──
            // Matches vanilla GameRenderer.renderLevel(): mulPose(XP.rotDeg(xRot)),
            // then mulPose(YP.rotDeg(yRot + 180))
            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
            poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0f));

            // ── Build projection matrix ──
            float aspect = (float) renderTarget.width / (float) renderTarget.height;
            float zFar = config.renderDistance() * 16.0f;
            Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(cmd.fov()), aspect, 0.05F, zFar
            );

            // ── Set projection on RenderSystem (required by world shaders) ──
            mc.gameRenderer.resetProjectionMatrix(projection);

            // ── Prepare chunk cull frustum using our camera ──
            mc.levelRenderer.prepareCullFrustum(poseStack, camera.getPosition(), projection);

            // ── Prevent light texture update from being consumed by this pass ──
            LightTextureAccessor lightAccessor = (LightTextureAccessor) mc.gameRenderer.lightTexture();
            lightAccessor.livehelper$setUpdateLightTexture(false);

            // ── Render world directly ──
            // Calls LevelRenderer.renderLevel() directly, bypassing GameRenderer.render()
            // which would add GUI, hand, post-processing, and a destructive clear.
            mc.levelRenderer.renderLevel(
                poseStack,
                1.0F,
                System.nanoTime(),
                false,
                camera,
                mc.gameRenderer,
                mc.gameRenderer.lightTexture(),
                projection
            );

            lightAccessor.livehelper$setUpdateLightTexture(true);

            // ── Send offscreen color texture to Spout ──
            spoutSender.send(renderTarget.frameBufferId, renderTarget.width, renderTarget.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        } finally {
            // ── Restore original state ──
            mcAccessor.livehelper$setMainRenderTarget(prevTarget);
            grAccessor.livehelper$setMainCamera(prevCamera);
            prevTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    public void tick() {
    }

    @Override
    public void close() {
        stopped = true;
        renderTarget.destroyBuffers();
        spoutSender.close();
    }
}
