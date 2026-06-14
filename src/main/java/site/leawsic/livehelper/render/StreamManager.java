package site.leawsic.livehelper.render;

import net.minecraft.client.Minecraft;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.storage.StorageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public enum StreamManager {
    INSTANCE;

    private final Map<Integer, StreamInstance> activeStreams = new ConcurrentHashMap<>();
    private final Set<Integer> pendingStarts = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pendingStops = ConcurrentHashMap.newKeySet();

    public void start(int managerId) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            if (activeStreams.containsKey(managerId) || !pendingStarts.add(managerId)) return;
            mc.execute(() -> {
                pendingStarts.remove(managerId);
                startOnMainThread(managerId);
            });
            return;
        }
        startOnMainThread(managerId);
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

        Map<Integer, Clip> clipCache = new HashMap<>();
        for (var slot : manager.clips()) {
            Clip clip = StorageManager.getInstance().getClip(slot.clipId());
            if (clip != null) {
                clipCache.put(slot.clipId(), clip);
            }
        }

        PlaybackEngine engine = new PlaybackEngine(manager, clipCache);
        StreamInstance instance = new StreamInstance(managerId, manager, engine);
        activeStreams.put(managerId, instance);
        LiveHelper.LOGGER.info("Started stream for manager: {}", manager.name());
    }

    public void stop(int managerId) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            pendingStarts.remove(managerId);
            if (!pendingStops.add(managerId)) return;
            mc.execute(() -> {
                pendingStops.remove(managerId);
                stopOnMainThread(managerId);
            });
            return;
        }
        stopOnMainThread(managerId);
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
            stop(id);
        }
        pendingStarts.clear();
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
        RUNNING,
        STOPPED,
        ERROR
    }
}
