package games.brennan.dungeontrain.track.variant;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-{@link TrackKind} weight maps biasing the seeded random pick in
 * {@link TrackVariantRegistry}. Mirrors
 * {@link games.brennan.dungeontrain.train.CarriageWeights} but indexed by
 * kind — each kind has its own {@code weights.json} living next to its
 * templates so weights for the tunnel section don't bleed into the pillar
 * top.
 *
 * <p>Storage: {@code config/dungeontrain/<kind.subdir>/weights.json} —
 * a flat JSON object mapping name to integer weight, e.g.
 * {@code {"default": 1, "stone_section": 3}}. Bundled defaults at
 * {@code /data/dungeontrain/<kind.subdir>/weights.json} on the classpath.
 * Both files optional; missing or empty = uniform pick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrackVariantWeights {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int MIN = 0;
    public static final int MAX = 100;
    public static final int DEFAULT = 1;

    /** Per-kind cache, populated on {@link #reload()}. */
    private static final Map<TrackKind, Map<String, Integer>> CURRENT = new EnumMap<>(TrackKind.class);
    static {
        for (TrackKind k : TrackKind.values()) CURRENT.put(k, Map.of());
    }

    private TrackVariantWeights() {}

    public static int clamp(int value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }

    /** Clamped weight for {@code (kind, name)}; {@link #DEFAULT} if no entry. */
    public static synchronized int weightFor(TrackKind kind, String name) {
        if (name == null) return DEFAULT;
        Integer w = CURRENT.get(kind).get(name.toLowerCase(Locale.ROOT));
        if (w == null) return DEFAULT;
        return clamp(w);
    }

    /** Update one weight on disk and in memory. Returns the clamped value. */
    public static synchronized int set(TrackKind kind, String name, int value) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        int clamped = clamp(value);
        Map<String, Integer> next = new HashMap<>(CURRENT.get(kind));
        next.put(key, clamped);
        CURRENT.put(kind, next);
        writeConfig(kind, next);
        trySaveToSource(kind, next);
        LOGGER.info("[DungeonTrain] Set track weight {}:{}={} (persisted to {}).",
            kind.id(), key, clamped, configPath(kind));
        return clamped;
    }

    /** Remove the entry for {@code (kind, name)}. Returns true if removed. */
    public static synchronized boolean unset(TrackKind kind, String name) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, Integer> cur = CURRENT.get(kind);
        if (!cur.containsKey(key)) return false;
        Map<String, Integer> next = new HashMap<>(cur);
        next.remove(key);
        CURRENT.put(kind, next);
        writeConfig(kind, next);
        trySaveToSource(kind, next);
        LOGGER.info("[DungeonTrain] Cleared track weight override for {}:{} (persisted to {}).",
            kind.id(), key, configPath(kind));
        return true;
    }

    /** Reload every kind from disk. Wired to {@link ServerStartingEvent}. */
    public static synchronized void reload() {
        int total = 0;
        for (TrackKind kind : TrackKind.values()) {
            Map<String, Integer> merged = new HashMap<>();
            int bundled = loadInto(kind, merged, true);
            int config = loadInto(kind, merged, false);
            CURRENT.put(kind, Map.copyOf(merged));
            total += merged.size();
            if (bundled + config > 0) {
                LOGGER.info("[DungeonTrain] Track weights for {} loaded — {} entries ({} bundled, {} config).",
                    kind.id(), merged.size(), bundled, config);
            }
        }
        LOGGER.info("[DungeonTrain] Track weights reload complete — {} entries across {} kinds.",
            total, TrackKind.values().length);
    }

    public static synchronized void clear() {
        for (TrackKind k : TrackKind.values()) CURRENT.put(k, Map.of());
    }

    public static Path configPath(TrackKind kind) {
        return FMLPaths.CONFIGDIR.get().resolve(kind.configSubdir()).resolve(TrackKind.WEIGHTS_FILE);
    }

    public static String bundledResource(TrackKind kind) {
        return kind.bundledResourcePrefix() + TrackKind.WEIGHTS_FILE;
    }

    /**
     * Source-tree path for the bundled weights file (under
     * {@code src/main/resources/data/dungeontrain/<kind.subdir>/weights.json}).
     * Returns null outside dev mode. Mirrors
     * {@link games.brennan.dungeontrain.track.variant.TrackVariantStore#sourceFileFor}.
     */
    public static Path sourceFile(TrackKind kind) {
        Path resources = resourcesRootOrNull();
        if (resources == null) return null;
        return resources.resolve(bundledResource(kind).substring(1));
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    /**
     * Write the per-{@code kind} weights map to {@link #sourceFile(TrackKind)}
     * so a dev's in-game tweak ships with the next build. Throws if the source
     * tree isn't writable.
     */
    public static synchronized void saveToSource(TrackKind kind, Map<String, Integer> weights) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFile(kind);
        Files.createDirectories(file.getParent());
        Map<String, Integer> sorted = new TreeMap<>(weights);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(sorted, w);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled track weights for {} to {} (devmode promote).", kind.id(), file);
    }

    /** Best-effort source-tree write invoked from {@link #set} / {@link #unset}. */
    private static void trySaveToSource(TrackKind kind, Map<String, Integer> weights) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(kind, weights);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled track weights for {} to source tree: {} (config write succeeded).",
                kind.id(), e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path projectRoot = gameDir.getParent();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    private static void writeConfig(TrackKind kind, Map<String, Integer> weights) throws IOException {
        Path file = configPath(kind);
        Files.createDirectories(file.getParent());
        Map<String, Integer> sorted = new TreeMap<>(weights);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(sorted, w);
        }
    }

    /**
     * Pull weight entries for {@code kind} into {@code into}. When
     * {@code fromResource} is true, reads the classpath resource; otherwise
     * the per-install config file. Returns the count of entries added.
     */
    private static int loadInto(TrackKind kind, Map<String, Integer> into, boolean fromResource) {
        try (Reader reader = fromResource ? openResource(kind) : openConfig(kind)) {
            if (reader == null) return 0;
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Track weights {} is not a JSON object — ignoring.",
                    fromResource ? bundledResource(kind) : configPath(kind));
                return 0;
            }
            JsonObject obj = root.getAsJsonObject();
            int added = 0;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String name = e.getKey().toLowerCase(Locale.ROOT);
                Integer parsed = parseWeight(kind, name, e.getValue(), fromResource);
                if (parsed == null) continue;
                into.put(name, parsed);
                added++;
            }
            return added;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read track weights for {} from {}: {}",
                kind.id(), fromResource ? bundledResource(kind) : configPath(kind), e.toString());
            return 0;
        }
    }

    private static Reader openResource(TrackKind kind) {
        InputStream in = TrackVariantWeights.class.getResourceAsStream(bundledResource(kind));
        if (in == null) return null;
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openConfig(TrackKind kind) {
        Path file = configPath(kind);
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not open track weights file {}: {}", file, e.toString());
            return null;
        }
    }

    private static Integer parseWeight(TrackKind kind, String name, JsonElement el, boolean fromResource) {
        String origin = fromResource ? "bundled" : "config";
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            LOGGER.warn("[DungeonTrain] Track weight for {}:{} in {} is not a number — ignoring.",
                kind.id(), name, origin);
            return null;
        }
        double raw;
        try {
            raw = el.getAsDouble();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Track weight for {}:{} in {} failed to parse: {}",
                kind.id(), name, origin, e.toString());
            return null;
        }
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return null;
        int rounded = (int) Math.round(raw);
        int clamped = clamp(rounded);
        if (clamped != rounded) {
            LOGGER.warn("[DungeonTrain] Track weight {}:{} in {} clamped from {} to {} (range {}..{}).",
                kind.id(), name, origin, rounded, clamped, MIN, MAX);
        }
        return clamped;
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
