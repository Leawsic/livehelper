package site.leawsic.livehelper.model;

public record FrameCommand(
    double x,
    double y,
    double z,
    float qx,
    float qy,
    float qz,
    float qw,
    float fov
) {}
