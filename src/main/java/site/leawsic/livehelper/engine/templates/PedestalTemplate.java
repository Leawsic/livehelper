package site.leawsic.livehelper.engine.templates;

import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

public class PedestalTemplate implements MotionTemplate {
    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        float eased = ease(progress, ps(params, "easing", "linear"));
        double y = lerp(p(params, "fromHeight", 0.0), p(params, "toHeight", 0.0), eased);
        Quaternionf q = AngleConvert.toQuaternion(pf(params, "rotX", 0f), pf(params, "rotY", 0f), 0f);
        return new FrameCommand(
            p(params, "centerX", 0.0), y, p(params, "centerZ", 0.0),
            q.x, q.y, q.z, q.w,
            pf(params, "fov", 70f)
        );
    }
}
