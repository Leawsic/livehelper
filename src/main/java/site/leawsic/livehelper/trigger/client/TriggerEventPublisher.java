package site.leawsic.livehelper.trigger.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import site.leawsic.livehelper.trigger.TriggerEvent;
import site.leawsic.livehelper.trigger.TriggerTypes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mixin 与纯引擎之间的唯一通道。
 *
 * <p>mixin 里不允许出现业务逻辑，所以这一层只做两件事：把 Minecraft 对象翻译成
 * {@link TriggerEvent}（纯数据），以及记账攻击过的实体以便判定击杀。
 *
 * <p>事件先进队列，由 {@link ClientTriggerBridge} 在 client tick 里取出——这样即使
 * 交互发生在渲染线程的其他时刻，也不会让引擎在非 tick 上下文里被调用。
 */
public final class TriggerEventPublisher {

    private TriggerEventPublisher() {}

    /** 上限与引擎的事件队列保护一致，避免极端情况下无限堆积。 */
    private static final int MAX_QUEUE = 256;
    /** 同时追踪的「被攻击过的实体」数量上限。 */
    private static final int MAX_TRACKED = 32;
    /** 一次攻击后多久判定为「这次击杀算我的」，单位 tick。 */
    private static final long KILL_WINDOW_TICKS = 100L;

    private static final Deque<TriggerEvent> QUEUE = new ArrayDeque<>();
    /** 被攻击过的实体：entityId -> 追踪信息。 */
    private static final Map<Integer, PendingKill> PENDING = new LinkedHashMap<>();

    private static long currentTick = 0L;

    private record PendingKill(Entity entity, String typeId, long lastAttackTick) {}

    public static void beginTick(long tick) {
        currentTick = tick;
    }

    // ── mixin 入口 ────────────────────────────────────────────

    public static void onAttack(Player player, Entity target) {
        if (player == null || target == null) return;
        String typeId = entityId(target);
        if (typeId.isEmpty()) return;

        synchronized (QUEUE) {
            if (PENDING.size() >= MAX_TRACKED) {
                PENDING.entrySet().iterator().next();
                PENDING.remove(PENDING.keySet().iterator().next());
            }
            PENDING.put(target.getId(), new PendingKill(target, typeId, currentTick));
            offer(TriggerEvent.of(TriggerTypes.ENTITY_ATTACK, typeId, "", heldItem(player),
                target.getX(), target.getY(), target.getZ(), currentTick));
        }
    }

    public static void onEntityInteract(Player player, Entity target, InteractionHand hand) {
        if (player == null || target == null) return;
        String typeId = entityId(target);
        String item = heldItem(player, hand);
        String type = item.isEmpty() ? TriggerTypes.ENTITY_INTERACT : TriggerTypes.ITEM_ON_INTERACT;
        offer(TriggerEvent.of(type, typeId, "", item, target.getX(), target.getY(), target.getZ(), currentTick));
    }

    public static void onBlockInteract(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player == null || hit == null) return;
        BlockState state = player.level().getBlockState(hit.getBlockPos());
        String blockId = blockId(state);
        String item = heldItem(player, hand);
        String type = item.isEmpty() ? TriggerTypes.BLOCK_INTERACT : TriggerTypes.ITEM_ON_INTERACT;
        offer(TriggerEvent.of(type, "", blockId, item,
            hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ(), currentTick));
    }

    public static void onItemUse(Player player, InteractionHand hand) {
        if (player == null) return;
        offer(TriggerEvent.simple(TriggerTypes.ITEM_USE, heldItem(player, hand), currentTick));
    }

    public static void onItemRelease(Player player, ItemStack released) {
        if (player == null) return;
        String item = itemId(released);
        // 松手时已不再处于使用状态，因此归类为 release；若物品已被用尽则由轮询侧判 consume。
        offer(TriggerEvent.simple(TriggerTypes.ITEM_RELEASE, item, currentTick));
    }

    // ── 轮询侧事件（由 ClientTriggerBridge 调用）────────────────

    public static void offer(TriggerEvent event) {
        synchronized (QUEUE) {
            if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
            QUEUE.addLast(event);
        }
    }

    /** 取出本 tick 积累的事件。 */
    public static TriggerEvent[] drain() {
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) return new TriggerEvent[0];
            TriggerEvent[] out = QUEUE.toArray(new TriggerEvent[0]);
            QUEUE.clear();
            return out;
        }
    }

    /**
     * 检查被攻击过的实体是否已死亡或从世界消失，命中则产出 entity_kill 事件。
     *
     * <p>客户端拿不到「服务端判定击杀」的结果，只能用「我打过它 + 它死了/没了」来近似。
     * 对直播用途足够：观众看到的是画面，而画面上确实倒了。
     *
     * @return 本 tick 检出的击杀事件
     */
    public static TriggerEvent[] pollKills() {
        java.util.List<TriggerEvent> kills = new java.util.ArrayList<>();
        synchronized (QUEUE) {
            java.util.Iterator<Map.Entry<Integer, PendingKill>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, PendingKill> entry = it.next();
                PendingKill pending = entry.getValue();
                Entity entity = pending.entity();
                boolean gone = entity == null
                    || !entity.isAlive()
                    || entity.isRemoved()
                    || entity.level() == null;
                boolean expired = currentTick - pending.lastAttackTick() > KILL_WINDOW_TICKS;

                if (gone) {
                    kills.add(TriggerEvent.simple(TriggerTypes.ENTITY_KILL, pending.typeId(), currentTick));
                    it.remove();
                } else if (expired) {
                    it.remove();
                }
            }
        }
        return kills.toArray(new TriggerEvent[0]);
    }

    /** 离开世界时清空记账，避免跨世界误判。 */
    public static void reset() {
        synchronized (QUEUE) {
            QUEUE.clear();
            PENDING.clear();
        }
    }

    // ── 标识翻译 ──────────────────────────────────────────────

    public static String entityId(Entity entity) {
        if (entity == null) return "";
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "" : key.toString();
    }

    public static String blockId(BlockState state) {
        if (state == null) return "";
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "" : key.toString();
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    public static String heldItem(Player player) {
        return heldItem(player, InteractionHand.MAIN_HAND);
    }

    public static String heldItem(Player player, InteractionHand hand) {
        if (player == null) return "";
        return itemId(player.getItemInHand(hand));
    }
}
