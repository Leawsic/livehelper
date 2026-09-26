package site.leawsic.livehelper.schema;

import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.trigger.TriggerRule;
import site.leawsic.livehelper.trigger.TriggerTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerValidatorTest {

    private static final Set<Integer> MANAGERS = Set.of(1, 2);

    private static Map<String, Object> conditions(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static TriggerRule rule(String type, Map<String, Object> conditions) {
        return new TriggerRule(1, "r", type, conditions, 1, true, true, 0L, 0L, false, 0.0);
    }

    @Test
    void acceptsMinimalValidRule() {
        TriggerValidator.Result result = TriggerValidator.validate(
            TriggerRule.of(1, "killcam", TriggerTypes.ENTITY_KILL, 1), MANAGERS);
        assertTrue(result.ok(), result.message());
    }

    @Test
    void rejectsUnknownType() {
        TriggerValidator.Result result = TriggerValidator.validate(
            TriggerRule.of(1, "x", "not_a_trigger", 1), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("unknown trigger type"));
    }

    @Test
    void rejectsMissingTargetManager() {
        TriggerValidator.Result result = TriggerValidator.validate(
            TriggerRule.of(1, "x", TriggerTypes.DAMAGE, 0), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("targetManager"));
    }

    @Test
    void rejectsTargetManagerThatDoesNotExist() {
        TriggerValidator.Result result = TriggerValidator.validate(
            TriggerRule.of(1, "x", TriggerTypes.DAMAGE, 99), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("does not exist"));
    }

    @Test
    void locationNeedsPositionAndRadius() {
        TriggerValidator.Result noRadius = TriggerValidator.validate(
            rule(TriggerTypes.LOCATION, conditions("position", "0,64,0")), MANAGERS);
        assertFalse(noRadius.ok());
        assertTrue(noRadius.message().contains("radius"));

        TriggerValidator.Result noPosition = TriggerValidator.validate(
            rule(TriggerTypes.LOCATION, conditions("radius", 5.0)), MANAGERS);
        assertFalse(noPosition.ok());
    }

    @Test
    void locationAcceptsCuboidInsteadOfRadius() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.LOCATION, conditions("corner1", "0,60,0", "corner2", "10,70,10")),
            MANAGERS);
        assertTrue(result.ok(), result.message());
    }

    @Test
    void locationRejectsHalfSpecifiedCuboid() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.LOCATION, conditions("corner1", "0,60,0", "radius", 5.0)), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("corner"));
    }

    @Test
    void locationRejectsMalformedPosition() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.LOCATION, conditions("position", "0,64", "radius", 5.0)), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("position"));
    }

    @Test
    void locationAcceptsJsonStylePosition() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.LOCATION, conditions(
                "position", "{\"x\":0,\"y\":64,\"z\":0}", "radius", 5.0)), MANAGERS);
        assertTrue(result.ok(), result.message());
    }

    @Test
    void xpNeedsAtLeastOneThreshold() {
        assertFalse(TriggerValidator.validate(
            rule(TriggerTypes.XP, new LinkedHashMap<>()), MANAGERS).ok());
        assertTrue(TriggerValidator.validate(
            rule(TriggerTypes.XP, conditions("level", 30)), MANAGERS).ok());
        assertTrue(TriggerValidator.validate(
            rule(TriggerTypes.XP, conditions("total", 1000)), MANAGERS).ok());
    }

    @Test
    void observationRejectsBadTargetType() {
        assertFalse(TriggerValidator.validate(
            rule(TriggerTypes.OBSERVATION, conditions("target_type", "sheep")), MANAGERS).ok());
        assertTrue(TriggerValidator.validate(
            rule(TriggerTypes.OBSERVATION, conditions("target_type", "entity")), MANAGERS).ok());
        assertTrue(TriggerValidator.validate(
            rule(TriggerTypes.OBSERVATION, conditions("target_type", "")), MANAGERS).ok());
    }

    @Test
    void itemOnInteractRejectsBadTargetTypeEnum() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.ITEM_ON_INTERACT, conditions("target_type", "player")), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("target_type"));
    }

    @Test
    void rejectsNonFiniteNumericCondition() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.DAMAGE, conditions("min_damage", Double.NaN)), MANAGERS);
        assertFalse(result.ok());
    }

    @Test
    void rejectsNegativeMinimumDamage() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.DAMAGE, conditions("min_damage", -1.0)), MANAGERS);
        assertFalse(result.ok());
        assertTrue(result.message().contains("min_damage"));
    }

    @Test
    void warnsAboutUnusedConditionInsteadOfFailing() {
        TriggerValidator.Result result = TriggerValidator.validate(
            rule(TriggerTypes.DAMAGE, conditions("min_damage", 4.0, "legacyThing", 1)), MANAGERS);
        assertTrue(result.ok(), "未知条件只应告警，否则旧配置无法保存");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("legacyThing")));
    }

    @Test
    void warnsAboutUnnamedRule() {
        TriggerValidator.Result result = TriggerValidator.validate(
            new TriggerRule(1, "  ", TriggerTypes.DAMAGE, new LinkedHashMap<>(), 1,
                true, true, 0L, 0L, false, 0.0), MANAGERS);
        assertTrue(result.ok());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("name")));
    }

    @Test
    void everyTriggerTypeHasSchemaAndValidatesWithDefaults() {
        assertEquals("", TriggerSchemas.missingSchemas());
        for (String type : site.leawsic.livehelper.trigger.TriggerTypes.ALL) {
            assertFalse(TriggerSchemas.forType(type).isEmpty(), "no schema for " + type);
            TriggerValidator.Result result = TriggerValidator.validate(
                TriggerRule.of(1, "r", type, 1), MANAGERS);
            // 少数类型必须给条件才能生效，单独在下面覆盖；这里只关心「不因未知类型而失败」。
            assertFalse(result.message().contains("unknown trigger type"),
                "type " + type + " should be recognised");
        }
    }

    @Test
    void schemaJsonCarriesLabelsAndFields() {
        com.google.gson.JsonObject root =
            com.google.gson.JsonParser.parseString(TriggerSchemas.toJson()).getAsJsonObject();
        com.google.gson.JsonArray types = root.getAsJsonArray("types");
        assertEquals(site.leawsic.livehelper.trigger.TriggerTypes.ALL.size(), types.size());

        boolean sawLabel = false;
        for (var element : types) {
            com.google.gson.JsonObject entry = element.getAsJsonObject();
            assertTrue(entry.has("type"));
            assertTrue(entry.has("label"));
            assertTrue(entry.has("fields"));
            if (entry.get("label").getAsString().contains("击杀")) sawLabel = true;
            for (var f : entry.getAsJsonArray("fields")) {
                com.google.gson.JsonObject field = f.getAsJsonObject();
                assertTrue(field.has("type"));
                assertTrue(field.has("key"));
                assertTrue(field.has("label"));
                assertTrue(field.has("help"));
            }
        }
        assertTrue(sawLabel, "expected a Chinese label for entity_kill");
    }

    @Test
    void nullRuleIsReportedNotThrown() {
        assertFalse(TriggerValidator.validate(null, MANAGERS).ok());
    }

    @Test
    void nullManagerSetSkipsExistenceCheck() {
        assertNotNull(TriggerValidator.validate(
            TriggerRule.of(1, "x", TriggerTypes.DAMAGE, 77), null).message());
        assertTrue(TriggerValidator.validate(
            TriggerRule.of(1, "x", TriggerTypes.DAMAGE, 77), null).ok(),
            "manager 集合未知时不应误判为不存在");
    }
}
