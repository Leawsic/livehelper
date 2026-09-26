package site.leawsic.livehelper.trigger.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.mixin.ClientAdvancementsAccessor;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.storage.StorageManager;
import site.leawsic.livehelper.trigger.TriggerContext;
import site.leawsic.livehelper.trigger.TriggerEngine;
import site.leawsic.livehelper.trigger.TriggerEvent;
import site.leawsic.livehelper.trigger.TriggerRule;
import site.leawsic.livehelper.trigger.TriggerTypes;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端桥接层：把本地玩家状态翻译成 {@link TriggerContext}，驱动 {@link TriggerEngine}。
 *
 * <p>绝大多数触发靠「每 tick 对比上一 tick 的快照」实现，不额外引入 mixin：
 * 受伤、升级、切维度、获得进度、开始/用完/松手物品都能直接从玩家状态读出。
 * 只有交互与攻击需要 mixin（见 {@code MultiPlayerGameModeMixin}）。
 *
 * <p>判定完全基于本地视角，多人服同样可用：推流画面本来就来自本机，
 * 只要主播这边看到的时机对，画面就是对的。
 */
public final class ClientTriggerBridge {

    private ClientTriggerBridge() {}

    private static final TriggerEngine ENGINE = new TriggerEngine();

    private static float lastHealth = Float.NaN;
    private static int lastLevel = -1;
    private static String lastDimension = "";
    private static boolean lastUsingItem = false;
    private static int lastUseTicksLeft = 0;
    private static String lastUsedItemId = "";
    private static final Set<String> DONE_ADVANCEMENTS = new HashSet<>();
    private static boolean warnedMissingManager = false;

    public static TriggerEngine engine() {
        return ENGINE;
    }

    /** 规则集合变化后调用，会清空引擎的运行时状态与本层的快照基线。 */
    public static void reload(List<TriggerRule> rules) {
        ENGINE.setRules(rules);
        resetSnapshot();
    }

    public static void resetSnapshot() {
        lastHealth = Float.NaN;
        lastLevel = -1;
        lastDimension = "";
        lastUsingItem = false;
        lastUseTicksLeft = 0;
        lastUsedItemId = "";
        DONE_ADVANCEMENTS.clear();
        TriggerEventPublisher.reset();
        warnedMissingManager = false;
    }

    public static int enabledRuleCount() {
        return ENGINE.enabledRuleIds().size();
    }

