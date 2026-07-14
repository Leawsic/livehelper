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
    private boolean framePrepared;
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
    }

    /**
     * 挂起实例：停止后续帧调度，但保留 persistent context 和 Spout sender，
     * 直至接管它的后继实例完成首帧发送。用于切换 Manager 时避免黑屏。
     */
    public void suspend() {
        stopped = true;
    }

    /**
     * 在 GameRenderer 渲染当前帧前调用，更新虚拟相机参数。
     */
    public void prepareFrameIfDue(long nowNs) {
        if (stopped) return;
        if (nowNs - lastRenderNs < frameIntervalNs) return;
        lastRenderNs = nowNs;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        FrameCommand cmd = engine.computeFrame();
        if (cmd == null) {
            ActiveRenderContext.clearPersistent();
            return;
        }

        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        ActiveRenderContext.setPersistent(cmd, width, height, config.renderDistance());
        framePrepared = true;
    }

    /** Sends the framebuffer after GameRenderer has completed the prepared frame. */
    public void sendPreparedFrame() {
        if (stopped || !framePrepared) return;
        framePrepared = false;
        try {
            RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
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
