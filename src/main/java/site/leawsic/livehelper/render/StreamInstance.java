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

    /** 最后一帧通过消毒的相机参数；单帧异常时回退到这里，避免 NaN 污染整段推流。 */
    private FrameCommand lastGoodFrame;

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

        FrameCommand raw = engine.computeFrame();
        FrameCommand cmd = PlaybackEngine.sanitize(raw, lastGoodFrame);
        if (cmd == null) {
            // 无活跃片段：交回玩家视角，镜头不做任何接管。
            ActiveRenderContext.clearPersistent();
            return;
        }
        if (cmd != raw) {
            LiveHelper.LOGGER.warn("Manager {} produced an unusable frame ({}); reusing last valid camera state",
                    managerId, describe(raw));
        }
        lastGoodFrame = cmd;

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
        lastGoodFrame = null;
        spoutSender.close();
        if (predecessors != null) {
            for (StreamInstance p : predecessors) p.closeAfterHandoff();
            predecessors = null;
        }
    }

    /** 定位不可用帧的成因，仅用于日志，不参与播放逻辑。 */
    private static String describe(FrameCommand cmd) {
        if (cmd == null) return "no active clip";
        if (!Double.isFinite(cmd.x()) || !Double.isFinite(cmd.y()) || !Double.isFinite(cmd.z())) {
            return "non-finite position";
        }
        float length = (float) Math.sqrt(cmd.qx() * cmd.qx() + cmd.qy() * cmd.qy()
                + cmd.qz() * cmd.qz() + cmd.qw() * cmd.qw());
        if (!Float.isFinite(length) || length < 1e-6f) return "degenerate quaternion";
        return "out-of-range fov=" + cmd.fov();
    }

    @Override
    public void close() {
        stopped = true;
        lastGoodFrame = null;
        ActiveRenderContext.clearPersistent();
        spoutSender.close();
        if (predecessors != null) {
            for (StreamInstance p : predecessors) p.close();
            predecessors = null;
        }
    }
}
