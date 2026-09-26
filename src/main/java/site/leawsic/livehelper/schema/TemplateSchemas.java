package site.leawsic.livehelper.schema;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.util.MathUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个运镜模板的参数字段定义。
 *
 * <p>取代原先散落在 Web UI 里的四张硬编码表（模板→字段、字段→中文名、字段→说明、默认值），
 * 也取代模板类内部各自散落的默认参数。新增模板只需在这里登记一次，
 * {@code /api/templates} 会带上 schema 输出，前端据此动态生成表单。
 */
public final class TemplateSchemas {

    private TemplateSchemas() {}

    private static final Map<String, List<FieldDef>> SCHEMAS = new LinkedHashMap<>();

    /** 相机位置与朝向，三个模板共用。 */
    private static List<FieldDef> positionFields() {
        return List.of(
            FieldDef.number("posX", "位置 X", 0, 0.1, "摄像机所在的世界 X 坐标。"),
            FieldDef.number("posY", "位置 Y", 0, 0.1, "摄像机所在的世界 Y 坐标，通常用玩家眼睛高度。"),
            FieldDef.number("posZ", "位置 Z", 0, 0.1, "摄像机所在的世界 Z 坐标。")
        );
    }

    private static List<FieldDef> rotationFields() {
        return List.of(
            FieldDef.number("rotX", "俯仰 Pitch", 0, 1, "上下看，正值向下，负值向上。"),
            FieldDef.number("rotY", "偏航 Yaw", 0, 1, "水平朝向，使用 Minecraft yaw。"),
            FieldDef.number("rotZ", "滚转 Roll", 0, 1,
                "画面滚转角。注意：当前渲染链路尚未应用 roll，此参数暂不生效。")
        );
    }

    private static FieldDef fovField() {
        return FieldDef.number("fov", "视场角 FOV", 70, 1, 1.0, 179.0,
            "镜头视场角，数值越大越广角。渲染要求 1~179。");
    }

    private static FieldDef easingField(String def) {
        return FieldDef.choice("easing", "缓动", def, MathUtil.EASINGS,
            "控制运动速度曲线。null 或未知值按 linear 处理。");
    }

    private static List<FieldDef> concat(List<FieldDef>... groups) {
        java.util.List<FieldDef> all = new java.util.ArrayList<>();
        for (List<FieldDef> group : groups) {
            all.addAll(group);
        }
        return List.copyOf(all);
    }

