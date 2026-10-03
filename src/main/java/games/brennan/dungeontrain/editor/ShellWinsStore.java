package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.TemplateWeightOverlay;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which carriage templates have "carriage blocks win" switched on — the contents laid into such a
 * carriage leave every block the carriage already has standing (see
 * {@link games.brennan.dungeontrain.train.ShellWinsMask}). Off for any id neither tier names, which
 * is every template made before the setting existed: there the contents win, as they always have.
 *
 * <p>Two tiers, merged per id with the config dir winning: the bundled manifest, then the user file.
 * The value is kept explicit in the user tier so an author can switch a bundled On back Off.</p>
 *
 * <pre>{"schemaVersion": 1, "shellWins": {"cargo": true}}</pre>
 */
public final class ShellWinsStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int SCHEMA_VERSION = 1;
    private static final String BUNDLED = "/data/dungeontrain/templates/shell-wins.json";
    private static final String KEY = "shellWins";

    private static Map<String, Boolean> bundled;
    private static Map<String, Boolean> config;

    private ShellWinsStore() {}

    /** True when the carriage template {@code id} keeps its own blocks against its contents. */
    public static synchronized boolean wins(String id) {
        ensureLoaded();
        Boolean fromConfig = config.get(id);
        if (fromConfig != null) return fromConfig;
        return bundled.getOrDefault(id, false);
    }

    /**
     * Switch {@code id} on or off in the config tier, and in the source tree when this is a dev
     * checkout so a bundled template ships its setting with it.
     */
    public static synchronized void set(String id, boolean wins) throws IOException {
        ensureLoaded();
        Map<String, Boolean> next = new TreeMap<>(config);
        if (!wins && !bundled.getOrDefault(id, false)) next.remove(id);
        else next.put(id, wins);
        write(configPath(), next);
        config = Map.copyOf(next);
        trySaveToSource(id, wins);
        LOGGER.info("[DungeonTrain] carriage blocks win for '{}' = {}", id, wins);
    }

    /** Give {@code to} whatever {@code from} has — a duplicate or a rename keeps its source's setting. */
    public static synchronized void copy(String from, String to) throws IOException {
        boolean wins = wins(from);
        if (wins || wins(to)) set(to, wins);
    }

    /** Drop the config-tier entry for a deleted {@code id}, so a later template of that name starts Off. */
    public static synchronized void forget(String id) throws IOException {
        ensureLoaded();
        if (!config.containsKey(id)) return;
        Map<String, Boolean> next = new TreeMap<>(config);
        next.remove(id);
        write(configPath(), next);
        config = Map.copyOf(next);
    }

    /** Drop both caches so the next read goes back to disk. */
    public static synchronized void invalidate() {
        bundled = null;
        config = null;
    }

    private static void ensureLoaded() {
        if (bundled == null) bundled = loadBundled();
        if (config == null) config = loadConfig();
    }

    private static Path configPath() {
        return UserContentPaths.root().resolve("template-shell-wins.json");
    }

    private static Map<String, Boolean> loadBundled() {
        try (InputStream in = ShellWinsStore.class.getResourceAsStream(BUNDLED)) {
            if (in == null) return Map.of();
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read bundled {}: {}", BUNDLED, e.toString());
            return Map.of();
        }
    }

    private static Map<String, Boolean> loadConfig() {
        // A world that disabled custom content gets the bundled catalogue and nothing else.
        Path file;
        try {
            if (!TemplateWeightOverlay.overlayReadable()) return Map.of();
            file = configPath();
        } catch (RuntimeException e) {
            // No game directory yet (unit tests, very early boot): nothing authored to read.
            return Map.of();
        }
        if (!Files.isRegularFile(file)) return Map.of();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read {}: {}", file, e.toString());
            return Map.of();
        }
    }

    /** Parse one manifest; anything that is not a boolean is skipped rather than failing the file. */
    static Map<String, Boolean> parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) throw new IllegalArgumentException("root is not an object");
        JsonElement body = root.getAsJsonObject().get(KEY);
        if (body == null || !body.isJsonObject()) return Map.of();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : body.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) out.put(e.getKey(), v.getAsBoolean());
        }
        return Map.copyOf(out);
    }

    static JsonObject toJson(Map<String, Boolean> values) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject body = new JsonObject();
        new TreeMap<>(values).forEach(body::addProperty);
        root.add(KEY, body);
        return root;
    }

    private static void write(Path file, Map<String, Boolean> values) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(toJson(values), w);
        }
    }

    /** Best-effort dev-checkout write-through of one id into the bundled manifest. */
    private static void trySaveToSource(String id, boolean wins) {
        if (!EditorDevMode.isEnabled()) return;
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        Path resources = projectRoot == null ? null : projectRoot.resolve("src/main/resources");
        if (resources == null || !Files.isDirectory(resources) || !Files.isWritable(resources)) return;
        Path file = resources.resolve(BUNDLED.substring(1));
        try {
            Map<String, Boolean> next = new TreeMap<>(bundled);
            if (wins) next.put(id, true);
            else next.remove(id);
            write(file, next);
            bundled = Map.copyOf(next);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled {} to {}: {} (config write succeeded).",
                BUNDLED, file, e.toString());
        }
    }
}
