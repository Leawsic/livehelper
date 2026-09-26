package site.leawsic.livehelper.engine.templates;

import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;
import site.leawsic.livehelper.util.MathUtil;

import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

public class PanTiltTemplate implements MotionTemplate {
    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        float eased = ease(progress, ps(params, "easing", "linear"));
        float pan = lerp(pf(params, "startPan", 0f), pf(params, "endPan", 0f), eased);
        float tilt = lerp(pf(params, "startTilt", 0f), pf(params, "endTilt", 0f), eased);
        Quaternionf q = AngleConvert.toQuaternion(tilt, pan, 0f);

        return new FrameCommand(
            p(params, "posX", 0.0), p(params, "posY", 0.0), p(params, "posZ", 0.0),
            q.x, q.y, q.z, q.w,
            MathUtil.clamp(pf(params, "fov", 70f), MathUtil.MIN_FOV, MathUtil.MAX_FOV),
            tilt, pan, 0f
        );
    }
}
