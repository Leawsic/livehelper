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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackEngineTest {

    private static Clip clip(int id, String template, long duration, Map<String, Object> params) {
        return new Clip(id, "clip-" + id, duration, template, params);
    }

    private static Map<String, Object> staticParams(double x, double y, double z, double yaw) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("posX", x);
        params.put("posY", y);
        params.put("posZ", z);
        params.put("rotX", 0.0);
        params.put("rotY", yaw);
        params.put("rotZ", 0.0);
        params.put("fov", 70.0);
        return params;
    }

    private static PlaybackEngine engine(Manager manager, Clip... clips) {
        Map<Integer, Clip> cache = new LinkedHashMap<>();
        for (Clip c : clips) {
            cache.put(c.id(), c);
        }
        return new PlaybackEngine(manager, cache, 0L);
    }

    // ── 消毒 ───────────────────────────────────────────────

    @Test
    void sanitizeRejectsNonFinitePosition() {
        FrameCommand bad = new FrameCommand(Double.NaN, 1, 2, 0, 0, 0, 1, 70f);
        assertNull(PlaybackEngine.sanitizeOrNull(bad));
    }

    @Test
    void sanitizeRejectsDegenerateQuaternion() {
        FrameCommand bad = new FrameCommand(1, 2, 3, 0, 0, 0, 0, 70f);
        assertNull(PlaybackEngine.sanitizeOrNull(bad));
    }

    @Test
    void sanitizeFallsBackToLastGoodFrame() {
        FrameCommand good = new FrameCommand(1, 2, 3, 0, 0, 0, 1, 70f);
        FrameCommand bad = new FrameCommand(Double.NaN, 2, 3, 0, 0, 0, 1, 70f);

        FrameCommand result = PlaybackEngine.sanitize(bad, good);
        assertNotNull(result);
        assertEquals(1.0, result.x(), 1e-9);
    }

    @Test
    void sanitizeReturnsNullWhenNothingUsable() {
        FrameCommand bad = new FrameCommand(Double.NaN, 0, 0, 0, 0, 0, 1, 70f);
        assertNull(PlaybackEngine.sanitize(bad, null));
        assertNull(PlaybackEngine.sanitize(null, null));
    }

    @Test
    void sanitizeClampsFovIntoRenderableRange() {
        FrameCommand wide = new FrameCommand(0, 0, 0, 0, 0, 0, 1, 5000f);
        assertEquals(179f, PlaybackEngine.sanitizeOrNull(wide).fov(), 1e-4);

        FrameCommand narrow = new FrameCommand(0, 0, 0, 0, 0, 0, 1, -20f);
        assertEquals(1f, PlaybackEngine.sanitizeOrNull(narrow).fov(), 1e-4);
    }

    @Test
    void sanitizeNormalizesUnnormalizedQuaternion() {
        FrameCommand scaled = new FrameCommand(0, 0, 0, 0, 0, 0, 5f, 70f);
        FrameCommand clean = PlaybackEngine.sanitizeOrNull(scaled);
        double length = Math.sqrt(clean.qx() * clean.qx() + clean.qy() * clean.qy()
                + clean.qz() * clean.qz() + clean.qw() * clean.qw());
        assertEquals(1.0, length, 1e-5);
    }

    @Test
    void sanitizeTreatsInfiniteEulerAsAbsent() {
        FrameCommand frame = new FrameCommand(0, 0, 0, 0, 0, 0, 1, 70f,
                Float.POSITIVE_INFINITY, 30f, Float.NaN);
        FrameCommand clean = PlaybackEngine.sanitizeOrNull(frame);
        assertTrue(Float.isNaN(clean.pitch()));
        assertEquals(30f, clean.yaw(), 1e-6);
        assertTrue(Float.isNaN(clean.roll()));
    }

    // ── 时间线 ─────────────────────────────────────────────

    @Test
    void returnsNullOutsideAnyClip() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear")),
                1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        PlaybackEngine engine = engine(manager, clip(1, "STATIC", 1000L, staticParams(0, 80, 0, 0)));

        assertNotNull(engine.computeFrameAt(0L));
        assertNotNull(engine.computeFrameAt(999L));
        assertNull(engine.computeFrameAt(1000L));
        assertNull(engine.computeFrameAt(5000L));
    }

    @Test
    void zeroDurationClipYieldsNoFrameInsteadOfDividingByZero() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear")),
                1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        PlaybackEngine engine = engine(manager, clip(1, "STATIC", 0L, staticParams(0, 80, 0, 0)));
        assertNull(engine.computeFrameAt(0L));
    }

    @Test
    void repeatLoopWrapsToStart() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear")),
                1280, 720, 30, 12, true, Manager.LOOP_REPEAT, false);
        PlaybackEngine engine = engine(manager, clip(1, "STATIC", 1000L, staticParams(7, 80, 0, 0)));

        assertEquals(7.0, engine.computeFrameAt(0L).x(), 1e-6);
        assertEquals(7.0, engine.computeFrameAt(1500L).x(), 1e-6);
        assertEquals(7.0, engine.computeFrameAt(2500L).x(), 1e-6);
    }

    @Test
    void pingpongLoopReversesTimeline() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 1000L, 0L, "linear")),
                1280, 720, 30, 12, true, Manager.LOOP_PINGPONG, false);
        // 用 DOLLY 而非 STATIC，才能从位置上看出时间线被折返。
        Map<String, Object> dollyA = new LinkedHashMap<>();
        dollyA.put("fromX", 0.0);
        dollyA.put("toX", 100.0);
        dollyA.put("easing", "linear");
        dollyA.put("fov", 70.0);
        Map<String, Object> dollyB = new LinkedHashMap<>();
        dollyB.put("fromX", 100.0);
        dollyB.put("toX", 0.0);
        dollyB.put("easing", "linear");
        dollyB.put("fov", 70.0);

        PlaybackEngine engine = engine(manager,
                clip(1, "DOLLY", 1000L, dollyA),
                clip(2, "DOLLY", 1000L, dollyB));

        // 正向：0 -> 100 -> 0（两段各 1000ms）
        assertEquals(0.0, engine.computeFrameAt(0L).x(), 1.0);
        assertEquals(100.0, engine.computeFrameAt(1000L).x(), 1.0);
        assertEquals(0.0, engine.computeFrameAt(2000L).x(), 1.0);
        // 折返：2500ms 映射回 1500ms，此时应处于第二段中点 x≈50
        assertEquals(50.0, engine.computeFrameAt(2500L).x(), 1.0);
        assertEquals(100.0, engine.computeFrameAt(3000L).x(), 1.0);
    }

    /** 折返点不能落进片段空隙，否则每个往返周期会闪一帧玩家视角。 */
    @Test
    void pingpongTurnaroundNeverReturnsNull() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 1000L, 0L, "linear")),
                1280, 720, 30, 12, true, Manager.LOOP_PINGPONG, false);
        PlaybackEngine engine = engine(manager,
                clip(1, "STATIC", 1000L, staticParams(0, 80, 0, 0)),
                clip(2, "STATIC", 1000L, staticParams(100, 80, 0, 0)));

        for (long t = 0L; t < 8000L; t += 10L) {
            assertNotNull(engine.computeFrameAt(t), "pingpong produced a gap frame at t=" + t);
        }
    }

    @Test
    void missingLoopModeFallsBackToRepeat() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear")),
                1280, 720, 30, 12, true, null, false);
        assertEquals(Manager.LOOP_REPEAT, manager.effectiveLoopMode());
        assertEquals(Manager.LOOP_REPEAT, new Manager(1, "m", List.of(), 1, 1, 1, 1).effectiveLoopMode());
    }

    // ── 转场 ───────────────────────────────────────────────

    @Test
    void contiguousTransitionStartsExactlyOnPreviousFinalFrame() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 1000L, 1000L, "linear")),
                1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        PlaybackEngine engine = engine(manager,
                clip(1, "STATIC", 1000L, staticParams(0, 80, 0, 0)),
                clip(2, "STATIC", 1000L, staticParams(100, 80, 0, 90)));

        // 转场起点必须与上一段末帧完全一致，否则切换瞬间会跳。
        FrameCommand atCut = engine.computeFrameAt(1000L);
        FrameCommand previousEnd = engine.computeFrameAt(999L);
        assertEquals(previousEnd.x(), atCut.x(), 1e-6);
        assertEquals(previousEnd.yaw(), atCut.yaw(), 1e-4);
    }

    /** Phase 1.3 回归：片段重叠时上一段仍在中途，不能取它的末帧。 */
    @Test
    void overlappingTransitionUsesLivePreviousPoseNotItsEndFrame() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 500L, 1000L, "linear")),
                1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        // 第一段用 DOLLY，让中途与末帧位置不同。
        Map<String, Object> dolly = new LinkedHashMap<>();
        dolly.put("fromX", 0.0);
        dolly.put("fromY", 80.0);
        dolly.put("fromZ", 0.0);
        dolly.put("toX", 100.0);
        dolly.put("toY", 80.0);
        dolly.put("toZ", 0.0);
        dolly.put("easing", "linear");
        dolly.put("fov", 70.0);

        Map<String, Object> target = staticParams(0, 80, 0, 0);
        PlaybackEngine engine = engine(manager,
                clip(1, "DOLLY", 1000L, dolly),
                clip(2, "STATIC", 1000L, target));

        // 重叠段起点：上一段实际只走到 50%（x≈50），而它的末帧是 x=100。
        FrameCommand atOverlapStart = engine.computeFrameAt(500L);
        assertEquals(50.0, atOverlapStart.x(), 0.5,
                "transition must start from the previous clip's live pose (x≈50), not its end (x=100)");
    }

    @Test
    void transitionBlendsWithSlerpSoFacingStaysUnitLength() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 500L, 1000L, "linear")),
                1280, 720, 30, 12, false, Manager.LOOP_REPEAT, false);
        PlaybackEngine engine = engine(manager,
                clip(1, "STATIC", 1000L, staticParams(0, 80, 0, 0)),
                clip(2, "STATIC", 1000L, staticParams(0, 80, 0, 180)));

        for (long t = 500L; t <= 1499L; t += 100L) {
            FrameCommand frame = engine.computeFrameAt(t);
            assertNotNull(frame, "unexpected gap at t=" + t);
            double length = Math.sqrt(frame.qx() * frame.qx() + frame.qy() * frame.qy()
                    + frame.qz() * frame.qz() + frame.qw() * frame.qw());
            assertEquals(1.0, length, 1e-4, "blended quaternion must stay normalized at t=" + t);
        }
    }

    @Test
    void allComputedFramesAreFinite() {
        Manager manager = new Manager(1, "m",
                List.of(new ClipSlot(1, 0L, 400L, "easeInOut"), new ClipSlot(2, 800L, 400L, "linear")),
                1280, 720, 30, 12, true, Manager.LOOP_PINGPONG, false);
        PlaybackEngine engine = engine(manager,
                clip(1, "ORBIT", 800L, new LinkedHashMap<>(Map.of("targetX", 0.0, "targetY", 70.0, "targetZ", 0.0))),
                clip(2, "PEDESTAL", 800L, new LinkedHashMap<>(Map.of("centerX", 4.0, "centerZ", 4.0,
                        "fromHeight", 60.0, "toHeight", 90.0))));

        for (long t = 0L; t < 5000L; t += 37L) {
            FrameCommand frame = engine.computeFrameAt(t);
            if (frame == null) continue;
            assertTrue(Double.isFinite(frame.x()) && Double.isFinite(frame.y()) && Double.isFinite(frame.z()),
                    "non-finite position at t=" + t);
            assertTrue(Float.isFinite(frame.fov()), "non-finite fov at t=" + t);
            double length = Math.sqrt(frame.qx() * frame.qx() + frame.qy() * frame.qy()
                    + frame.qz() * frame.qz() + frame.qw() * frame.qw());
            assertTrue(Double.isFinite(length) && length > 0.5, "bad quaternion at t=" + t);
        }
    }
}
