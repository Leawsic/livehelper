package site.leawsic.livehelper.engine.templates;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

public class StaticTrackTemplate implements MotionTemplate {
    private final Map<String, TrackState> states = new HashMap<>();

    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        double x = p(params, "posX", 0.0);
        double y = p(params, "posY", 0.0);
        double z = p(params, "posZ", 0.0);
        float fallbackPitch = pf(params, "rotX", 0f);
        float fallbackYaw = pf(params, "rotY", 0f);
        float fallbackRoll = pf(params, "rotZ", 0f);
        float fov = pf(params, "fov", 70f);

        Entity target = findTarget(params);
        if (target == null) {
            Quaternionf fallback = AngleConvert.toQuaternion(fallbackPitch, fallbackYaw, fallbackRoll);
            return new FrameCommand(x, y, z, fallback.x, fallback.y, fallback.z, fallback.w, fov,
                fallbackPitch, fallbackYaw, fallbackRoll);
        }

        double yOffset = p(params, "targetYOffset", 0.0);
        float[] angles = lookAngles(x, y, z, target.getX(), target.getEyeY() + yOffset, target.getZ());
        float pitch = angles[0];
        float yaw = angles[1];
        float trackSpeed = Math.max(0f, pf(params, "trackSpeed", 8f));
        if (trackSpeed > 0f) {
            float initialPitch = pitch;
            float initialYaw = yaw;
            TrackState state = states.computeIfAbsent(stateKey(target, x, y, z), key -> new TrackState(initialPitch, initialYaw));
            long now = System.nanoTime();
            float deltaSeconds = state.lastNs == 0L ? 0f : Math.min((now - state.lastNs) / 1_000_000_000f, 0.25f);
            state.lastNs = now;
            float amount = deltaSeconds <= 0f ? 1f : 1f - (float) Math.exp(-trackSpeed * deltaSeconds);
            state.pitch = lerpAngle(state.pitch, pitch, amount);
            state.yaw = lerpAngle(state.yaw, yaw, amount);
            pitch = state.pitch;
            yaw = state.yaw;
        }

        Quaternionf q = AngleConvert.toQuaternion(pitch, yaw, 0f);
        return new FrameCommand(x, y, z, q.x, q.y, q.z, q.w, fov, pitch, yaw, 0f);
    }

    private static float[] lookAngles(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontalDist));
        return new float[] {pitch, yaw};
    }

    private static float lerpAngle(float from, float to, float amount) {
        return from + wrapDegrees(to - from) * Math.min(Math.max(amount, 0f), 1f);
    }

    private static float wrapDegrees(float degrees) {
        degrees %= 360f;
        if (degrees >= 180f) degrees -= 360f;
        if (degrees < -180f) degrees += 360f;
        return degrees;
    }

    private static String stateKey(Entity target, double x, double y, double z) {
        return target.getUUID() + ":" + Math.round(x * 100.0) + ":" + Math.round(y * 100.0) + ":" + Math.round(z * 100.0);
    }

    private static Entity findTarget(Map<String, Object> params) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        int entityId = (int) p(params, "entityId", -1);
        if (entityId >= 0) {
            Entity entity = mc.level.getEntity(entityId);
            if (entity != null) return entity;
        }

        String uuid = ps(params, "entityUuid", "").trim();
        if (!uuid.isEmpty()) {
            try {
                UUID parsed = UUID.fromString(uuid);
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity.getUUID().equals(parsed)) return entity;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid UUID falls through to name matching.
            }
        }

        String name = ps(params, "entityName", "").trim();
        if (!name.isEmpty()) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getName().getString().equalsIgnoreCase(name)) return entity;
            }
        }

        return null;
    }

    private static final class TrackState {
        private float pitch;
        private float yaw;
        private long lastNs;

        private TrackState(float pitch, float yaw) {
            this.pitch = pitch;
            this.yaw = yaw;
        }
    }
}
