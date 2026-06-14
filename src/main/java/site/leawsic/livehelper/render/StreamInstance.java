package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.mixin.GameRendererAccessor;
import site.leawsic.livehelper.mixin.MinecraftAccessor;
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
        if (computed != null) {
            lastCommand = computed;
        }
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
            CameraSetup.apply(dummyCamera, cmd, config.width(), config.height(), config.renderDistance());

            ActiveRenderContext.runWithContext(cmd, config.width(), config.height(), config.renderDistance(), () ->
                mc.gameRenderer.renderLevel(1.0F, System.nanoTime(), new PoseStack())
            );

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
