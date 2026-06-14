package site.leawsic.livehelper.engine;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.engine.templates.MotionTemplate;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.engine.templates.PathTemplate;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaybackEngine {
    private static final Gson GSON = new Gson();
    private static final Type KEYFRAME_LIST_TYPE = new TypeToken<List<PathTemplate.Keyframe>>() {}.getType();

    private final Manager manager;
    private final long startTimeNs;
    private final Map<Integer, Clip> clipCache;
    private final Map<Integer, List<PathTemplate.Keyframe>> pathCache = new HashMap<>();

    public PlaybackEngine(Manager manager, Map<Integer, Clip> clipCache) {
        this.manager = manager;
        this.startTimeNs = System.nanoTime();
        this.clipCache = clipCache;

        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip != null && "PATH".equals(clip.template())) {
                parsePathKeyframes(slot.clipId(), clip.params().get("keyframes"));
            }
        }
    }

    private void parsePathKeyframes(int clipId, Object raw) {
        if (raw == null) return;
        try {
            JsonElement element;
            if (raw instanceof String string) {
                element = GSON.fromJson(string, JsonElement.class);
            } else {
                element = GSON.toJsonTree(raw);
            }
            List<PathTemplate.Keyframe> keyframes = GSON.fromJson(element, KEYFRAME_LIST_TYPE);
            if (keyframes != null && !keyframes.isEmpty()) {
                pathCache.put(clipId, keyframes);
            }
        } catch (Exception e) {
            LiveHelper.LOGGER.warn("Failed to parse PATH keyframes for clip {}", clipId, e);
        }
    }

    public FrameCommand computeFrame() {
        long elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L;

        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip == null) continue;

            long clipStart = slot.startOffset();
            long clipEnd = clipStart + clip.duration();
            if (elapsedMs >= clipStart && elapsedMs < clipEnd) {
                float progress = (float) (elapsedMs - clipStart) / (float) clip.duration();
                progress = Math.min(progress, 0.9999f);

                if ("PATH".equals(clip.template()) && pathCache.containsKey(slot.clipId())) {
                    float fov = MotionTemplates.pf(clip.params(), "fov", 70f);
                    return PathTemplate.evaluateFromKeyframes(pathCache.get(slot.clipId()), progress, fov);
                }

                MotionTemplate template = MotionTemplates.get(clip.template());
                return template.evaluate(clip.params(), progress);
            }
        }

        return null;
    }

    public boolean isFinished() {
        long elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L;
        long totalDuration = 0L;
        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip != null) {
                totalDuration = Math.max(totalDuration, slot.startOffset() + clip.duration());
            }
        }
        return elapsedMs >= totalDuration;
    }
}
