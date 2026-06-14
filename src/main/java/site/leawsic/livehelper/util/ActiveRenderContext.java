package site.leawsic.livehelper.util;

public final class ActiveRenderContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private ActiveRenderContext() {}

    public static void runWithContext(Runnable action) {
        ACTIVE.set(true);
        try {
            action.run();
        } finally {
            ACTIVE.set(false);
        }
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}
