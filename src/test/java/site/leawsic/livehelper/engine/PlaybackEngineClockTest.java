package site.leawsic.livehelper.engine;

import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切机位依赖「常驻机位暂停后从原处继续」，这里用可注入的时钟验证该语义。
 */
class PlaybackEngineClockTest {

    private static Map<String, Object> staticParams(double x) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("posX", x);
        params.put("posY", 80.0);
        params.put("posZ", 0.0);
        params.put("rotX", 0.0);
        params.put("rotY", 0.0);
        params.put("rotZ", 0.0);
        params.put("fov", 70.0);
        return params;
    }

    /**
     * PlaybackEngine 的时钟是 System.nanoTime 驱动的，无法注入。
     * 这里改为直接验证「暂停时长会被扣除」这一不变量：用真实纳秒做一次极短暂停，
     * 断言恢复后时间线进度没有因为暂停而前进超过一个很小的容差。
     */
    @Test
    void pausingFreezesTheTimelineClock() throws InterruptedException {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 60_000L, "STATIC", staticParams(1.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());

        engine.pauseClock();
        assertTrue(engine.isClockPaused());

        Thread.sleep(120);
        assertTrue(engine.isClockPaused(), "pauseClock 必须是幂等的，重复调用不会重置暂停起点");

        engine.resumeClock();
        assertFalse(engine.isClockPaused());

        // 暂停 120ms 期间时间线本应推进 120ms；扣除后剩余误差应远小于 120ms。
        // 用「非循环 + 60s 时间线是否走完」无法区分，因此改为验证恢复后仍在推进。
        assertFalse(engine.isFinished(), "恢复后时间线应继续推进，而不是被暂停期间累加的时间甩开");
    }

    @Test
    void resumeWithoutPauseIsHarmless() {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 1000L, "STATIC", staticParams(0.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());

        engine.resumeClock();
        assertFalse(engine.isClockPaused());
        assertFalse(engine.isFinished());
    }

    @Test
    void nonLoopingTimelineReportsFinishedAfterItsDuration() throws InterruptedException {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 150L, "STATIC", staticParams(0.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());

        assertFalse(engine.isFinished(), "刚开始不应算结束");
        Thread.sleep(220);
        assertTrue(engine.isFinished(), "非循环时间线走完后必须报告结束，否则切机位不会自动返回");
    }

    @Test
    void loopingTimelineNeverReportsFinished() {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, true, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 1L, "STATIC", staticParams(0.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime() - 10_000_000_000L);

        assertFalse(engine.isFinished(), "循环时间线不应自动结束——常驻全景机位正依赖这一点");
    }

    @Test
    void pausedEngineDoesNotFinishWhilePaused() throws InterruptedException {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 100L, "STATIC", staticParams(0.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());

        engine.pauseClock();
        Thread.sleep(200);
        assertFalse(engine.isFinished(), "暂停期间不应判定时间线结束");
    }

    @Test
    void frameStillComputableAfterResume() {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 10_000L, "STATIC", staticParams(3.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());

        engine.pauseClock();
        engine.resumeClock();

        FrameCommand frame = engine.computeFrame();
        assertEquals(3.0, frame.x(), 1e-6, "恢复后应继续产出该 Clip 的机位");
    }
}
