package site.leawsic.livehelper.engine.templates;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MotionTemplates {
    private static final Map<String, MotionTemplate> REGISTRY = new LinkedHashMap<>();

    static {
        register("STATIC", new StaticTemplate());
        register("ORBIT", new OrbitTemplate());
        register("DOLLY", new DollyTemplate());
        register("TRUCK", new TruckTemplate());
        register("PEDESTAL", new PedestalTemplate());
        register("PAN_TILT", new PanTiltTemplate());
        register("PATH", new PathTemplate());
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

    public static float ease(float t, String type) {
        if (type == null) type = "linear";
        return switch (type) {
            case "easeIn" -> t * t;
            case "easeOut" -> t * (2 - t);
            case "easeInOut" -> t < 0.5f ? 2f * t * t : -1f + (4f - 2f * t) * t;
            default -> t;
        };
    }

    public static double p(Map<String, Double> params, String key, double def) {
        return params == null ? def : params.getOrDefault(key, def);
    }

    public static float pf(Map<String, Double> params, String key, float def) {
        return params == null ? def : params.getOrDefault(key, (double) def).floatValue();
    }
}
