package site.leawsic.livehelper.engine.templates;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;
import site.leawsic.livehelper.util.ArcLengthLUT;
import site.leawsic.livehelper.util.MathUtil;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.pf;
import static site.leawsic.livehelper.engine.templates.MotionTemplates.ps;

/**
 * 样条路径：关键帧之间用 Catmull-Rom 转三次贝塞尔平滑过渡，并按弧长重参数化实现段内匀速。
 *
 * <p>关键帧格式与 {@link PathTemplate} 完全一致（t / x / y / z / rx / ry / rz / fov），
 * 所以既有 PATH 配置只要把模板名改成 SPLINE 即可平滑升级，无需重录关键帧。
 *
 * <p>相比 PATH 的三点差异：
 * <ul>
 *   <li>路径是 C1 连续的曲线，关键帧处不再有速度突变（PATH 是折线）。</li>
 *   <li>段内按弧长推进，弯段不会被贝塞尔参数化拉快或压慢。</li>
 *   <li>朝向可选择跟随路径切线（{@code orientMode=tangent}），适合「边飞边看前方」。</li>
 * </ul>
 *
 * <p>段与段之间仍然是恒速衔接；若相邻关键帧的 t 间隔不同，跨段速度会变化——
 * 这是关键帧 t 的语义（显式时间分配）决定的，不是插值缺陷。
 */
public class SplineTemplate implements PreparedMotionTemplate {

    private static final Gson GSON = new Gson();
    private static final Type KEYFRAME_LIST_TYPE = new TypeToken<List<Keyframe>>() {}.getType();

    public static final String ORIENT_KEYFRAME = "keyframe";
    public static final String ORIENT_TANGENT = "tangent";

    public record Keyframe(float t, double x, double y, double z, float rx, float ry, float rz, Float fov) {}

    /** 一个关键帧区间对应的贝塞尔段与其弧长表。 */
    private static final class Segment {
        final Keyframe a;
        final Keyframe b;
        final double[] c1;
        final double[] c2;
        final ArcLengthLUT lut;

        Segment(Keyframe a, Keyframe b, double[] c1, double[] c2) {
            this.a = a;
            this.b = b;
            this.c1 = c1;
            this.c2 = c2;
            this.lut = new ArcLengthLUT(vec(a.x(), a.y(), a.z()), c1, c2, vec(b.x(), b.y(), b.z()));
        }

        double[] pointAtT(double t) {
            return new double[] {
                    MathUtil.cubicBezier(a.x(), c1[0], c2[0], b.x(), t),
                    MathUtil.cubicBezier(a.y(), c1[1], c2[1], b.y(), t),
                    MathUtil.cubicBezier(a.z(), c1[2], c2[2], b.z(), t)
            };
        }
    }

    private record Prepared(Keyframe[] keys, Segment[] segments, double totalLength) {}

