package games.brennan.dungeontrain.train;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The set of carriage-variant ids that are <b>relay-sourced ("shared")</b>. When a slot's selected
 * variant is in this set (and {@code sharedCarriagesEnabled} is on), that carriage participates in the
 * shared-carriage pool: it either leases an existing build from the relay or, placed fresh, is uploaded
 * the first time a player actually changes it — see {@link SharedCarriageRegistry} and the relay's
 * {@code /carriages/*} endpoints.
 *
 * <p>Three-tier load at {@link ServerStartingEvent}, mirroring {@link CarriageWeights}:
 * <ol>
 *   <li><b>Bundled default</b> — {@code /data/dungeontrain/templates/shared-carriages.json} (ships
 *       with the jar; default flags the {@code shared} variant on).</li>
 *   <li><b>Per-install override</b> — {@code config/dungeontrain/user/shared-carriages.json}. Its set
 *       <em>replaces</em> the bundled set when present (a whole-set override, not a per-id merge).</li>
 *   <li><b>Empty default</b> — no shared carriages when neither file exists.</li>
 * </ol>
 *
 * <p>JSON schema: {@code { "schemaVersion": 1, "shared": ["shared", ...] }}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public record SharedCarriageFlags(Set<String> ids) {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String SCHEMA_KEY = "schemaVersion";
    public static final int SCHEMA_VERSION = 1;

    static final String BUNDLED_RESOURCE = "/data/dungeontrain/templates/shared-carriages.json";
    static final String CONFIG_FILE = "shared-carriages.json";

    /** No shared carriages. */
    public static final SharedCarriageFlags EMPTY = new SharedCarriageFlags(Collections.emptySet());

    /** Cached flags for the active server. Loaded on ServerStartingEvent, cleared on stop. */
    private static volatile SharedCarriageFlags current = EMPTY;

    public SharedCarriageFlags {
        if (ids == null) {
            ids = Collections.emptySet();
        } else {
            TreeSet<String> norm = new TreeSet<>();
            for (String s : ids) {
                if (s == null || s.isBlank()) continue;
                norm.add(s.toLowerCase(Locale.ROOT));
            }
            ids = Collections.unmodifiableSet(norm);
        }
    }

    /** True iff {@code variantId} is flagged relay-sourced. */
    public boolean isShared(String variantId) {
        return variantId != null && ids.contains(variantId.toLowerCase(Locale.ROOT));
    }

    /** The active server's flags. {@link #EMPTY} outside a server session. */
    public static SharedCarriageFlags current() {
        return current;
    }

    /** Convenience: is this variant id flagged shared on the active server? */
    public static boolean isSharedVariant(String variantId) {
        return current.isShared(variantId);
    }

    // ---- load (three-tier) ----

    /** Reload bundled + config flags into {@link #current}. */
    public static synchronized void reload() {
        Set<String> bundled = load(BUNDLED_RESOURCE, true);
        Set<String> config = load(null, false);
        // Config, when present, replaces the bundled set wholesale (a whole-set override).
        Set<String> chosen = config != null ? config : (bundled != null ? bundled : Collections.emptySet());
        current = new SharedCarriageFlags(chosen);
        LOGGER.info("[DungeonTrain] Shared-carriage flags loaded — {} variant(s): {}.",
                current.ids.size(), current.ids);
    }

    public static synchronized void clear() {
        current = EMPTY;
    }

    /** Returns the parsed set, or null when the tier's file is absent. */
    private static Set<String> load(String source, boolean fromResource) {
        try (Reader reader = fromResource ? openResource(source) : openConfig()) {
            if (reader == null) return null;
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Shared-carriage flags {} is not a JSON object — ignoring.",
                        fromResource ? source : configPath());
                return Collections.emptySet();
            }
            return fromJson(root.getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read shared-carriage flags from {}: {}",
                    fromResource ? source : configPath(), e.toString());
            return null;
        }
    }

    static Set<String> fromJson(JsonObject o) {
        Set<String> out = new LinkedHashSet<>();
        JsonElement el = o.get("shared");
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    String s = item.getAsString();
                    if (!s.isBlank()) out.add(s);
                }
            }
        }
        return out;
    }

    static JsonObject toJson(Set<String> ids) {
        JsonObject o = new JsonObject();
        o.addProperty(SCHEMA_KEY, SCHEMA_VERSION);
        JsonArray arr = new JsonArray();
        for (String s : ids) arr.add(s);
        o.add("shared", arr);
        return o;
    }

    // ---- mutation + persistence ----

    /**
     * Flag {@code variantId} shared ({@code on=true}) or clear it. Persists to
     * {@code config/dungeontrain/user/shared-carriages.json} and, in dev mode, through to the bundled
     * source file. Returns the new membership.
     */
    public static synchronized boolean set(String variantId, boolean on) throws IOException {
        String key = variantId.toLowerCase(Locale.ROOT);
        Set<String> next = new LinkedHashSet<>(current.ids);
        boolean changed = on ? next.add(key) : next.remove(key);
        if (changed) {
            current = new SharedCarriageFlags(next);
            writeConfig(current);
            trySaveToSource(current);
            LOGGER.info("[DungeonTrain] Shared-carriage flag {}={} (persisted to {}).", key, on, configPath());
        }
        return on;
    }

    private static void writeConfig(SharedCarriageFlags flags) throws IOException {
        Path file = configPath();
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(toJson(flags.ids), w);
        }
    }

    private static Reader openResource(String resource) {
        InputStream in = SharedCarriageFlags.class.getResourceAsStream(resource);
        if (in == null) return null;
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openConfig() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not open shared-carriage flags file {}: {}", file, e.toString());
            return null;
        }
    }

    public static Path configPath() {
        return games.brennan.dungeontrain.editor.UserContentPaths.root().resolve(CONFIG_FILE);
    }

    public static Path sourceFile() {
        Path resources = resourcesRootOrNull();
        if (resources == null) return null;
        return resources.resolve(BUNDLED_RESOURCE.substring(1));
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void saveToSource(SharedCarriageFlags flags) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFile();
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(toJson(flags.ids), w);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled shared-carriage flags to {} (devmode promote).", file);
    }

    private static void trySaveToSource(SharedCarriageFlags flags) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(flags);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled shared-carriage flags to source tree: {} (config write succeeded).",
                    e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path projectRoot = gameDir.getParent();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