    static {
        SCHEMAS.put("STATIC", concat(positionFields(), rotationFields(), List.of(fovField())));

        SCHEMAS.put("STATIC_TRACK", concat(positionFields(), List.of(
            FieldDef.number("entityId", "实体 ID", -1, 1,
                "Minecraft 运行时实体 ID，优先级最高；可用 /livehelper entities 查看附近实体。"),
            FieldDef.text("entityUuid", "实体 UUID", "",
                "实体 UUID，适合长期锁定同一个实体。entityId 找不到时按 UUID 匹配。"),
            FieldDef.text("entityName", "实体名称", "",
                "实体显示名称。entityId 与 UUID 都找不到时按名称精确匹配。"),
            FieldDef.number("targetYOffset", "目标 Y 偏移", 0, 0.1,
                "在实体眼睛高度基础上额外增加的 Y 偏移。"),
            FieldDef.number("trackSpeed", "追踪平滑速度", 8, 0.5, 0.0, 60.0,
                "镜头追踪的平滑速度。0 为即时锁定；越小越丝滑但延迟越明显。推荐 5-25。"),
            fovField()
        )));

        SCHEMAS.put("ORBIT", List.of(
            FieldDef.number("targetX", "目标 X", 0, 0.1, "环绕时始终看向的目标 X 坐标。"),
            FieldDef.number("targetY", "目标 Y", 0, 0.1, "环绕时始终看向的目标 Y 坐标。"),
            FieldDef.number("targetZ", "目标 Z", 0, 0.1, "环绕时始终看向的目标 Z 坐标。"),
            FieldDef.number("radius", "环绕半径", 10, 0.1, 0.0, null, "摄像机到目标点的水平距离。"),
            FieldDef.number("speed", "环绕速度", 1, 0.05, "Clip 播放期间绕目标旋转的圈数。"),
            FieldDef.number("startAngle", "起始角度", 0, 1, "环绕起始角度，单位度。"),
            FieldDef.number("elevation", "仰角", 0, 1, "摄像机相对目标点的仰角，单位度。"),
            easingField("linear"),
            fovField()
        ));

        List<FieldDef> dollyFields = List.of(
            FieldDef.number("fromX", "起点 X", 0, 0.1, "移动起点 X 坐标。"),
            FieldDef.number("fromY", "起点 Y", 0, 0.1, "移动起点 Y 坐标。"),
            FieldDef.number("fromZ", "起点 Z", 0, 0.1, "移动起点 Z 坐标。"),
            FieldDef.number("toX", "终点 X", 0, 0.1, "移动终点 X 坐标。"),
            FieldDef.number("toY", "终点 Y", 0, 0.1, "移动终点 Y 坐标。"),
            FieldDef.number("toZ", "终点 Z", 0, 0.1, "移动终点 Z 坐标。")
        );
        SCHEMAS.put("DOLLY", concat(dollyFields, List.of(easingField("linear"), fovField())));
        // TRUCK 与 DOLLY 共用同一套运动逻辑与参数，约定上用于横向平移（Y/Z 不变，只改 X）。
        SCHEMAS.put("TRUCK", SCHEMAS.get("DOLLY"));

        SCHEMAS.put("PEDESTAL", List.of(
            FieldDef.number("centerX", "中心 X", 0, 0.1, "升降镜头固定 X 坐标。"),
            FieldDef.number("centerZ", "中心 Z", 0, 0.1, "升降镜头固定 Z 坐标。"),
            FieldDef.number("fromHeight", "起始高度", 0, 0.1, "升降镜头起始 Y 高度。"),
            FieldDef.number("toHeight", "结束高度", 0, 0.1, "升降镜头结束 Y 高度。"),
            FieldDef.number("rotX", "俯仰 Pitch", 0, 1, "固定俯仰角。"),
            FieldDef.number("rotY", "偏航 Yaw", 0, 1, "固定偏航角。"),
            easingField("linear"),
            fovField()
        ));

        SCHEMAS.put("PAN_TILT", concat(positionFields(), List.of(
            FieldDef.number("startPan", "起始水平角", 0, 1, "水平旋转起始角度，单位度。"),
            FieldDef.number("endPan", "结束水平角", 0, 1, "水平旋转结束角度，单位度。"),
            FieldDef.number("startTilt", "起始俯仰角", 0, 1, "俯仰起始角度，单位度。"),
            FieldDef.number("endTilt", "结束俯仰角", 0, 1, "俯仰结束角度，单位度。"),
            easingField("linear"),
            fovField()
        )));

        SCHEMAS.put("PATH", List.of(
            FieldDef.keyframes("keyframes", "关键帧",
                "路径点列表，每点含 t、x、y、z、rx、ry、rz、fov。段内为直线插值，关键帧处速度会突变。"),
            easingField(PathTemplateEasing.DEFAULT),
            fovField()
        ));

        SCHEMAS.put("SPLINE", List.of(
            FieldDef.keyframes("keyframes", "关键帧",
                "关键帧格式与 PATH 完全一致。位置走 Catmull-Rom 平滑曲线并按弧长匀速，"
                    + "关键帧处无速度突变。已有 PATH 配置改模板名即可平滑升级。"),
            FieldDef.choice("orientMode", "朝向模式", "keyframe", List.of("keyframe", "tangent"),
                "keyframe=按关键帧的 rx/ry/rz 插值；tangent=相机始终朝向路径切线前方。"),
            easingField("linear"),
            fovField()
        ));
    }

    /** PATH 的历史默认缓动，独立常量以免引用模板包。 */
    static final class PathTemplateEasing {
        static final String DEFAULT = "easeInOut";
    }

    public static Map<String, List<FieldDef>> all() {
        return SCHEMAS;
    }

    public static List<FieldDef> forTemplate(String template) {
        return SCHEMAS.getOrDefault(template, List.of());
    }

    /**
     * 输出 {@code /api/templates} 的 JSON：模板名 + 字段 schema。
     *
     * <p>同时输出已注册模板与已登记 schema 的差集警告信息，便于发现漏登记。
     */
    public static String toJson() {
        Gson gson = new Gson();
        JsonArray templates = new JsonArray();
        for (String name : MotionTemplates.getAvailable()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("template", name);
            JsonArray fields = new JsonArray();
            for (FieldDef field : forTemplate(name)) {
                JsonObject f = new JsonObject();
                f.addProperty("type", field.type());
                f.addProperty("key", field.key());
                f.addProperty("label", field.label());
                f.addProperty("help", field.help());
                f.addProperty("step", field.step());
                f.addProperty("required", field.required());
                if (field.def() != null) {
                    // GSON.toJsonTree 统一处理 Boolean/Number/String，避免逐类型分支。
                    f.add("def", gson.toJsonTree(field.def()));
                }
                if (field.min() != null) {
                    f.addProperty("min", field.min());
                }
                if (field.max() != null) {
                    f.addProperty("max", field.max());
                }
                if (!field.enumValues().isEmpty()) {
                    JsonArray values = new JsonArray();
                    field.enumValues().forEach(values::add);
                    f.add("values", values);
                }
                fields.add(f);
            }
            entry.add("fields", fields);
            templates.add(entry);
        }

        JsonObject root = new JsonObject();
        root.add("templates", templates);
        root.addProperty("missingSchema", missingSchemas());
        return gson.toJson(root);
    }

    /** 返回已注册但未登记 schema 的模板名；正常应为空。 */
    public static String missingSchemas() {
        StringBuilder sb = new StringBuilder();
        for (String name : MotionTemplates.getAvailable()) {
            if (!SCHEMAS.containsKey(name)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
            }
        }
        return sb.toString();
    }
}
