package site.leawsic.livehelper.render;

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

    public synchronized void start(int managerId) {
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

    public synchronized void stop(int managerId) {
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
