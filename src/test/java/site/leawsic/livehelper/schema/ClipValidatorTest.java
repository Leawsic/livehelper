package site.leawsic.livehelper.schema;

import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.Manager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipValidatorTest {

    private static Map<String, Object> baseParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("posX", 0.0);
        params.put("posY", 80.0);
        params.put("posZ", 0.0);
        params.put("rotX", 0.0);
        params.put("rotY", 0.0);
        params.put("rotZ", 0.0);
        params.put("fov", 70.0);
        return params;
    }

    private static Map<String, Object> keyframe(double t, double x) {
        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("t", t);
        kf.put("x", x);
        kf.put("y", 80.0);
        kf.put("z", 0.0);
        kf.put("rx", 0.0);
        kf.put("ry", 0.0);
        kf.put("rz", 0.0);
        kf.put("fov", 70.0);
        return kf;
    }

    private static Clip clip(String template, long duration, Map<String, Object> params) {
        return new Clip(1, "c", duration, template, params);
    }

    @Test
    void acceptsAWellFormedStaticClip() {
        ClipValidator.Result result = ClipValidator.validate(clip("STATIC", 5000L, baseParams()));
        assertTrue(result.ok(), result.message());
    }

    @Test
    void rejectsNonPositiveDuration() {
        assertFalse(ClipValidator.validate(clip("STATIC", 0L, baseParams())).ok());
        assertFalse(ClipValidator.validate(clip("STATIC", -1L, baseParams())).ok());
    }

    @Test
    void rejectsUnknownTemplate() {
        ClipValidator.Result result = ClipValidator.validate(clip("NOPE", 1000L, baseParams()));
        assertFalse(result.ok());
        assertTrue(result.message().contains("unknown template"));
    }

    /** 有默认值的字段缺失不算错——模板会回退到 schema 里的默认值。 */
    @Test
    void missingParamWithDefaultIsAccepted() {
        Map<String, Object> params = baseParams();
        params.remove("posX");
        ClipValidator.Result result = ClipValidator.validate(clip("STATIC", 1000L, params));
        assertTrue(result.ok(), "posX 有默认值 0，缺失应回退默认值而非报错: " + result.message());
    }

    /** 没有默认值的字段（keyframes）缺失必须报错。 */
    @Test
    void rejectsMissingKeyframes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fov", 70.0);
        ClipValidator.Result result = ClipValidator.validate(clip("PATH", 5000L, params));
        assertFalse(result.ok());
        assertTrue(result.message().contains("keyframes"), result.message());

        assertFalse(ClipValidator.validate(clip("SPLINE", 5000L, params)).ok());
    }

    /** 这正是原先静默按默认值跑、导致画面与预期不符的那类配置。 */
    @Test
    void rejectsInvalidEasingInsteadOfFallingBackSilently() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fromX", 0.0);
        params.put("fromY", 80.0);
        params.put("fromZ", 0.0);
        params.put("toX", 10.0);
        params.put("toY", 80.0);
        params.put("toZ", 0.0);
        params.put("easing", "bounce");
        params.put("fov", 70.0);

        ClipValidator.Result result = ClipValidator.validate(clip("DOLLY", 1000L, params));
        assertFalse(result.ok());
        assertTrue(result.message().contains("easing"));
    }

    @Test
    void acceptsEveryKnownEasingValue() {
        for (String easing : site.leawsic.livehelper.util.MathUtil.EASINGS) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("fromX", 0.0);
            params.put("toX", 10.0);
            params.put("easing", easing);
            params.put("fov", 70.0);
            ClipValidator.Result result = ClipValidator.validate(clip("DOLLY", 1000L, params));
            assertTrue(result.ok(), "easing '" + easing + "' should be accepted: " + result.message());
        }
    }

    @Test
    void rejectsOutOfRangeFov() {
        Map<String, Object> params = baseParams();
        params.put("fov", 5000.0);
        assertFalse(ClipValidator.validate(clip("STATIC", 1000L, params)).ok());

        params.put("fov", -5.0);
        assertFalse(ClipValidator.validate(clip("STATIC", 1000L, params)).ok());
    }

    @Test
    void rejectsNonFiniteNumber() {
        Map<String, Object> params = baseParams();
        params.put("posX", Double.NaN);
        ClipValidator.Result result = ClipValidator.validate(clip("STATIC", 1000L, params));
        assertFalse(result.ok());
        assertTrue(result.message().contains("posX"));
    }

    @Test
    void warnsAboutUnknownParamsButStillAccepts() {
        Map<String, Object> params = baseParams();
        params.put("somethingLegacy", 1.0);
        ClipValidator.Result result = ClipValidator.validate(clip("STATIC", 1000L, params));
        assertTrue(result.ok(), "未知参数不应阻断保存，以免旧配置无法打开");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("somethingLegacy")));
    }

    @Test
    void acceptsWellFormedKeyframes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", List.of(keyframe(0.0, 0.0), keyframe(0.5, 10.0), keyframe(1.0, 0.0)));
        params.put("fov", 70.0);
        assertTrue(ClipValidator.validate(clip("PATH", 5000L, params)).ok());
        assertTrue(ClipValidator.validate(clip("SPLINE", 5000L, params)).ok());
    }

    @Test
    void rejectsDescendingKeyframeTimes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", List.of(keyframe(0.0, 0.0), keyframe(0.8, 10.0), keyframe(0.2, 0.0)));
        params.put("fov", 70.0);
        ClipValidator.Result result = ClipValidator.validate(clip("PATH", 5000L, params));
        assertFalse(result.ok());
        assertTrue(result.message().contains("not greater than"));
    }

    @Test
    void rejectsDuplicateKeyframeTimes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", List.of(keyframe(0.5, 0.0), keyframe(0.5, 10.0)));
        params.put("fov", 70.0);
        assertFalse(ClipValidator.validate(clip("PATH", 5000L, params)).ok());
    }

    @Test
    void rejectsKeyframeTimeOutOfRange() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", List.of(keyframe(0.0, 0.0), keyframe(1.5, 10.0)));
        params.put("fov", 70.0);
        assertFalse(ClipValidator.validate(clip("PATH", 5000L, params)).ok());
    }

    @Test
    void rejectsEmptyKeyframes() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", new ArrayList<>());
        params.put("fov", 70.0);
        assertFalse(ClipValidator.validate(clip("PATH", 5000L, params)).ok());
    }

    @Test
    void rejectsKeyframesGivenAsGarbage() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", "not json at all");
        params.put("fov", 70.0);
        assertFalse(ClipValidator.validate(clip("PATH", 5000L, params)).ok());
    }

    @Test
    void acceptsKeyframesEncodedAsJsonString() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", "[{\"t\":0.0,\"x\":0,\"y\":80,\"z\":0},{\"t\":1.0,\"x\":10,\"y\":80,\"z\":0}]");
        params.put("fov", 70.0);
        assertTrue(ClipValidator.validate(clip("SPLINE", 5000L, params)).ok());
    }

    @Test
    void rejectsMissingPositionOnAKeyframe() {
        Map<String, Object> broken = keyframe(0.0, 0.0);
        broken.remove("y");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyframes", List.of(broken, keyframe(1.0, 10.0)));
        params.put("fov", 70.0);
        assertFalse(ClipValidator.validate(clip("PATH", 5000L, params)).ok());
    }

    // ── Manager ─────────────────────────────────────────────

    private static Manager manager(List<ClipSlot> slots, boolean loop, String loopMode) {
        return new Manager(1, "m", slots, 1280, 720, 30, 12, loop, loopMode, false);
    }

    private static Map<Integer, Clip> clips(Clip... cs) {
        Map<Integer, Clip> map = new LinkedHashMap<>();
        for (Clip c : cs) {
            map.put(c.id(), c);
        }
        return map;
    }

    @Test
    void acceptsAContiguousTimeline() {
        Clip a = clip("STATIC", 1000L, baseParams());
        Clip b = new Clip(2, "b", 2000L, "STATIC", baseParams());
        Manager m = manager(List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 1000L, 500L, "easeInOut")),
            true, Manager.LOOP_PINGPONG);
        ClipValidator.Result result = ClipValidator.validateManager(m, clips(a, b));
        assertTrue(result.ok(), result.message());
    }

    @Test
    void rejectsTimelineReferencingMissingClip() {
        Clip a = clip("STATIC", 1000L, baseParams());
        Manager m = manager(List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(99, 1000L, 0L, "linear")),
            false, null);
        ClipValidator.Result result = ClipValidator.validateManager(m, clips(a));
        assertFalse(result.ok());
        assertTrue(result.message().contains("does not exist"));
    }

    @Test
    void warnsButAcceptsOverlappingClips() {
        Clip a = clip("STATIC", 2000L, baseParams());
        Clip b = new Clip(2, "b", 1000L, "STATIC", baseParams());
        Manager m = manager(List.of(new ClipSlot(1, 0L, 0L, "linear"), new ClipSlot(2, 1000L, 0L, "linear")),
            false, null);
        ClipValidator.Result result = ClipValidator.validateManager(m, clips(a, b));
        assertTrue(result.ok(), "片段重叠是允许的（转场按同一墙钟求值），只提示");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("overlapping")));
    }

    @Test
    void rejectsInvalidFpsAndNegativeOffset() {
        Manager bad = new Manager(1, "m", List.of(), 1280, 720, 0, 12, false, null, false);
        assertFalse(ClipValidator.validateManager(bad, clips()).ok());

        Clip a = clip("STATIC", 1000L, baseParams());
        Manager negative = manager(List.of(new ClipSlot(1, -5L, 0L, "linear")), false, null);
        assertFalse(ClipValidator.validateManager(negative, clips(a)).ok());
    }

    @Test
    void warnsAboutUnknownTransitionEasing() {
        Clip a = clip("STATIC", 1000L, baseParams());
        Manager m = manager(List.of(new ClipSlot(1, 0L, 0L, "warp")), false, null);
        ClipValidator.Result result = ClipValidator.validateManager(m, clips(a));
        assertTrue(result.ok());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("transitionEasing")));
    }

    /** 拿每个模板的 schema 默认值当样本，全部应当能通过校验。 */
    @Test
    void validatesEveryRegisteredTemplateSample() {
        for (String template : site.leawsic.livehelper.engine.templates.MotionTemplates.getAvailable()) {
            Map<String, Object> params = new LinkedHashMap<>();
            for (FieldDef field : TemplateSchemas.forTemplate(template)) {
                if (field.def() != null) {
                    params.put(field.key(), field.def());
                } else if (FieldDef.TYPE_KEYFRAMES.equals(field.type())) {
                    params.put(field.key(), List.of(keyframe(0.0, 0.0), keyframe(1.0, 10.0)));
                }
            }
            ClipValidator.Result result = ClipValidator.validate(clip(template, 1000L, params));
            assertTrue(result.ok(), "template " + template + " defaults should validate: " + result.message());
        }
    }

    @Test
    void nullClipIsReportedNotThrown() {
        assertFalse(ClipValidator.validate(null).ok());
        assertFalse(ClipValidator.validateManager(null, clips()).ok());
    }

    @Test
    void errorMessageJoinsAllErrorsForApiResponse() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fromX", 0.0);
        params.put("toX", 10.0);
        params.put("easing", "bounce");
        params.put("fov", 5000.0);

        ClipValidator.Result result = ClipValidator.validate(clip("DOLLY", 0L, params));
        assertFalse(result.ok());
        assertTrue(result.message().contains("duration"), result.message());
        assertTrue(result.message().contains("easing"), result.message());
        assertTrue(result.message().contains("fov"), result.message());
        assertEquals(3, result.errors().size(), result.message());
    }
}
