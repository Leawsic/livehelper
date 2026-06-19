package site.leawsic.livehelper.util;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Thread-safe holder for the original window render target during offscreen rendering.
 * Used by GameRendererMixin to swap back before the GUI clear.
 */
public final class OffscreenTargetTracker {
    private OffscreenTargetTracker() {}

    private static RenderTarget originalTarget = null;

    public static void setOriginalTarget(RenderTarget target) {
        originalTarget = target;
    }

    public static RenderTarget getOriginalTarget() {
        return originalTarget;
    }

    public static void clear() {
        originalTarget = null;
    }
}
