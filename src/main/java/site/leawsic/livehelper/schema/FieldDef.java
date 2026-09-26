package site.leawsic.livehelper.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个参数字段的元数据。
 *
 * <p>移植自 ImmersiveCinematics 的 {@code script/schema/FieldDef.java}，去掉了那边为
 * 游戏内编辑器预留的 section 分组，改为直接携带前端展示所需的 label / help。
 *
 * <p>设计意图：Java 侧是唯一权威。校验、Web UI 表单渲染、默认值全部由这一份定义驱动，
 * 新增模板或新增参数时不再需要前后端各改一遍。
 *
 * @param type        字段类型：number / string / boolean / enum / keyframes
 * @param key         参数键名（写入 {@code Clip.params} 的键）
 * @param label       中文显示名
 * @param def         默认值；null 表示无默认值
 * @param enumValues  type 为 enum 时的合法取值；否则为空列表
 * @param help        参数说明，直接展示在 Web UI 上
 * @param step        数字输入框的步长；<=0 表示不指定
 * @param min         数字/枚举下拉的最小值或索引下界；null 表示不限
 * @param max         数字输入框的最大值；null 表示不限
 */
public record FieldDef(
    String type,
    String key,
    String label,
    Object def,
    List<String> enumValues,
    String help,
    double step,
    Double min,
    Double max
) {
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_ENUM = "enum";
    public static final String TYPE_KEYFRAMES = "keyframes";

    public FieldDef {
        enumValues = enumValues == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(enumValues));
        help = help == null ? "" : help;
        step = step <= 0 ? 0.1 : step;
    }

    public static FieldDef number(String key, String label, double def, String help) {
        return new FieldDef(TYPE_NUMBER, key, label, def, null, help, 0.1, null, null);
    }

    public static FieldDef number(String key, String label, double def, double step, String help) {
        return new FieldDef(TYPE_NUMBER, key, label, def, null, help, step, null, null);
    }

    public static FieldDef number(String key, String label, double def, double step,
                                  Double min, Double max, String help) {
        return new FieldDef(TYPE_NUMBER, key, label, def, null, help, step, min, max);
    }

    public static FieldDef text(String key, String label, String def, String help) {
        return new FieldDef(TYPE_STRING, key, label, def, null, help, 0, null, null);
    }

    public static FieldDef bool(String key, String label, boolean def, String help) {
        return new FieldDef(TYPE_BOOLEAN, key, label, def, null, help, 0, null, null);
    }

    public static FieldDef choice(String key, String label, String def, List<String> values, String help) {
        return new FieldDef(TYPE_ENUM, key, label, def, values, help, 0, null, null);
    }

    public static FieldDef keyframes(String key, String label, String help) {
        return new FieldDef(TYPE_KEYFRAMES, key, label, null, null, help, 0, null, null);
    }

    /** 该字段是否必填（无默认值即视为必填）。 */
    public boolean required() {
        return def == null;
    }
}
