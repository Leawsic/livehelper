package site.leawsic.livehelper.render;

import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
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
        List<StreamInstance> predecessors = suspendUnlockedStreamsExcept(managerId);
        Map<Integer, Clip> clipCache = new HashMap<>();
        for (var slot : manager.clips()) {
            Clip clip = StorageManager.getInstance().getClip(slot.clipId());
            if (clip != null) clipCache.put(slot.clipId(), clip);
        }
        PlaybackEngine engine = new PlaybackEngine(manager, clipCache);
        StreamInstance instance = new StreamInstance(managerId, manager, engine, predecessors);
        activeStreams.put(managerId, instance);
        LiveHelper.LOGGER.info("Started stream for manager: {}", manager.name());
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
    }

    public synchronized void stopAll() {
        for (int id : new ArrayList<>(activeStreams.keySet())) {
            StreamInstance instance = activeStreams.get(id);
            if (instance != null) {
                instance.close();
                activeStreams.remove(id);
            }
        }
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

    public void tickAll() {
        for (StreamInstance instance : activeStreams.values()) {
            instance.tick();
        }
    }

    public enum StreamStatus {
        RUNNING, STOPPED
    }
}
