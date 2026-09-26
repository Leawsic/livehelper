package site.leawsic.livehelper.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import site.leawsic.livehelper.engine.PlaybackEngine;
import site.leawsic.livehelper.engine.templates.MotionTemplate;
import site.leawsic.livehelper.engine.templates.MotionTemplates;
import site.leawsic.livehelper.engine.templates.PreparedMotionTemplate;
import site.leawsic.livehelper.engine.templates.StaticTrackTemplate;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.FrameCommand;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.schema.ClipValidator;
import site.leawsic.livehelper.storage.StorageManager;

import java.net.URI;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class LiveHelperCommands {
    private static final String WEB_URL = "http://localhost:23512/";

    private LiveHelperCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("livehelper")
            .executes(context -> showStatus(context.getSource()))
            .then(literal("status").executes(context -> showStatus(context.getSource())))
            .then(literal("open").executes(context -> openWebUi(context.getSource())))
            .then(literal("reload").executes(context -> reloadStorage(context.getSource())))
            .then(literal("pose").executes(context -> showPose(context.getSource())))
            .then(literal("entities").executes(context -> listEntities(context.getSource(), 32))
                .then(argument("radius", IntegerArgumentType.integer(1, 256))
                    .executes(context -> listEntities(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
            .then(literal("validate")
                .then(argument("clipId", IntegerArgumentType.integer(1))
                    .executes(c -> validateClip(c.getSource(), IntegerArgumentType.getInteger(c, "clipId")))))
            .then(evalCommand())
            .then(literal("list")
                .then(literal("clips").executes(context -> listClips(context.getSource())))
                .then(literal("managers").executes(context -> listManagers(context.getSource()))))
            .then(literal("start")
                .then(argument("managerId", IntegerArgumentType.integer(1))
                    .executes(context -> startManager(context.getSource(), IntegerArgumentType.getInteger(context, "managerId")))))
            .then(literal("stop")
                .then(argument("managerId", IntegerArgumentType.integer(1))
                    .executes(context -> stopManager(context.getSource(), IntegerArgumentType.getInteger(context, "managerId")))))
            .then(literal("stop-all").executes(context -> stopAll(context.getSource())))));
    }

    /** {@code /livehelper validate <clipId>}：跑一遍写入前校验，方便排查画面与预期不符的问题。 */
    private static int validateClip(FabricClientCommandSource source, int clipId) {
        Clip clip = StorageManager.getInstance().getClip(clipId);
        if (clip == null) {
            send(source, "Clip not found: #" + clipId);
            return 0;
        }
        ClipValidator.Result result = ClipValidator.validate(clip);
        for (String warning : result.warnings()) {
            send(source, "  [warn] " + warning);
        }
        if (!result.ok()) {
            for (String error : result.errors()) {
                send(source, "  [error] " + error);
            }
            send(source, "Clip #" + clipId + " is INVALID (" + result.errors().size() + " errors)");
            return 0;
        }
        send(source, "Clip #" + clipId + " is OK"
            + (result.warnings().isEmpty() ? "" : " (" + result.warnings().size() + " warnings)"));
        return 1;
    }

    /**
     * {@code /livehelper eval} 子树。
     *
     * <p>抽成独立方法时必须显式写出命令源类型：内联写在 register() 里时 S 由
     * {@code dispatcher.register} 推断为 {@link FabricClientCommandSource}，抽出来后不再有推断上下文。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> evalCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("eval");

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> clipIdArg =
            argument("clipId", IntegerArgumentType.integer(1));
        clipIdArg.executes(c -> evalClip(c.getSource(),
            IntegerArgumentType.getInteger(c, "clipId"), 0.5f, 1));

        // 注意：Brigadier 的浮点工厂方法叫 floatArg / doubleArg，因为 float / double 是关键字，
        // 不能直接写成 FloatArgumentType.float(...)（那会报「需要<标识符>」）。
        RequiredArgumentBuilder<FabricClientCommandSource, Float> progressArg =
            argument("progress", FloatArgumentType.floatArg(0f, 1f));
        progressArg.executes(c -> evalClip(c.getSource(),
            IntegerArgumentType.getInteger(c, "clipId"),
            FloatArgumentType.getFloat(c, "progress"), 1));

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> samplesArg =
            argument("samples", IntegerArgumentType.integer(2, 200));
        samplesArg.executes(c -> evalClip(c.getSource(),
            IntegerArgumentType.getInteger(c, "clipId"),
            FloatArgumentType.getFloat(c, "progress"),
            IntegerArgumentType.getInteger(c, "samples")));

        progressArg.then(samplesArg);
        clipIdArg.then(progressArg);
        return root.then(clipIdArg);
    }

    /**
     * 离线查看某个 Clip 在给定进度下的相机参数，不需要启动推流或 OBS。
     *
     * <p>samples 大于 1 时会沿进度均匀采样并打印相邻点之间的世界距离：
     * SPLINE 的 step 应基本均匀，PATH 在关键帧处会出现突变——这是肉眼对比两种路径手感的最快办法。
     */
    private static int evalClip(FabricClientCommandSource source, int clipId, float progress, int samples) {
        Clip clip = StorageManager.getInstance().getClip(clipId);
        if (clip == null) {
            send(source, "Clip not found: #" + clipId);
            return 0;
        }

        MotionTemplate template;
        try {
            template = MotionTemplates.get(clip.template());
        } catch (IllegalArgumentException e) {
            send(source, "Unknown template: " + clip.template());
            return 0;
        }

        Object prepared = null;
        if (template instanceof PreparedMotionTemplate preparable) {
            try {
                prepared = preparable.prepare(clip.params());
            } catch (Exception e) {
                send(source, "Precompute failed: " + e);
                return 0;
            }
        }

        send(source, String.format("Clip #%d '%s' [%s] duration=%dms%s",
            clipId, clip.name(), clip.template(), clip.duration(),
            prepared != null ? " (precomputed)" : ""));

        FrameCommand previous = null;
        for (int i = 0; i < samples; i++) {
            float t = samples == 1 ? progress : i / (float) (samples - 1);
            FrameCommand raw;
            if (prepared != null && template instanceof PreparedMotionTemplate preparable) {
                raw = preparable.evaluatePrepared(clip.params(), t, prepared);
            } else {
                raw = template.evaluate(clip.params(), t);
            }

            FrameCommand cmd = PlaybackEngine.sanitizeOrNull(raw);
            if (cmd == null) {
                send(source, String.format("  t=%.4f  <unusable frame: NaN/Inf or degenerate quaternion>", t));
                previous = null;
                continue;
            }

            double step = previous == null ? Double.NaN : Math.sqrt(
                Math.pow(cmd.x() - previous.x(), 2)
                + Math.pow(cmd.y() - previous.y(), 2)
                + Math.pow(cmd.z() - previous.z(), 2));

            send(source, String.format("  t=%.4f  pos=(%8.3f, %7.3f, %8.3f)  fov=%6.2f  step=%s",
                t, cmd.x(), cmd.y(), cmd.z(), cmd.fov(),
                Double.isNaN(step) ? "-" : String.format("%.4f", step)));
            previous = cmd;
        }

        if (samples > 1) {
            send(source, "step = 相邻采样点的世界距离；SPLINE 基本均匀，PATH 在关键帧处会突变。");
        }
        return samples;
    }

    private static int showStatus(FabricClientCommandSource source) {
        StorageManager storage = StorageManager.getInstance();
        Set<Integer> activeIds = StreamManager.INSTANCE.getActiveStreamIds();
        send(source, "LiveHelper: " + storage.getAllClips().size() + " clips, "
            + storage.getAllManagers().size() + " managers, active=" + activeIds);
        return activeIds.size();
    }

    private static int openWebUi(FabricClientCommandSource source) {
        Util.getPlatform().openUri(URI.create(WEB_URL));
        send(source, "Opened LiveHelper Web UI: " + WEB_URL);
        return 1;
    }

    private static int reloadStorage(FabricClientCommandSource source) {
        StorageManager.getInstance().load();
        StaticTrackTemplate.resetAllStates();
        send(source, "Reloaded LiveHelper config from config/livehelper/.");
        return 1;
    }

    private static int showPose(FabricClientCommandSource source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            send(source, "Player is not in a world.");
            return 0;
        }
        var player = mc.player;
        var block = player.blockPosition();
        send(source, String.format("Eye: %.2f %.2f %.2f | Block: %d %d %d | pitch %.2f yaw %.2f",
            player.getX(), player.getEyeY(), player.getZ(),
            block.getX(), block.getY(), block.getZ(),
            player.getXRot(), player.getYRot()));
        return 1;
    }

    private static int listEntities(FabricClientCommandSource source, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            send(source, "Player is not in a world.");
            return 0;
        }

        int count = 0;
        double maxDistanceSq = radius * radius;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity.distanceToSqr(mc.player) > maxDistanceSq) continue;
            send(source, String.format("#%d %s uuid=%s pos=%.1f %.1f %.1f",
                entity.getId(), entity.getName().getString(), entity.getUUID(),
                entity.getX(), entity.getY(), entity.getZ()));
            count++;
            if (count >= 20) {
                send(source, "Showing first 20 entities. Use a smaller radius if needed.");
                break;
            }
        }
        if (count == 0) {
            send(source, "No entities within " + radius + " blocks.");
        }
        return count;
    }

    private static int listClips(FabricClientCommandSource source) {
        var clips = StorageManager.getInstance().getAllClips();
        if (clips.isEmpty()) {
            send(source, "No LiveHelper clips configured.");
            return 0;
        }
        for (Clip clip : clips) {
            send(source, "#" + clip.id() + " " + clip.name() + " [" + clip.template() + ", " + clip.duration() + "ms]");
        }
        return clips.size();
    }

    private static int listManagers(FabricClientCommandSource source) {
        var storage = StorageManager.getInstance();
        var managers = storage.getAllManagers();
        if (managers.isEmpty()) {
            send(source, "No LiveHelper managers configured.");
            return 0;
        }
        for (Manager manager : managers) {
            send(source, "#" + manager.id() + " " + manager.name() + " ["
                + manager.clips().size() + " clips, " + managerDuration(manager) + "ms, "
                + manager.effectiveLoopMode() + ", "
                + StreamManager.INSTANCE.getStatus(manager.id()).name().toLowerCase() + "]");
        }
        return managers.size();
    }

    private static int startManager(FabricClientCommandSource source, int managerId) {
        Manager manager = StorageManager.getInstance().getManager(managerId);
        if (manager == null) {
            send(source, "Manager not found: #" + managerId);
            return 0;
        }
        StreamManager.INSTANCE.start(managerId);
        send(source, "Started manager #" + managerId + " " + manager.name());
        return 1;
    }

    private static int stopManager(FabricClientCommandSource source, int managerId) {
        StreamManager.INSTANCE.stop(managerId);
        send(source, "Stopped manager #" + managerId);
        return 1;
    }

    private static int stopAll(FabricClientCommandSource source) {
        int count = StreamManager.INSTANCE.getActiveStreamIds().size();
        StreamManager.INSTANCE.stopAll();
        send(source, "Stopped " + count + " active manager(s).");
        return count;
    }

    private static long managerDuration(Manager manager) {
        long duration = 0L;
        for (ClipSlot slot : manager.clips()) {
            Clip clip = StorageManager.getInstance().getClip(slot.clipId());
            if (clip != null) {
                duration = Math.max(duration, slot.startOffset() + clip.duration());
            }
        }
        return duration;
    }

    private static void send(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
