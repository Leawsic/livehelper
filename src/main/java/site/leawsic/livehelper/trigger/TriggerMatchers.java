package site.leawsic.livehelper.trigger;

import java.util.List;
import java.util.Map;

/**
 * 各触发类型的条件匹配。
 *
 * <p>全部为纯函数：给定快照 / 事件与条件，返回是否命中。这样匹配规则可以脱离游戏单测，
 * 改动匹配逻辑也不会影响运行时状态。
 *
 * <p>标识匹配统一支持三种写法：精确 id（{@code minecraft:zombie}）、
 * 简写（{@code zombie}，等价于补全为当前命名空间下的 minecraft:zombie）、以及
 * {@code *} 通配。空条件视为「不限制」。
 */
public final class TriggerMatchers {

    private TriggerMatchers() {}

    public static boolean matches(TriggerRule rule, TriggerContext context, TriggerEvent event) {
        if (!TriggerTypes.isKnown(rule.type())) return false;
        return switch (rule.type()) {
            case TriggerTypes.ENTITY_KILL -> matchesEntity(rule.conditions(), event);
            case TriggerTypes.DAMAGE -> matchesDamage(rule.conditions(), event);
            case TriggerTypes.ENTITY_ATTACK -> matchesEntity(rule.conditions(), event);
            case TriggerTypes.ENTITY_INTERACT -> matchesEntityInteract(rule.conditions(), event);
            case TriggerTypes.BLOCK_INTERACT -> matchesBlockInteract(rule.conditions(), event);
            case TriggerTypes.ITEM_ON_INTERACT -> matchesItemOnInteract(rule.conditions(), event);
            case TriggerTypes.ITEM_USE, TriggerTypes.ITEM_CONSUME, TriggerTypes.ITEM_RELEASE ->
                matchesItem(rule.conditions(), event);
            case TriggerTypes.DIMENSION_CHANGE -> matchesDimension(rule.conditions(), event, context);
            case TriggerTypes.LOCATION -> matchesLocation(rule.conditions(), context);
            case TriggerTypes.ADVANCEMENT -> matchesAdvancement(rule.conditions(), event, context);
            case TriggerTypes.XP -> matchesXp(rule.conditions(), context);
            case TriggerTypes.OBSERVATION -> matchesObservation(rule.conditions(), context);
            default -> false;
        };
    }

    // ── 实体类 ────────────────────────────────────────────────

    private static boolean matchesEntity(Map<String, Object> c, TriggerEvent e) {
        if (e == null) return false;
        String target = str(c, "target", "");
        if (target.isEmpty()) return true;
        return identifierMatches(target, e.subject());
    }

    private static boolean matchesEntityInteract(Map<String, Object> c, TriggerEvent e) {
        if (e == null) return false;
        String target = str(c, "target", "");
        if (!target.isEmpty() && !identifierMatches(target, e.subject())) return false;
        String item = str(c, "item", "");
        return item.isEmpty() || identifierMatches(item, e.item());
    }

    private static boolean matchesBlockInteract(Map<String, Object> c, TriggerEvent e) {
        if (e == null) return false;
        String target = str(c, "target", "");
        if (!target.isEmpty() && !identifierMatches(target, e.subject())) return false;
        String item = str(c, "item", "");
        return item.isEmpty() || identifierMatches(item, e.item());
    }

    private static boolean matchesItemOnInteract(Map<String, Object> c, TriggerEvent e) {
        if (e == null) return false;
        String item = str(c, "item", "");
        if (item.isEmpty()) return true;
        if (!identifierMatches(item, e.item())) return false;
        String target = str(c, "target", "");
        if (target.isEmpty()) return true;
        // target_type 决定拿 subject 还是 target 去比；未填则两边都试。
        String type = str(c, "target_type", "");
        if ("entity".equals(type)) return identifierMatches(target, e.subject());
        if ("block".equals(type)) return identifierMatches(target, e.target());
        return identifierMatches(target, e.subject()) || identifierMatches(target, e.target());
    }

    private static boolean matchesItem(Map<String, Object> c, TriggerEvent e) {
        if (e == null) return false;
        String item = str(c, "item", "");
        return item.isEmpty() || identifierMatches(item, e.subject());
    }

    // ── 状态类（轮询） ────────────────────────────────────────

    private static boolean matchesDamage(Map<String, Object> c, TriggerEvent e) {
        if (e == null) return false;
        double min = num(c, "min_damage", 0.0);
        if (min <= 0.0) return true;
        // 事件携带单次伤害量，只校验下限。
        return e.value() >= min;
    }

    private static boolean matchesDimension(Map<String, Object> c, TriggerEvent e, TriggerContext ctx) {
        String want = str(c, "dimension", "");
        if (want.isEmpty()) return true;
        String current = e != null ? e.subject() : (ctx == null ? "" : ctx.dimension());
        return identifierMatches(want, current);
    }

    private static boolean matchesLocation(Map<String, Object> c, TriggerContext ctx) {
        return regionDistance(c, ctx) == 0.0;
    }

