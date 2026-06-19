package site.leawsic.livehelper.util;

import site.leawsic.livehelper.model.FrameCommand;

public final class ActiveRenderContext {
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private ActiveRenderContext() {}

    public static void runWithContext(FrameCommand command, int width, int height, int renderDistance, Runnable action) {
        ACTIVE.set(new Context(command, width, height, renderDistance));
        try {
            action.run();
        } finally {
            ACTIVE.remove();
        }
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    public static Context current() {
        return ACTIVE.get();
    }

    public record Context(FrameCommand command, int width, int height, int renderDistance) {}
}
