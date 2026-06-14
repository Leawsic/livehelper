package site.leawsic.livehelper.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.model.Clip;
import site.leawsic.livehelper.model.Manager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久化管理器。
 * 使用 Gson 将 Clip 和 Manager 序列化为 JSON 文件。
 * 存储路径：config/livehelper/clips.json, config/livehelper/managers.json
 */
public class StorageManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CLIP_LIST_TYPE = new TypeToken<List<Clip>>() {}.getType();
    private static final Type MANAGER_LIST_TYPE = new TypeToken<List<Manager>>() {}.getType();

    private static StorageManager INSTANCE;

    private final Path storageDir;
    private final Path clipsFile;
    private final Path managersFile;

    private List<Clip> clips = new ArrayList<>();
    private List<Manager> managers = new ArrayList<>();
    private final AtomicInteger nextClipId = new AtomicInteger(1);
    private final AtomicInteger nextManagerId = new AtomicInteger(1);

    public StorageManager(Path configDir) {
        this.storageDir = configDir.resolve("livehelper");
        this.clipsFile = storageDir.resolve("clips.json");
        this.managersFile = storageDir.resolve("managers.json");
    }

    public static StorageManager getInstance() {
        if (INSTANCE == null) {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            INSTANCE = new StorageManager(configDir);
        }
        return INSTANCE;
    }

    // ─── 加载/保存 ───

    public void load() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            LiveHelper.LOGGER.error("Failed to create storage directory", e);
        }

        clips = loadJsonFile(clipsFile, CLIP_LIST_TYPE, new ArrayList<>());
        managers = loadJsonFile(managersFile, MANAGER_LIST_TYPE, new ArrayList<>());

        // 计算下一个可用的 ID
        for (Clip clip : clips) {
            if (clip.id() >= nextClipId.get()) {
                nextClipId.set(clip.id() + 1);
            }
        }
        for (Manager manager : managers) {
            if (manager.id() >= nextManagerId.get()) {
                nextManagerId.set(manager.id() + 1);
            }
        }

        LiveHelper.LOGGER.info("Loaded {} clips and {} managers", clips.size(), managers.size());
    }

    private <T> T loadJsonFile(Path path, Type type, T defaultValue) {
        if (!Files.exists(path)) return defaultValue;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T result = GSON.fromJson(reader, type);
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Failed to load {}", path, e);
            return defaultValue;
        }
    }

    private void saveJsonFile(Path path, Object data) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LiveHelper.LOGGER.error("Failed to save {}", path, e);
        }
    }

    // ─── Clip CRUD ───

    public List<Clip> getAllClips() {
        return clips;
    }

    public Clip getClip(int id) {
        for (Clip clip : clips) {
            if (clip.id() == id) return clip;
        }
        return null;
    }

    public Clip createClip(Clip clip) {
        int id = nextClipId.getAndIncrement();
        Clip newClip = new Clip(id, clip.name(), clip.duration(), clip.template(), clip.params());
        clips.add(newClip);
        saveClips();
        return newClip;
    }

    public void updateClip(int id, Clip clip) {
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).id() == id) {
                clips.set(i, new Clip(id, clip.name(), clip.duration(), clip.template(), clip.params()));
                saveClips();
                return;
            }
        }
    }

    public void deleteClip(int id) {
        clips.removeIf(c -> c.id() == id);
        saveClips();
    }

    private void saveClips() {
        saveJsonFile(clipsFile, clips);
    }

    // ─── Manager CRUD ───

    public List<Manager> getAllManagers() {
        return managers;
    }

    public Manager getManager(int id) {
        for (Manager manager : managers) {
            if (manager.id() == id) return manager;
        }
        return null;
    }

    public Manager createManager(Manager manager) {
        int id = nextManagerId.getAndIncrement();
        Manager newManager = new Manager(id, manager.name(), manager.clips(),
            manager.width(), manager.height(), manager.fps(), manager.renderDistance());
        managers.add(newManager);
        saveManagers();
        return newManager;
    }

    public void updateManager(int id, Manager manager) {
        for (int i = 0; i < managers.size(); i++) {
            if (managers.get(i).id() == id) {
                managers.set(i, new Manager(id, manager.name(), manager.clips(),
                    manager.width(), manager.height(), manager.fps(), manager.renderDistance()));
                saveManagers();
                return;
            }
        }
    }

    public void deleteManager(int id) {
        managers.removeIf(m -> m.id() == id);
        saveManagers();
    }

    private void saveManagers() {
        saveJsonFile(managersFile, managers);
    }
}
