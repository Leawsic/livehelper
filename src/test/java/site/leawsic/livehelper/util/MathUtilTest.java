package site.leawsic.livehelper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathUtilTest {

    @Test
    void wrapDegreesNormalizesToHalfOpenRange() {
        assertEquals(0f, MathUtil.wrapDegrees(360f), 1e-4f);
        assertEquals(-90f, MathUtil.wrapDegrees(270f), 1e-4f);
        // 与 ImmersiveCinematics 同一约定：区间为 [-180, 180)，180 归到 -180。
        assertEquals(-180f, MathUtil.wrapDegrees(180f), 1e-4f);
        assertEquals(-179f, MathUtil.wrapDegrees(181f), 1e-4f);
        assertEquals(0f, MathUtil.wrapDegrees(-720f), 1e-4f);
    }

    /** 回归测试：普通 lerp 会让 170 -> -170 绕 340 度远路。 */
    @Test
    void lerpAngleTakesShortestArc() {
        float mid = MathUtil.lerpAngle(170f, -170f, 0.5f);
        assertEquals(180f, Math.abs(mid), 1e-3f);

        float quarter = MathUtil.lerpAngle(170f, -170f, 0.25f);
        assertEquals(175f, quarter, 1e-3f);
    }

    @Test
    void lerpAngleEndpointsAreExact() {
        assertEquals(30f, MathUtil.lerpAngle(30f, 90f, 0f), 1e-4f);
        assertEquals(90f, MathUtil.lerpAngle(30f, 90f, 1f), 1e-4f);
    }

    @Test
    void easeIsClampedAndMonotonic() {
        assertEquals(0f, MathUtil.ease(-5f, "easeIn"), 1e-6f);
        assertEquals(1f, MathUtil.ease(5f, "easeInOut"), 1e-6f);
        assertEquals(0f, MathUtil.ease(Float.NaN, "easeIn"), 1e-6f);

        float previous = -1f;
        for (int i = 0; i <= 20; i++) {
            float value = MathUtil.ease(i / 20f, "easeInOut");
            assertTrue(value >= previous, "easeInOut must be monotonic at i=" + i);
            previous = value;
        }
    }

    @Test
    void easeInOutIsSymmetricAroundMidpoint() {
        assertEquals(0.5f, MathUtil.ease(0.5f, "easeInOut"), 1e-6f);
        float a = MathUtil.ease(0.25f, "easeInOut");
        float b = MathUtil.ease(0.75f, "easeInOut");
        assertEquals(1f - a, b, 1e-6f);
    }

    @Test
    void unknownEasingFallsBackToLinear() {
        assertEquals(0.42f, MathUtil.ease(0.42f, "notAnEasing"), 1e-6f);
        assertEquals(0.42f, MathUtil.ease(0.42f, null), 1e-6f);
    }

    @Test
    void sanitizeReplacesNonFinite() {
        assertEquals(7f, MathUtil.sanitizeFloat(Float.NaN, 7f), 1e-6f);
        assertEquals(7f, MathUtil.sanitizeFloat(Float.POSITIVE_INFINITY, 7f), 1e-6f);
        assertEquals(3f, MathUtil.sanitizeFloat(3f, 7f), 1e-6f);
        // fallback 本身也不可用时退到 0，而不是继续传播 NaN。
        assertEquals(0f, MathUtil.sanitizeFloat(Float.NaN, Float.NaN), 1e-6f);
        assertEquals(1.5, MathUtil.sanitizeDouble(Double.NaN, 1.5), 1e-9);
    }

    @Test
    void clampGuardsFovRange() {
        assertEquals(1f, MathUtil.clamp(-3f, 1f, 179f), 1e-6f);
        assertEquals(179f, MathUtil.clamp(500f, 1f, 179f), 1e-6f);
        assertEquals(70f, MathUtil.clamp(70f, 1f, 179f), 1e-6f);
        assertEquals(1f, MathUtil.clamp(Float.NaN, 1f, 179f), 1e-6f);
    }

    @Test
    void cubicBezierEndpointsAreControlPoints() {
        assertEquals(0.0, MathUtil.cubicBezier(0, 5, 10, 15, 0.0), 1e-9);
        assertEquals(15.0, MathUtil.cubicBezier(0, 5, 10, 15, 1.0), 1e-9);
    }

    @Test
    void cubicBezierDerivativeMatchesNumericSlope() {
        double p0 = 0, p1 = 3, p2 = 7, p3 = 10;
        double t = 0.37;
        double h = 1e-6;
        double numeric = (MathUtil.cubicBezier(p0, p1, p2, p3, t + h)
                - MathUtil.cubicBezier(p0, p1, p2, p3, t - h)) / (2 * h);
        double analytic = MathUtil.cubicBezierDerivative(p0, p1, p2, p3, t);
        assertEquals(analytic, numeric, 1e-4);
    }

    @Test
    void isValidEasingAcceptsOnlyKnownCurves() {
        assertTrue(MathUtil.isValidEasing("linear"));
        assertTrue(MathUtil.isValidEasing("smoothstep"));
        assertFalse(MathUtil.isValidEasing("bounce"));
        assertFalse(MathUtil.isValidEasing(null));
    }
}
