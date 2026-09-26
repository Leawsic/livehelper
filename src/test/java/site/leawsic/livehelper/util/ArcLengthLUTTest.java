package site.leawsic.livehelper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcLengthLUTTest {

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] bez(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        return new double[] {
                MathUtil.cubicBezier(p0[0], p1[0], p2[0], p3[0], t),
                MathUtil.cubicBezier(p0[1], p1[1], p2[1], p3[1], t),
                MathUtil.cubicBezier(p0[2], p1[2], p2[2], p3[2], t)
        };
    }

    /** 按等弧长参数 s 取点：先查表换算成曲线参数 t，再求值。 */
    private static double[] pointAtS(ArcLengthLUT lut,
                                     double[] p0, double[] p1, double[] p2, double[] p3, double s) {
        return bez(p0, p1, p2, p3, lut.lookupT(s));
    }

    @Test
    void straightLineTotalLengthMatchesEuclideanDistance() {
        double[] p0 = {0, 0, 0};
        double[] p1 = {3, 0, 0};
        double[] p2 = {7, 0, 0};
        double[] p3 = {10, 0, 0};
        ArcLengthLUT lut = new ArcLengthLUT(p0, p1, p2, p3);
        assertEquals(10.0, lut.totalLength(), 1e-6);
    }

    /**
     * 核心性质：等分 s 必须等分弧长。曲线参数 t 本身非均匀，这正是需要 LUT 的原因。
     */
    @Test
    void equalArcStepsProduceEqualDistanceOnCurvedPath() {
        double[] p0 = {0, 0, 0};
        double[] p1 = {0, 10, 0};
        double[] p2 = {10, 10, 0};
        double[] p3 = {10, 0, 0};
        ArcLengthLUT lut = new ArcLengthLUT(p0, p1, p2, p3);

        double[] previous = pointAtS(lut, p0, p1, p2, p3, 0.0);
        double firstStep = Double.NaN;
        for (int i = 1; i <= 20; i++) {
            double[] current = pointAtS(lut, p0, p1, p2, p3, i / 20.0);
            double step = dist(previous, current);
            if (i == 1) {
                firstStep = step;
            } else {
                assertEquals(firstStep, step, firstStep * 0.05,
                        "arc step " + i + " deviates from the first step");
            }
            previous = current;
        }
    }

    /** 不做重参数化、直接用 t 采样时步长明显不均——证明 LUT 确有作用。 */
    @Test
    void rawParameterIsNonUniformButArcParameterIsUniform() {
        double[] p0 = {0, 0, 0};
        double[] p1 = {0, 10, 0};
        double[] p2 = {10, 10, 0};
        double[] p3 = {10, 0, 0};
        ArcLengthLUT lut = new ArcLengthLUT(p0, p1, p2, p3);

        double[] previous = bez(p0, p1, p2, p3, 0.0);
        double minRaw = Double.MAX_VALUE;
        double maxRaw = 0.0;
        double firstRaw = Double.NaN;
        for (int i = 1; i <= 20; i++) {
            double[] current = bez(p0, p1, p2, p3, i / 20.0);
            double step = dist(previous, current);
            if (i == 1) firstRaw = step;
            minRaw = Math.min(minRaw, step);
            maxRaw = Math.max(maxRaw, step);
            previous = current;
        }
        assertTrue(maxRaw / minRaw > 1.5,
                "raw Bezier parameter is expected to be non-uniform, ratio=" + (maxRaw / minRaw));
        assertTrue(lut.size() > 4, "LUT should subdivide a curved segment");
    }

    @Test
    void lookupTIsMonotonicAndClamped() {
        double[] p0 = {0, 0, 0};
        double[] p1 = {5, 12, 3};
        double[] p2 = {9, -4, 7};
        double[] p3 = {14, 6, 2};
        ArcLengthLUT lut = new ArcLengthLUT(p0, p1, p2, p3);

        double previous = -1.0;
        for (int i = 0; i <= 100; i++) {
            double t = lut.lookupT(i / 100.0);
            assertTrue(t >= previous - 1e-9, "lookupT must be monotonic at i=" + i);
            previous = t;
        }
        assertEquals(0.0, lut.lookupT(-1.0), 1e-9);
        assertEquals(1.0, lut.lookupT(2.0), 1e-9);
        assertEquals(0.0, lut.lookupT(Double.NaN), 1e-9);
    }

    @Test
    void tangentAtEndpointsFollowsCurveDirection() {
        // p1 在 p0 正上方，故 t=0 处切线沿 +y；p3 在 p2 正下方，故 t=1 处沿 -y。
        double[] p0 = {0, 0, 0};
        double[] p1 = {0, 5, 0};
        double[] p2 = {10, 5, 0};
        double[] p3 = {10, 0, 0};
        ArcLengthLUT lut = new ArcLengthLUT(p0, p1, p2, p3);

        double[] startTangent = lut.tangentAt(0.0);
        assertEquals(0.0, startTangent[0], 1e-9);
        assertTrue(startTangent[1] > 0, "tangent at t=0 should head toward +y, got " + startTangent[1]);

        double[] endTangent = lut.tangentAt(1.0);
        assertEquals(0.0, endTangent[0], 1e-9);
        assertTrue(endTangent[1] < 0, "tangent at t=1 should head toward -y, got " + endTangent[1]);
    }

    @Test
    void tValuesAreStrictlyIncreasing() {
        double[] p0 = {0, 0, 0};
        double[] p1 = {1, 9, 2};
        double[] p2 = {8, 9, -3};
        double[] p3 = {9, 1, 4};
        ArcLengthLUT lut = new ArcLengthLUT(p0, p1, p2, p3);
        double[] values = lut.tValues();
        assertTrue(values.length >= 2);
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i] > values[i - 1], "tValues must strictly increase at " + i);
        }
    }
}
