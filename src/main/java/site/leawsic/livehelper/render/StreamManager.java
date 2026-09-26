package site.leawsic.livehelper.render;

import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.engine.templates.StaticTrackTemplate;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.storage.StorageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public enum StreamManager {
    INSTANCE;

    private final Map<Integer, StreamInstance> activeStreams = new ConcurrentHashMap<>();

    /**
     * 常驻机位：由 {@link #start} 手动启动，持续推流直到手动停止。
     * <p>取值 -1 表示当前没有常驻机位。
     */
    private volatile int baseManagerId = -1;

    /**
     * 当前切机位（cue）：由触发器或 {@link #cutTo} 产生，时间线播完自动返回常驻机位。
     * <p>取值 -1 表示当前没有切机位。同一时刻只允许一层，不做栈。
     */
    private volatile int cueManagerId = -1;

    private volatile boolean warnedCueWithoutBase = false;

    public void start(int managerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            startOnMainThread(managerId);
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                startOnMainThread(managerId);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LiveHelper.LOGGER.warn("Start manager {} timed out", managerId);
        } catch (CancellationException e) {
            // ignored
        } catch (Exception e) {
            throw new RuntimeException("Failed to start manager " + managerId, e);
        }
    }

    private synchronized void startOnMainThread(int managerId) {
        if (activeStreams.containsKey(managerId)) {
            LiveHelper.LOGGER.warn("Manager {} is already running", managerId);
            return;
        }
        Manager manager = StorageManager.getInstance().getManager(managerId);
        if (manager == null) {
            throw new IllegalArgumentException("Manager not found: " + managerId);
        }
        // 手动启动即声明常驻机位：先释放任何切机位，避免 cue 悬着无处可回。
        releaseCueOnMainThread();
        List<StreamInstance> predecessors = suspendUnlockedStreamsExcept(managerId);
        activeStreams.put(managerId, createInstance(managerId, manager, predecessors));
        baseManagerId = managerId;
        LiveHelper.LOGGER.info("Started stream for manager: {}", manager.name());
    }

    private StreamInstance createInstance(int managerId, Manager manager, List<StreamInstance> predecessors) {
        Map<Integer, Clip> clipCache = new HashMap<>();
        for (var slot : manager.clips()) {
            Clip clip = StorageManager.getInstance().getClip(slot.clipId());
            if (clip != null) clipCache.put(slot.clipId(), clip);
        }
        PlaybackEngine engine = new PlaybackEngine(manager, clipCache);
        return new StreamInstance(managerId, manager, engine, predecessors);
    }

    private List<StreamInstance> suspendUnlockedStreamsExcept(int managerId) {
        List<StreamInstance> suspended = new ArrayList<>();
        for (int activeId : new ArrayList<>(activeStreams.keySet())) {
            if (activeId == managerId) continue;
            Manager activeManager = StorageManager.getInstance().getManager(activeId);
            if (activeManager != null && activeManager.locked()) continue;
            StreamInstance instance = activeStreams.remove(activeId);
            if (instance == null) continue;
            instance.suspend();
            suspended.add(instance);
            LiveHelper.LOGGER.info("Suspended unlocked manager {} before starting {}", activeId, managerId);
        }
        return suspended;
    }

    // ── 切机位（cue）──────────────────────────────────────────

    /**
     * 切到某个机位播一小段，播完自动返回常驻机位。触发器走这条路径。
     *
     * <p>与 {@link #start} 的区别正是「切换」与「重播」的差别：start 之后该 Manager
     * 会一直推流直到手动停止，而 cutTo 只是临时接管镜头。
     *
     * <p>三种退化情况：
     * <ul>
     *   <li>常驻机位是 locked——那是「多机位并行推流、OBS 里各占一个源」的语义，
     *       没有「当前画面是谁」可言，因此退化为普通 start。</li>
     *   <li>根本没有常驻机位——退化为普通 start，这样「只用触发器驱动」也能工作，
     *       但镜头不会自动回来（没有可回的目标）。</li>
     *   <li>切回常驻机位本身——等价于清除当前 cue。</li>
     * </ul>
     *
     * @return 是否按「切机位」处理；false 表示退化成常驻启动
     */
    public boolean cutTo(int managerId) {
        if (managerId <= 0) return false;
        if (managerId == cueManagerId) return true;

        Manager target = StorageManager.getInstance().getManager(managerId);
        if (target == null) {
            LiveHelper.LOGGER.warn("cutTo: manager #{} not found", managerId);
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            return cutToOnMainThread(managerId, target);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(cutToOnMainThread(managerId, target));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LiveHelper.LOGGER.warn("cutTo manager {} timed out or failed", managerId, e);
            return false;
        }
    }

    private synchronized boolean cutToOnMainThread(int managerId, Manager target) {
        Manager base = baseManagerId > 0 ? StorageManager.getInstance().getManager(baseManagerId) : null;

        if (base != null && base.locked()) {
            LiveHelper.LOGGER.info("cutTo: base manager #{} is locked (multi-cam); starting #{} as a base instead",
                baseManagerId, managerId);
            startOnMainThread(managerId);
            return false;
        }
        if (base == null) {
            if (!warnedCueWithoutBase) {
                warnedCueWithoutBase = true;
                LiveHelper.LOGGER.warn("cutTo: no base manager running; #{} becomes the base and will not auto-return. "
                    + "Start a base with /livehelper start first to get auto-return.", managerId);
            }
            startOnMainThread(managerId);
            return false;
        }
        if (managerId == baseManagerId) {
            clearCueOnMainThread();
            return true;
        }

        releaseCueOnMainThread();

        StreamInstance baseInstance = activeStreams.get(baseManagerId);
        if (baseInstance != null) {
            baseInstance.pauseForCue();
        }
        // cue 不挂 predecessor：常驻机位要保持可恢复，而 predecessor 交接完成后会被关闭。
        // 常驻机位的最后一帧此刻仍在 persistent context 里，cue 首帧同 tick 覆盖，不闪黑。
        activeStreams.put(managerId, createInstance(managerId, target, List.of()));
        cueManagerId = managerId;
        LiveHelper.LOGGER.info("Cut to manager #{} ({}), base #{}", managerId, target.name(), baseManagerId);
        return true;
    }

    /** 立即返回常驻机位。 */
    public void returnToBase() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            clearCueOnMainThread();
            return;
        }
        mc.execute(this::clearCueOnMainThread);
    }

    private synchronized void clearCueOnMainThread() {
        if (cueManagerId <= 0) return;
        int returning = cueManagerId;
        cueManagerId = -1;

        StreamInstance cue = activeStreams.remove(returning);
        if (cue != null) {
            cue.close();
        }
        StreamInstance base = activeStreams.get(baseManagerId);
        if (base != null) {
            // 先关 cue（会清空 context）再恢复 base（立刻补回最后一帧），中间不闪玩家视角。
            base.resumeFromCue();
            LiveHelper.LOGGER.info("Cue manager #{} ended; returned to base #{}", returning, baseManagerId);
        } else {
            LiveHelper.LOGGER.info("Cue manager #{} ended; base #{} is gone, nothing to return to",
                returning, baseManagerId);
            baseManagerId = -1;
        }
    }

    /** 关闭当前 cue，不触碰常驻机位。 */
    private void releaseCueOnMainThread() {
        if (cueManagerId <= 0) return;
        StreamInstance cue = activeStreams.remove(cueManagerId);
        cueManagerId = -1;
        if (cue != null) {
            cue.close();
        }
    }

    public int getBaseManagerId() {
        return baseManagerId;
    }

    public int getCueManagerId() {
        return cueManagerId;
    }

    public void stop(int managerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            stopOnMainThread(managerId);
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                stopOnMainThread(managerId);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LiveHelper.LOGGER.warn("Stop manager {} timed out", managerId);
        } catch (CancellationException e) {
            // ignored
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop manager " + managerId, e);
        }
    }

    private synchronized void stopOnMainThread(int managerId) {
        StreamInstance instance = activeStreams.remove(managerId);
        if (instance != null) {
            instance.close();
            LiveHelper.LOGGER.info("Stopped stream for manager: {}", managerId);
        }
        if (managerId == cueManagerId) cueManagerId = -1;
        if (managerId == baseManagerId) baseManagerId = -1;
    }

    public synchronized void stopAll() {
        for (int id : new ArrayList<>(activeStreams.keySet())) {
            StreamInstance instance = activeStreams.get(id);
            if (instance != null) {
                instance.close();
                activeStreams.remove(id);
            }
        }
        cueManagerId = -1;
        baseManagerId = -1;
        StaticTrackTemplate.resetAllStates();
    }

    public boolean hasActive() {
        return !activeStreams.isEmpty();
    }

    public StreamStatus getStatus(int managerId) {
        return activeStreams.containsKey(managerId) ? StreamStatus.RUNNING : StreamStatus.STOPPED;
    }

    public Set<Integer> getActiveStreamIds() {
        return activeStreams.keySet();
    }

    public void prepareDueFrames() {
        if (activeStreams.isEmpty()) return;
        long nowNs = System.nanoTime();
        for (StreamInstance instance : activeStreams.values()) {
            instance.prepareFrameIfDue(nowNs);
        }
        checkCueCompletion();
    }

    /**
     * 切机位的时间线走完（非循环）就自动返回常驻机位。
     *
     * <p>这就是「触发器是切换而不是重播」的落点：cue 播完即回，镜头回到常驻全景，
     * 下一次触发再切过去。循环的 cue 永远不会自动返回——那是用户明确要常驻的选择。
     */
    private void checkCueCompletion() {
        int cue = cueManagerId;
        if (cue <= 0) return;
        StreamInstance instance = activeStreams.get(cue);
        if (instance == null) {
            cueManagerId = -1;
            return;
        }
        if (instance.isTimelineFinished()) {
            clearCueOnMainThread();
        }
    }

    public void sendPreparedFrames() {
        if (activeStreams.isEmpty()) return;
        for (StreamInstance instance : activeStreams.values()) {
            instance.sendPreparedFrame();
        }
    }

    public enum StreamStatus {
        RUNNING, STOPPED
    }
}
