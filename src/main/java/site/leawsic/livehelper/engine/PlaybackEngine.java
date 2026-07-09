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
        long totalDuration = totalDuration();
        if (manager.loop() && totalDuration > 0L) {
            elapsedMs %= totalDuration;
        }
        List<ClipSlot> slots = manager.clips();

        for (int i = 0; i < slots.size(); i++) {
            ClipSlot slot = slots.get(i);
            Clip clip = clipCache.get(slot.clipId());
            if (clip == null) continue;

            long clipStart = slot.startOffset();
            long clipEnd = clipStart + clip.duration();
            if (elapsedMs >= clipStart && elapsedMs < clipEnd) {
                long clipElapsed = elapsedMs - clipStart;
                FrameCommand current = evaluateSlot(slot, clip, clipElapsed);
                long transitionDuration = Math.min(Math.max(slot.transitionDuration(), 0L), clip.duration());
                ClipSlot previousSlot = i > 0 ? slots.get(i - 1) : null;
                Clip previousClip = previousSlot != null ? clipCache.get(previousSlot.clipId()) : null;
                if (transitionDuration > 0 && previousClip != null && clipElapsed < transitionDuration) {
                    FrameCommand previous = evaluateSlot(previousSlot, previousClip, previousClip.duration());
                    float amount = applyEasing((float) clipElapsed / (float) transitionDuration, slot.transitionEasing());
                    return blend(previous, current, amount);
                }
                return current;
            }
        }

        return null;
    }

    private FrameCommand evaluateSlot(ClipSlot slot, Clip clip, long clipElapsedMs) {
        float progress = (float) clipElapsedMs / (float) clip.duration();
        progress = Math.min(Math.max(progress, 0f), 0.9999f);

        if ("PATH".equals(clip.template()) && pathCache.containsKey(slot.clipId())) {
            float fov = MotionTemplates.pf(clip.params(), "fov", 70f);
            return PathTemplate.evaluateFromKeyframes(pathCache.get(slot.clipId()), progress, fov);
        }

        MotionTemplate template = MotionTemplates.get(clip.template());
        return template.evaluate(clip.params(), progress);
    }

    private static FrameCommand blend(FrameCommand from, FrameCommand to, float amount) {
        float qx = lerp(from.qx(), to.qx(), amount);
        float qy = lerp(from.qy(), to.qy(), amount);
        float qz = lerp(from.qz(), to.qz(), amount);
        float qw = lerp(from.qw(), to.qw(), amount);
        float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (length > 0.0001f) {
            qx /= length;
            qy /= length;
            qz /= length;
            qw /= length;
        }
        return new FrameCommand(
            lerp(from.x(), to.x(), amount),
            lerp(from.y(), to.y(), amount),
            lerp(from.z(), to.z(), amount),
            qx,
            qy,
            qz,
            qw,
            lerp(from.fov(), to.fov(), amount),
            from.hasEulerAngles() && to.hasEulerAngles() ? lerp(from.pitch(), to.pitch(), amount) : Float.NaN,
            from.hasEulerAngles() && to.hasEulerAngles() ? lerp(from.yaw(), to.yaw(), amount) : Float.NaN,
            from.hasEulerAngles() && to.hasEulerAngles() ? lerp(from.roll(), to.roll(), amount) : Float.NaN
        );
    }

    private static float applyEasing(float t, String easing) {
        t = Math.min(Math.max(t, 0f), 1f);
        if ("easeIn".equals(easing)) {
            return t * t;
        }
        if ("easeOut".equals(easing)) {
            return 1f - (1f - t) * (1f - t);
        }
        if ("easeInOut".equals(easing)) {
            return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
        }
        return t;
    }

    private static double lerp(double from, double to, float amount) {
        return from + (to - from) * amount;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    public boolean isFinished() {
        long elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L;
        if (manager.loop()) return false;
        return elapsedMs >= totalDuration();
    }

    private long totalDuration() {
        long totalDuration = 0L;
        for (ClipSlot slot : manager.clips()) {
            Clip clip = clipCache.get(slot.clipId());
            if (clip != null) {
                totalDuration = Math.max(totalDuration, slot.startOffset() + clip.duration());
            }
        }
        return totalDuration;
    }
}
