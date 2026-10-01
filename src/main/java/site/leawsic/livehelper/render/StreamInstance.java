package site.leawsic.livehelper.render;

import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;

import java.util.concurrent.TimeUnit;

/**
 * 一个 Manager 的播放状态：只负责按自己的节奏算出「这一帧相机该在哪儿」。
 *
 * <p>刻意不持有 Spout sender，也不写 {@link ActiveRenderContext}。推流收束为单流之后，
 * 「这一帧由谁推出去、谁接管渲染上下文」是全局唯一的决策，交给
 * {@link StreamManager} 统一处理——否则多个实例会互相覆盖那个静态上下文，
 * 谁在接管镜头就变成不确定的了。
 */
public class StreamInstance {

    private final int managerId;
    private final Manager config;
    private final PlaybackEngine engine;
    private final long frameIntervalNs;

    private boolean stopped;
    private long lastRenderNs;
    /** 切机位期间的暂停：可恢复，且时间线从原处继续。 */
    private boolean pausedForCue;

    /** 最后一帧通过消毒的相机参数；单帧异常时回退到这里，避免 NaN 污染整段推流。 */
    private FrameCommand lastGoodFrame;

    public StreamInstance(int managerId, Manager manager, PlaybackEngine engine) {
        this.managerId = managerId;
        this.config = manager;
        this.engine = engine;
        this.frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / Math.max(1, manager.fps());
        this.lastRenderNs = System.nanoTime() - frameIntervalNs;
    }

    public int managerId() {
        return managerId;
    }

    public Manager config() {
        return config;
    }

    /** 渲染距离，参与投影矩阵的远平面计算。 */
    public int renderDistance() {
        return config.renderDistance();
    }

    /**
     * 切机位期间暂停：时间线冻结，恢复后从原处继续。
     *
     * <p>对循环的全景机位来说这才是想要的语义——恢复后画面从暂停处继续，而不是跳到中间。
     */
    public void pauseForCue() {
        if (stopped) return;
        stopped = true;
        pausedForCue = true;
        engine.pauseClock();
    }

    public void resumeFromCue() {
        if (!pausedForCue) return;
        engine.resumeClock();
        stopped = false;
        pausedForCue = false;
        // 回拨到「立即可产出」，这样同一帧的 prepare 就能补上画面，不会闪一帧玩家视角。
        lastRenderNs = System.nanoTime() - frameIntervalNs;
    }

    public boolean isPausedForCue() {
        return pausedForCue;
    }

    /** 时间线是否已走完（未开启循环）。切机位据此自动返回常驻机位。 */
    public boolean isTimelineFinished() {
        return engine.isFinished();
    }

    /**
     * 若本帧已到产出时机，返回该帧的相机参数。
     *
     * @return 相机参数；未到时间点、时间线空档、或不可用且无兜底时返回 null
     */
    public FrameCommand pollFrame(long nowNs) {
        if (stopped) return null;
        if (nowNs - lastRenderNs < frameIntervalNs) return null;
        lastRenderNs = nowNs;

        FrameCommand raw = engine.computeFrame();
        FrameCommand cmd = PlaybackEngine.sanitize(raw, lastGoodFrame);
        if (cmd == null) {
            return null;
        }
        if (cmd != raw) {
            LiveHelper.LOGGER.warn("Manager {} produced an unusable frame ({}); reusing last valid camera state",
                    managerId, describe(raw));
        }
        lastGoodFrame = cmd;
        return cmd;
    }

    public void stop() {
        stopped = true;
        pausedForCue = false;
    }

    /** 定位不可用帧的成因，仅用于日志，不参与播放逻辑。 */
    private static String describe(FrameCommand cmd) {
        if (cmd == null) return "no active clip";
        if (!Double.isFinite(cmd.x()) || !Double.isFinite(cmd.y()) || !Double.isFinite(cmd.z())) {
            return "non-finite position";
        }
        double length = Math.sqrt(
            (double) cmd.qx() * cmd.qx() + (double) cmd.qy() * cmd.qy()
                + (double) cmd.qz() * cmd.qz() + (double) cmd.qw() * cmd.qw());
        if (!Double.isFinite(length) || length < 1e-6) return "degenerate quaternion";
        return "out-of-range fov=" + cmd.fov();
    }
}
