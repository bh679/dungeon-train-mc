package games.brennan.dungeontrain.train;
import games.brennan.dungeontrain.platform.DtPlatform;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.template.TemplateWeightCodec;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-id weight map biasing the seeded random pick in
 * {@link CarriageContentsRegistry#pick}. Mirrors {@link CarriageWeights}
 * but for carriage <em>interior</em> contents rather than the carriage
 * shell — same MIN/MAX/DEFAULT contract, same two-tier load (bundled
 * resource + per-install config override), same flat JSON schema.
 *
 * <p>Loaded from two tiers at {@link ServerStartingEvent}:
 * <ol>
 *   <li><b>Bundled default</b> — {@code /data/dungeontrain/contents/weights.json}
 *       on the classpath. Ships with the mod jar.</li>
 *   <li><b>Per-install override</b> — {@code config/dungeontrain/user/contents/weights.json}.
 *       Per-id entries in this file replace entries from the bundled copy.</li>
 * </ol>
 *
 * <p>Schema: a flat JSON object mapping contents id to a non-negative integer.
 * Weight 0 excludes the contents from the spawn pool entirely; missing ids
 * resolve to {@link #DEFAULT} (= 1) which matches the pre-feature uniform
 * pick when every contents is at default weight.</p>
 */
public record CarriageContentsWeights(Map<String, TemplateMeta> byId) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Minimum allowed weight. 0 excludes a contents from the pool. */
    public static final int MIN = 0;
    /** Maximum allowed weight. Bounds the cumulative sum and keeps the distribution readable. */
    public static final int MAX = 100;
    /** Weight assumed for any contents not mentioned in the map. 1 matches uniform behaviour when every entry defaults. */
    public static final int DEFAULT = 1;

    /** Shared empty instance — identity weight (1) for every id. */
    public static final CarriageContentsWeights EMPTY = new CarriageContentsWeights(Map.of());

    static final String BUNDLED_RESOURCE = "/data/dungeontrain/contents/weights.json";
    static final String CONFIG_SUBDIR = "contents";
    static final String CONFIG_FILE = "weights.json";

    /** Cached weights for the active server. Loaded on ServerStartingEvent, cleared on stop. */
    private static volatile CarriageContentsWeights current = EMPTY;

    public CarriageContentsWeights {
        byId = Map.copyOf(byId);
    }

    /**
     * Build a weights table from a plain {@code id → weight} map, every entry with the default
     * (eligible-everywhere) {@link TemplateGate}. The in-memory equivalent of the bare-int JSON
     * legacy form; convenient for callers/tests that only set weights.
     */
    public static CarriageContentsWeights ofWeights(Map<String, Integer> weights) {
        Map<String, TemplateMeta> m = new HashMap<>();
        weights.forEach((k, v) -> m.put(k, TemplateMeta.of(v)));
        return new CarriageContentsWeights(m);
    }

    /**
     * Return the clamped weight for {@code id}, or {@link #DEFAULT} if not in the map.
     * Always in {@code [MIN, MAX]}.
     */
    public int weightFor(String id) {
        TemplateMeta m = byId.get(id);
        if (m == null) return DEFAULT;
        return clamp(m.weight());
    }

    /**
     * The <b>effective</b> spawn {@link TemplateGate gate} for {@code id}: the linked Stage's gate
     * when this entry is Stage-linked, else its inline gate ({@link TemplateGate#DEFAULT} if absent).
     * Resolution is an O(1) {@link games.brennan.dungeontrain.editor.StageStore} map lookup.
     */
    public TemplateGate gateFor(String id) {
        TemplateMeta m = byId.get(id);
        if (m == null) return TemplateGate.DEFAULT;
        return games.brennan.dungeontrain.editor.StageStore.effectiveGate(m.gate(), m.stageId());
    }

    /** The Stage id this contents is linked to, or {@code null} when it has a Custom inline gate. */
    public String stageIdFor(String id) {
        TemplateMeta m = byId.get(id);
        return m == null ? null : m.stageId();
    }

    public static int clamp(int value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }

    /** The active server's weights. {@link #EMPTY} outside a server session. */
    public static CarriageContentsWeights current() {
        return current;
    }

    /** Reload bundled + config weights into {@link #current}. Safe to call outside event handlers. */
    public static synchronized void reload() {
        Map<String, TemplateMeta> merged = new HashMap<>();
        int bundled = loadInto(BUNDLED_RESOURCE, merged, true);
        int config = loadInto(null, merged, false);
        current = new CarriageContentsWeights(merged);
        LOGGER.info("[DungeonTrain] Carriage contents weights loaded — {} entries ({} bundled, {} config overlays).",
                merged.size(), bundled, config);
    }

    public static synchronized void clear() {
        current = EMPTY;
    }

    /**
     * Update the weight for {@code id} in-memory and persist the full weights
     * map to {@code config/dungeontrain/user/contents/weights.json}. The new weight
     * takes effect for the next carriage that spawns. Returns the clamped
     * value that was stored.
     */
    public static synchronized int set(String id, int value) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        int clamped = clamp(value);
        Map<String, TemplateMeta> next = new HashMap<>(current.byId());
        TemplateMeta prev = next.get(key);
        next.put(key, new TemplateMeta(clamped, prev == null ? TemplateGate.DEFAULT : prev.gate()));
        current = new CarriageContentsWeights(next);
        writeConfig(current);
        trySaveToSource(current);
        LOGGER.info("[DungeonTrain] Set carriage contents weight {}={} (persisted to {}).",
                key, clamped, configPath());
        return clamped;
    }

    /**
     * Update the spawn {@link TemplateGate gate} (min/max Diff-Level + phase set) for {@code id},
     * preserving its current weight, and persist. Returns the stored gate.
     */
    public static synchronized TemplateGate setGate(String id, TemplateGate gate) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> next = new HashMap<>(current.byId());
        TemplateMeta prev = next.get(key);
        int weight = prev == null ? DEFAULT : prev.weight();
        next.put(key, new TemplateMeta(weight, gate));
        current = new CarriageContentsWeights(next);
        writeConfig(current);
        trySaveToSource(current);
        LOGGER.info("[DungeonTrain] Set carriage contents gate {}={} (persisted to {}).",
                key, gate, configPath());
        return gate;
    }

    /**
     * Link {@code id} to the named Stage ({@code stageId}), or detach to Custom when {@code stageId}
     * is null (snapshotting the Stage's current gate inline). Weight preserved. Persists. See
     * {@link CarriageWeights#setStage}.
     */
    public static synchronized String setStage(String id, String stageId) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        String link = (stageId == null || stageId.isBlank()) ? null : stageId.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> next = new HashMap<>(current.byId());
        TemplateMeta prev = next.get(key);
        int weight = prev == null ? DEFAULT : prev.weight();
        TemplateGate inline = prev == null ? TemplateGate.DEFAULT : prev.gate();
        if (link == null && prev != null && prev.stageId() != null) {
            inline = games.brennan.dungeontrain.editor.StageStore.effectiveGate(inline, prev.stageId());
        }
        next.put(key, new TemplateMeta(weight, inline, link));
        current = new CarriageContentsWeights(next);
        writeConfig(current);
        trySaveToSource(current);
        LOGGER.info("[DungeonTrain] Set carriage contents stage {}={} (persisted to {}).",
                key, link == null ? "<custom>" : link, configPath());
        return link;
    }

    /**
     * Remove an override for {@code id} (reverts it to {@link #DEFAULT} on the
     * next lookup). Persists immediately. Returns true if an entry was removed.
     */
    public static synchronized boolean unset(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        if (!current.byId().containsKey(key)) return false;
        Map<String, TemplateMeta> next = new HashMap<>(current.byId());
        next.remove(key);
        current = new CarriageContentsWeights(next);
        writeConfig(current);
        trySaveToSource(current);
        LOGGER.info("[DungeonTrain] Cleared carriage contents weight override for {} (persisted to {}).",
                key, configPath());
        return true;
    }

    private static void writeConfig(CarriageContentsWeights weights) throws IOException {
        Path file = configPath();
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create()
                    .toJson(TemplateWeightCodec.toJson(weights.byId()), w);
        }
    }

    private static int loadInto(String source, Map<String, TemplateMeta> into, boolean fromResource) {
        try (Reader reader = fromResource ? openResource(source) : openConfig()) {
            if (reader == null) return 0;
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Contents weights {} is not a JSON object — ignoring.",
                        fromResource ? source : configPath());
                return 0;
            }
            JsonObject obj = root.getAsJsonObject();
            int added = 0;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String id = e.getKey().toLowerCase(Locale.ROOT);
                TemplateMeta parsed = TemplateWeightCodec.parseEntry(e.getValue(), CarriageContentsWeights::clamp);
                if (parsed == null) {
                    LOGGER.warn("[DungeonTrain] Contents weight entry for '{}' in {} is invalid — ignoring.",
                            id, fromResource ? source : configPath());
                    continue;
                }
                into.put(id, parsed);
                added++;
            }
            return added;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read contents weights from {}: {}",
                    fromResource ? source : configPath(), e.toString());
            return 0;
        }
    }

    private static Reader openResource(String resource) {
        InputStream in = CarriageContentsWeights.class.getResourceAsStream(resource);
        if (in == null) return null;
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openConfig() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not open contents weights file {}: {}", file, e.toString());
            return null;
        }
    }

    public static Path configPath() {
        return games.brennan.dungeontrain.editor.UserContentPaths.dir(CONFIG_SUBDIR).resolve(CONFIG_FILE);
    }

    /**
     * Source-tree path for the bundled weights file (under
     * {@code src/main/resources/data/dungeontrain/contents/}). Returns null
     * outside dev mode.
     */
    public static Path sourceFile() {
        Path resources = resourcesRootOrNull();
        if (resources == null) return null;
        return resources.resolve(BUNDLED_RESOURCE.substring(1));
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    /**
     * Write {@code weights} to {@link #sourceFile()} so a dev's in-game tweak
     * ships with the next build. Throws if the source tree isn't writable.
     */
    public static synchronized void saveToSource(CarriageContentsWeights weights) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFile();
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create()
                    .toJson(TemplateWeightCodec.toJson(weights.byId()), w);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled contents weights to {} (devmode promote).", file);
    }

    /** Best-effort source-tree write invoked from {@link #set} / {@link #unset}. */
    private static void trySaveToSource(CarriageContentsWeights weights) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(weights);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled contents weights to source tree: {} (config write succeeded).",
                e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path gameDir = DtPlatform.get().gameDir();
        Path projectRoot = gameDir.getParent();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        reload();
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        clear();
    }
}
