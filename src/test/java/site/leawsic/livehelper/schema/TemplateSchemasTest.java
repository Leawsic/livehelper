package site.leawsic.livehelper.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.util.MathUtil;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateSchemasTest {

    /** 新增模板却忘记登记 schema 时，前端会退化成自由输入，必须在测试里挡住。 */
    @Test
    void everyRegisteredTemplateHasSchema() {
        for (String template : MotionTemplates.getAvailable()) {
            assertFalse(TemplateSchemas.forTemplate(template).isEmpty(),
                "template " + template + " has no schema; Web UI would fall back to free-form input");
        }
        assertEquals("", TemplateSchemas.missingSchemas());
    }

    @Test
    void everySchemaEntryCorrespondsToARealTemplate() {
        for (String template : TemplateSchemas.all().keySet()) {
            assertTrue(MotionTemplates.getAvailable().contains(template),
                "schema declared for unknown template " + template);
        }
    }

    @Test
    void fieldKeysAreUniqueWithinEachTemplate() {
        for (var entry : TemplateSchemas.all().entrySet()) {
            Set<String> keys = new HashSet<>();
            for (FieldDef field : entry.getValue()) {
                assertTrue(keys.add(field.key()),
                    "duplicate field key '" + field.key() + "' in template " + entry.getKey());
            }
        }
    }

    @Test
    void everyFieldHasLabelAndHelp() {
        for (var entry : TemplateSchemas.all().entrySet()) {
            for (FieldDef field : entry.getValue()) {
                assertFalse(field.label().isBlank(),
                    "field " + field.key() + " of " + entry.getKey() + " has no label");
                assertFalse(field.help().isBlank(),
                    "field " + field.key() + " of " + entry.getKey() + " has no help text");
                assertFalse(field.key().isBlank(), "blank field key in " + entry.getKey());
                assertTrue(List.of("number", "string", "boolean", "enum", "keyframes").contains(field.type()),
                    "unknown field type '" + field.type() + "' for " + field.key());
            }
        }
    }

    @Test
    void enumFieldsDeclareValidValues() {
        for (var entry : TemplateSchemas.all().entrySet()) {
            for (FieldDef field : entry.getValue()) {
                if (!FieldDef.TYPE_ENUM.equals(field.type())) continue;
                assertFalse(field.enumValues().isEmpty(),
                    "enum field " + field.key() + " of " + entry.getKey() + " has no values");
                Object def = field.def();
                if (def != null) {
                    assertTrue(field.enumValues().contains(String.valueOf(def)),
                        "default '" + def + "' of " + field.key() + " is not among " + field.enumValues());
                }
            }
        }
    }

    @Test
    void easingDefaultsAreRealEasingNames() {
        for (var entry : TemplateSchemas.all().entrySet()) {
            for (FieldDef field : entry.getValue()) {
                if (!"easing".equals(field.key())) continue;
                assertTrue(MathUtil.isValidEasing(String.valueOf(field.def())),
                    "template " + entry.getKey() + " has invalid easing default " + field.def());
            }
        }
    }

    @Test
    void pathKeepsItsLegacyEaseInOutDefault() {
        FieldDef easing = null;
        for (FieldDef field : TemplateSchemas.forTemplate("PATH")) {
            if ("easing".equals(field.key())) easing = field;
        }
        assertEquals("easeInOut", String.valueOf(easing.def()),
            "PATH must default to easeInOut to preserve existing clip feel");
    }

    @Test
    void splineDefaultsToLinearAndKeyframeOrientation() {
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        for (FieldDef field : TemplateSchemas.forTemplate("SPLINE")) {
            fields.put(field.key(), field);
        }
        assertEquals("linear", String.valueOf(fields.get("easing").def()));
        assertEquals("keyframe", String.valueOf(fields.get("orientMode").def()));
        assertEquals(FieldDef.TYPE_KEYFRAMES, fields.get("keyframes").type());
    }

    @Test
    void splineAndPathShareKeyframeFormat() {
        FieldDef pathKeys = null;
        FieldDef splineKeys = null;
        for (FieldDef field : TemplateSchemas.forTemplate("PATH")) {
            if ("keyframes".equals(field.key())) pathKeys = field;
        }
        for (FieldDef field : TemplateSchemas.forTemplate("SPLINE")) {
            if ("keyframes".equals(field.key())) splineKeys = field;
        }
        assertEquals(pathKeys.type(), splineKeys.type());
    }

    /**
     * 钉住 app.js 实际读取的每一个 JSON 键。前端按 field.type 分支渲染表单、
     * 按 field.step / min / max 建输入框、按 field.values 建下拉，
     * 少任何一个键都会让 Web UI 静默退化。
     */
    @Test
    void jsonPayloadCarriesEveryKeyTheWebUiReads() {
        JsonObject root = JsonParser.parseString(TemplateSchemas.toJson()).getAsJsonObject();
        JsonArray templates = root.getAsJsonArray("templates");
        assertEquals(MotionTemplates.getAvailable().size(), templates.size());

        boolean sawFov = false;
        boolean sawEnum = false;
        boolean sawKeyframes = false;
        boolean sawRequiredFlag = false;

        for (var element : templates) {
            JsonObject entry = element.getAsJsonObject();
            assertTrue(entry.has("template"), "entry must expose 'template'");
            assertTrue(entry.has("fields"), "entry must expose 'fields'");

            for (var f : entry.getAsJsonArray("fields")) {
                JsonObject field = f.getAsJsonObject();
                for (String key : new String[] {"type", "key", "label", "help", "step", "required"}) {
                    assertTrue(field.has(key),
                        "field " + field.get("key") + " is missing '" + key + "' required by app.js");
                }
                if (field.has("required")) {
                    sawRequiredFlag = true;
                }
                String type = field.get("type").getAsString();
                if ("enum".equals(type)) {
                    assertTrue(field.has("values"), "enum field " + field.get("key").getAsString() + " needs 'values'");
                    assertTrue(field.getAsJsonArray("values").size() > 0);
                    sawEnum = true;
                }
                if ("keyframes".equals(type)) {
                    sawKeyframes = true;
                }
                if ("fov".equals(field.get("key").getAsString())) {
                    sawFov = true;
                    assertTrue(field.has("min") && field.has("max"), "fov must expose range to the UI");
                }
            }
        }
        assertTrue(sawFov, "expected at least one template exposing fov");
        assertTrue(sawEnum, "expected at least one enum field so the UI renders a dropdown");
        assertTrue(sawKeyframes, "expected keyframes fields for PATH/SPLINE");
        assertTrue(sawRequiredFlag, "required flag must be emitted");
    }

    /** SPLINE 的 orientMode 必须以 enum 形式下发，前端才能渲染成下拉而不是输入框。 */
    @Test
    void splineOrientModeIsAnEnumInThePayload() {
        JsonObject root = JsonParser.parseString(TemplateSchemas.toJson()).getAsJsonObject();
        for (var element : root.getAsJsonArray("templates")) {
            JsonObject entry = element.getAsJsonObject();
            if (!"SPLINE".equals(entry.get("template").getAsString())) continue;
            for (var f : entry.getAsJsonArray("fields")) {
                JsonObject field = f.getAsJsonObject();
                if (!"orientMode".equals(field.get("key").getAsString())) continue;
                assertEquals("enum", field.get("type").getAsString());
                JsonArray values = field.getAsJsonArray("values");
                assertEquals(2, values.size());
                assertTrue(values.toString().contains("tangent"));
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("SPLINE orientMode field not found in payload");
    }
}
