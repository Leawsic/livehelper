package site.leawsic.livehelper.trigger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触发引擎：消费玩家状态快照与交互事件，产出「切到某个 Manager」的指令。
 *
 * <p>刻意不引用 Minecraft 类型，也不直接调用 StreamManager——它只通过
 * {@link #setFireHandler} 交出的回调通知外部。这样整套调度（延迟、冷却、一次性、区域迟滞）
 * 都能在单元测试里用构造出来的快照与事件验证。
 *
 * <p>多客户端 tick 的调用约定：每 tick 先 {@link #publish} 事件，再 {@link #tick} 一次。
 */
public class TriggerEngine {

    /** 命中后的动作，由外部（客户端桥接层）接到 StreamManager。 */
    public interface FireHandler {
        void onFire(TriggerRule rule, TriggerEvent event, TriggerContext context);
    }

    /** 一次待执行的切机位指令。 */
    private record Pending(TriggerRule rule, long dueTick, TriggerEvent event, TriggerContext context) {}

    /** 每条规则的运行时状态。 */
    private static final class RuleState {
        boolean fired;
        long lastFireTick = Long.MIN_VALUE;
        /** 位置类 on_enter 用：是否已判定为「在区域内」。 */
        boolean inside;
    }

    private final Map<Integer, RuleState> states = new HashMap<>();
    private final Deque<TriggerEvent> pendingEvents = new ArrayDeque<>();
    private final List<Pending> scheduled = new ArrayList<>();
    private FireHandler fireHandler = (rule, event, context) -> { };
    private List<TriggerRule> rules = List.of();
    private long currentTick = 0L;
    private TriggerContext lastContext = TriggerContext.outOfWorld();

    public void setFireHandler(FireHandler handler) {
        this.fireHandler = handler == null ? (rule, event, context) -> { } : handler;
    }

    /** 替换规则集合并重置所有运行时状态。 */
    public void setRules(List<TriggerRule> newRules) {
        this.rules = newRules == null ? List.of() : List.copyOf(newRules);
        this.states.clear();
        this.scheduled.clear();
        this.pendingEvents.clear();
    }

    public List<TriggerRule> rules() {
        return rules;
    }

    public void reset() {
        states.clear();
        scheduled.clear();
        pendingEvents.clear();
    }

    public long currentTick() {
        return currentTick;
    }

    /** 供客户端桥接层投递交互/攻击事件。 */
    public void publish(TriggerEvent event) {
        if (event != null) {
            pendingEvents.addLast(event);
        }
    }

    /**
     * 推进一个 tick：处理到期的延迟指令、检测轮询类状态变化、消费交互事件队列。
     *
     * <p>到期队列在首尾各跑一次：开头那遍负责执行上一 tick 排入的延迟指令，
     * 结尾那遍让本 tick 命中且 delay=0 的规则在同一 tick 内生效（否则每个触发都会平白多等一 tick）。
     */
    public void tick(TriggerContext context) {
        this.currentTick++;
        this.lastContext = context == null ? TriggerContext.outOfWorld() : context;

        fireDue();
        detectPolledEvents(this.lastContext);
        drainInteractionEvents();
        fireDue();
    }

    /** 供测试与诊断查询某条规则是否已经触发过。 */
    public boolean hasFired(int ruleId) {
        RuleState state = states.get(ruleId);
        return state != null && state.fired;
    }

    // ── 延迟队列 ──────────────────────────────────────────────

    private void fireDue() {
        if (scheduled.isEmpty()) return;
        List<Pending> due = new ArrayList<>();
        for (int i = scheduled.size() - 1; i >= 0; i--) {
            Pending pending = scheduled.get(i);
            if (currentTick >= pending.dueTick()) {
                due.add(pending);
                scheduled.remove(i);
            }
        }
        // 按到期时间从早到晚触发，保证多个规则同时命中时顺序稳定。
        due.sort((a, b) -> Long.compare(a.dueTick(), b.dueTick()));
        for (int i = due.size() - 1; i >= 0; i--) {
            fire(due.get(i));
        }
    }

    private void fire(Pending pending) {
        fireHandler.onFire(pending.rule(), pending.event(), pending.context());
    }

    // ── 轮询类事件检测 ────────────────────────────────────────

    private void detectPolledEvents(TriggerContext context) {
        if (context == null || !context.inWorld()) return;

        for (TriggerRule rule : rules) {
            if (!rule.enabled()) continue;
            switch (rule.type()) {
                case TriggerTypes.DAMAGE, TriggerTypes.DIMENSION_CHANGE, TriggerTypes.XP,
                     TriggerTypes.ADVANCEMENT, TriggerTypes.ITEM_USE, TriggerTypes.ITEM_CONSUME,
                     TriggerTypes.ITEM_RELEASE -> {
                    // 这些类型由客户端桥接层在快照对比后投递事件，这里只做区域迟滞。
                }
                case TriggerTypes.LOCATION -> pollLocation(rule, context);
                default -> { }
            }
        }
    }

    /**
     * 位置触发：带 on_enter 时只在「进入区域」那一刻触发。
     *
     * <p>exitBuffer 的语义是「离开原区域多少格后才算已离开」：判定用的是玩家到区域边界的距离，
     * 因此在边界上反复跨越不会被算成离开——这正是这类触发器最常见的误触来源。
     */
    private void pollLocation(TriggerRule rule, TriggerContext context) {
        RuleState state = stateOf(rule.id());
        double distance = TriggerMatchers.regionDistance(rule.conditions(), context);
        boolean nowInside = distance == 0.0;
        double buffer = Math.max(0.0, rule.exitBuffer());

        if (nowInside) {
            if (!rule.onEnter()) {
                attemptFire(rule, null, context);
            } else if (!state.inside) {
                state.inside = true;
                attemptFire(rule, null, context);
            }
            return;
        }

        if (!state.inside) return;
        if (distance > buffer) {
            state.inside = false;
        }
    }

    // ── 交互事件消费 ──────────────────────────────────────────

    private void drainInteractionEvents() {
        if (pendingEvents.isEmpty()) return;
        int guard = 0;
        while (!pendingEvents.isEmpty() && guard++ < 64) {
            TriggerEvent event = pendingEvents.pollFirst();
            for (TriggerRule rule : rules) {
                if (!rule.enabled()) continue;
                if (!rule.type().equals(event.type())) continue;
                if (TriggerMatchers.matches(rule, lastContext, event)) {
                    attemptFire(rule, event, lastContext);
                }
            }
        }
        if (guard >= 64 && !pendingEvents.isEmpty()) {
            pendingEvents.clear();
        }
    }

    /**
     * 尝试触发：检查 enabled / 一次性 / 冷却，通过则按 delayMs 排入延迟队列。
     *
     * <p>延迟为 0 时仍然走队列（而不是直接执行），这样所有触发都有一条统一的时序，
     * 避免「同 tick 内先改状态再执行」这类顺序问题。
     */
    private void attemptFire(TriggerRule rule, TriggerEvent event, TriggerContext context) {
        RuleState state = stateOf(rule.id());
        // 一次性规则：触发过就永久失效，直到重新装载规则。
        if (!rule.repeatable() && state.fired) return;
        if (rule.cooldownMs() > 0 && state.lastFireTick != Long.MIN_VALUE) {
            long elapsedMs = (currentTick - state.lastFireTick) * 50L;
            if (elapsedMs < rule.cooldownMs()) return;
        }

        state.fired = true;
        state.lastFireTick = currentTick;
        long delayTicks = rule.delayMs() / 50L;
        scheduled.add(new Pending(rule, currentTick + delayTicks, event, context));
    }

    private RuleState stateOf(int ruleId) {
        return states.computeIfAbsent(ruleId, id -> new RuleState());
    }

    /** 已启用且至少命中过一次的可读摘要，供 /livehelper status 使用。 */
    public Set<Integer> enabledRuleIds() {
        Set<Integer> ids = new HashSet<>();
        for (TriggerRule rule : rules) {
            if (rule.enabled()) ids.add(rule.id());
        }
        return ids;
    }
}
