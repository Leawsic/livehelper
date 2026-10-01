package site.leawsic.livehelper.util;

import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.render.StreamManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ActiveRenderContext} 是全局静态的，且 {@code CameraSetup} 每帧都读它来决定要不要接管镜头。
 * 因此一旦残留了最后一帧，即使推流已停，玩家的视角也会一直被控制。
 *
 * <p>这些用例直接检查静态状态本身，避开渲染线程：这里验证的是「谁负责清理」的契约。
 */
class ActiveRenderContextOwnershipTest {

    @Test
    void persistentContextIsClearedWhenNoManagerOwnsTheOutput() {
        ActiveRenderContext.clearPersistent();

        // 模拟「某个 Manager 推过流、随后被停掉」之后的残留状态。
        ActiveRenderContext.setPersistent(new FrameCommand(1, 2, 3, 0, 0, 0, 1, 70f), 1280, 720, 12);
        assertNotNull(ActiveRenderContext.current(),
            "前置条件：上下文里应当有一帧，否则测不出残留");

        // 没有机位在跑时，StreamManager 的每帧收尾必须把它清掉。
        StreamManager.INSTANCE.prepareDueFrames();
        assertNull(ActiveRenderContext.current(),
            "没有机位拥有输出时必须清空渲染上下文，否则镜头会一直被残留帧接管");
    }

    @Test
    void outputOwnerIsAbsentWhenNothingIsRunning() {
        assertEquals(-1, StreamManager.INSTANCE.getOutputOwnerId(),
            "没有任何 Manager 运行时不应存在输出拥有者");
    }

    @Test
    void currentFallsBackToNullAfterClear() {
        ActiveRenderContext.setPersistent(new FrameCommand(9, 9, 9, 0, 0, 0, 1, 70f), 800, 600, 8);
        assertNotNull(ActiveRenderContext.current());

        ActiveRenderContext.clearPersistent();
        assertNull(ActiveRenderContext.current(),
            "clearPersistent 之后 current() 必须返回 null，否则 CameraSetup 会继续接管镜头");
    }

    @Test
    void contextFieldsAreCarriedThroughIntact() {
        FrameCommand frame = new FrameCommand(1.5, 64.0, -2.5, 0, 0, 0, 1, 55.0f);
        ActiveRenderContext.setPersistent(frame, 1920, 1080, 16);

        ActiveRenderContext.Context ctx = ActiveRenderContext.current();
        assertNotNull(ctx);
        assertEquals(1.5, ctx.command().x(), 1e-9);
        assertEquals(1920, ctx.width());
        assertEquals(1080, ctx.height());
        assertEquals(16, ctx.renderDistance());

        ActiveRenderContext.clearPersistent();
    }

    @Test
    void offscreenActiveTracksTheContext() {
        ActiveRenderContext.clearPersistent();
        assertEquals(false, ActiveRenderContext.isOffscreenActive());

        ActiveRenderContext.setPersistent(new FrameCommand(0, 0, 0, 0, 0, 0, 1, 70f), 1280, 720, 12);
        assertEquals(true, ActiveRenderContext.isOffscreenActive(),
            "接管镜头期间 isOffscreenActive 必须为真，否则 HUD 与手臂不会被隐藏");

        ActiveRenderContext.clearPersistent();
        assertEquals(false, ActiveRenderContext.isOffscreenActive());
    }
}
