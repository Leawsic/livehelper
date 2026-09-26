package site.leawsic.livehelper.trigger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerEngineTest {

    private static final int TICKS_PER_SECOND = 20;

    private static TriggerContext ctx(double x, double y, double z, String dim) {
        return new TriggerContext(x, y, z, dim, 20f, 20f, 0, 0,
            false, 0, Set.of(), "", "", true);
    }

    private static Map<String, Object> conditions(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> point(double x, double y, double z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    private static TriggerEngine engineWith(TriggerEngine.FireHandler handler, TriggerRule... rules) {
        TriggerEngine engine = new TriggerEngine();
        engine.setFireHandler(handler);
        engine.setRules(List.of(rules));
        return engine;
    }

    // ── 延迟 ──────────────────────────────────────────────────

    @Test
    void zeroDelayFiresOnTheSameTickButAfterTickReturns() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = TriggerRule.of(1, "kill", TriggerTypes.ENTITY_ATTACK, 7);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 1L));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(List.of("kill"), fired);
    }

    @Test
    void delayPushesFireIntoLaterTicks() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "kill", TriggerTypes.ENTITY_ATTACK,
            new LinkedHashMap<>(), 7, true, true, 500L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 1L));
        // 500ms = 10 tick：事件发生在 tick 1，因此应在 tick 11 触发。
        for (int i = 0; i < 10; i++) {
            engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
            assertTrue(fired.isEmpty(), "must not fire early, fired at tick " + (i + 1));
        }
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(List.of("kill"), fired);
    }

    @Test
    void delayIsCoarseToTickGranularity() {
        List<String> fired = new ArrayList<>();
        // 30ms 不足一个 tick(50ms)，应落在下一个 tick 触发而不是永不触发。
        TriggerRule rule = new TriggerRule(1, "kill", TriggerTypes.ENTITY_ATTACK,
            new LinkedHashMap<>(), 7, true, true, 30L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 1L));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(List.of("kill"), fired);
    }

    // ── 冷却与一次性 ──────────────────────────────────────────

    @Test
    void cooldownBlocksRapidRepeatFires() {
        List<String> fired = new ArrayList<>();
        // 1000ms 冷却 = 20 tick
        TriggerRule rule = new TriggerRule(1, "hit", TriggerTypes.ENTITY_ATTACK,
            new LinkedHashMap<>(), 7, true, true, 0L, 1000L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        for (int i = 0; i < 5; i++) {
            engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", i));
            engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        }
        assertEquals(1, fired.size(), "cooldown should swallow the rapid repeats");

        // 冷却过去后再次命中应重新触发。
        for (int i = 0; i < 20; i++) {
            engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        }
        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 100L));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(2, fired.size());
    }

    @Test
    void nonRepeatableRuleFiresOnlyOnce() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "once", TriggerTypes.ENTITY_ATTACK,
            new LinkedHashMap<>(), 7, true, false, 0L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        for (int i = 0; i < 5; i++) {
            engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", i));
            engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        }
        assertEquals(1, fired.size());
        assertTrue(engine.hasFired(1));
    }

    @Test
    void disabledRuleNeverFires() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = TriggerRule.of(1, "off", TriggerTypes.ENTITY_ATTACK, 7).withEnabled(false);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 1L));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertTrue(fired.isEmpty());
    }

    // ── 位置与迟滞 ────────────────────────────────────────────

    @Test
    void onEnterFiresOnceWhileStayingInside() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "arena", TriggerTypes.LOCATION,
            conditions("position", point(0, 64, 0), "radius", 5.0),
            3, true, true, 0L, 0L, true, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        for (int i = 0; i < 10; i++) {
            engine.tick(ctx(1, 64, 1, "minecraft:overworld"));
        }
        assertEquals(1, fired.size(), "on_enter must fire only on entry, not every tick inside");
    }

    @Test
    void onEnterRefiresAfterLeavingAndReturning() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "arena", TriggerTypes.LOCATION,
            conditions("position", point(0, 64, 0), "radius", 5.0),
            3, true, true, 0L, 0L, true, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        engine.tick(ctx(50, 64, 50, "minecraft:overworld"));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(2, fired.size());
    }

    /** 边界抖动是这类触发器最常见的误触来源，exit_buffer 必须真的兜住。 */
    @Test
    void exitBufferAbsorbsBoundaryFlicker() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "arena", TriggerTypes.LOCATION,
            conditions("position", point(0, 64, 0), "radius", 5.0),
            3, true, true, 0L, 0L, true, 3.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        // 在半径边缘反复进出，但始终没走出 3 格缓冲。
        for (int i = 0; i < 20; i++) {
            engine.tick(ctx(i % 2 == 0 ? 5.5 : 4.0, 64, 0, "minecraft:overworld"));
        }
        assertEquals(1, fired.size(), "exit_buffer should prevent re-triggering on boundary flicker");

        // 真正走远之后应复位。
        engine.tick(ctx(80, 64, 0, "minecraft:overworld"));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(2, fired.size());
    }

    @Test
    void cuboidRegionIsSupported() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "room", TriggerTypes.LOCATION,
            conditions("corner1", point(0, 60, 0), "corner2", point(10, 70, 10)),
            3, true, true, 0L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.tick(ctx(5, 65, 5, "minecraft:overworld"));
        assertEquals(1, fired.size());
    }

    @Test
    void locationRespectsDimensionFilter() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "netherArena", TriggerTypes.LOCATION,
            conditions("dimension", "minecraft:the_nether", "position", point(0, 64, 0), "radius", 5.0),
            3, true, true, 0L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertTrue(fired.isEmpty());
        engine.tick(ctx(0, 64, 0, "minecraft:the_nether"));
        assertEquals(1, fired.size());
    }

    // ── 条件匹配 ──────────────────────────────────────────────

    @Test
    void damageRespectsMinimumAmount() {
        TriggerEngine engine = new TriggerEngine();
        assertTrue(TriggerMatchers.matches(
            TriggerRule.of(1, "d", TriggerTypes.DAMAGE, 1).withConditions(conditions("min_damage", 5.0)),
            null, TriggerEvent.valued(TriggerTypes.DAMAGE, 8.0, 1L)));
        assertFalse(TriggerMatchers.matches(
            TriggerRule.of(1, "d", TriggerTypes.DAMAGE, 1).withConditions(conditions("min_damage", 5.0)),
            null, TriggerEvent.valued(TriggerTypes.DAMAGE, 2.0, 1L)));
    }

    @Test
    void itemOnInteractPrefersTargetType() {
        TriggerRule rule = TriggerRule.of(1, "boss", TriggerTypes.ITEM_ON_INTERACT, 1)
            .withConditions(conditions("item", "carrot", "target", "zombie", "target_type", "entity"));

        TriggerEvent onEntity = TriggerEvent.of(TriggerTypes.ITEM_ON_INTERACT,
            "minecraft:zombie", "", "minecraft:carrot", 0, 0, 0, 1L);
        assertTrue(TriggerMatchers.matches(rule, null, onEntity));

        TriggerEvent onBlock = TriggerEvent.of(TriggerTypes.ITEM_ON_INTERACT,
            "", "minecraft:iron_block", "minecraft:carrot", 0, 0, 0, 1L);
        assertFalse(TriggerMatchers.matches(rule, null, onBlock));
    }

    @Test
    void itemOnInteractWithoutTargetTypeTriesBothSides() {
        TriggerRule rule = TriggerRule.of(1, "boss", TriggerTypes.ITEM_ON_INTERACT, 1)
            .withConditions(conditions("item", "carrot", "target", "iron_block"));
        assertTrue(TriggerMatchers.matches(rule, null,
            TriggerEvent.of(TriggerTypes.ITEM_ON_INTERACT, "", "minecraft:iron_block", "minecraft:carrot", 0, 0, 0, 1L)));
    }

    @Test
    void bareHandInteractionStillMatchesWhenItemOmitted() {
        TriggerRule rule = TriggerRule.of(1, "any", TriggerTypes.ENTITY_INTERACT, 1)
            .withConditions(conditions("target", "villager"));
        assertTrue(TriggerMatchers.matches(rule, null,
            TriggerEvent.of(TriggerTypes.ENTITY_INTERACT, "minecraft:villager", "", "", 0, 0, 0, 1L)));
    }

    @Test
    void identifierMatchingAcceptsShortFormAndWildcard() {
        assertTrue(TriggerMatchers.identifierMatches("zombie", "minecraft:zombie"));
        assertTrue(TriggerMatchers.identifierMatches("minecraft:zombie", "minecraft:zombie"));
        assertTrue(TriggerMatchers.identifierMatches("*", "minecraft:creeper"));
        assertTrue(TriggerMatchers.identifierMatches("minecraft:*", "minecraft:creeper"));
        assertFalse(TriggerMatchers.identifierMatches("zombie", "minecraft:skeleton"));
        assertFalse(TriggerMatchers.identifierMatches("zombie", ""));
    }

    @Test
    void xpThresholdUsesLevelOrTotal() {
        TriggerRule level = TriggerRule.of(1, "lvl", TriggerTypes.XP, 1).withConditions(conditions("level", 30));
        assertTrue(TriggerMatchers.matches(level, withXp(30, 500), null));
        assertFalse(TriggerMatchers.matches(level, withXp(29, 900), null));

        TriggerRule total = TriggerRule.of(1, "tot", TriggerTypes.XP, 1).withConditions(conditions("total", 1000));
        assertTrue(TriggerMatchers.matches(total, withXp(1, 1200), null));
    }

    private static TriggerContext withXp(int level, int total) {
        return new TriggerContext(0, 64, 0, "minecraft:overworld", 20f, 20f, level, total,
            false, 0, Set.of(), "", "", true);
    }

    @Test
    void observationRequiresLookingAtSomething() {
        TriggerRule rule = TriggerRule.of(1, "look", TriggerTypes.OBSERVATION, 1)
            .withConditions(conditions("target_type", "entity"));

        TriggerContext looking = new TriggerContext(0, 64, 0, "minecraft:overworld", 20f, 20f, 0, 0,
            false, 0, Set.of(), "entity", "minecraft:creeper", true);
        assertTrue(TriggerMatchers.matches(rule, looking, null));

        TriggerContext idle = new TriggerContext(0, 64, 0, "minecraft:overworld", 20f, 20f, 0, 0,
            false, 0, Set.of(), "", "", true);
        assertFalse(TriggerMatchers.matches(rule, idle, null));
    }

    @Test
    void outOfWorldContextNeverMatchesStateTriggers() {
        TriggerRule rule = TriggerRule.of(1, "xp", TriggerTypes.XP, 1);
        assertFalse(TriggerMatchers.matches(rule, TriggerContext.outOfWorld(), null));
    }

    // ── 引擎整体 ──────────────────────────────────────────────

    @Test
    void setRulesResetsRuntimeState() {
        List<String> fired = new ArrayList<>();
        TriggerRule once = new TriggerRule(1, "once", TriggerTypes.ENTITY_ATTACK,
            new LinkedHashMap<>(), 1, true, false, 0L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), once);

        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 1L));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(1, fired.size());

        // 重新装载规则后一次性状态应被清空，允许再次触发。
        engine.setRules(List.of(once));
        engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", 2L));
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(2, fired.size());
    }

    @Test
    void eventQueueIsBoundedAgainstFlooding() {
        List<String> fired = new ArrayList<>();
        TriggerRule rule = new TriggerRule(1, "hit", TriggerTypes.ENTITY_ATTACK,
            new LinkedHashMap<>(), 1, true, false, 0L, 0L, false, 0.0);
        TriggerEngine engine = engineWith((r, e, c) -> fired.add(r.name()), rule);

        for (int i = 0; i < 500; i++) {
            engine.publish(TriggerEvent.simple(TriggerTypes.ENTITY_ATTACK, "minecraft:zombie", i));
        }
        engine.tick(ctx(0, 64, 0, "minecraft:overworld"));
        assertEquals(1, fired.size());
    }

    @Test
    void enabledRuleIdsReflectsConfiguration() {
        TriggerEngine engine = engineWith((r, e, c) -> { },
            TriggerRule.of(1, "a", TriggerTypes.ENTITY_ATTACK, 1),
            TriggerRule.of(2, "b", TriggerTypes.DAMAGE, 1).withEnabled(false));
        assertEquals(Set.of(1), engine.enabledRuleIds());
    }
}
