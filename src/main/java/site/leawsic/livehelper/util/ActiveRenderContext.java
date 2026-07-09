package site.leawsic.livehelper.util;

import site.leawsic.livehelper.model.FrameCommand;

public final class ActiveRenderContext {
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();
    private static volatile Context fallbackActive = null;
    private static volatile Context persistentActive = null;

    private ActiveRenderContext() {}

    public static boolean isOffscreenActive() {
        return ACTIVE.get() != null || fallbackActive != null;
    }

    public static Context current() {
        Context context = ACTIVE.get();
        if (context != null) return context;
        if (fallbackActive != null) return fallbackActive;
        return persistentActive;
    }

    public static void setPersistent(FrameCommand command, int width, int height, int renderDistance) {
        persistentActive = new Context(command, width, height, renderDistance);
    }

    public static void clearPersistent() {
        persistentActive = null;
    }

    public record Context(FrameCommand command, int width, int height, int renderDistance) {}
}