    /** 每个客户端 tick 调用一次。 */
    public static void onClientTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            ENGINE.tick(TriggerContext.outOfWorld());
            return;
        }

        TriggerEventPublisher.beginTick(ENGINE.currentTick() + 1);

        detectKillEvents();
        detectHealthChange(player);
        detectXpChange(player);
        detectDimensionChange(player);
        detectItemUseChange(player);
        Set<String> newAdvancements = detectAdvancements(mc);

        for (TriggerEvent event : TriggerEventPublisher.drain()) {
            ENGINE.publish(event);
        }

        ENGINE.tick(buildContext(player, mc, newAdvancements));
    }

    // ── 轮询检测 ──────────────────────────────────────────────

    private static void detectKillEvents() {
        for (TriggerEvent event : TriggerEventPublisher.pollKills()) {
            ENGINE.publish(event);
        }
    }

    private static void detectHealthChange(LocalPlayer player) {
        float health = player.getHealth();
        if (!Float.isNaN(lastHealth) && health < lastHealth - 1e-4f) {
            ENGINE.publish(TriggerEvent.valued(TriggerTypes.DAMAGE, lastHealth - health, currentTick()));
        }
        lastHealth = health;
    }

    private static void detectXpChange(LocalPlayer player) {
        int level = player.experienceLevel;
        if (lastLevel >= 0 && level > lastLevel) {
            ENGINE.publish(TriggerEvent.valued(TriggerTypes.XP, level - lastLevel, currentTick()));
        }
        lastLevel = level;
    }

    private static void detectDimensionChange(LocalPlayer player) {
        String dimension = dimensionId(player);
        if (!lastDimension.isEmpty() && !lastDimension.equals(dimension)) {
            ENGINE.publish(TriggerEvent.of(TriggerTypes.DIMENSION_CHANGE, dimension, "", "",
                player.getX(), player.getY(), player.getZ(), currentTick()));
        }
        lastDimension = dimension;
    }

    /**
     * 由 {@code isUsingItem()} 的状态迁移推出三种物品事件：开始使用、中途松手、用完。
     *
     * <p>「用完」的判据是松手时剩余 ticks 已归零；弓/弩这类中途松手的剩余 ticks 必然大于 0。
     */
    private static void detectItemUseChange(LocalPlayer player) {
        boolean using = player.isUsingItem();
        int ticksLeft = player.getUseItemRemainingTicks();
        String item = using ? TriggerEventPublisher.heldItem(player) : lastUsedItemId;

        if (using) {
            if (!lastUsingItem) {
                lastUsedItemId = item;
                ENGINE.publish(TriggerEvent.simple(TriggerTypes.ITEM_USE, item, currentTick()));
            }
        } else if (lastUsingItem) {
            String type = lastUseTicksLeft <= 1 ? TriggerTypes.ITEM_CONSUME : TriggerTypes.ITEM_RELEASE;
            ENGINE.publish(TriggerEvent.simple(type, lastUsedItemId, currentTick()));
        }

        lastUsingItem = using;
        lastUseTicksLeft = ticksLeft;
    }

    /** 返回本 tick 新完成的进度 id 集合。 */
    private static Set<String> detectAdvancements(Minecraft mc) {
        Set<String> fresh = new LinkedHashSet<>();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return fresh;
        }
        var clientAdvancements = listener.getAdvancements();
        if (clientAdvancements == null) {
            return fresh;
        }
        var progressMap = ((ClientAdvancementsAccessor) clientAdvancements).livehelper$getProgress();
        if (progressMap == null) {
            return fresh;
        }
        for (var entry : progressMap.entrySet()) {
            var progress = entry.getValue();
            if (progress == null || !progress.isDone()) continue;
            var key = entry.getKey().getId();
            if (key == null) continue;
            if (DONE_ADVANCEMENTS.add(key.toString())) {
                fresh.add(key.toString());
            }
        }
        return fresh;
    }

    // ── 快照组装 ──────────────────────────────────────────────

    private static TriggerContext buildContext(LocalPlayer player, Minecraft mc, Set<String> newAdvancements) {
        String[] look = lookTarget(mc, player);
        return new TriggerContext(
            player.getX(), player.getY(), player.getZ(),
            dimensionId(player),
            player.getHealth(), player.getMaxHealth(),
            player.experienceLevel, player.totalExperience,
            player.isUsingItem(), player.getUseItemRemainingTicks(),
            newAdvancements,
            look[0], look[1],
            true);
    }

    /** 视线命中的目标：{type, id}，type 为 entity / block / 空串。 */
    private static String[] lookTarget(Minecraft mc, LocalPlayer player) {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() != null) {
            return new String[] {"entity", TriggerEventPublisher.entityId(entityHit.getEntity())};
        }
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            return new String[] {"block", TriggerEventPublisher.blockId(player.level().getBlockState(pos))};
        }
        return new String[] {"", ""};
    }

    private static String dimensionId(LocalPlayer player) {
        ResourceKey<Level> key = player.level().dimension();
        return key == null ? "" : key.location().toString();
    }

    private static long currentTick() {
        return ENGINE.currentTick() + 1;
    }

    // ── 触发动作 ──────────────────────────────────────────────

    /** 由 LiveHelper 初始化时调用一次，把触发结果接到 StreamManager。 */
    public static void install() {
        ENGINE.setFireHandler(ClientTriggerBridge::onRuleFired);
    }

    private static void onRuleFired(TriggerRule rule, TriggerEvent event, TriggerContext context) {
        int managerId = rule.targetManager();
        if (StorageManager.getInstance().getManager(managerId) == null) {
            if (!warnedMissingManager) {
                warnedMissingManager = true;
                LiveHelper.LOGGER.warn("Trigger '{}' targets missing manager #{}; no camera will switch",
                    rule.name(), managerId);
            }
            return;
        }
        // 用 cutTo 而不是 start：触发器要做的是「切过去播一小段」，播完自动回常驻机位。
        boolean asCue = StreamManager.INSTANCE.cutTo(managerId);
        LiveHelper.LOGGER.info("Trigger '{}' ({}) -> manager #{}{}",
            rule.name(), rule.type(), managerId,
            asCue ? " (cue, will auto-return)" : " (started as base)");
    }
}
