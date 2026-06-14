package site.leawsic.livehelper.engine.templates;

import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.List;
import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

public class PathTemplate implements MotionTemplate {
    public record Keyframe(float t, double x, double y, double z, float rx, float ry, float rz) {}

    @Override
    public FrameCommand evaluate(Map<String, Double> params, float progress) {
        throw new UnsupportedOperationException("PATH template requires pre-parsed keyframes");
    }

    public static FrameCommand evaluateFromKeyframes(List<Keyframe> keyframes, float progress, float fov) {
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("Empty keyframes");
        }
        if (keyframes.size() == 1) {
            Keyframe kf = keyframes.get(0);
            Quaternionf q = AngleConvert.toQuaternion(kf.rx, kf.ry, kf.rz);
            return new FrameCommand(kf.x, kf.y, kf.z, q.x, q.y, q.z, q.w, fov);
        }

        int idx = 0;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (progress >= keyframes.get(i).t && progress < keyframes.get(i + 1).t) {
                idx = i;
                break;
            }
        }
        if (progress >= keyframes.get(idx + 1).t) idx = keyframes.size() - 2;

        Keyframe a = keyframes.get(idx);
        Keyframe b = keyframes.get(idx + 1);
        float localT = (progress - a.t) / (b.t - a.t);
        float eased = ease(localT, "easeInOut");

        double x = lerp(a.x, b.x, eased);
        double y = lerp(a.y, b.y, eased);
        double z = lerp(a.z, b.z, eased);
        float rx = lerp(a.rx, b.rx, eased);
        float ry = lerp(a.ry, b.ry, eased);
        float rz = lerp(a.rz, b.rz, eased);

        Quaternionf q = AngleConvert.toQuaternion(rx, ry, rz);
        return new FrameCommand(x, y, z, q.x, q.y, q.z, q.w, fov);
    }
}
