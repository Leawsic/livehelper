package site.leawsic.livehelper.render;

import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.Manager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Manager 的 fps 通常低于游戏渲染帧率，两个相邻 MC 帧之间多半还没到产出点。
 * 「没到点」必须与「时间线没有画面」区分开，否则 MC 画面会在相机视角与玩家视角之间来回闪。
 */
class StreamInstanceTest {

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

    private static StreamInstance instance(int fps, long durationMs) {
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, fps, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", durationMs, "STATIC", staticParams(5.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());
        return new StreamInstance(1, manager, engine);
    }

    @Test
    void firstPollProducesAFrame() {
        StreamInstance instance = instance(30, 10_000L);
        StreamInstance.Frame frame = instance.pollFrame(System.nanoTime());
        assertEquals(StreamInstance.FrameStatus.PRODUCED, frame.status());
        assertNotNull(frame.command());
        assertEquals(5.0, frame.command().x(), 1e-6);
    }

    /** 核心回归：产出间隔内的重复 poll 必须报 NOT_DUE，而不是伪装成「没有画面」。 */
    @Test
    void pollsBeforeTheIntervalReportNotDueNotEmpty() {
        StreamInstance instance = instance(30, 10_000L);
        long now = System.nanoTime();

        assertEquals(StreamInstance.FrameStatus.PRODUCED, instance.pollFrame(now).status());

        // 30fps 的产出间隔约 33ms；此后的若干次 poll 都应报 NOT_DUE。
        for (int i = 0; i < 3; i++) {
            now += 8_000_000L; // 8ms，仍未到 33ms
            StreamInstance.Frame frame = instance.pollFrame(now);
            assertEquals(StreamInstance.FrameStatus.NOT_DUE, frame.status(),
                "间隔内的 poll 必须报 NOT_DUE，否则调用方会清空渲染上下文导致画面闪烁");
            assertNull(frame.command());
        }

        // 越过间隔后应重新产出。
        now += 40_000_000L;
        assertEquals(StreamInstance.FrameStatus.PRODUCED, instance.pollFrame(now).status());
    }

    @Test
    void highGameFpsAgainstLowManagerFpsNeverReportsEmpty() {
        // 60fps 的游戏渲染、30fps 的 Manager：每个产出间隔里有 1~2 次「间隔内」poll。
        StreamInstance instance = instance(30, 10_000L);
        long now = System.nanoTime();
        int empty = 0;
        for (int i = 0; i < 120; i++) { // 模拟 120 个 MC 帧
            now += 16_666_667L;        // 60fps
            StreamInstance.Frame frame = instance.pollFrame(now);
            if (frame.status() == StreamInstance.FrameStatus.EMPTY) {
                empty++;
            }
        }
        assertEquals(0, empty,
            "连续播放的时间线里不应出现 EMPTY；否则 MC 画面会在相机视角与玩家视角之间闪");
    }

    @Test
    void emptyTimelineIsReportedOnceAsEmpty() {
        // duration=0 的 Clip 没有任何可播放内容。
        Manager manager = new Manager(1, "m",
            List.of(new ClipSlot(1, 0L, 0L, "linear")),
            1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        Clip clip = new Clip(1, "c", 0L, "STATIC", staticParams(5.0));
        PlaybackEngine engine = new PlaybackEngine(manager, Map.of(1, clip), System.nanoTime());
        StreamInstance instance = new StreamInstance(1, manager, engine);

        StreamInstance.Frame frame = instance.pollFrame(System.nanoTime());
        assertEquals(StreamInstance.FrameStatus.EMPTY, frame.status(),
            "时间线确实没有画面时才报 EMPTY");
    }

    @Test
    void pausedInstanceReportsNotDue() {
        StreamInstance instance = instance(30, 10_000L);
        assertEquals(StreamInstance.FrameStatus.PRODUCED, instance.pollFrame(System.nanoTime()).status());

        instance.pauseForCue();
        assertEquals(StreamInstance.FrameStatus.NOT_DUE, instance.pollFrame(System.nanoTime()).status());

        instance.resumeFromCue();
        assertEquals(StreamInstance.FrameStatus.PRODUCED, instance.pollFrame(System.nanoTime()).status(),
            "恢复后必须立即可产出，否则返回常驻机位时会闪一帧玩家视角");
    }
}
