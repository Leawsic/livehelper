package site.leawsic.livehelper.engine.templates;

import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.Map;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.p;
import static site.leawsic.livehelper.engine.templates.MotionTemplates.pf;

public class OrbitTemplate implements MotionTemplate {
    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        double targetX = p(params, "targetX", 0.0);
        double targetY = p(params, "targetY", 0.0);
        double targetZ = p(params, "targetZ", 0.0);
        double radius = p(params, "radius", 10.0);
        double speed = p(params, "speed", 1.0);
        double startAngle = Math.toRadians(p(params, "startAngle", 0.0));
        double elevation = Math.toRadians(p(params, "elevation", 0.0));

        double angle = startAngle + progress * 2.0 * Math.PI * speed;
        double x = targetX + radius * Math.cos(angle);
        double z = targetZ + radius * Math.sin(angle);
        double y = targetY + Math.tan(elevation) * radius;

        Quaternionf q = AngleConvert.lookAt(x, y, z, targetX, targetY, targetZ);
        return new FrameCommand(x, y, z, q.x, q.y, q.z, q.w, pf(params, "fov", 70f));
    }
}
