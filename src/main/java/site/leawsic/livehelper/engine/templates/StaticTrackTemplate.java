package site.leawsic.livehelper.engine.templates;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.util.AngleConvert;
import site.leawsic.livehelper.util.MathUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static site.leawsic.livehelper.engine.templates.MotionTemplates.*;

/**
 * 固定机位 + 实体跟踪。
 *
 * <p>三条相对早期实现的行为约定：
 * <ul>
 *   <li>平滑状态只按机位坐标缓存，不再把目标 UUID 编进 key。目标切换时状态自然延续，
 *       由指数平滑器把镜头缓过去，不会硬切；同时每个机位只留一条记录，消除长时推流的堆积。</li>
 *   <li>目标暂时不可用（死亡 / 卸载 / 未加载）时进入搜索态：保持最后一帧并按固定节奏重扫，
 *       命中任意符合规则的目标即恢复，而不是立刻跳回 fallback 角度造成画面硬切。</li>
 *   <li>搜索态下按 {@link #RESCAN_INTERVAL_NS} 节流重扫，避免每帧全表遍历实体；
 *       已锁定目标时仍每帧解析，保证跟拍响应不被节流拖慢。</li>
 * </ul>
 */
public class StaticTrackTemplate implements MotionTemplate {

    private static final long RESCAN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(200);

    /** 缓存的机位数上限；一条 Manager 时间线只会用到个位数机位，留足余量。 */
    private static final int MAX_TRACK_STATES = 64;

    private static final Map<String, TrackState> states = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TrackState> eldest) {
            return size() > MAX_TRACK_STATES;
        }
    };

    /** 推流停止或配置重载后调用，释放跟踪状态。 */
    public static void resetAllStates() {
        states.clear();
    }

    @Override
    public FrameCommand evaluate(Map<String, Object> params, float progress) {
        double x = p(params, "posX", 0.0);
        double y = p(params, "posY", 0.0);
        double z = p(params, "posZ", 0.0);
        float fallbackPitch = pf(params, "rotX", 0f);
        float fallbackYaw = pf(params, "rotY", 0f);
        float fallbackRoll = pf(params, "rotZ", 0f);
        float fov = MathUtil.clamp(pf(params, "fov", 70f), MathUtil.MIN_FOV, MathUtil.MAX_FOV);

        long now = System.nanoTime();
        TrackState state = states.computeIfAbsent(stateKey(x, y, z), k -> new TrackState());
        Entity target = resolveTarget(params, state, now);

        if (target == null) {
            // 搜索态：保持最后一帧，避免目标闪退时画面抽搐。
            if (state.hasOrientation) {
                return frame(x, y, z, state.pitch, state.yaw, 0f, fov);
            }
            return frame(x, y, z, fallbackPitch, fallbackYaw, fallbackRoll, fov);
        }

        double yOffset = p(params, "targetYOffset", 0.0);
        float[] angles = lookAngles(x, y, z, target.getX(), target.getEyeY() + yOffset, target.getZ());
        float pitch = angles[0];
        float yaw = angles[1];

        float trackSpeed = Math.max(0f, pf(params, "trackSpeed", 8f));
        if (trackSpeed > 0f) {
            if (!state.hasOrientation) {
                state.pitch = pitch;
                state.yaw = yaw;
                state.hasOrientation = true;
            }
            float deltaSeconds = state.lastNs == 0L
                    ? 0f
                    : Math.min((now - state.lastNs) / 1_000_000_000f, 0.25f);
            state.lastNs = now;
            float amount = deltaSeconds <= 0f ? 1f : 1f - (float) Math.exp(-trackSpeed * deltaSeconds);
            state.pitch = MathUtil.lerpAngle(state.pitch, pitch, amount);
            state.yaw = MathUtil.lerpAngle(state.yaw, yaw, amount);
            pitch = state.pitch;
            yaw = state.yaw;
        } else {
            state.pitch = pitch;
            state.yaw = yaw;
            state.hasOrientation = true;
        }

        return frame(x, y, z, pitch, yaw, 0f, fov);
    }

    /**
     * 解析跟踪目标，并在搜索态下节流重扫。
     *
     * @return 命中的目标；处于节流窗口内时返回 null（调用方按搜索态处理）
     */
    private Entity resolveTarget(Map<String, Object> params, TrackState state, long nowNs) {
        if (state.searching && state.lastScanNs != 0L && nowNs - state.lastScanNs < RESCAN_INTERVAL_NS) {
            return null;
        }
        state.lastScanNs = nowNs;
        Entity found = findTarget(params);
        state.searching = (found == null);
        if (found != null) {
            state.targetUuid = found.getUUID();
        }
        return found;
    }

    private static FrameCommand frame(double x, double y, double z, float pitch, float yaw, float roll, float fov) {
        Quaternionf q = AngleConvert.toQuaternion(pitch, yaw, roll);
        return new FrameCommand(
                MathUtil.sanitizeDouble(x, 0.0),
                MathUtil.sanitizeDouble(y, 0.0),
                MathUtil.sanitizeDouble(z, 0.0),
                q.x, q.y, q.z, q.w,
                fov,
                pitch, yaw, roll
        );
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

    /** 只按机位坐标分组：目标切换时平滑状态得以延续。 */
    private static String stateKey(double x, double y, double z) {
        return Math.round(x * 100.0) + ":" + Math.round(y * 100.0) + ":" + Math.round(z * 100.0);
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
        private long lastScanNs;
        private boolean hasOrientation;
        private boolean searching;
        @SuppressWarnings("unused")
        private UUID targetUuid;
    }
}
