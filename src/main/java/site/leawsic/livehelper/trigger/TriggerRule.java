package site.leawsic.livehelper.trigger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条触发规则：满足条件时把推流切到指定 Manager。
 *
 * <p>{@code delayMs} 与 {@code cooldownMs} 是直播场景比剧情过场更重要的两个参数：
 * 前者让镜头晚一点切过去（比如击杀后稍等才给 kill cam），后者避免同一次事件被反复触发。
 *
 * @param id           规则唯一 id
 * @param name         显示名
 * @param type         触发类型，取值见 {@link TriggerTypes}
 * @param conditions   类型对应的条件参数
 * @param targetManager 命中后启动的 Manager id
 * @param enabled      是否启用
 * @param repeatable   是否可重复触发；false 时命中一次即失效（直到 reload）
 * @param delayMs      命中后延迟多少毫秒再切机位
 * @param cooldownMs   两次命中之间的最小间隔（毫秒）
 * @param onEnter      位置类触发器：只在「进入」区域时触发
 * @param exitBuffer   位置类触发器：离开区域多少格后才算已离开（防边界抖动反复触发）
 */
public record TriggerRule(
    int id,
    String name,
    String type,
    Map<String, Object> conditions,
    int targetManager,
    boolean enabled,
    boolean repeatable,
    long delayMs,
    long cooldownMs,
    boolean onEnter,
    double exitBuffer
) {
    public static final long MAX_DELAY_MS = 60_000L;
    public static final long MAX_COOLDOWN_MS = 600_000L;

    public TriggerRule {
        name = name == null ? "" : name;
        conditions = conditions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(conditions);
        delayMs = Math.max(0L, Math.min(MAX_DELAY_MS, delayMs));
        cooldownMs = Math.max(0L, Math.min(MAX_COOLDOWN_MS, cooldownMs));
        exitBuffer = Math.max(0.0, exitBuffer);
    }

    public static TriggerRule of(int id, String name, String type, int targetManager) {
        return new TriggerRule(id, name, type, new LinkedHashMap<>(), targetManager,
            true, true, 0L, 0L, false, 0.0);
    }

    public TriggerRule withConditions(Map<String, Object> newConditions) {
        return new TriggerRule(id, name, type, newConditions, targetManager, enabled,
            repeatable, delayMs, cooldownMs, onEnter, exitBuffer);
    }

    public TriggerRule withEnabled(boolean value) {
        return new TriggerRule(id, name, type, conditions, targetManager, value,
            repeatable, delayMs, cooldownMs, onEnter, exitBuffer);
    }
}
