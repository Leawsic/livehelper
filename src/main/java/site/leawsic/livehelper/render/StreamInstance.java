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
    /** 切机位期间的暂停：可恢复，且时间线从原处继续。区别于 {@link #suspend()} 的彻底让位。 */
    private boolean pausedForCue;

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
     *
     * <p>这是<b>不可恢复</b>的让位：交接完成后调用方会把它关闭。切机位场景请用
     * {@link #pauseForCue()} / {@link #resumeFromCue()}。
     */
    public void suspend() {
        stopped = true;
        pausedForCue = false;
    }

    /**
     * 切机位期间暂停：保留 sender 与最后一帧，等 cue 播完再恢复。
     *
     * <p>与 {@link #suspend()} 的关键差别是时间线被冻结——常驻机位恢复后从暂停处继续，
     * 而不是跳到「暂停了多久」之后的位置。对循环的全景机位来说这才是想要的语义。
     */
    public void pauseForCue() {
        if (stopped) return;
        stopped = true;
        pausedForCue = true;
        framePrepared = false;
        engine.pauseClock();
    }

    /**
     * 从切机位暂停中恢复，并立刻把最后一帧推回渲染上下文。
     *
     * <p>之所以要补这一下：关闭 cue 时 {@link #close()} 会清空 persistent context，
     * 若等到下一次 tick 才产出新帧，中间会闪一下玩家视角。
     */
    public void resumeFromCue() {
        if (!pausedForCue) return;
        engine.resumeClock();
        stopped = false;
        pausedForCue = false;
        framePrepared = false;
        lastRenderNs = System.nanoTime() - frameIntervalNs;

        Minecraft mc = Minecraft.getInstance();
        if (lastGoodFrame != null && mc.level != null && mc.getWindow() != null) {
            ActiveRenderContext.setPersistent(lastGoodFrame,
                mc.getWindow().getWidth(), mc.getWindow().getHeight(), config.renderDistance());
        }
    }

    public boolean isPausedForCue() {
        return pausedForCue;
    }

    /** 时间线是否已走完（未开启循环）。切机位据此自动返回常驻机位。 */
    public boolean isTimelineFinished() {
        return engine.isFinished();
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
        pausedForCue = false;
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
        pausedForCue = false;
        lastGoodFrame = null;
        ActiveRenderContext.clearPersistent();
        spoutSender.close();
        if (predecessors != null) {
            for (StreamInstance p : predecessors) p.close();
            predecessors = null;
        }
    }
}
