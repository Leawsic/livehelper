package site.leawsic.livehelper.engine;

import org.joml.Quaternionf;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.templates.MotionTemplate;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.engine.templates.PreparedMotionTemplate;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.util.MathUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager 时间线求值：按墙钟定位当前片段，可选地从上一段末帧做转场混合。
 *
 * <p>使用墙钟而非游戏 tick 是直播场景的刻意选择：推流帧率由 Manager.fps 决定，
 * 不应被游戏暂停或 tick 抖动影响。
 */
public class PlaybackEngine {
    private final Manager manager;
    private long startTimeNs;
    /** 累计暂停时长。切机位期间常驻机位靠它让时间线从原处继续，而不是跳到中间。 */
    private long pausedDurationNs;
    /** 本次暂停的起点；0 表示当前未暂停。 */
    private long pausedAtNs;
    private final Map<Integer, Clip> clipCache;
    /** 预计算产物（如 PATH/SPLINE 的关键帧与弧长表），按 clipId 缓存，避免逐帧重建。 */
    private final Map<Integer, Object> preparedCache = new HashMap<>();

    public PlaybackEngine(Manager manager, Map<Integer, Clip> clipCache) {
        this(manager, clipCache, System.nanoTime());
    }

    /**
     * 显式起点版本，供单元测试在确定时刻求值；运行时请用 {@link #PlaybackEngine(Manager, Map)}。
     */
    public PlaybackEngine(Manager manager, Map<Integer, Clip> clipCache, long startTimeNs) {
        this.manager = manager;
        this.startTimeNs = startTimeNs;
        this.clipCache = clipCache;

        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip == null) continue;
            MotionTemplate template = MotionTemplates.get(clip.template());
            if (template instanceof PreparedMotionTemplate preparable) {
                try {
                    Object prepared = preparable.prepare(clip.params());
                    if (prepared != null) {
                        preparedCache.put(slot.clipId(), prepared);
                    }
                } catch (Exception e) {
                    LiveHelper.LOGGER.warn("Failed to precompute template {} for clip {}",
                            clip.template(), slot.clipId(), e);
                }
            }
        }
    }

    public FrameCommand computeFrame() {
        return computeFrameAt(elapsedMs());
    }

    /** 时间线已走完且未开启循环。切机位据此自动返回常驻机位。 */
    public boolean isFinished() {
        if (manager.loop()) return false;
        return elapsedMs() >= totalDuration();
    }

    /** 暂停时钟。期间 elapsedMs 冻结，恢复后从原处继续。 */
    public void pauseClock() {
        if (pausedAtNs == 0L) {
            pausedAtNs = System.nanoTime();
        }
    }

    public void resumeClock() {
        if (pausedAtNs != 0L) {
            pausedDurationNs += System.nanoTime() - pausedAtNs;
            pausedAtNs = 0L;
        }
    }

    /** 时钟是否处于暂停。切机位期间常驻机位靠它冻结时间线，由 PlaybackEngineClockTest 锁定语义。 */
    public boolean isClockPaused() {
        return pausedAtNs != 0L;
    }

    private long elapsedMs() {
        long now = pausedAtNs != 0L ? pausedAtNs : System.nanoTime();
        return (now - startTimeNs - pausedDurationNs) / 1_000_000L;
    }

    /** 按 Manager 时间线上的毫秒偏移求值，便于定点验证。 */
    public FrameCommand computeFrameAt(long elapsedMs) {
        long totalDuration = totalDuration();
        if (manager.loop() && totalDuration > 0L) {
            elapsedMs = wrapTimeline(elapsedMs, totalDuration, manager.effectiveLoopMode());
        }
        List<ClipSlot> slots = manager.clips();

        for (int i = 0; i < slots.size(); i++) {
            ClipSlot slot = slots.get(i);
            Clip clip = clipCache.get(slot.clipId());
            if (clip == null) continue;

            long clipStart = slot.startOffset();
            long clipEnd = clipStart + clip.duration();
            if (elapsedMs >= clipStart && elapsedMs < clipEnd) {
                long clipElapsed = elapsedMs - clipStart;
                FrameCommand current = evaluateSlot(slot, clip, clipElapsed);
                if (current == null) return null;

                long transitionDuration = Math.min(Math.max(slot.transitionDuration(), 0L), clip.duration());
                ClipSlot previousSlot = i > 0 ? slots.get(i - 1) : null;
                Clip previousClip = previousSlot != null ? clipCache.get(previousSlot.clipId()) : null;
                if (transitionDuration > 0 && previousClip != null && clipElapsed < transitionDuration) {
                    // 上一段按「同一墙钟时刻」求值，而不是固定取它的末帧：
                    // 首尾相接时 previousElapsed 恰好等于上一段时长（与旧行为一致，末帧冻结）；
                    // 片段重叠时上一段仍在中途，取末帧会让画面在转场起点跳一下。
                    long previousElapsed = Math.max(0L, slot.startOffset() + clipElapsed - previousSlot.startOffset());
                    FrameCommand previous = evaluateSlot(previousSlot, previousClip, previousElapsed);
                    if (previous != null) {
                        float amount = MathUtil.ease((float) clipElapsed / (float) transitionDuration, slot.transitionEasing());
                        return blend(previous, current, amount);
                    }
                }
                return current;
            }
        }

        return null;
    }

    /**
     * 时间线循环映射。
     *
     * <p>pingpong 以 2×总时长为模做三角折返，首尾各停一次，监控式来回摇机位只需一个 Manager。
     * 注意：折返点两侧要求首段起始姿态与末段结束姿态接近，否则折返处会有一次跳变。
     */
    private static long wrapTimeline(long elapsedMs, long totalDuration, String loopMode) {
        if (Manager.LOOP_PINGPONG.equals(loopMode)) {
            long period = totalDuration * 2L;
            if (period <= 0L) return 0L;
            long folded = elapsedMs % period;
            long mapped = folded <= totalDuration ? folded : period - folded;
            // 片段窗口是左闭右开 [start, end)，折返点恰好等于总时长会落进空隙，
            // 导致每个往返周期有一帧退回玩家视角。这里夹到最后一个有效毫秒。
            return Math.min(mapped, totalDuration - 1L);
        }
        return elapsedMs % totalDuration;
    }

    private FrameCommand evaluateSlot(ClipSlot slot, Clip clip, long clipElapsedMs) {
        if (clip.duration() <= 0L) {
            return null;
        }
        float progress = (float) clipElapsedMs / (float) clip.duration();
        progress = Math.min(Math.max(progress, 0f), 0.9999f);

        MotionTemplate template = MotionTemplates.get(clip.template());
        if (template instanceof PreparedMotionTemplate preparable) {
            Object prepared = preparedCache.get(slot.clipId());
            if (prepared != null) {
                return preparable.evaluatePrepared(clip.params(), progress, prepared);
            }
        }
        return template.evaluate(clip.params(), progress);
    }

    /**
     * 转场混合：位置与 FOV 线性插值，朝向用四元数 slerp（等角速度），
     * euler 角走最短弧插值。全程消毒，非法分量回退到 from。
     */
    private static FrameCommand blend(FrameCommand from, FrameCommand to, float amount) {
        float t = MathUtil.clamp01(amount);
        Quaternionf q = slerpSafe(from, to, t);

        float fov = MathUtil.clamp(
                MathUtil.sanitizeFloat(lerp(from.fov(), to.fov(), t), from.fov()),
                MathUtil.MIN_FOV, MathUtil.MAX_FOV);

        boolean euler = from.hasEulerAngles() && to.hasEulerAngles();
        return new FrameCommand(
                MathUtil.sanitizeDouble(lerp(from.x(), to.x(), t), from.x()),
                MathUtil.sanitizeDouble(lerp(from.y(), to.y(), t), from.y()),
                MathUtil.sanitizeDouble(lerp(from.z(), to.z(), t), from.z()),
                q.x(), q.y(), q.z(), q.w(),
                fov,
                euler ? MathUtil.lerpAngle(from.pitch(), to.pitch(), t) : Float.NaN,
                euler ? MathUtil.lerpAngle(from.yaw(), to.yaw(), t) : Float.NaN,
                euler ? MathUtil.lerpAngle(from.roll(), to.roll(), t) : Float.NaN
        );
    }

    private static Quaternionf slerpSafe(FrameCommand from, FrameCommand to, float t) {
        Quaternionf a = normalizedQuat(from, null);
        Quaternionf b = normalizedQuat(to, a);
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        return a.slerp(b, t);
    }

    /** 取出可归一化的四元数；退化或非有限时返回 fallback（可为 null，此时用单位四元数）。 */
    private static Quaternionf normalizedQuat(FrameCommand frame, Quaternionf fallback) {
        Quaternionf q = new Quaternionf(frame.qx(), frame.qy(), frame.qz(), frame.qw());
        if (!Float.isFinite(q.x()) || !Float.isFinite(q.y())
                || !Float.isFinite(q.z()) || !Float.isFinite(q.w())) {
            return fallback != null ? fallback : new Quaternionf();
        }
        float lengthSquared = q.x() * q.x() + q.y() * q.y() + q.z() * q.z() + q.w() * q.w();
        if (!Float.isFinite(lengthSquared) || lengthSquared < MathUtil.QUAT_EPSILON * MathUtil.QUAT_EPSILON) {
            return fallback != null ? fallback : new Quaternionf();
        }
        return q.normalize();
    }

    /**
     * 消毒单个 FrameCommand。位置非有限、或四元数退化到无法归一化时返回 null。
     *
     * <p>euler 三轴保留 NaN 语义（{@link FrameCommand#hasEulerAngles()} 以 NaN 表示缺省），
     * 但正负 Infinity 同样按缺省处理。
     */
    public static FrameCommand sanitizeOrNull(FrameCommand cmd) {
        if (cmd == null) return null;
        double x = cmd.x();
        double y = cmd.y();
        double z = cmd.z();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        // 四元数退化时必须判为不可用，交由 sanitize 回退到 lastGood；
        // 若在此处替换为单位四元数，画面会突然朝向固定方向而不是保持上一帧。
        if (!hasUsableQuaternion(cmd)) return null;
        Quaternionf q = normalizedQuat(cmd, null);
        return new FrameCommand(
                x, y, z,
                q.x(), q.y(), q.z(), q.w(),
                MathUtil.clamp(cmd.fov(), MathUtil.MIN_FOV, MathUtil.MAX_FOV),
                eulerOrNaN(cmd.pitch()),
                eulerOrNaN(cmd.yaw()),
                eulerOrNaN(cmd.roll()));
    }

    private static boolean hasUsableQuaternion(FrameCommand frame) {
        if (!Float.isFinite(frame.qx()) || !Float.isFinite(frame.qy())
                || !Float.isFinite(frame.qz()) || !Float.isFinite(frame.qw())) {
            return false;
        }
        float lengthSquared = frame.qx() * frame.qx() + frame.qy() * frame.qy()
                + frame.qz() * frame.qz() + frame.qw() * frame.qw();
        return Float.isFinite(lengthSquared)
                && lengthSquared >= MathUtil.QUAT_EPSILON * MathUtil.QUAT_EPSILON;
    }

    /**
     * 消毒收口：cmd 可用则返回消毒后的 cmd；cmd 非法时回退到 lastGood；两者都不可用返回 null。
     *
     * <p>由 StreamInstance 持有 lastGood 调用，保证单帧异常不会污染相机与 Spout 输出。
     */
    public static FrameCommand sanitize(FrameCommand cmd, FrameCommand lastGood) {
        if (cmd == null) return null;
        FrameCommand clean = sanitizeOrNull(cmd);
        if (clean != null) return clean;
        return lastGood == null ? null : sanitizeOrNull(lastGood);
    }

    private static float eulerOrNaN(float value) {
        return Float.isFinite(value) ? value : Float.NaN;
    }

    private static double lerp(double from, double to, float amount) {
        return from + (to - from) * amount;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private long totalDuration() {
        long totalDuration = 0L;
        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip != null) {
                totalDuration = Math.max(totalDuration, slot.startOffset() + clip.duration());
            }
        }
        return totalDuration;
    }
}
