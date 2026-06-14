package site.leawsic.livehelper.render;

import net.minecraft.client.Camera;
import site.leawsic.livehelper.model.FrameCommand;

public final class CameraSetup {
    private CameraSetup() {}

    public static void apply(Camera camera, FrameCommand cmd, int width, int height, int renderDistance) {
        throw new UnsupportedOperationException("Camera reflection setup must be verified against 1.20.1 Mojang mappings before runtime use");
    }
}
