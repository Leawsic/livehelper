package site.leawsic.livehelper.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.engine.templates.StaticTrackTemplate;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.spout.SpoutSender;
import site.leawsic.livehelper.storage.StorageManager;
import site.leawsic.livehelper.trigger.TriggerRule;
import site.leawsic.livehelper.util.ActiveRenderContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 推流调度：全流程只有<b>一个</b> Spout sender，Manager 只决定「往这条流里推什么画面」。
 *
 * <p>这是刻意的架构取舍。早期实现是「每个 Manager 一个 sender」，于是 OBS 那边会同时出现
 * 多个 Spout sender，源选哪个就成了 OBS 侧的选择题：切机位瞬间新旧 sender 并存，
 * 一旦选错或旧 sender 没被及时注销，画面就会一直停在错的机位上，而且没有任何代码层面的
 * 办法可靠地纠正 OBS 的选择。
 *
 * <p>收束成单流之后，OBS 永远只看到一个固定名字的 sender，切换机位变成「改变推流内容」
 * 而不是「切换 sender」，上述整类问题从根上消失。
 *
 * <p>机位模型：
 * <ul>
 *   <li><b>常驻机位</b>（base）：{@link #start} 手动启动，持续推流直到手动停止。</li>
 *   <li><b>切机位</b>（cue）：触发器或 {@link #cutTo} 产生，时间线播完自动返回常驻机位。</li>
 * </ul>
 * 同一时刻只有「当前输出拥有者」会写入渲染上下文并推流，拥有者为 cue 优先、其次常驻机位。
 */
public enum StreamManager {
    INSTANCE;

    /**
     * 唯一的 Spout sender 名字。OBS 的 Spout2 Capture 源应当固定选择这个名字。
     * 改这个值会让 OBS 里已选的源失效，需要重新选一次。
     */
    public static final String SENDER_NAME = "LiveHelper";

    private final Map<Integer, StreamInstance> streams = new ConcurrentHashMap<>();

    /** 常驻机位；-1 表示当前没有。 */
    private volatile int baseManagerId = -1;
    /** 当前切机位；-1 表示当前没有。 */
    private volatile int cueManagerId = -1;

    /** 唯一的 Spout sender，懒创建。 */
    private SpoutSender sender;
    /** 本帧是否已产出可推送的画面。 */
    private boolean frameReady;
    private boolean warnedNoBase = false;
    private boolean warnedMultipleRunning = false;

    // ── 常驻机位 ─────────────────────────────────────────────

    public void start(int managerId) {
        runOnMainThread(() -> startOnMainThread(managerId), "start");
    }

    private synchronized void startOnMainThread(int managerId) {
        if (streams.containsKey(managerId)) {
            LiveHelper.LOGGER.warn("Manager #{} is already running", managerId);
            return;
        }
        Manager manager = StorageManager.getInstance().getManager(managerId);
        if (manager == null) {
            throw new IllegalArgumentException("Manager not found: " + managerId);
        }
        releaseCueOnMainThread();
        stopUnlockedExcept(managerId);
        warnIfMultipleRunning(managerId);
        streams.put(managerId, new StreamInstance(managerId, manager, newEngine(manager)));
        baseManagerId = managerId;
        LiveHelper.LOGGER.info("Started base manager #{} ({}), Spout sender '{}'",
            managerId, manager.name(), SENDER_NAME);
    }

    // ── 切机位 ───────────────────────────────────────────────

    /**
     * 切到某个机位播一小段，播完自动返回常驻机位。触发器走这条路径。
     *
     * <p>与 {@link #start} 的区别正是「切换」与「重播」的差别：start 之后该 Manager 会一直
     * 推流直到手动停止；cutTo 只是临时接管画面。
     *
     * @return 是否按「切机位」处理；false 表示退化成常驻启动（没有可返回的目标）
     */
    public boolean cutTo(int managerId) {
        return cutToResult(managerId, "cutTo");
    }

    private boolean cutToResult(int managerId, String op) {
        if (managerId <= 0) return false;
        if (managerId == cueManagerId) return true;
        Manager target = StorageManager.getInstance().getManager(managerId);
        if (target == null) {
            LiveHelper.LOGGER.warn("{}: manager #{} not found", op, managerId);
            return false;
        }
        if (Minecraft.getInstance().isSameThread()) {
            return cutToOnMainThread(managerId, target);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(cutToOnMainThread(managerId, target));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LiveHelper.LOGGER.warn("{} manager #{} timed out or failed", op, managerId, e);
            return false;
        }
    }

    private synchronized boolean cutToOnMainThread(int managerId, Manager target) {
        if (baseManagerId <= 0 || !streams.containsKey(baseManagerId)) {
            if (!warnedNoBase) {
                warnedNoBase = true;
                LiveHelper.LOGGER.warn("No base manager running; manager #{} becomes the base and will not auto-return. "
                    + "Run /livehelper start <id> first to get auto-return.", managerId);
            }
            startOnMainThread(managerId);
            return false;
        }
        if (managerId == baseManagerId) {
            clearCueOnMainThread();
            return true;
        }

        releaseCueOnMainThread();

        // 常驻机位一律暂停，与其 locked 无关：locked 的含义是「别在我启动时把我停掉」，
        // 而暂停恰好满足这个意图。早前对 locked 的常驻机位退化成 start()，导致常驻机位
        // 根本没被暂停、反而多出一个常驻流。
        StreamInstance base = streams.get(baseManagerId);
        if (base != null) {
            base.pauseForCue();
        }
        streams.put(managerId, new StreamInstance(managerId, target, newEngine(target)));
        cueManagerId = managerId;
        LiveHelper.LOGGER.info("Cut to manager #{} ({}), base #{} paused", managerId, target.name(), baseManagerId);
        return true;
    }

    /** 立即返回常驻机位。 */
    public void returnToBase() {
        runOnMainThread(this::clearCueOnMainThread, "returnToBase");
    }

    private synchronized void clearCueOnMainThread() {
        if (cueManagerId <= 0) return;
        int returning = cueManagerId;
        cueManagerId = -1;
        StreamInstance cue = streams.remove(returning);
        if (cue != null) {
            cue.stop();
        }
        StreamInstance base = streams.get(baseManagerId);
        if (base != null) {
            base.resumeFromCue();
            LiveHelper.LOGGER.info("Cue manager #{} ended; returned to base #{}", returning, baseManagerId);
        } else {
            LiveHelper.LOGGER.info("Cue manager #{} ended; base is gone, nothing to return to", returning);
            baseManagerId = -1;
        }
    }

    /** 关闭当前切机位，不触碰常驻机位。 */
    private void releaseCueOnMainThread() {
        if (cueManagerId <= 0) return;
        StreamInstance cue = streams.remove(cueManagerId);
        cueManagerId = -1;
        if (cue != null) {
            cue.stop();
        }
    }

    // ── 停止 ─────────────────────────────────────────────────

    public void stop(int managerId) {
        runOnMainThread(() -> {
            StreamInstance instance = streams.remove(managerId);
            if (instance != null) {
                instance.stop();
            }
            if (managerId == cueManagerId) cueManagerId = -1;
            if (managerId == baseManagerId) baseManagerId = -1;
            // 停掉的若是当前输出拥有者且已无其他机位，立刻交还镜头。
            if (streams.isEmpty()) {
                frameReady = false;
                ActiveRenderContext.clearPersistent();
            }
            LiveHelper.LOGGER.info("Stopped manager #{}", managerId);
        }, "stop");
    }

    public void stopAll() {
        runOnMainThread(() -> {
            for (StreamInstance instance : new ArrayList<>(streams.values())) {
                instance.stop();
            }
            streams.clear();
            cueManagerId = -1;
            baseManagerId = -1;
            frameReady = false;
            // 主动交还镜头，不依赖下一帧的 prepareDueFrames 去发现 streams 已空。
            ActiveRenderContext.clearPersistent();
            closeSender();
            StaticTrackTemplate.resetAllStates();
        }, "stopAll");
    }

    // ── 每帧 ─────────────────────────────────────────────────

    /**
     * 当前输出拥有者：切机位优先，其次常驻机位。
     *
     * <p>渲染上下文是全局唯一的，所以必须有明确的拥有者，否则多个实例会互相覆盖。
     */
    private int outputOwnerId() {
        int cue = cueManagerId;
        if (cue > 0 && streams.containsKey(cue)) return cue;
        int base = baseManagerId;
        if (base > 0 && streams.containsKey(base)) return base;
        return -1;
    }

    public void prepareDueFrames() {
        if (streams.isEmpty()) {
            // 必须在没有拥有者时清空渲染上下文：它是全局静态的，
            // 最后一个 Manager 停止后若不清掉，CameraSetup 会永远照着残留的最后一帧
            // 接管镜头，表现为「推流已停但玩家视角仍被控制」。
            frameReady = false;
            ActiveRenderContext.clearPersistent();
            return;
        }
        // 先结算切机位：可能关闭 cue 并恢复常驻机位，恢复后本轮就能补上它的画面，不闪帧。
        checkCueCompletion();

        long nowNs = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            frameReady = false;
            ActiveRenderContext.clearPersistent();
            return;
        }

        int owner = outputOwnerId();
        if (owner < 0) {
            ActiveRenderContext.clearPersistent();
            frameReady = false;
            return;
        }

        StreamInstance instance = streams.get(owner);
        if (instance == null) {
            ActiveRenderContext.clearPersistent();
            frameReady = false;
            return;
        }

        StreamInstance.Frame frame = instance.pollFrame(nowNs);
        switch (frame.status()) {
            case PRODUCED -> {
                ActiveRenderContext.setPersistent(frame.command(),
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), instance.renderDistance());
                frameReady = true;
            }
            case NOT_DUE -> {
                // 没到本 Manager 的产出时机：保持现有渲染上下文不动。
                // Manager 的 fps 低于游戏渲染帧率时，每两次产出之间会经过多个 MC 帧，
                // 若在这里清空上下文，MC 画面就会在相机视角与玩家视角之间来回闪。
                // frameReady 保持原值：画面已经在上下文里，不需要重复推送同一帧。
            }
            case EMPTY -> {
                // 到了产出时机但时间线上没有活跃片段：交回玩家视角。
                ActiveRenderContext.clearPersistent();
                frameReady = false;
            }
        }
    }

    /**
     * 切机位时间线走完（且未开启循环、未设 locked）就自动返回常驻机位。
     */
    private void checkCueCompletion() {
        int cue = cueManagerId;
        if (cue <= 0) return;
        StreamInstance instance = streams.get(cue);
        if (instance == null) {
            cueManagerId = -1;
            return;
        }
        Manager manager = StorageManager.getInstance().getManager(cue);
        if (manager != null && manager.locked()) return;
        if (instance.isTimelineFinished()) {
            clearCueOnMainThread();
        }
    }

    public void sendPreparedFrames() {
        if (!frameReady || streams.isEmpty()) return;
        try {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            sender().send(target.frameBufferId, target.width, target.height);
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Failed to send Spout frame", e);
        }
    }

    // ── 状态查询 ─────────────────────────────────────────────

    public boolean hasActive() {
        return !streams.isEmpty();
    }

    public StreamStatus getStatus(int managerId) {
        return streams.containsKey(managerId) ? StreamStatus.RUNNING : StreamStatus.STOPPED;
    }

    public Set<Integer> getActiveStreamIds() {
        return new HashSet<>(streams.keySet());
    }

    public int getBaseManagerId() {
        return baseManagerId;
    }

    public int getCueManagerId() {
        return cueManagerId;
    }

    public int getOutputOwnerId() {
        return outputOwnerId();
    }

    // ── 内部 ─────────────────────────────────────────────────

    private PlaybackEngine newEngine(Manager manager) {
        Map<Integer, Clip> clipCache = new HashMap<>();
        for (var slot : manager.clips()) {
            Clip clip = StorageManager.getInstance().getClip(slot.clipId());
            if (clip != null) clipCache.put(slot.clipId(), clip);
        }
        return new PlaybackEngine(manager, clipCache);
    }

    private void stopUnlockedExcept(int keepManagerId) {
        for (int id : new ArrayList<>(streams.keySet())) {
            if (id == keepManagerId) continue;
            Manager manager = StorageManager.getInstance().getManager(id);
            if (manager != null && manager.locked()) continue;
            StreamInstance instance = streams.remove(id);
            if (instance != null) {
                instance.stop();
            }
        }
    }

    private void warnIfMultipleRunning(int aboutToStart) {
        if (warnedMultipleRunning || streams.size() < 1) return;
        warnedMultipleRunning = true;
        LiveHelper.LOGGER.warn("More than one manager is running. Spout output is now a single stream, so only the "
            + "active camera (cut manager, else base) is sent; locked managers no longer produce separate OBS sources.");
    }

    private synchronized SpoutSender sender() {
        if (sender == null) {
            sender = new SpoutSender(SENDER_NAME);
        }
        return sender;
    }

    private synchronized void closeSender() {
        if (sender != null) {
            sender.close();
            sender = null;
        }
    }

    private void runOnMainThread(Runnable action, String op) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            action.run();
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LiveHelper.LOGGER.warn("{} timed out", op);
        } catch (CancellationException e) {
            // ignored
        } catch (Exception e) {
            LiveHelper.LOGGER.error("{} failed", op, e);
        }
    }

    public enum StreamStatus {
        RUNNING, STOPPED
    }
}
