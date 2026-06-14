package site.leawsic.livehelper.util;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class AngleConvert {
    private AngleConvert() {}

    public static Quaternionf toQuaternion(float pitchDeg, float yawDeg, float rollDeg) {
        Quaternionf q = new Quaternionf();
        q.rotationYXZ(
            (float) Math.PI - yawDeg * (float) (Math.PI / 180.0),
            -pitchDeg * (float) (Math.PI / 180.0),
            -rollDeg * (float) (Math.PI / 180.0)
        );
        return q;
    }

    public static Vector3f toEulerAngles(Quaternionf q) {
        Vector3f euler = new Vector3f();
        q.getEulerAnglesYXZ(euler);
        euler.set(
            (float) Math.toDegrees(-euler.x),
            (float) Math.toDegrees(Math.PI - euler.y),
            (float) Math.toDegrees(-euler.z)
        );
        return euler;
    }

    public static Quaternionf lookAt(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double yaw = Math.atan2(dz, dx);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.atan2(dy, horizontalDist);

        return toQuaternion((float) Math.toDegrees(pitch), (float) Math.toDegrees(yaw), 0f);
    }

    public static Quaternionf lookInDirection(double dx, double dy, double dz) {
        double yaw = Math.atan2(dz, dx);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.atan2(dy, horizontalDist);
        return toQuaternion((float) Math.toDegrees(pitch), (float) Math.toDegrees(yaw), 0f);
    }
}
