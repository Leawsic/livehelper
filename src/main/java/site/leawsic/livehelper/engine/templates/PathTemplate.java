package site.leawsic.livehelper.engine.templates;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

public class PathTemplate implements MotionTemplate {
    private static final Gson GSON = new Gson();
    private static final Type KEYFRAME_LIST_TYPE = new TypeToken<List<Keyframe>>() {}.getType();

    public record Keyframe(float t, double x, double y, double z, float rx, float ry, float rz, Float fov) {}

    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        Object raw = params == null ? null : params.get("keyframes");
        if (raw == null) throw new IllegalArgumentException("PATH template requires keyframes");

        JsonElement element = raw instanceof String string
            ? GSON.fromJson(string, JsonElement.class)
            : GSON.toJsonTree(raw);
        List<Keyframe> keyframes = GSON.fromJson(element, KEYFRAME_LIST_TYPE);
        return evaluateFromKeyframes(keyframes, progress, pf(params, "fov", 70f));
    }

    public static FrameCommand evaluateFromKeyframes(List<Keyframe> keyframes, float progress, float fov) {
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("Empty keyframes");
        }
        if (keyframes.size() == 1) {
            Keyframe kf = keyframes.get(0);
            float frameFov = kf.fov == null ? fov : kf.fov;
            Quaternionf q = AngleConvert.toQuaternion(kf.rx, kf.ry, kf.rz);
            return new FrameCommand(kf.x, kf.y, kf.z, q.x, q.y, q.z, q.w, frameFov, kf.rx, kf.ry, kf.rz);
        }

        progress = Math.max(0f, Math.min(0.9999f, progress));
        int idx = keyframes.size() - 2;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (progress >= keyframes.get(i).t && progress < keyframes.get(i + 1).t) {
                idx = i;
                break;
            }
        }

        Keyframe a = keyframes.get(idx);
        Keyframe b = keyframes.get(idx + 1);
        float span = b.t - a.t;
        float localT = span <= 0.00001f ? 0f : (progress - a.t) / span;
        float eased = ease(Math.max(0f, Math.min(1f, localT)), "easeInOut");

        double x = lerp(a.x, b.x, eased);
        double y = lerp(a.y, b.y, eased);
        double z = lerp(a.z, b.z, eased);
        float rx = lerp(a.rx, b.rx, eased);
        float ry = lerp(a.ry, b.ry, eased);
        float rz = lerp(a.rz, b.rz, eased);
        float frameFov = lerp(a.fov == null ? fov : a.fov, b.fov == null ? fov : b.fov, eased);

        Quaternionf q = AngleConvert.toQuaternion(rx, ry, rz);
        return new FrameCommand(x, y, z, q.x, q.y, q.z, q.w, frameFov, rx, ry, rz);
    }
}
