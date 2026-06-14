package site.leawsic.livehelper.spout;

import com.sun.jna.Pointer;
import oshi.PlatformEnum;
import oshi.SystemInfo;
import site.leawsic.livehelper.LiveHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class SpoutSender implements AutoCloseable {
    private static boolean dllExtracted = false;

    private final Pointer handle;

    public SpoutSender(String name) {
        if (SystemInfo.getCurrentPlatform() != PlatformEnum.WINDOWS) {
            throw new RuntimeException("LiveHelper Spout requires Windows 10+");
        }
        ensureDllExtracted();
        this.handle = SpoutBinding.INSTANCE.spCreateSpout(name);
        if (this.handle == null) {
            throw new RuntimeException("Failed to create Spout sender: " + name);
        }
    }

    public void send(int fbo, int width, int height) {
        int result = SpoutBinding.INSTANCE.spSendFrameBufferObject(handle, fbo, width, height);
        if (result == 0) {
            LiveHelper.LOGGER.warn("Spout send failed for FBO {}", fbo);
        }
    }

    @Override
    public void close() {
        SpoutBinding.INSTANCE.spReleaseSpout(handle);
    }

    private static synchronized void ensureDllExtracted() {
        if (dllExtracted) return;
        try {
            Path dllPath = Files.createTempFile("libSpoutBinding-", ".dll").toAbsolutePath();
            try (InputStream is = SpoutSender.class.getResourceAsStream("/assets/livehelper/libSpoutBinding.dll");
                 OutputStream os = Files.newOutputStream(dllPath)) {
                Objects.requireNonNull(is, "libSpoutBinding.dll not found in jar").transferTo(os);
            }
            System.load(dllPath.toString());
            dllPath.toFile().deleteOnExit();
            dllExtracted = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract Spout DLL", e);
        }
    }
}
