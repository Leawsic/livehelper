package site.leawsic.livehelper.util;

import site.leawsic.livehelper.model.FrameCommand;

public final class ActiveRenderContext {
    /**
     * 接管中的渲染上下文。
     *
     * <p>只有一个写入方 {@link #setPersistent}：主摄像机接管式推流每产出一帧就写一次。
     * 早前还并存过一套离屏渲染方案的 ThreadLocal 与 fallback 字段，但已无任何写入方，
     * 留着只会让 {@link #isOffscreenActive()} 永远读到一个恒为 null 的分支——那正是
     * HUD 与第一人称手臂抑制长期失效的原因，现已删除。
     */
    private static volatile Context persistentActive = null;

    private ActiveRenderContext() {}

    /** 镜头当前是否被虚拟相机接管。CameraSetup 与 HUD/手臂抑制都以此为准。 */
    public static boolean isOffscreenActive() {
        return persistentActive != null;
    }

    /** 当前生效的帧；null 表示镜头已交还玩家。 */
    public static Context current() {
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
