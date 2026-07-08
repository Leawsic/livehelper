package site.leawsic.livehelper.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.ClipSlot;
import site.leawsic.livehelper.model.Manager;
import site.leawsic.livehelper.render.StreamManager;
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
