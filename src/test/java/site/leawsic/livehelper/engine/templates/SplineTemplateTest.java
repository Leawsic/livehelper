package site.leawsic.livehelper.engine.templates;

import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.model.FrameCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplineTemplateTest {

    private static Map<String, Object> keyframe(double t, double x, double y, double z,
                                               double rx, double ry, double rz, Double fov) {
        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("t", t);
        kf.put("x", x);
        kf.put("y", y);
        kf.put("z", z);
        kf.put("rx", rx);
        kf.put("ry", ry);
        kf.put("rz", rz);
        kf.put("fov", fov);
        return kf;
    }

    private static Map<String, Object> params(List<Map<String, Object>> keyframes, String... extra) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", keyframes);
        params.put("fov", 70.0);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            params.put(extra[i], extra[i + 1]);
        }
        return params;
    }

    private static List<Map<String, Object>> cornerPath() {
        List<Map<String, Object>> keys = new ArrayList<>();
        keys.add(keyframe(0.0, 0, 80, 0, 0, 0, 0, 75.0));
        keys.add(keyframe(0.5, 10, 80, 0, 0, 90, 0, 45.0));
        keys.add(keyframe(1.0, 10, 80, 10, 0, 180, 0, 75.0));
        return keys;
    }

    @Test
    void prepareReturnsNullWhenKeyframesMissing() {
        assertNull(new SplineTemplate().prepare(new LinkedHashMap<>()));
        assertNull(new SplineTemplate().prepare(null));
    }

    @Test
    void singleKeyframeHoldsThatPose() {
        List<Map<String, Object>> keys = new ArrayList<>();
        keys.add(keyframe(0.0, 5, 70, -3, 10, 20, 0, 60.0));
        FrameCommand frame = new SplineTemplate().evaluate(params(keys), 0.5f);
        assertEquals(5.0, frame.x(), 1e-6);
        assertEquals(70.0, frame.y(), 1e-6);
        assertEquals(-3.0, frame.z(), 1e-6);
    }

    @Test
    void endpointsSitExactlyOnKeyframes() {
        SplineTemplate spline = new SplineTemplate();
        Map<String, Object> p = params(cornerPath());
        Object prepared = spline.prepare(p);

        FrameCommand start = spline.evaluatePrepared(p, 0f, prepared);
        assertEquals(0.0, start.x(), 1e-6);
        assertEquals(80.0, start.y(), 1e-6);
        assertEquals(0.0, start.z(), 1e-6);

        FrameCommand end = spline.evaluatePrepared(p, 1f, prepared);
        assertEquals(10.0, end.x(), 1e-6);
        assertEquals(80.0, end.y(), 1e-6);
        assertEquals(10.0, end.z(), 1e-6);
    }

    /** 关键性质：SPLINE 按弧长匀速，采样点之间的世界距离应基本相等。 */
    @Test
    void arcLengthReparameterizationYieldsConstantSpeed() {
        SplineTemplate spline = new SplineTemplate();
        Map<String, Object> p = params(cornerPath());
        Object prepared = spline.prepare(p);

        final int samples = 40;
        double[] previous = null;
        double firstStep = Double.NaN;
        double minStep = Double.MAX_VALUE;
        double maxStep = 0.0;
        for (int i = 0; i <= samples; i++) {
            FrameCommand frame = spline.evaluatePrepared(p, i / (float) samples, prepared);
            double[] point = {frame.x(), frame.y(), frame.z()};
            if (previous != null) {
                double step = dist(previous, point);
                if (firstStep != firstStep) {
                    firstStep = step;
                } else {
                    minStep = Math.min(minStep, step);
                    maxStep = Math.max(maxStep, step);
                }
            }
            previous = point;
        }
        assertTrue(maxStep / minStep < 1.15,
                "arc-length reparameterization should keep step lengths uniform, min=" + minStep + " max=" + maxStep);
    }

    /** SPLINE 走弧线，PATH 走折线——这正是保留 PATH 的理由。 */
    @Test
    void splineBendsAwayFromStraightLineWherePathDoesNot() {
        List<Map<String, Object>> keys = cornerPath();
        Map<String, Object> p = params(keys);

        FrameCommand splineMid = new SplineTemplate().evaluate(p, 0.25f);
        FrameCommand pathMid = new PathTemplate().evaluate(p, 0.25f);

        // 0.25 落在第一段中点：PATH 必在 (5, 80, 0)，SPLINE 因切线方向而偏离。
        assertEquals(5.0, pathMid.x(), 1e-6);
        assertTrue(Math.abs(splineMid.x() - 5.0) > 0.1 || Math.abs(splineMid.z()) > 0.1,
                "SPLINE should not be collinear with the straight segment");
    }

    @Test
    void keyframesOutOfOrderAreSortedOnPrepare() {
        List<Map<String, Object>> keys = new ArrayList<>();
        keys.add(keyframe(1.0, 20, 80, 0, 0, 0, 0, 70.0));
        keys.add(keyframe(0.0, 0, 80, 0, 0, 0, 0, 70.0));
        keys.add(keyframe(0.5, 10, 80, 0, 0, 0, 0, 70.0));

        Map<String, Object> p = params(keys);
        FrameCommand start = new SplineTemplate().evaluate(p, 0f);
        FrameCommand end = new SplineTemplate().evaluate(p, 1f);
        assertEquals(0.0, start.x(), 1e-6);
        assertEquals(20.0, end.x(), 1e-6);
    }

    @Test
    void tangentOrientationLooksAlongPathDirection() {
        Map<String, Object> p = params(cornerPath(), "orientMode", "tangent");
        FrameCommand keyframeMode = new SplineTemplate().evaluate(params(cornerPath()), 0.25f);
        FrameCommand tangentMode = new SplineTemplate().evaluate(p, 0.25f);

        // 第一段整体朝 +x 飞，切线朝向应与关键帧给的 ry=0（朝 +z）明显不同。
        assertTrue(Math.abs(tangentMode.yaw() - keyframeMode.yaw()) > 1.0,
                "tangent mode should produce a different heading than keyframe mode");
    }

    @Test
    void fovIsInterpolatedAndClamped() {
        SplineTemplate spline = new SplineTemplate();
        Map<String, Object> p = params(cornerPath());
        Object prepared = spline.prepare(p);
        // 关键帧 fov 为 75 -> 45 -> 75；段内中点（progress=0.25 与 0.75）应为 60。
        assertEquals(60.0, spline.evaluatePrepared(p, 0.25f, prepared).fov(), 1e-3);
        assertEquals(45.0, spline.evaluatePrepared(p, 0.5f, prepared).fov(), 1e-3);
        assertEquals(60.0, spline.evaluatePrepared(p, 0.75f, prepared).fov(), 1e-3);

        List<Map<String, Object>> keys = new ArrayList<>();
        keys.add(keyframe(0.0, 0, 80, 0, 0, 0, 0, 900.0));
        keys.add(keyframe(1.0, 5, 80, 0, 0, 0, 0, 900.0));
        Map<String, Object> overWide = params(keys);
        overWide.put("fov", 900.0);
        FrameCommand clamped = spline.evaluate(overWide, 0.5f);
        assertTrue(clamped.fov() <= 179f, "fov must be clamped into the renderable range");
    }

    @Test
    void everyFrameIsFinite() {
        SplineTemplate spline = new SplineTemplate();
        Map<String, Object> p = params(cornerPath(), "orientMode", "tangent");
        Object prepared = spline.prepare(p);
        assertNotNull(prepared);
        for (int i = 0; i <= 100; i++) {
            FrameCommand frame = spline.evaluatePrepared(p, i / 100f, prepared);
            assertTrue(Double.isFinite(frame.x()) && Double.isFinite(frame.y()) && Double.isFinite(frame.z()));
            assertTrue(Float.isFinite(frame.fov()));
            assertTrue(Double.isFinite(frame.qw()));
        }
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
