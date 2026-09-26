package site.leawsic.livehelper.engine.templates;

import site.leawsic.livehelper.util.MathUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MotionTemplates {
    private static final Map<String, MotionTemplate> REGISTRY = new LinkedHashMap<>();

    static {
        register("STATIC", new StaticTemplate());
        register("STATIC_TRACK", new StaticTrackTemplate());
        register("ORBIT", new OrbitTemplate());
        register("DOLLY", new DollyTemplate());
        register("TRUCK", new TruckTemplate());
        register("PEDESTAL", new PedestalTemplate());
        register("PAN_TILT", new PanTiltTemplate());
        register("PATH", new PathTemplate());
        register("SPLINE", new SplineTemplate());
    }

    private MotionTemplates() {}

    public static void register(String name, MotionTemplate template) {
        REGISTRY.put(name, template);
    }

    public static MotionTemplate get(String name) {
        MotionTemplate template = REGISTRY.get(name);
        if (template == null) throw new IllegalArgumentException("Unknown template: " + name);
        return template;
    }

    public static Set<String> getAvailable() {
        return REGISTRY.keySet();
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 缓动曲线转发到 {@link MathUtil#ease}，保持本类静态导入的调用点不变。
     */
    public static float ease(float t, String type) {
        return MathUtil.ease(t, type);
    }

    public static double p(Map<String, Object> params, String key, double def) {
        if (params == null) return def;
        Object value = params.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static float pf(Map<String, Object> params, String key, float def) {
        return (float) p(params, key, def);
    }

    public static String ps(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object value = params.get(key);
        return value == null ? def : String.valueOf(value);
    }
}
