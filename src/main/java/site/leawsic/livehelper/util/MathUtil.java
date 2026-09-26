package site.leawsic.livehelper.util;

/**
 * 通用数学工具：有限性消毒、角度环绕插值、缓动曲线、三次贝塞尔求值。
 *
 * <p>消毒的必要性：FrameCommand 最终写入 Camera.setup()，一个 NaN / Infinity 会污染整帧
 * 渲染与 Spout 输出，且不会自行恢复，只能靠重启推流。移植自 ImmersiveCinematics 的
 * MathUtil.sanitize* 思路，改为在本项目所有插值出口统一兜底。
 */
public final class MathUtil {

    /** 方向向量长度阈值，低于此值视为无有效方向。 */
    public static final double VECTOR_EPSILON = 1e-6;

    /** 四元数长度阈值，低于此值视为退化（无法归一化）。 */
    public static final float QUAT_EPSILON = 1e-6f;

    /** FOV 合法区间：原版投影矩阵要求 0 < fov < 180，越界会让整帧渲染崩掉。 */
    public static final float MIN_FOV = 1f;
    public static final float MAX_FOV = 179f;

    private MathUtil() {}

    // ── 有限性消毒 ─────────────────────────────────────────────

    /** 非有限值（非 NaN 且非 ±Infinity）时返回 value，否则返回可用的 fallback。 */
    public static float sanitizeFloat(float value, float fallback) {
        if (Float.isFinite(value)) return value;
        return Float.isFinite(fallback) ? fallback : 0f;
    }

    public static double sanitizeDouble(double value, double fallback) {
        if (Double.isFinite(value)) return value;
        return Double.isFinite(fallback) ? fallback : 0.0;
    }

    /** 钳制到 [min, max]，非有限值时返回 min。 */
    public static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return value < min ? min : (value > max ? max : value);
    }

    public static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    // ── 角度环绕插值 ───────────────────────────────────────────

    /**
     * 把角度规整到 [-180, 180)。
     *
     * <p>注意 180 会被规整成 -180（与 ImmersiveCinematics 的 wrapDegrees 同一约定）。
     */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    /**
     * 沿最短弧插值两个角度。
     *
     * <p>普通 lerp 会让 170° → -170° 走 340° 的远路，导致转场时相机绕整圈。
     */
    public static float lerpAngle(float from, float to, float t) {
        return from + wrapDegrees(to - from) * t;
    }

    // ── 缓动 ───────────────────────────────────────────────────

    public static final String EASE_LINEAR = "linear";
    public static final String EASE_IN = "easeIn";
    public static final String EASE_OUT = "easeOut";
    public static final String EASE_IN_OUT = "easeInOut";
    public static final String EASE_SMOOTHSTEP = "smoothstep";

    /** 全部合法缓动名，Web UI / 校验共用。 */
    public static final java.util.List<String> EASINGS = java.util.List.of(
            EASE_LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, EASE_SMOOTHSTEP);

    public static boolean isValidEasing(String type) {
        return type != null && EASINGS.contains(type);
    }

    /**
     * 缓动曲线唯一实现。所有模板与转场都必须走这里，避免多处公式漂移。
     *
     * <p>入参 t 会被钳制到 [0,1]，非有限值按 0 处理。
     */
    public static float ease(float t, String type) {
        if (!Float.isFinite(t)) return 0f;
        float x = clamp01(t);
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        if (type == null) return x;
        return switch (type) {
            case EASE_IN -> x * x;
            case EASE_OUT -> x * (2f - x);
            case EASE_IN_OUT -> x < 0.5f ? 2f * x * x : 1f - 2f * (1f - x) * (1f - x);
            case EASE_SMOOTHSTEP -> x * x * (3f - 2f * x);
            default -> x;
        };
    }

    // ── 贝塞尔 ─────────────────────────────────────────────────

    /** 三次贝塞尔求值（标量分量通用）。 */
    public static double cubicBezier(double p0, double p1, double p2, double p3, double t) {
        double u = 1.0 - t;
        return u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3;
    }

    /** 三次贝塞尔的一阶导数（切线方向），用于「相机朝向沿路径切线」。 */
    public static double cubicBezierDerivative(double p0, double p1, double p2, double p3, double t) {
        double u = 1.0 - t;
        return 3.0 * u * u * (p1 - p0) + 6.0 * u * t * (p2 - p1) + 3.0 * t * t * (p3 - p2);
    }
}
