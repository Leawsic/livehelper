package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.mixin.LightTextureAccessor;
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

        MinecraftAccessor mcAccessor = (MinecraftAccessor) mc;
        RenderTarget prevTarget = mcAccessor.livehelper$getMainRenderTarget();

        try {
            mcAccessor.livehelper$setMainRenderTarget(renderTarget);

            renderTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, renderTarget.width, renderTarget.height);
            renderTarget.clear(Minecraft.ON_OSX);

            // ── Set virtual camera ──
            CameraSetup.apply(dummyCamera, cmd, config.width(), config.height(), config.renderDistance());

            // ── Prevent light texture corruption ──
            LightTextureAccessor lightAccessor = (LightTextureAccessor) mc.gameRenderer.lightTexture();
            lightAccessor.livehelper$setUpdateLightTexture(false);

            // ── Render full game frame into offscreen target ──
            mc.gameRenderer.render(1.0F, System.nanoTime(), true);

            // ── Re-enable light texture updates ──
            lightAccessor.livehelper$setUpdateLightTexture(true);

            spoutSender.send(renderTarget.frameBufferId, renderTarget.width, renderTarget.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        } finally {
            mcAccessor.livehelper$setMainRenderTarget(prevTarget);
            prevTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    public void tick() {
        dummyCamera.tick();
    }

    @Override
    public void close() {
        stopped = true;
        renderTarget.destroyBuffers();
        spoutSender.close();
    }
}
