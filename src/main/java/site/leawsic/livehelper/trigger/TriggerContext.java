package site.leawsic.livehelper.trigger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本地玩家每 tick 的状态快照。
 *
 * <p>与 {@link TriggerEvent} 一样不引用 Minecraft 类型：轮询类触发器全部基于这份快照求值，
 * 因此「位置触发」「升级触发」「受伤触发」这类判定可以在单元测试里直接构造。
 *
 * @param x,y,z               玩家世界坐标
 * @param dimension           当前维度 id
 * @param health,maxHealth    当前生命值与上限
 * @param experienceLevel     经验等级
 * @param totalExperience     累计经验
 * @param usingItem           是否正在使用物品
 * @param useTicksLeft        剩余使用 ticks（0 表示未使用）
 * @param completedAdvancements 本 tick 新获得的进度 id
 * @param lookedAtType        注视目标的种类：{@code entity} / {@code block} / 空串
 * @param lookedAtId          注视目标的标识；无目标时为空串
 * @param inWorld             是否已载入世界
 */
public record TriggerContext(
    double x,
    double y,
    double z,
    String dimension,
    float health,
    float maxHealth,
    int experienceLevel,
    int totalExperience,
    boolean usingItem,
    int useTicksLeft,
    Set<String> completedAdvancements,
    String lookedAtType,
    String lookedAtId,
    boolean inWorld
) {
    public TriggerContext {
        dimension = dimension == null ? "" : dimension;
        completedAdvancements = completedAdvancements == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new LinkedHashSet<>(completedAdvancements));
        lookedAtType = lookedAtType == null ? "" : lookedAtType;
        lookedAtId = lookedAtId == null ? "" : lookedAtId;
    }

    /** 未载入世界时的空快照，引擎据此跳过所有轮询判定。 */
    public static TriggerContext outOfWorld() {
        return new TriggerContext(0, 0, 0, "", 0, 0, 0, 0,
            false, 0, Collections.emptySet(), "", "", false);
    }
}
