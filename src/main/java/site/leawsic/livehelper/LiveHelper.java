package site.leawsic.livehelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.leawsic.livehelper.command.LiveHelperCommands;
import site.leawsic.livehelper.server.ApiServer;
import site.leawsic.livehelper.storage.StorageManager;

import java.net.URI;

@Environment(EnvType.CLIENT)
public class LiveHelper implements ClientModInitializer {
    public static final String MOD_ID = "livehelper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("LiveHelper initializing...");

        StorageManager.getInstance().load();

        try {
            ApiServer.start();
            Util.getPlatform().openUri(URI.create("http://localhost:23512/"));
        } catch (Exception e) {
            LOGGER.error("Failed to start API server", e);
        }

        LiveHelperCommands.register();

        LOGGER.info("LiveHelper initialized!");
    }
}
