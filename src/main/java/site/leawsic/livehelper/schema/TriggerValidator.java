package site.leawsic.livehelper.schema;

import site.leawsic.livehelper.trigger.TriggerMatchers;
import site.leawsic.livehelper.trigger.TriggerRule;
import site.leawsic.livehelper.trigger.TriggerTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触发规则写入前校验。
 *
 * <p>与 {@link ClipValidator} 同思路：与其让配错的规则静默不生效，不如在保存时明确报错。
 * 重点是拦住「保存成功但永远不会触发」的配置——这类问题在直播现场最难排查。
 */
public final class TriggerValidator {

    private TriggerValidator() {}

    public record Result(List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }

        public String message() {
            return String.join("; ", errors);
        }
    }

    public static Result validate(TriggerRule rule, Set<Integer> knownManagerIds) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (rule == null) {
            errors.add("rule is null");
            return new Result(errors, warnings);
        }

        if (rule.type() == null || !TriggerTypes.isKnown(rule.type())) {
            errors.add("unknown trigger type '" + rule.type() + "'; available: "
                + String.join(", ", TriggerTypes.ALL));
            return new Result(errors, warnings);
        }

        if (rule.targetManager() <= 0) {
            errors.add("targetManager must be a positive manager id, got " + rule.targetManager());
        } else if (knownManagerIds != null && !knownManagerIds.contains(rule.targetManager())) {
            errors.add("targetManager #" + rule.targetManager() + " does not exist");
        }

        if (rule.name() == null || rule.name().isBlank()) {
            warnings.add("rule has no name; it will be listed as 'rule #" + rule.id() + "'");
        }

        validateConditions(rule, errors, warnings);
        return new Result(errors, warnings);
    }

    private static void validateConditions(TriggerRule rule, List<String> errors, List<String> warnings) {
        Map<String, Object> conditions = rule.conditions();
        List<FieldDef> fields = TriggerSchemas.forType(rule.type());
        Set<String> known = new HashSet<>();
        for (FieldDef field : fields) {
            known.add(field.key());
        }

        if (conditions == null) return;

        switch (rule.type()) {
            case TriggerTypes.LOCATION -> validateLocation(conditions, errors, warnings);
            case TriggerTypes.XP -> {
                boolean hasLevel = conditions.containsKey("level");
                boolean hasTotal = conditions.containsKey("total");
                if (!hasLevel && !hasTotal) {
                    errors.add("xp trigger needs at least one of 'level' or 'total'");
                }
            }
            case TriggerTypes.OBSERVATION -> {
                Object type = conditions.get("target_type");
                if (type != null && !String.valueOf(type).isEmpty()
                    && !List.of("entity", "block").contains(String.valueOf(type))) {
                    errors.add("target_type must be entity, block or empty, got '" + type + "'");
                }
            }
            default -> { }
        }

        for (FieldDef field : fields) {
            Object value = conditions.get(field.key());
            if (value == null) continue;
            if (FieldDef.TYPE_ENUM.equals(field.type())
                && !field.enumValues().contains(String.valueOf(value))) {
                errors.add("condition '" + field.key() + "' must be one of "
                    + String.join(", ", field.enumValues()) + ", got '" + value + "'");
            }
            if (FieldDef.TYPE_NUMBER.equals(field.type())) {
                double d = toDouble(value, Double.NaN);
                if (!Double.isFinite(d)) {
                    errors.add("condition '" + field.key() + "' must be a finite number, got " + value);
                } else if (field.min() != null && d < field.min()) {
                    errors.add("condition '" + field.key() + "' must be >= " + field.min() + ", got " + d);
                } else if (field.max() != null && d > field.max()) {
                    errors.add("condition '" + field.key() + "' must be <= " + field.max() + ", got " + d);
                }
            }
            if ((FieldDef.TYPE_KEYFRAMES.equals(field.type()))) {
                warnings.add("condition '" + field.key() + "' is a structured field stored as text");
            }
        }

        for (String key : conditions.keySet()) {
            if (!known.contains(key)) {
                warnings.add("condition '" + key + "' is not used by trigger type '" + rule.type()
                    + "' and will be ignored");
            }
        }
    }

    private static void validateLocation(Map<String, Object> c, List<String> errors, List<String> warnings) {
        boolean hasCuboid = c.get("corner1") != null || c.get("corner2") != null;
        if (hasCuboid) {
            if (c.get("corner1") == null || c.get("corner2") == null) {
                errors.add("location trigger needs both corner1 and corner2, or neither");
                return;
            }
            double[] a = parsePoint(c.get("corner1"));
            double[] b = parsePoint(c.get("corner2"));
            if (a == null || b == null) {
                errors.add("corner1/corner2 must be 'x,y,z' or {\"x\":..,\"y\":..,\"z\":..}");
                return;
            }
            for (int i = 0; i < 3; i++) {
                if (Math.abs(a[i] - b[i]) < 1e-6) {
                    warnings.add("corner1 and corner2 share the same coordinate on one axis; the region is flat");
                }
            }
            return;
        }

        Object center = c.get("position");
        if (center == null) {
            errors.add("location trigger needs 'position' (or corner1+corner2)");
            return;
        }
        if (parsePoint(center) == null) {
            errors.add("position must be 'x,y,z' or {\"x\":..,\"y\":..,\"z\":..}");
            return;
        }
        double radius = toDouble(c.get("radius"), 0.0);
        if (!(radius > 0.0)) {
            errors.add("location trigger needs a positive 'radius', got " + c.get("radius"));
        }
    }

    /**
     * 解析区域坐标。直接复用 {@link TriggerMatchers#point}，避免校验器与匹配器各写一份解析逻辑
     * 而产生漂移——那种漂移的后果正是「校验通过但规则永远不匹配」，属于最难排查的一类问题。
     */
    static double[] parsePoint(Object raw) {
        return TriggerMatchers.point(raw);
    }

    private static double toDouble(Object value, double def) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : def;
        }
        if (value instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : def;
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }
}
