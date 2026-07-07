package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
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
    private final SpoutSender spoutSender;
    private final Camera dummyCamera;
    private final long frameIntervalNs;

    private boolean stopped;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.dummyCamera = new Camera();
        this.frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / Math.max(1, manager.fps());
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

        FrameCommand cmd = engine.computeFrame();
        if (cmd == null) {
            ActiveRenderContext.clearPersistent();
            return;
        }

        try {
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            ActiveRenderContext.setPersistent(cmd, width, height, config.renderDistance());

            RenderTarget mainTarget = mc.getMainRenderTarget();
            spoutSender.send(mainTarget.frameBufferId, mainTarget.width, mainTarget.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        }
    }

    public void tick() {
        dummyCamera.tick();
    }

    @Override
    public void close() {
        stopped = true;
        ActiveRenderContext.clearPersistent();
        spoutSender.close();
    }
}
