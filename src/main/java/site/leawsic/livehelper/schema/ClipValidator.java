package site.leawsic.livehelper.schema;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.util.MathUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写入前静态校验。
 *
 * <p>移植自 ImmersiveCinematics 的 ScriptValidator 思路，但校验对象是单条 Clip 而不是整份脚本。
 * 目的是把「配错了但静默按默认值跑」变成明确的 400 错误——原先
 * {@code MotionTemplates.p()} 对无法解析的参数一律静默回退默认值，画面表现和预期不符却无从排查。
 *
 * <p>校验规则由 {@link TemplateSchemas} 驱动，新增模板无需改这里。
 */
public final class ClipValidator {

    private static final Gson GSON = new Gson();
    /** 单个 Clip 时长上限：24 小时，纯粹用于挡住手滑填错单位。 */
    private static final long MAX_DURATION_MS = 24L * 60 * 60 * 1000;

    private ClipValidator() {}

    public record Result(List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }

        public String message() {
            return String.join("; ", errors);
        }
    }

    public static Result validate(Clip clip) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (clip == null) {
            errors.add("clip is null");
            return new Result(errors, warnings);
        }

        validateDuration(clip, errors);
        // 模板未知时无法按 schema 校验参数，跳过；其余情况即使 duration 有问题也继续校验参数，
        // 免得用户要修好几轮才能看到全部问题。
        if (validateTemplate(clip, errors, warnings)) {
            validateParams(clip, errors, warnings);
        }
        return new Result(errors, warnings);
    }

    public static Result validateManager(Manager manager, Map<Integer, Clip> clips) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (manager == null) {
            errors.add("manager is null");
            return new Result(errors, warnings);
        }
        if (manager.width() <= 0 || manager.height() <= 0) {
            errors.add("width/height must be positive");
        }
        if (manager.fps() <= 0 || manager.fps() > 240) {
            errors.add("fps must be within 1..240, got " + manager.fps());
        }
        if (manager.renderDistance() < 2 || manager.renderDistance() > 64) {
            warnings.add("renderDistance " + manager.renderDistance() + " is outside the usual 2..64 range");
        }
        if (manager.clips() == null || manager.clips().isEmpty()) {
            warnings.add("manager has no clips; it will output the player view only");
        } else {
            long previousEnd = Long.MIN_VALUE;
            for (ClipSlot slot : manager.clips()) {
                if (slot.startOffset() < 0) {
                    errors.add("startOffset must be >= 0, got " + slot.startOffset());
                }
                if (!MathUtil.isValidEasing(slot.transitionEasing())) {
                    warnings.add("clip #" + slot.clipId() + " has unknown transitionEasing '"
                        + slot.transitionEasing() + "', treated as linear");
                }
                Clip clip = clips.get(slot.clipId());
                if (clip == null) {
                    errors.add("clip #" + slot.clipId() + " referenced by timeline does not exist");
                    continue;
                }
                long end = slot.startOffset() + clip.duration();
                if (end <= 0) {
                    errors.add("clip #" + slot.clipId() + " has non-positive duration");
                }
                if (slot.startOffset() < previousEnd) {
                    // 重叠是允许的（转场按同一墙钟求值），但通常是配错偏移导致的，值得提示。
                    warnings.add("clip #" + slot.clipId() + " starts at " + slot.startOffset()
                        + "ms, overlapping the previous clip which ends at " + previousEnd + "ms");
                }
                previousEnd = Math.max(previousEnd, end);
            }
        }
        return new Result(errors, warnings);
    }

    private static void validateDuration(Clip clip, List<String> errors) {
        if (clip.duration() <= 0) {
            errors.add("duration must be > 0, got " + clip.duration());
        } else if (clip.duration() > MAX_DURATION_MS) {
            errors.add("duration " + clip.duration() + "ms exceeds the 24h sanity limit");
        }
    }

    /** @return 模板是否可用于校验参数 */
    private static boolean validateTemplate(Clip clip, List<String> errors, List<String> warnings) {
        Set<String> available = MotionTemplates.getAvailable();
        if (clip.template() == null || clip.template().isBlank()) {
            errors.add("template is required");
            return false;
        }
        if (!available.contains(clip.template())) {
            errors.add("unknown template '" + clip.template() + "'; available: " + String.join(", ", available));
            return false;
        }
        List<FieldDef> fields = TemplateSchemas.forTemplate(clip.template());
        if (fields.isEmpty()) {
            warnings.add("template '" + clip.template() + "' has no schema; params are not validated");
        }
        return true;
    }

    private static void validateParams(Clip clip, List<String> errors, List<String> warnings) {
        List<FieldDef> fields = TemplateSchemas.forTemplate(clip.template());
        Map<String, Object> params = clip.params();

        if (params == null) {
            if (requiresParams(fields)) {
                errors.add("template '" + clip.template() + "' requires params but none were given");
            }
            return;
        }

        Set<String> known = new java.util.HashSet<>();
        for (FieldDef field : fields) {
            known.add(field.key());
            Object value = params.get(field.key());

            if (value == null) {
                // def == null 的字段没有默认值可退，缺失即错误。keyframes 也归入这一类：
                // 否则「完全没有关键帧」的 PATH/SPLINE 能通过校验，直到播放时才抛
                // IllegalArgumentException，正是校验器要拦的那类问题。
                if (field.required()) {
                    errors.add("missing required param '" + field.key() + "'");
                }
                continue;
            }

            switch (field.type()) {
                case FieldDef.TYPE_ENUM -> {
                    String actual = String.valueOf(value);
                    if (!field.enumValues().contains(actual)) {
                        errors.add("param '" + field.key() + "' must be one of "
                            + String.join(", ", field.enumValues()) + ", got '" + actual + "'");
                    }
                }
                case FieldDef.TYPE_NUMBER -> {
                    if (!isFiniteNumber(value)) {
                        errors.add("param '" + field.key() + "' must be a finite number, got " + value);
                    } else {
                        double d = toDouble(value);
                        if (field.min() != null && d < field.min()) {
                            errors.add("param '" + field.key() + "' must be >= " + field.min() + ", got " + d);
                        }
                        if (field.max() != null && d > field.max()) {
                            errors.add("param '" + field.key() + "' must be <= " + field.max() + ", got " + d);
                        }
                    }
                }
                case FieldDef.TYPE_BOOLEAN -> {
                    if (!(value instanceof Boolean)) {
                        warnings.add("param '" + field.key() + "' should be a boolean, got " + value);
                    }
                }
                case FieldDef.TYPE_KEYFRAMES -> validateKeyframes(field.key(), value, errors);
                default -> { }
            }
        }

        for (String key : params.keySet()) {
            if (!known.contains(key)) {
                warnings.add("param '" + key + "' is not part of template '" + clip.template()
                    + "' and will be ignored");
            }
        }
    }

    private static boolean requiresParams(List<FieldDef> fields) {
        return fields.stream().anyMatch(FieldDef::required);
    }

    private static void validateKeyframes(String key, Object raw, List<String> errors) {
        JsonArray array = asArray(raw);
        if (array == null) {
            errors.add("param '" + key + "' must be a list of keyframes");
            return;
        }
        if (array.isEmpty()) {
            errors.add("param '" + key + "' is empty; at least one keyframe is required");
            return;
        }

        double previousT = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                errors.add("keyframe " + i + " is not an object");
                continue;
            }
            JsonObject kf = element.getAsJsonObject();

            if (!kf.has("t")) {
                errors.add("keyframe " + i + " is missing 't'");
                continue;
            }
            double t = kf.get("t").getAsDouble();
            if (!Double.isFinite(t)) {
                errors.add("keyframe " + i + " has non-finite t");
                continue;
            }
            if (t < 0.0 || t > 1.0) {
                errors.add("keyframe " + i + " has t=" + t + " outside [0, 1]");
            }
            if (t <= previousT) {
                errors.add("keyframe " + i + " has t=" + t
                    + " which is not greater than the previous keyframe's t=" + previousT);
            }
            previousT = t;

            for (String axis : new String[] {"x", "y", "z"}) {
                if (!kf.has(axis)) {
                    errors.add("keyframe " + i + " is missing '" + axis + "'");
                    continue;
                }
                if (!kf.get(axis).isJsonPrimitive() || !kf.get(axis).getAsJsonPrimitive().isNumber()) {
                    errors.add("keyframe " + i + " field '" + axis + "' must be a number");
                    continue;
                }
                if (!Double.isFinite(kf.get(axis).getAsDouble())) {
                    errors.add("keyframe " + i + " field '" + axis + "' is not finite");
                }
            }
        }
    }

    private static JsonArray asArray(Object raw) {
        try {
            JsonElement element;
            if (raw instanceof String s) {
                String trimmed = s.trim();
                if (trimmed.isEmpty()) {
                    return null;
                }
                element = JsonParser.parseString(trimmed);
            } else {
                element = GSON.toJsonTree(raw);
            }
            return element.isJsonArray() ? element.getAsJsonArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isFiniteNumber(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        return Double.isFinite(number.doubleValue());
    }

    private static double toDouble(Object value) {
        return ((Number) value).doubleValue();
    }
}
