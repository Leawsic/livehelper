package site.leawsic.livehelper.model;

public record FrameCommand(
    double x,
    double y,
    double z,
    float qx,
    float qy,
    float qz,
    float qw,
    float fov,
    float pitch,
    float yaw,
    float roll
) {
    public FrameCommand(double x, double y, double z, float qx, float qy, float qz, float qw, float fov) {
        this(x, y, z, qx, qy, qz, qw, fov, Float.NaN, Float.NaN, Float.NaN);
    }

    public boolean hasEulerAngles() {
        return !Float.isNaN(pitch) && !Float.isNaN(yaw) && !Float.isNaN(roll);
    }
}
