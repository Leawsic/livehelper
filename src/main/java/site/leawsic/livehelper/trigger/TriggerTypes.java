package site.leawsic.livehelper.trigger;

import java.util.List;

/**
 * 触发类型常量。
 *
 * <p>命名沿用 ImmersiveCinematics 的触发器 id，便于对照迁移；但语义改为
 * 「本地客户端观察到的画面事件」——因为 LiveHelper 接管的是本地相机，推流画面也来自本机，
 * 判定以主播视角为准即可，不需要服务端权威。
 */
public final class TriggerTypes {

    private TriggerTypes() {}

    /** 玩家攻击过的实体随后死亡或从世界消失。 */
    public static final String ENTITY_KILL = "entity_kill";
    /** 玩家自身生命值下降。 */
    public static final String DAMAGE = "damage";
    /** 玩家攻击了某个实体。 */
    public static final String ENTITY_ATTACK = "entity_attack";
    /** 玩家与某个实体发生交互。 */
    public static final String ENTITY_INTERACT = "entity_interact";
    /** 玩家与某个方块发生交互。 */
    public static final String BLOCK_INTERACT = "block_interact";
    /** 玩家持某个物品与目标交互。 */
    public static final String ITEM_ON_INTERACT = "item_on_interact";
    /** 玩家开始使用物品。 */
    public static final String ITEM_USE = "item_use";
    /** 玩家把物品用完。 */
    public static final String ITEM_CONSUME = "item_consume";
    /** 玩家使用中途松手。 */
    public static final String ITEM_RELEASE = "item_release";
    /** 玩家切换维度。 */
    public static final String DIMENSION_CHANGE = "dimension_change";
    /** 玩家进入指定区域。 */
    public static final String LOCATION = "location";
    /** 玩家获得进度。 */
    public static final String ADVANCEMENT = "advancement";
    /** 玩家获得经验。 */
    public static final String XP = "xp";
    /** 玩家注视某个目标。 */
    public static final String OBSERVATION = "observation";

    public static final List<String> ALL = List.of(
        ENTITY_KILL,
        DAMAGE,
        ENTITY_ATTACK,
        ENTITY_INTERACT,
        BLOCK_INTERACT,
        ITEM_ON_INTERACT,
        ITEM_USE,
        ITEM_CONSUME,
        ITEM_RELEASE,
        DIMENSION_CHANGE,
        LOCATION,
        ADVANCEMENT,
        XP,
        OBSERVATION
    );

    public static boolean isKnown(String type) {
        return ALL.contains(type);
    }
}
