package site.leawsic.livehelper.trigger.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import site.leawsic.livehelper.LiveHelper;
import site.leawsic.livehelper.trigger.TriggerRule;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 触发规则的持久化，落在 {@code config/livehelper/triggers.json}。
 *
 * <p>与 {@code StorageManager} 保持同样的落盘方式（临时文件 + 原子替换、UTF-8、Gson 缩进），
 * 单独成类是因为规则的读写时机和 Clip/Manager 不同：规则改动后必须立刻
 * {@link ClientTriggerBridge#reload} 让引擎生效。
 */
public final class TriggerStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type RULE_LIST_TYPE = new TypeToken<List<TriggerRule>>() {}.getType();

    private static TriggerStore INSTANCE;

    private final Path file;
    private List<TriggerRule> rules = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private TriggerStore(Path configDir) {
        this.file = configDir.resolve("livehelper").resolve("triggers.json");
    }

    public static TriggerStore getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TriggerStore(FabricLoader.getInstance().getConfigDir());
            INSTANCE.load();
        }
        return INSTANCE;
    }

    public void load() {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            LiveHelper.LOGGER.error("Failed to create trigger storage directory", e);
        }
        if (!Files.exists(file)) {
            rules = new ArrayList<>();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<TriggerRule> loaded = GSON.fromJson(reader, RULE_LIST_TYPE);
            rules = loaded != null ? loaded : new ArrayList<>();
        } catch (Exception e) {
            LiveHelper.LOGGER.error("Failed to load triggers.json", e);
            rules = new ArrayList<>();
        }
        for (TriggerRule rule : rules) {
            if (rule.id() >= nextId.get()) {
                nextId.set(rule.id() + 1);
            }
        }
        LiveHelper.LOGGER.info("Loaded {} trigger rules", rules.size());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(rules, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LiveHelper.LOGGER.error("Failed to save triggers.json", e);
        }
    }

    /** 落盘后立即让引擎重新装载，使 Web UI 的改动立刻生效。 */
    private void persistAndReload() {
        save();
        ClientTriggerBridge.reload(rules);
    }

    public List<TriggerRule> getAll() {
        return rules;
    }

    public TriggerRule get(int id) {
        for (TriggerRule rule : rules) {
            if (rule.id() == id) return rule;
        }
        return null;
    }

    public TriggerRule create(TriggerRule rule) {
        int id = nextId.getAndIncrement();
        TriggerRule created = new TriggerRule(id, rule.name(), rule.type(), rule.conditions(),
            rule.targetManager(), rule.enabled(), rule.repeatable(),
            rule.delayMs(), rule.cooldownMs(), rule.onEnter(), rule.exitBuffer());
        rules.add(created);
        persistAndReload();
        return created;
    }

    public void update(int id, TriggerRule rule) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id() == id) {
                rules.set(i, new TriggerRule(id, rule.name(), rule.type(), rule.conditions(),
                    rule.targetManager(), rule.enabled(), rule.repeatable(),
                    rule.delayMs(), rule.cooldownMs(), rule.onEnter(), rule.exitBuffer()));
                persistAndReload();
                return;
            }
        }
    }

    public void delete(int id) {
        if (rules.removeIf(r -> r.id() == id)) {
            persistAndReload();
        }
    }

    public void setEnabled(int id, boolean enabled) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id() == id) {
                rules.set(i, rules.get(i).withEnabled(enabled));
                persistAndReload();
                return;
            }
        }
    }
}
