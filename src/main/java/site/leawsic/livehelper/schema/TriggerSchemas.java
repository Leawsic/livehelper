package site.leawsic.livehelper.schema;

import site.leawsic.livehelper.trigger.TriggerTypes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 触发器 conditions 的字段定义，与 {@link TemplateSchemas} 同构。
 *
 * <p>沿用 ImmersiveCinematics 的做法：Java 侧是唯一权威，{@code /api/triggers/schema}
 * 输出给 Web UI 动态生成条件表单，新增触发类型只改这里。
 */
public final class TriggerSchemas {

    private TriggerSchemas() {}

    private static final Map<String, List<FieldDef>> SCHEMAS = new LinkedHashMap<>();

    private static final FieldDef DIMENSION = FieldDef.text("dimension", "维度", "",
        "留空表示不限；支持 minecraft:the_nether 或简写 the_nether。");

    private static final FieldDef TARGET = FieldDef.text("target", "目标", "",
        "目标标识，例如 zombie（等价于 minecraft:zombie）、minecraft:iron_golem 或 * 通配。");

    private static final FieldDef ITEM = FieldDef.text("item", "手持物品", "",
        "当时手持的物品标识；留空表示不限。空手交互请留空。");

    private static final FieldDef TARGET_TYPE = FieldDef.choice("target_type", "目标种类", "",
        List.of("", "entity", "block"), "entity / block / 留空（两边都试）。");

    private static final FieldDef POSITION = FieldDef.text("position", "区域中心", "",
        "区域中心，格式 x,y,z（也接受 {\"x\":..,\"y\":..,\"z\":..}）。");

    static {
        SCHEMAS.put(TriggerTypes.ENTITY_KILL, List.of(
            FieldDef.text("target", "生物类型", "", "被击杀的生物标识；留空表示任意。")));

        SCHEMAS.put(TriggerTypes.DAMAGE, List.of(
            FieldDef.number("min_damage", "最小伤害量", 0, 0.5, 0.0, null,
                "单次掉血量下限，用于过滤挠痒般的伤害。0 表示不限制。")));

        SCHEMAS.put(TriggerTypes.ENTITY_ATTACK, List.of(TARGET));

        SCHEMAS.put(TriggerTypes.ENTITY_INTERACT, List.of(TARGET, ITEM));

        SCHEMAS.put(TriggerTypes.BLOCK_INTERACT, List.of(
            FieldDef.text("target", "方块", "", "交互的方块标识；留空表示任意。"), ITEM));

        SCHEMAS.put(TriggerTypes.ITEM_ON_INTERACT, List.of(ITEM, TARGET, TARGET_TYPE));

        SCHEMAS.put(TriggerTypes.ITEM_USE, List.of(ITEM));
        SCHEMAS.put(TriggerTypes.ITEM_CONSUME, List.of(ITEM));
        SCHEMAS.put(TriggerTypes.ITEM_RELEASE, List.of(ITEM));

        SCHEMAS.put(TriggerTypes.DIMENSION_CHANGE, List.of(
            FieldDef.text("dimension", "目标维度", "", "切换到的维度；留空表示任意。")));

        SCHEMAS.put(TriggerTypes.LOCATION, List.of(
            DIMENSION, POSITION,
            FieldDef.number("radius", "半径", 0, 0.5, 0.0, null, "以区域中心为圆心的半径（格）。"),
            FieldDef.text("corner1", "长方体角点 1", "", "与 corner2 一起定义轴对齐区域，格式 x,y,z。"),
            FieldDef.text("corner2", "长方体角点 2", "", "与 corner1 一起定义轴对齐区域。")));

        SCHEMAS.put(TriggerTypes.ADVANCEMENT, List.of(
            FieldDef.text("advancement", "进度 id", "", "例如 minecraft:story/mine_stone；留空表示任意新进度。")));

        SCHEMAS.put(TriggerTypes.XP, List.of(
            FieldDef.number("level", "最低等级", 0, 1, 0.0, null, "达到或超过该等级时触发。"),
            FieldDef.number("total", "最低累计经验", 0, 1, 0.0, null, "达到或超过该累计经验时触发。")));

        SCHEMAS.put(TriggerTypes.OBSERVATION, List.of(
            FieldDef.text("target", "注视目标", "", "视线命中的目标标识；留空表示看向任意东西。"),
            TARGET_TYPE));
    }

    public static Map<String, List<FieldDef>> all() {
        return SCHEMAS;
    }

    public static List<FieldDef> forType(String type) {
        return SCHEMAS.getOrDefault(type, List.of());
    }

    /** 输出给 Web UI 的 JSON 负载。 */
    public static String toJson() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray types = new com.google.gson.JsonArray();
        for (String type : TriggerTypes.ALL) {
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("type", type);
            entry.addProperty("label", labelOf(type));
            com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
            for (FieldDef field : forType(type)) {
                com.google.gson.JsonObject f = new com.google.gson.JsonObject();
                f.addProperty("type", field.type());
                f.addProperty("key", field.key());
                f.addProperty("label", field.label());
                f.addProperty("help", field.help());
                f.addProperty("step", field.step());
                f.addProperty("required", field.required());
                if (field.def() != null) {
                    f.add("def", gson.toJsonTree(field.def()));
                }
                if (!field.enumValues().isEmpty()) {
                    com.google.gson.JsonArray values = new com.google.gson.JsonArray();
                    field.enumValues().forEach(values::add);
                    f.add("values", values);
                }
                fields.add(f);
            }
            entry.add("fields", fields);
            types.add(entry);
        }
        root.add("types", types);
        root.addProperty("missingSchema", missingSchemas());
        return gson.toJson(root);
    }

    public static String missingSchemas() {
        StringBuilder sb = new StringBuilder();
        for (String type : TriggerTypes.ALL) {
            if (!SCHEMAS.containsKey(type)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(type);
            }
        }
        return sb.toString();
    }

    /** 触发类型的显示名，Web UI 与命令输出共用。 */
    public static String labelOf(String type) {
        return switch (type) {
            case TriggerTypes.ENTITY_KILL -> "击杀生物";
            case TriggerTypes.DAMAGE -> "自身受伤";
            case TriggerTypes.ENTITY_ATTACK -> "攻击实体";
            case TriggerTypes.ENTITY_INTERACT -> "与实体交互";
            case TriggerTypes.BLOCK_INTERACT -> "与方块交互";
            case TriggerTypes.ITEM_ON_INTERACT -> "持物交互";
            case TriggerTypes.ITEM_USE -> "开始使用物品";
            case TriggerTypes.ITEM_CONSUME -> "用完物品";
            case TriggerTypes.ITEM_RELEASE -> "中途松手";
            case TriggerTypes.DIMENSION_CHANGE -> "切换维度";
            case TriggerTypes.LOCATION -> "进入区域";
            case TriggerTypes.ADVANCEMENT -> "获得进度";
            case TriggerTypes.XP -> "获得经验";
            case TriggerTypes.OBSERVATION -> "注视目标";
            default -> type;
        };
    }
}
