package site.leawsic.livehelper.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import site.leawsic.livehelper.mixin.CameraAccessor;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

public final class CameraSetup {
    private CameraSetup() {}

    private static long lastDebugLogNs = 0L;

    public static void apply(Camera camera, FrameCommand cmd, int width, int height, int renderDistance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        camera.setup(mc.level, mc.player, false, false, 1.0F);
        applyAfterSetup(camera, cmd);
    }

    public static void applyAfterSetup(Camera camera, FrameCommand cmd) {
        CameraAccessor accessor = (CameraAccessor) camera;
        accessor.livehelper$setPosition(cmd.x(), cmd.y(), cmd.z());

        Vector3f angles = AngleConvert.toEulerAngles(new Quaternionf(cmd.qx(), cmd.qy(), cmd.qz(), cmd.qw()));
        accessor.livehelper$setRotation(angles.y, angles.x);
        accessor.livehelper$setInitialized(true);

        long now = System.nanoTime();
        if (now - lastDebugLogNs > 2_000_000_000L) {
            lastDebugLogNs = now;
        }
    }
}
