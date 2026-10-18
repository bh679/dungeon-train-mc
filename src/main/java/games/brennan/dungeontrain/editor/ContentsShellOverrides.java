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
import java.util.Optional;
import java.util.TreeMap;

/**
 * The carriage an author rerolled a contents plot into, by contents id — the X menu's Reroll
 * carriage icon. Kept across sessions, so the plot stands in the same carriage next time.
 *
 * <p>Two tiers, merged per id with the user file winning: the bundled manifest (written through in a
 * dev checkout, so a shipped template opens in the carriage its author chose), then the user file.
 * Saved straight away on a reroll; the plot is a view of the template, so no Save is needed. A
 * remembered carriage that would no longer carry the contents is ignored on read — see
 * {@link CarriageContentsEditor#shellFor}.</p>
 *
 * <pre>{"schemaVersion": 1, "shells": {"creeking": "cargo"}}</pre>
 */
public final class ContentsShellOverrides {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int SCHEMA_VERSION = 1;
    private static final String BUNDLED = "/data/dungeontrain/templates/contents-shells.json";
    private static final String KEY = "shells";

    private static Map<String, String> bundled;
    private static Map<String, String> config;

    private ContentsShellOverrides() {}

    public static synchronized Optional<String> get(String contentsId) {
        ensureLoaded();
        String fromConfig = config.get(contentsId);
        return Optional.ofNullable(fromConfig != null ? fromConfig : bundled.get(contentsId));
    }

    /**
     * Remember {@code carriageId} for {@code contentsId}. A failed write is logged, not thrown: the
     * reroll still holds for this session.
     */
    public static synchronized void set(String contentsId, String carriageId) {
        ensureLoaded();
        Map<String, String> next = new TreeMap<>(config);
        next.put(contentsId, carriageId);
        config = Map.copyOf(next);
        try {
            write(configPath(), next);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not save the carriage for contents '{}': {}",
                contentsId, e.toString());
        }
        trySaveToSource(contentsId, carriageId);
    }

    /** Give {@code to} whatever {@code from} stands in — a renamed template keeps its carriage. */
    public static synchronized void copy(String from, String to) {
        get(from).ifPresent(carriage -> set(to, carriage));
    }

    /** Drop the user-tier entry for a deleted {@code contentsId}, so a later template of that name starts fresh. */
    public static synchronized void forget(String contentsId) {
        ensureLoaded();
        if (!config.containsKey(contentsId)) return;
        Map<String, String> next = new TreeMap<>(config);
        next.remove(contentsId);
        config = Map.copyOf(next);
        try {
            write(configPath(), next);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not forget the carriage for contents '{}': {}",
                contentsId, e.toString());
        }
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
        return UserContentPaths.root().resolve("contents-shells.json");
    }

    private static Map<String, String> loadBundled() {
        try (InputStream in = ContentsShellOverrides.class.getResourceAsStream(BUNDLED)) {
            if (in == null) return Map.of();
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read bundled {}: {}", BUNDLED, e.toString());
            return Map.of();
        }
    }

    private static Map<String, String> loadConfig() {
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

    /** Parse one manifest; anything that is not a string is skipped rather than failing the file. */
    static Map<String, String> parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) throw new IllegalArgumentException("root is not an object");
        JsonElement body = root.getAsJsonObject().get(KEY);
        if (body == null || !body.isJsonObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : body.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) out.put(e.getKey(), v.getAsString());
        }
        return Map.copyOf(out);
    }

    static JsonObject toJson(Map<String, String> values) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject body = new JsonObject();
        new TreeMap<>(values).forEach(body::addProperty);
        root.add(KEY, body);
        return root;
    }

    private static void write(Path file, Map<String, String> values) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(toJson(values), w);
        }
    }

    /** Best-effort dev-checkout write-through of one id into the bundled manifest. */
    private static void trySaveToSource(String contentsId, String carriageId) {
        if (!EditorDevMode.isEnabled()) return;
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        Path resources = projectRoot == null ? null : projectRoot.resolve("src/main/resources");
        if (resources == null || !Files.isDirectory(resources) || !Files.isWritable(resources)) return;
        Path file = resources.resolve(BUNDLED.substring(1));
        try {
            Map<String, String> next = new TreeMap<>(bundled);
            next.put(contentsId, carriageId);
            write(file, next);
            bundled = Map.copyOf(next);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled {} to {}: {} (config write succeeded).",
                BUNDLED, file, e.toString());
        }
    }
}
