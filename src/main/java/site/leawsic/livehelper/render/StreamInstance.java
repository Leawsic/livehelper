package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.mixin.GameRendererAccessor;
import site.leawsic.livehelper.mixin.MinecraftAccessor;
import site.leawsic.livehelper.util.OffscreenTargetTracker;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.scheduler.MainScheduler;
import site.leawsic.livehelper.spout.SpoutSender;
import site.leawsic.livehelper.util.ActiveRenderContext;

import java.util.concurrent.TimeUnit;

public class StreamInstance implements AutoCloseable {
    private final int managerId;
    private final Manager config;
    private final PlaybackEngine engine;
    private final MainTarget renderTarget;
    private final SpoutSender spoutSender;
    private final Camera virtualCamera;
    private final long frameIntervalNs;

    private boolean stopped;
    private FrameCommand lastCommand;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.virtualCamera = new Camera();
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

            // ── Set up virtual camera ──
            CameraSetup.apply(virtualCamera, cmd,
                config.width(), config.height(), config.renderDistance());

            // ── Swap mainCamera so GameRenderer uses our camera ──
            grAccessor.livehelper$setMainCamera(virtualCamera);

            // ── Register original target for mixin to swap back before GUI clear ──
            OffscreenTargetTracker.setOriginalTarget(prevTarget);

            // ── Render full frame ──
            ActiveRenderContext.runWithContext(cmd, config.width(), config.height(), config.renderDistance(),
                () -> mc.gameRenderer.render(1.0F, System.nanoTime(), true)
            );

            // ── Send offscreen color texture to Spout ──
            spoutSender.send(renderTarget.frameBufferId, renderTarget.width, renderTarget.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        } finally {
            // ── Restore original state ──
            OffscreenTargetTracker.clear();
            mcAccessor.livehelper$setMainRenderTarget(prevTarget);
            grAccessor.livehelper$setMainCamera(prevCamera);
            prevTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    public void tick() {
        virtualCamera.tick();
    }

    @Override
    public void close() {
        stopped = true;
        renderTarget.destroyBuffers();
        spoutSender.close();
    }
}
