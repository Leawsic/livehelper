package site.leawsic.livehelper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.server.ApiServer;
import site.leawsic.livehelper.storage.StorageManager;

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
        } catch (Exception e) {
            LOGGER.error("Failed to start API server", e);
        }

        ClientTickEvents.START_CLIENT_TICK.register(client -> StreamManager.INSTANCE.tickAll());

        LOGGER.info("LiveHelper initialized!");
    }
}
