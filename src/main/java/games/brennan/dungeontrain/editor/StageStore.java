package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single, <b>global</b> registry of {@link Stage named gate presets}. Unlike the per-package
 * editor content stores, Stages live once per install at
 * {@code <config>/dungeontrain/stages.json} ({@link UserContentPaths#legacyRoot()}, deliberately
 * <em>outside</em> the {@code user/} tree the dtpack exporter walks) so they are shared across every
 * world and content package and do <b>not</b> travel inside exported dtpacks.
 *
 * <p>Held in an in-memory, id-sorted snapshot ({@link #current}) so {@link #gateOf(String)} — which
 * sits on the worldgen candidate-filter path via each weight store's {@code gateFor} — is an O(1)
 * map lookup, never a file read. Mutators are {@code synchronized}, rebuild the snapshot, and
 * write-through to disk (pretty-printed, keys sorted for stable diffs). Loaded on
 * {@link ServerStartingEvent}, cleared on {@link ServerStoppedEvent}.</p>
 *
 * <p>A template linking to a Stage that does not exist here (e.g. a dtpack authored on another
 * install) is not an error: {@link #effectiveGate(TemplateGate, String)} falls back to the
 * template's inline snapshot gate and warns once per missing id.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StageStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String CONFIG_FILE = "stages.json";

    /** Live, id-sorted, unmodifiable snapshot. Replaced wholesale on every mutation. */
    private static volatile Map<String, Stage> current = Collections.emptyMap();

    /** Missing-stage ids already warned about, so a dangling link logs at most once per id. */
    private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();

    private StageStore() {}

    // ---------- reads ----------

    /** The Stage for {@code id}, if present. */
    public static Optional<Stage> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(current.get(id.toLowerCase(Locale.ROOT)));
    }

    /** The gate of the Stage {@code id}, if that Stage exists. */
    public static Optional<TemplateGate> gateOf(String id) {
        return get(id).map(Stage::gate);
    }

    /** True iff a Stage with this id exists. */
    public static boolean exists(String id) {
        return id != null && current.containsKey(id.toLowerCase(Locale.ROOT));
    }

    /** All Stages, sorted by id — drives the management list, the picker, and command suggestions. */
    public static List<Stage> allStages() {
        return new ArrayList<>(current.values());
    }

    /** All Stage ids, sorted — for Brigadier suggestion providers. */
    public static List<String> allIds() {
        return new ArrayList<>(current.keySet());
    }

    /**
     * Resolve the <b>effective</b> gate for a (possibly Stage-linked) template entry: the linked
     * Stage's gate when {@code stageId} names an existing Stage, else the inline {@code fallback}
     * (the Custom gate, or the detach snapshot of a now-deleted Stage). Pure map lookup — safe on the
     * worldgen hot path. Warns once when a link dangles.
     */
    public static TemplateGate effectiveGate(TemplateGate fallback, String stageId) {
        TemplateGate inline = fallback == null ? TemplateGate.DEFAULT : fallback;
        if (stageId == null) return inline;
        Stage s = current.get(stageId.toLowerCase(Locale.ROOT));
        if (s != null) return s.gate();
        if (WARNED_MISSING.add(stageId.toLowerCase(Locale.ROOT))) {
            LOGGER.warn("[DungeonTrain] Template links to missing Stage '{}' — using its inline gate.",
                stageId);
        }
        return inline;
    }

    // ---------- lifecycle ----------

    /** Replace the in-memory snapshot from the config file. Safe to call outside event handlers. */
    public static synchronized void reload() {
        TreeMap<String, Stage> loaded = new TreeMap<>();
        Path file = configPath();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                if (root != null && root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                        Stage s = Stage.fromJson(e.getKey(), e.getValue());
                        if (s != null) loaded.put(s.id(), s);
                    }
                } else {
                    LOGGER.warn("[DungeonTrain] {} is not a JSON object — ignoring.", file);
                }
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] Failed to read stages from {}: {}", file, e.toString());
            }
        }
        current = Collections.unmodifiableMap(loaded);
        WARNED_MISSING.clear();
        LOGGER.info("[DungeonTrain] Stages loaded — {} preset(s).", loaded.size());
    }

    /** Drop the in-memory snapshot (server stop / package reload barrier). */
    public static synchronized void clearCache() {
        current = Collections.emptyMap();
        WARNED_MISSING.clear();
    }

    // ---------- mutators (write-through) ----------

    /**
     * Create a new Stage {@code id} (default gate, name = id). No-op returning the existing Stage if
     * the id is already taken. Returns the resulting Stage, or {@code null} for an invalid id.
     */
    public static synchronized Stage add(String id) throws IOException {
        String key = normalise(id);
        if (key == null) return null;
        Stage existing = current.get(key);
        if (existing != null) return existing;
        Stage created = new Stage(key, key, TemplateGate.DEFAULT);
        putAndWrite(created);
        LOGGER.info("[DungeonTrain] Added stage '{}'.", key);
        return created;
    }

    /** Upsert {@code stage} (keyed by its id) and persist. */
    public static synchronized void save(Stage stage) throws IOException {
        if (stage == null || stage.id().isBlank()) return;
        putAndWrite(stage);
    }

    /** Replace the gate of Stage {@code id} (creating it if absent) and persist. Returns the Stage. */
    public static synchronized Stage setGate(String id, TemplateGate gate) throws IOException {
        String key = normalise(id);
        if (key == null) return null;
        Stage prev = current.get(key);
        Stage next = (prev == null) ? new Stage(key, key, gate) : prev.withGate(gate);
        putAndWrite(next);
        return next;
    }

    /** Replace the display name of Stage {@code id} (no id change) and persist. Returns the Stage. */
    public static synchronized Stage rename(String id, String newName) throws IOException {
        String key = normalise(id);
        if (key == null) return null;
        Stage prev = current.get(key);
        if (prev == null) return null;
        Stage next = prev.withName(newName);
        putAndWrite(next);
        return next;
    }

    /** Remove Stage {@code id} and persist. Returns true if it existed. Links to it then dangle. */
    public static synchronized boolean delete(String id) throws IOException {
        String key = normalise(id);
        if (key == null || !current.containsKey(key)) return false;
        TreeMap<String, Stage> next = new TreeMap<>(current);
        next.remove(key);
        current = Collections.unmodifiableMap(next);
        write(current);
        LOGGER.info("[DungeonTrain] Deleted stage '{}'.", key);
        return true;
    }

    // ---------- internals ----------

    private static String normalise(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static void putAndWrite(Stage stage) throws IOException {
        TreeMap<String, Stage> next = new TreeMap<>(current);
        next.put(stage.id(), stage);
        current = Collections.unmodifiableMap(next);
        WARNED_MISSING.remove(stage.id());
        write(current);
    }

    private static void write(Map<String, Stage> stages) throws IOException {
        Path file = configPath();
        Files.createDirectories(file.getParent());
        JsonObject out = new JsonObject();
        for (Stage s : new TreeMap<>(stages).values()) {
            out.add(s.id(), s.toJson());
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(out, w);
        }
    }

    /** {@code <config>/dungeontrain/stages.json} — global, outside the dtpack-exported {@code user/} tree. */
    public static Path configPath() {
        return UserContentPaths.legacyRoot().resolve(CONFIG_FILE);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearCache();
    }
}