    @Override
    public Object prepare(Map<String, Object> params) {
        List<Keyframe> parsed = parseKeyframes(params);
        if (parsed == null || parsed.isEmpty()) return null;

        List<Keyframe> keys = new ArrayList<>(parsed);
        keys.sort(Comparator.comparingDouble(Keyframe::t));

        if (keys.size() == 1) {
            return new Prepared(keys.toArray(new Keyframe[0]), new Segment[0], 0.0);
        }

        Segment[] segments = new Segment[keys.size() - 1];
        double total = 0.0;
        for (int i = 0; i < segments.length; i++) {
            Keyframe prev = keys.get(Math.max(0, i - 1));
            Keyframe cur = keys.get(i);
            Keyframe next = keys.get(i + 1);
            Keyframe after = keys.get(Math.min(keys.size() - 1, i + 2));

            // Catmull-Rom 转等价三次贝塞尔：端点处复制邻点，得到自然的出入缓入缓出。
            double[] c1 = new double[] {
                    cur.x() + (next.x() - prev.x()) / 6.0,
                    cur.y() + (next.y() - prev.y()) / 6.0,
                    cur.z() + (next.z() - prev.z()) / 6.0
            };
            double[] c2 = new double[] {
                    next.x() - (after.x() - cur.x()) / 6.0,
                    next.y() - (after.y() - cur.y()) / 6.0,
                    next.z() - (after.z() - cur.z()) / 6.0
            };
            segments[i] = new Segment(cur, next, c1, c2);
            total += segments[i].lut.totalLength();
        }
        return new Prepared(keys.toArray(new Keyframe[0]), segments, total);
    }

    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        Object prepared = prepare(params);
        if (prepared == null) {
            throw new IllegalArgumentException("SPLINE template requires keyframes");
        }
        return evaluatePrepared(params, progress, prepared);
    }

    @Override
    public FrameCommand evaluatePrepared(Map<String, Object> params, float progress, Object prepared) {
        if (!(prepared instanceof Prepared p)) {
            return evaluate(params, progress);
        }
        float fov = MathUtil.clamp(pf(params, "fov", 70f), MathUtil.MIN_FOV, MathUtil.MAX_FOV);
        String orientMode = ps(params, "orientMode", ORIENT_KEYFRAME);
        String easing = ps(params, "easing", MathUtil.EASE_LINEAR);

        Keyframe[] keys = p.keys();
        if (keys.length == 1) {
            Keyframe k = keys[0];
            Quaternionf q = AngleConvert.toQuaternion(k.rx(), k.ry(), k.rz());
            return frame(k.x(), k.y(), k.z(), k.rx(), k.ry(), k.rz(), q, fov);
        }

        float t = MathUtil.clamp01(progress);
        Segment[] segments = p.segments();

        int index = segments.length - 1;
        for (int i = 0; i < segments.length; i++) {
            if (t < keys[i + 1].t()) {
                index = i;
                break;
            }
        }
        Segment segment = segments[index];
        Keyframe a = segment.a;
        Keyframe b = segment.b;

        float span = b.t() - a.t();
        float localT = span <= 1e-5f ? 0f : MathUtil.clamp01((t - a.t()) / span);
        float eased = MathUtil.ease(localT, easing);

        // 弧长重参数化：eased 是时间比例，lookupT 把它换成等弧长比例。
        double curveT = segment.lut.lookupT(eased);
        double[] point = segment.pointAtT(curveT);

        float rx = (float) lerp(a.rx(), b.rx(), eased);
        float ry = (float) lerp(a.ry(), b.ry(), eased);
        float rz = (float) lerp(a.rz(), b.rz(), eased);
        float frameFov = (float) lerp(a.fov() == null ? fov : a.fov(), b.fov() == null ? fov : b.fov(), eased);

        Quaternionf q;
        if (ORIENT_TANGENT.equalsIgnoreCase(orientMode)) {
            double[] tangent = segment.lut.tangentAt(curveT);
            if (Math.abs(tangent[0]) + Math.abs(tangent[1]) + Math.abs(tangent[2]) > MathUtil.VECTOR_EPSILON) {
                q = AngleConvert.lookInDirection(tangent[0], tangent[1], tangent[2]);
                // CameraSetup 优先使用 euler 三轴（见 render/CameraSetup），
                // 所以必须让 euler 与切线朝向保持一致，否则 tangent 模式不会生效。
                // 切线朝向不含滚转，toEulerAngles 得到的 roll 约为 0。
                Vector3f euler = AngleConvert.toEulerAngles(q);
                return frame(point[0], point[1], point[2], euler.x, euler.y, euler.z, q, frameFov);
            }
            q = AngleConvert.toQuaternion(rx, ry, rz);
        } else {
            q = AngleConvert.toQuaternion(rx, ry, rz);
        }

        return frame(point[0], point[1], point[2], rx, ry, rz, q, frameFov);
    }

    private static FrameCommand frame(double x, double y, double z,
                                      float rx, float ry, float rz,
                                      Quaternionf q, float fov) {
        return new FrameCommand(
                MathUtil.sanitizeDouble(x, 0.0),
                MathUtil.sanitizeDouble(y, 0.0),
                MathUtil.sanitizeDouble(z, 0.0),
                q.x, q.y, q.z, q.w,
                MathUtil.clamp(fov, MathUtil.MIN_FOV, MathUtil.MAX_FOV),
                rx, ry, rz);
    }

    private static List<Keyframe> parseKeyframes(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("keyframes");
        if (raw == null) return null;
        JsonElement element = raw instanceof String string
                ? GSON.fromJson(string, JsonElement.class)
                : GSON.toJsonTree(raw);
        return GSON.fromJson(element, KEYFRAME_LIST_TYPE);
    }

    private static double[] vec(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    private static double lerp(double from, double to, float amount) {
        return from + (to - from) * amount;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }
}
