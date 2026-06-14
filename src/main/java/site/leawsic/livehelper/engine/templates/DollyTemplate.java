package site.leawsic.livehelper.engine.templates;

import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

public class DollyTemplate implements MotionTemplate {
    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        float eased = ease(progress, ps(params, "easing", "linear"));

        double x = lerp(p(params, "fromX", 0.0), p(params, "toX", 0.0), eased);
        double y = lerp(p(params, "fromY", 0.0), p(params, "toY", 0.0), eased);
        double z = lerp(p(params, "fromZ", 0.0), p(params, "toZ", 0.0), eased);

        double dx = p(params, "toX", 0.0) - p(params, "fromX", 0.0);
        double dy = p(params, "toY", 0.0) - p(params, "fromY", 0.0);
        double dz = p(params, "toZ", 0.0) - p(params, "fromZ", 0.0);

        Quaternionf q = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 0.001
            ? AngleConvert.lookInDirection(dx, dy, dz)
            : AngleConvert.toQuaternion(0f, 0f, 0f);

        return new FrameCommand(x, y, z, q.x, q.y, q.z, q.w, pf(params, "fov", 70f));
    }
}
