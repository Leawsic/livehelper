package site.leawsic.livehelper.trigger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次被捕获的交互/攻击事件。
 *
 * <p>刻意不引用任何 Minecraft 类型：捕获由客户端适配层完成，匹配与调度在纯 Java 侧进行，
 * 这样触发规则的核心逻辑可以直接单元测试，不必启动游戏。
 *
 * @param type    事件类型，取值见 {@link TriggerTypes}
 * @param subject 主体标识，如 {@code minecraft:zombie}；无主体时为空串
 * @param target  交互目标标识；无目标时为空串
 * @param item    当时手持物品标识；空手时为空串
 * @param x,y,z   事件发生位置
 * @param tick    事件被捕获时的客户端 tick
 * @param value   数值载荷：受伤量、经验量等；无载荷时为 0
 */
public record TriggerEvent(
    String type,
    String subject,
    String target,
    String item,
    double x,
    double y,
    double z,
    long tick,
    double value
) {
    public static TriggerEvent of(String type, String subject, String target, String item,
                                 double x, double y, double z, long tick) {
        return new TriggerEvent(type, nz(subject), nz(target), nz(item), x, y, z, tick, 0.0);
    }

    public static TriggerEvent of(String type, String subject, String target, String item,
                                 double x, double y, double z, long tick, double value) {
        return new TriggerEvent(type, nz(subject), nz(target), nz(item), x, y, z, tick, value);
    }

    public static TriggerEvent simple(String type, String subject, long tick) {
        return new TriggerEvent(type, nz(subject), "", "", 0, 0, 0, tick, 0.0);
    }

    public static TriggerEvent valued(String type, double value, long tick) {
        return new TriggerEvent(type, "", "", "", 0, 0, 0, tick, value);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** 供日志与调试展示。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(type);
        if (!subject.isEmpty()) sb.append(" subject=").append(subject);
        if (!target.isEmpty()) sb.append(" target=").append(target);
        if (!item.isEmpty()) sb.append(" item=").append(item);
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("subject", subject);
        map.put("target", target);
        map.put("item", item);
        return map;
    }
}