    /**
     * 玩家到触发区域的最短距离；在区域内时为 0。
     *
     * <p>与「是否在区域内」分开表达，是因为 {@code exit_buffer} 的语义是
     * 「离开原区域多少格后才算已离开」——需要的是到区域边界的距离，而不是到某个历史位置的距离。
     * 早期实现用「离上次在区域内位置的距离」，结果玩家跨出边界半格就被判定离开，防抖完全失效。
     *
     * @return 距离（格）；区域未配置或参数非法时返回 {@link Double#POSITIVE_INFINITY}
     */
    public static double regionDistance(Map<String, Object> c, TriggerContext ctx) {
        if (ctx == null || !ctx.inWorld()) return Double.POSITIVE_INFINITY;
        String dim = str(c, "dimension", "");
        if (!dim.isEmpty() && !identifierMatches(dim, ctx.dimension())) return Double.POSITIVE_INFINITY;

        if (c.containsKey("corner1") && c.containsKey("corner2")) {
            double[] a = point(c.get("corner1"));
            double[] b = point(c.get("corner2"));
            if (a == null || b == null) return Double.POSITIVE_INFINITY;
            double dx = axisGap(ctx.x(), a[0], b[0]);
            double dy = axisGap(ctx.y(), a[1], b[1]);
            double dz = axisGap(ctx.z(), a[2], b[2]);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        double[] center = point(c.get("position"));
        if (center == null) return Double.POSITIVE_INFINITY;
        double radius = num(c, "radius", 0.0);
        if (radius <= 0.0) return Double.POSITIVE_INFINITY;
        double dx = ctx.x() - center[0];
        double dy = ctx.y() - center[1];
        double dz = ctx.z() - center[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(0.0, distance - radius);
    }

    /** 某一轴上点到 [lo,hi] 区间外边界的距离；在区间内为 0。 */
    private static double axisGap(double value, double a, double b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        if (value < lo) return lo - value;
        if (value > hi) return value - hi;
        return 0.0;
    }

    private static boolean matchesAdvancement(Map<String, Object> c, TriggerEvent e, TriggerContext ctx) {
        String want = str(c, "advancement", "");
        if (want.isEmpty()) return true;
        if (e != null) return identifierMatches(want, e.subject());
        return ctx != null && ctx.completedAdvancements().stream().anyMatch(a -> identifierMatches(want, a));
    }

    private static boolean matchesXp(Map<String, Object> c, TriggerContext ctx) {
        if (ctx == null || !ctx.inWorld()) return false;
        boolean hasLevel = c.containsKey("level");
        boolean hasTotal = c.containsKey("total");
        if (!hasLevel && !hasTotal) return true;
        if (hasLevel && ctx.experienceLevel() < num(c, "level", 0.0)) return false;
        if (hasTotal && ctx.totalExperience() < num(c, "total", 0.0)) return false;
        return true;
    }

    private static boolean matchesObservation(Map<String, Object> c, TriggerContext ctx) {
        if (ctx == null || !ctx.inWorld()) return false;
        if (ctx.lookedAtType().isEmpty()) return false;
        String want = str(c, "target", "");
        if (want.isEmpty()) return true;
        String type = str(c, "target_type", "");
        if (!type.isEmpty() && !type.equals(ctx.lookedAtType())) return false;
        return identifierMatches(want, ctx.lookedAtId());
    }

    // ── 工具 ──────────────────────────────────────────────────

    /**
     * 标识匹配：支持 {@code *} 通配、{@code minecraft:} 前缀省略、以及 {@code #tag} 前缀的
     * 宽松处理（tag 需要注册表查询，交给调用方预筛；此处按字面比较）。
     */
    public static boolean identifierMatches(String pattern, String actual) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) return true;
        if (actual == null || actual.isEmpty()) return false;
        if (pattern.equals(actual)) return true;
        if (pattern.startsWith("#")) return false;

        String normalized = pattern.contains(":") ? pattern : "minecraft:" + pattern;
        if (normalized.equals(actual)) return true;

        // 允许只写命名空间，如 "minecraft:*"
        if (pattern.endsWith(":*")) {
            String ns = pattern.substring(0, pattern.length() - 1);
            return actual.startsWith(ns);
        }
        return false;
    }

    private static boolean between(double value, double a, double b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        return value >= lo && value <= hi;
    }

    /** 解析 {x,y,z} 形式的位置参数；非法返回 null。 */
    public static double[] point(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Object x = map.get("x");
            Object y = map.get("y");
            Object z = map.get("z");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                double[] out = {nx.doubleValue(), ny.doubleValue(), nz.doubleValue()};
                return allFinite(out) ? out : null;
            }
        }
        if (raw instanceof List<?> list && list.size() >= 3
            && list.get(0) instanceof Number x && list.get(1) instanceof Number y && list.get(2) instanceof Number z) {
            double[] out = {((Number) x).doubleValue(), ((Number) y).doubleValue(), ((Number) z).doubleValue()};
            return allFinite(out) ? out : null;
        }
        return null;
    }

    private static boolean allFinite(double[] values) {
        for (double v : values) {
            if (!Double.isFinite(v)) return false;
        }
        return true;
    }

    public static String str(Map<String, Object> conditions, String key, String def) {
        Object value = conditions == null ? null : conditions.get(key);
        if (value == null) return def;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? def : s;
    }

    public static double num(Map<String, Object> conditions, String key, double def) {
        Object value = conditions == null ? null : conditions.get(key);
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? d : def;
        }
        if (value instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : def;
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
