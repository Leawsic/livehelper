package site.leawsic.livehelper.spout;

import com.sun.jna.Pointer;
import site.leawsic.livehelper.LiveHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public class SpoutSender implements AutoCloseable {
    private static SpoutBinding binding;

    private final Pointer handle;

    public SpoutSender(String name) {
        if (!isWindows()) {
            throw new RuntimeException("LiveHelper Spout requires Windows 10+");
        }
        SpoutBinding spout = ensureBindingLoaded();
        this.handle = spout.spCreateSpout(name);
        if (this.handle == null) {
            throw new RuntimeException("Failed to create Spout sender: " + name);
        }
    }

    public void send(int fbo, int width, int height) {
        int result = ensureBindingLoaded().spSendFrameBufferObject(handle, fbo, width, height);
        if (result == 0) {
            LiveHelper.LOGGER.warn("Spout send failed for FBO {}", fbo);
        }
    }

    @Override
    public void close() {
        if (handle != null) {
            ensureBindingLoaded().spReleaseSpout(handle);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static synchronized SpoutBinding ensureBindingLoaded() {
        if (binding != null) return binding;
        try {
            Path dllPath = Files.createTempFile("libSpoutBinding-", ".dll").toAbsolutePath();
            try (InputStream is = SpoutSender.class.getResourceAsStream("/assets/livehelper/libSpoutBinding.dll");
                 OutputStream os = Files.newOutputStream(dllPath)) {
                Objects.requireNonNull(is, "libSpoutBinding.dll not found in jar").transferTo(os);
            }
            dllPath.toFile().deleteOnExit();
            binding = SpoutBinding.load(dllPath.toString());
            return binding;
        } catch (IOException | UnsatisfiedLinkError e) {
            throw new RuntimeException("Failed to load Spout DLL", e);
        }
    }
}
