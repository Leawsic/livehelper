package site.leawsic.livehelper.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 三次贝塞尔弧长查找表：把曲线参数 t 映射到等弧长参数 s，使 s 匀速变化时相机沿曲线匀速移动。
 *
 * <p>构建方式为 de Casteljau 自适应细分——用中间层点的偏离量判断平坦度，弯处密采、直线段疏采，
 * 最多细分 {@value #DEFAULT_MAX_DEPTH} 层。
 *
 * <p>不依赖 Minecraft 类，可直接单元测试。
 */
public final class ArcLengthLUT {

    /** 平坦度容差，单位为世界坐标。 */
    private static final double DEFAULT_TOLERANCE = 1e-3;
    private static final int DEFAULT_MAX_DEPTH = 8;

    private final double[] p0;
    private final double[] p1;
    private final double[] p2;
    private final double[] p3;

    /** 升序曲线参数。 */
    private final double[] tValues;
    /** 与 tValues 一一对应的归一化累积弧长，范围 [0,1]。 */
    private final double[] arcLengths;
    private final double totalLength;

    public ArcLengthLUT(double[] p0, double[] p1, double[] p2, double[] p3) {
        this(p0, p1, p2, p3, DEFAULT_TOLERANCE, DEFAULT_MAX_DEPTH);
    }

    public ArcLengthLUT(double[] p0, double[] p1, double[] p2, double[] p3,
                        double tolerance, int maxDepth) {
        this.p0 = p0.clone();
        this.p1 = p1.clone();
        this.p2 = p2.clone();
        this.p3 = p3.clone();

        List<Double> rawT = new ArrayList<>();
        rawT.add(0.0);
        subdivide(rawT, p0, p1, p2, p3, tolerance, maxDepth, 0.0, 1.0);
        rawT.add(1.0);

        double[] sorted = rawT.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] unique = dedupe(sorted);

        double[] cumulative = new double[unique.length];
        double total = 0.0;
        double[] previous = evaluate(unique[0]);
        for (int i = 1; i < unique.length; i++) {
            double[] current = evaluate(unique[i]);
            total += distance(previous, current);
            cumulative[i] = total;
            previous = current;
        }
        this.totalLength = total;

        this.tValues = unique;
        this.arcLengths = new double[unique.length];
        for (int i = 0; i < unique.length; i++) {
            this.arcLengths[i] = total > 0.0 ? cumulative[i] / total : (double) i / (unique.length - 1);
        }
    }

    /**
     * 按等弧长参数 s ∈ [0,1] 反查曲线参数 t。
     *
     * <p>二分定位后线性插值精化。非有限或越界的 s 会被钳制，保证调用方无需自行防御。
     */
    public double lookupT(double s) {
        if (tValues.length == 0 || !(s > 0.0)) return 0.0;
        if (s >= 1.0) return tValues[tValues.length - 1];

        int lo = 0;
        int hi = arcLengths.length - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (arcLengths[mid] < s) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        double sLo = arcLengths[lo];
        double sHi = arcLengths[hi];
        double range = sHi - sLo;
        if (!(range > 0.0)) return tValues[hi];

        double frac = (s - sLo) / range;
        return tValues[lo] + (tValues[hi] - tValues[lo]) * frac;
    }

    /** 该段曲线的总弧长（世界坐标单位）。 */
    public double totalLength() {
        return totalLength;
    }

    public int size() {
        return tValues.length;
    }

    private double[] evaluate(double t) {
        return new double[] {
                MathUtil.cubicBezier(p0[0], p1[0], p2[0], p3[0], t),
                MathUtil.cubicBezier(p0[1], p1[1], p2[1], p3[1], t),
                MathUtil.cubicBezier(p0[2], p1[2], p2[2], p3[2], t)
        };
    }

    /** 曲线在参数 t 处的一阶导数（切线向量）。 */
    public double[] tangentAt(double t) {
        return new double[] {
                MathUtil.cubicBezierDerivative(p0[0], p1[0], p2[0], p3[0], t),
                MathUtil.cubicBezierDerivative(p0[1], p1[1], p2[1], p3[1], t),
                MathUtil.cubicBezierDerivative(p0[2], p1[2], p2[2], p3[2], t)
        };
    }

    private static double[] dedupe(double[] sorted) {
        List<Double> unique = new ArrayList<>();
        for (double t : sorted) {
            if (unique.isEmpty() || Math.abs(t - unique.get(unique.size() - 1)) > 1e-7) {
                unique.add(t);
            }
        }
        return unique.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** de Casteljau 自适应细分：中间层线段偏离量小于容差即停止细分。 */
    private static void subdivide(List<Double> outT,
                                  double[] cp0, double[] cp1, double[] cp2, double[] cp3,
                                  double tolerance, int maxDepth,
                                  double tStart, double tEnd) {
        double[] q0 = midpoint(cp0, cp1);
        double[] q1 = midpoint(cp1, cp2);
        double[] q2 = midpoint(cp2, cp3);
        double[] r0 = midpoint(q0, q1);
        double[] r1 = midpoint(q1, q2);
        double[] mid = midpoint(r0, r1);

        double tMid = (tStart + tEnd) * 0.5;
        double flatness = Math.max(distance(q0, q2), distance(r0, r1));

        if (flatness < tolerance || maxDepth <= 0) {
            outT.add(tMid);
            return;
        }
        subdivide(outT, cp0, q0, r0, mid, tolerance, maxDepth - 1, tStart, tMid);
        subdivide(outT, mid, r1, q2, cp3, tolerance, maxDepth - 1, tMid, tEnd);
    }

    private static double[] midpoint(double[] a, double[] b) {
        return new double[] {
                (a[0] + b[0]) * 0.5,
                (a[1] + b[1]) * 0.5,
                (a[2] + b[2]) * 0.5
        };
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String toString() {
        return "ArcLengthLUT[size=" + tValues.length + ", length=" + totalLength + "]";
    }

    /** 便于测试与调试：返回内部参数副本。 */
    public double[] tValues() {
        return Arrays.copyOf(tValues, tValues.length);
    }
}
