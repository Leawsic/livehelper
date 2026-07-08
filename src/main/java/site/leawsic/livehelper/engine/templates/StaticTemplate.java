package site.leawsic.livehelper.engine.templates;

import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.p;
import static site.leawsic.livehelper.engine.templates.MotionTemplates.pf;

public class StaticTemplate implements MotionTemplate {
    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        float pitch = pf(params, "rotX", 0f);
        float yaw = pf(params, "rotY", 0f);
        float roll = pf(params, "rotZ", 0f);
        Quaternionf q = AngleConvert.toQuaternion(
            pitch,
            yaw,
            roll
        );

        return new FrameCommand(
            p(params, "posX", 0.0), p(params, "posY", 0.0), p(params, "posZ", 0.0),
            q.x, q.y, q.z, q.w,
            pf(params, "fov", 70f),
            pitch, yaw, roll
        );
    }
}
