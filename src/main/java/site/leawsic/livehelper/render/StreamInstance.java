package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.spout.SpoutSender;
import site.leawsic.livehelper.util.ActiveRenderContext;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class StreamInstance implements AutoCloseable {
    private final int managerId;
    private final Manager config;
    private final PlaybackEngine engine;
    private final SpoutSender spoutSender;
    private final long frameIntervalNs;

    private boolean stopped;
    private boolean firstFrameSent;
    private long lastRenderNs;
    private List<StreamInstance> predecessors;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this(managerId, manager, engine, List.of());
    }

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine, List<StreamInstance> predecessors) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / Math.max(1, manager.fps());
        this.spoutSender = new SpoutSender("LiveHelper-" + manager.name());
        this.predecessors = predecessors;
        this.lastRenderNs = System.nanoTime() - frameIntervalNs;

        renderIfDue(System.nanoTime());
    }

    /**
     * 挂起实例：停止后续帧调度，但保留 persistent context 和 Spout sender，
     * 直至接管它的后继实例完成首帧发送。用于切换 Manager 时避免黑屏。
     */
    public void suspend() {
        stopped = true;
    }

    /**
     * 由 MC 主循环每帧调用。若到达本实例的推流帧间隔则渲染一帧并发送到 Spout。
     * 渲染节奏由 frameIntervalNs 决定，与 MC 帧率解耦：MC 帧率高于推流帧率时跳帧，
     * 低于推流帧率时按 MC 速度输出。
     */
    public void renderIfDue(long nowNs) {
        if (stopped) return;
        if (nowNs - lastRenderNs < frameIntervalNs) return;
        lastRenderNs = nowNs;
        renderFrame();
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
            if (!firstFrameSent) {
                firstFrameSent = true;
                if (predecessors != null && !predecessors.isEmpty()) {
                    for (StreamInstance p : predecessors) p.closeAfterHandoff();
                    predecessors = null;
                }
            }
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Error rendering stream {}", managerId, e);
        }
    }

    private void closeAfterHandoff() {
        stopped = true;
        spoutSender.close();
        if (predecessors != null) {
            for (StreamInstance p : predecessors) p.closeAfterHandoff();
            predecessors = null;
        }
    }

    @Override
    public void close() {
        stopped = true;
        ActiveRenderContext.clearPersistent();
        spoutSender.close();
        if (predecessors != null) {
            for (StreamInstance p : predecessors) p.close();
            predecessors = null;
        }
    }
}
