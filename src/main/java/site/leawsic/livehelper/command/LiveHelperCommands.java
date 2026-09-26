package site.leawsic.livehelper.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import site.leawsic.livehelper.schema.TriggerSchemas;
import site.leawsic.livehelper.schema.TriggerValidator;
import site.leawsic.livehelper.storage.StorageManager;
import site.leawsic.livehelper.trigger.TriggerRule;
import site.leawsic.livehelper.trigger.client.ClientTriggerBridge;
import site.leawsic.livehelper.trigger.client.TriggerStore;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
            .then(triggerCommand())
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
     * {@code /livehelper trigger} 子树：list / add / remove / enable / disable / test。
     *
     * <p>{@code test} 会立即把该规则的目标 Manager 启起来，用来在配规则阶段先确认机位本身没问题，
     * 不用等到条件真的命中。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> triggerCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("trigger");

        root.then(literal("list").executes(c -> listTriggers(c.getSource())));
        root.then(literal("back").executes(c -> {
            int cue = StreamManager.INSTANCE.getCueManagerId();
            int base = StreamManager.INSTANCE.getBaseManagerId();
            if (cue <= 0) {
                send(c.getSource(), "当前没有切机位可返回。");
                return 0;
            }
            // 必须在 returnToBase 之前取 cue，因为它返回后就会被清空。
            StreamManager.INSTANCE.returnToBase();
            send(c.getSource(), "已结束切机位 #" + cue + "，返回常驻机位 #" + base);
            return cue;
        }));

        RequiredArgumentBuilder<FabricClientCommandSource, String> addType =
            argument("type", StringArgumentType.word());
        addType.then(argument("manager", IntegerArgumentType.integer(1))
            .executes(c -> addTrigger(c.getSource(),
                StringArgumentType.getString(c, "type"),
                IntegerArgumentType.getInteger(c, "manager"), "", new LinkedHashMap<>(), 0L, 0L, true))
            .then(argument("name", StringArgumentType.greedyString())
                .executes(c -> addTrigger(c.getSource(),
                    StringArgumentType.getString(c, "type"),
                    IntegerArgumentType.getInteger(c, "manager"),
                    StringArgumentType.getString(c, "name"), new LinkedHashMap<>(), 0L, 0L, true))));

        RequiredArgumentBuilder<FabricClientCommandSource, Integer> idArg =
            argument("id", IntegerArgumentType.integer(1));

        LiteralArgumentBuilder<FabricClientCommandSource> idBranch = literal("id")
            .then(literal("remove").then(idArg.executes(c -> removeTrigger(c.getSource(),
                IntegerArgumentType.getInteger(c, "id")))))
            .then(literal("enable").then(idArg.executes(c -> setTriggerEnabled(c.getSource(),
                IntegerArgumentType.getInteger(c, "id"), true))))
            .then(literal("disable").then(idArg.executes(c -> setTriggerEnabled(c.getSource(),
                IntegerArgumentType.getInteger(c, "id"), false))))
            .then(literal("test").then(idArg.executes(c -> testTrigger(c.getSource(),
                IntegerArgumentType.getInteger(c, "id")))));

        root.then(literal("add").then(addType));
        root.then(literal("id").then(idBranch));
        return root;
    }

    private static int listTriggers(FabricClientCommandSource source) {
        var rules = TriggerStore.getInstance().getAll();
        if (rules.isEmpty()) {
            send(source, "没有触发规则。用 /livehelper trigger add <type> <managerId> [名称] 创建。");
            return 0;
        }
        send(source, "已启用 " + ClientTriggerBridge.enabledRuleCount() + " / " + rules.size() + " 条规则：");
        for (TriggerRule rule : rules) {
            send(source, String.format("  #%d %-14s %-18s -> manager #%d  delay=%dms cooldown=%dms%s%s",
                rule.id(), rule.name().isBlank() ? "(未命名)" : rule.name(),
                rule.type() + " " + TriggerSchemas.labelOf(rule.type()),
                rule.targetManager(), rule.delayMs(), rule.cooldownMs(),
                rule.enabled() ? "" : "  [已禁用]",
                rule.repeatable() ? "" : "  [一次性]"));
        }
        return rules.size();
    }

    private static int addTrigger(FabricClientCommandSource source, String type, int managerId,
                                  String name, Map<String, Object> conditions, long delayMs,
                                  long cooldownMs, boolean repeatable) {
        TriggerRule candidate = new TriggerRule(0, name, type, conditions, managerId,
            true, repeatable, delayMs, cooldownMs, false, 0.0);
        TriggerValidator.Result result = TriggerValidator.validate(candidate, managerIds());
        for (String warning : result.warnings()) {
            send(source, "  [warn] " + warning);
        }
        if (!result.ok()) {
            send(source, "创建失败：" + result.message());
            return 0;
        }
        TriggerRule created = TriggerStore.getInstance().create(candidate);
        send(source, "已创建触发规则 #" + created.id() + "（" + type + " -> manager #" + managerId + "）");
        send(source, "提示：条件需要在 Web UI 的触发规则页里配置，或直接编辑 config/livehelper/triggers.json。");
        return created.id();
    }

    private static int removeTrigger(FabricClientCommandSource source, int id) {
        if (TriggerStore.getInstance().get(id) == null) {
            send(source, "触发规则不存在: #" + id);
            return 0;
        }
        TriggerStore.getInstance().delete(id);
        send(source, "已删除触发规则 #" + id);
        return id;
    }

    private static int setTriggerEnabled(FabricClientCommandSource source, int id, boolean enabled) {
        if (TriggerStore.getInstance().get(id) == null) {
            send(source, "触发规则不存在: #" + id);
            return 0;
        }
        TriggerStore.getInstance().setEnabled(id, enabled);
        send(source, "触发规则 #" + id + (enabled ? " 已启用" : " 已禁用"));
        return id;
    }

    private static int testTrigger(FabricClientCommandSource source, int id) {
        TriggerRule rule = TriggerStore.getInstance().get(id);
        if (rule == null) {
            send(source, "触发规则不存在: #" + id);
            return 0;
        }
        Manager manager = StorageManager.getInstance().getManager(rule.targetManager());
        if (manager == null) {
            send(source, "目标 Manager 不存在: #" + rule.targetManager());
            return 0;
        }
        boolean asCue = StreamManager.INSTANCE.cutTo(rule.targetManager());
        send(source, asCue
            ? "已按触发规则 #" + id + " 切到 manager #" + rule.targetManager() + "（" + manager.name()
                + "）；若该 Manager 未开启循环，播完会自动回常驻机位 #" + StreamManager.INSTANCE.getBaseManagerId() + "。"
            : "已按触发规则 #" + id + " 启动 manager #" + rule.targetManager() + "（" + manager.name()
                + "）；当前没有可返回的常驻机位，已按常驻方式启动。");
        return id;
    }

    private static Set<Integer> managerIds() {
        Set<Integer> ids = new HashSet<>();
        for (Manager manager : StorageManager.getInstance().getAllManagers()) {
            ids.add(manager.id());
        }
        return ids;
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
        int base = StreamManager.INSTANCE.getBaseManagerId();
        int cue = StreamManager.INSTANCE.getCueManagerId();
        if (base > 0 || cue > 0) {
            send(source, "机位: 常驻=" + (base > 0 ? describeManager(base) : "(无)")
                + " | 切机位=" + (cue > 0 ? describeManager(cue) : "(无)"));
        }
        return activeIds.size();
    }

    private static String describeManager(int managerId) {
        Manager manager = StorageManager.getInstance().getManager(managerId);
        return manager == null ? "#" + managerId + "(缺失)" : "#" + managerId + " " + manager.name();
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
