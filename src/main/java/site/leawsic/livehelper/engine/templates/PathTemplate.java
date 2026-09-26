package site.leawsic.livehelper.engine.templates;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;
import site.leawsic.livehelper.util.ArcLengthLUT;
import site.leawsic.livehelper.util.MathUtil;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.pf;
import static site.leawsic.livehelper.engine.templates.MotionTemplates.ps;

/**
 * 关键帧路径，逐段线性插值 + 段内 easeInOut。历史模板，保留以兼容既有配置。
 *
 * <p>局限：关键帧之间是折线，速度在每个关键帧处突变，且段内缓动被硬编码。
 * 需要平滑弧线时改用 {@link SplineTemplate}（同为关键帧格式，只需改模板名）。
 */
public class PathTemplate implements PreparedMotionTemplate {

    private static final Gson GSON = new Gson();
    private static final Type KEYFRAME_LIST_TYPE = new TypeToken<List<Keyframe>>() {}.getType();

    /** PATH 历史行为：段内缓动硬编码为 easeInOut。保留为默认值，现可由 {@code easing} 参数覆盖。 */
    public static final String DEFAULT_EASING = "easeInOut";

    public record Keyframe(float t, double x, double y, double z, float rx, float ry, float rz, Float fov) {}

    /** 预计算产物：仅解析结果，插值本身无状态。 */
    private record Prepared(List<Keyframe> keyframes) {}

    @Override
    public Object prepare(Map<String, Object> params) {
        List<Keyframe> keyframes = parseKeyframes(params);
        return keyframes == null || keyframes.isEmpty() ? null : new Prepared(keyframes);
    }

    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        List<Keyframe> keyframes = parseKeyframes(params);
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("PATH template requires keyframes");
        }
        return evaluateFromKeyframes(keyframes, progress, pf(params, "fov", 70f), ps(params, "easing", DEFAULT_EASING));
    }

    @Override
    public FrameCommand evaluatePrepared(Map<String, Object> params, float progress, Object prepared) {
        if (!(prepared instanceof Prepared p) || p.keyframes() == null || p.keyframes().isEmpty()) {
            return evaluate(params, progress);
        }
        return evaluateFromKeyframes(p.keyframes(), progress, pf(params, "fov", 70f), ps(params, "easing", DEFAULT_EASING));
    }

    private static List<Keyframe> parseKeyframes(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("keyframes");
        if (raw == null) return null;
        JsonElement element = raw instanceof String string
                ? GSON.fromJson(string, JsonElement.class)
                : GSON.toJsonTree(raw);
        return GSON.fromJson(element, KEYFRAME_LIST_TYPE);
    }

    public static FrameCommand evaluateFromKeyframes(List<Keyframe> keyframes, float progress, float fov) {
        return evaluateFromKeyframes(keyframes, progress, fov, DEFAULT_EASING);
    }

    public static FrameCommand evaluateFromKeyframes(List<Keyframe> keyframes, float progress, float fov, String easing) {
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("Empty keyframes");
        }
        if (keyframes.size() == 1) {
            Keyframe kf = keyframes.get(0);
            float frameFov = kf.fov() == null ? fov : kf.fov();
            Quaternionf q = AngleConvert.toQuaternion(kf.rx(), kf.ry(), kf.rz());
            return new FrameCommand(kf.x(), kf.y(), kf.z(), q.x, q.y, q.z, q.w, frameFov, kf.rx(), kf.ry(), kf.rz());
        }

        progress = Math.max(0f, Math.min(0.9999f, progress));
        int idx = keyframes.size() - 2;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (progress >= keyframes.get(i).t() && progress < keyframes.get(i + 1).t()) {
                idx = i;
                break;
            }
        }

        Keyframe a = keyframes.get(idx);
        Keyframe b = keyframes.get(idx + 1);
        float span = b.t() - a.t();
        float localT = span <= 0.00001f ? 0f : (progress - a.t()) / span;
        float eased = MathUtil.ease(localT, easing);

        double x = lerp(a.x(), b.x(), eased);
        double y = lerp(a.y(), b.y(), eased);
        double z = lerp(a.z(), b.z(), eased);
        float rx = (float) lerp(a.rx(), b.rx(), eased);
        float ry = (float) lerp(a.ry(), b.ry(), eased);
        float rz = (float) lerp(a.rz(), b.rz(), eased);
        float frameFov = (float) lerp(a.fov() == null ? fov : a.fov(), b.fov() == null ? fov : b.fov(), eased);

        Quaternionf q = AngleConvert.toQuaternion(rx, ry, rz);
        return new FrameCommand(x, y, z, q.x, q.y, q.z, q.w, frameFov, rx, ry, rz);
    }

    private static double lerp(double from, double to, float amount) {
        return from + (to - from) * amount;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }
}
