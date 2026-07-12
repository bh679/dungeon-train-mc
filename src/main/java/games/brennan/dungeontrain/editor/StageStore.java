package games.brennan.dungeontrain.editor;
import games.brennan.dungeontrain.platform.DtPlatform;

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
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * The single, <b>global</b> registry of {@link Stage named gate presets}, loaded from two tiers and
 * <b>shipped with the mod</b>:
 * <ol>
 *   <li><b>Bundled defaults</b> — {@code /data/dungeontrain/stages.json} on the classpath, packed
 *       into the jar. These are the Stages every install starts with.</li>
 *   <li><b>Per-install override</b> — {@code <config>/dungeontrain/stages.json}
 *       ({@link UserContentPaths#legacyRoot()}, deliberately outside the {@code user/} tree the dtpack
 *       exporter walks). Player edits write here; entries override the bundled defaults by id.</li>
 * </ol>
 *
 * <p><b>Dev-mode source promotion:</b> when the editor is in dev mode ({@link EditorDevMode#isEnabled()},
 * which defaults on under {@code ./gradlew runClient} from a checkout) every edit is also written to
 * the source tree at {@code src/main/resources/data/dungeontrain/stages.json}, so author-built Stages
 * get committed alongside the rest of the mod and ship in the next build — same contract as
 * {@link LootPrefabStore} and the weight stores. In a packaged player jar the source tree is absent,
 * so edits stay in the config override only.</p>
 *
 * <p>Held in an in-memory, id-sorted snapshot ({@link #current}) so {@link #gateOf(String)} — which
 * sits on the worldgen candidate-filter path via each weight store's {@code gateFor} — is an O(1)
 * map lookup, never a file read. Mutators are {@code synchronized}, rebuild the snapshot, and
 * write-through to disk (pretty-printed, keys sorted for stable diffs). Loaded on
 * {@link ServerStartingEvent}, cleared on {@link ServerStoppedEvent}.</p>
 *
 * <p>A template linking to a Stage that does not exist here is not an error:
 * {@link #effectiveGate(TemplateGate, String)} falls back to the template's inline snapshot gate and
 * warns once per missing id.</p>
 */
public final class StageStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String CONFIG_FILE = "stages.json";
    /** Bundled (jar) defaults — shipped with the mod. */
    static final String BUNDLED_RESOURCE = "/data/dungeontrain/stages.json";
    /** Source-tree path for dev-mode promotion, relative to the project root. */
    static final String SOURCE_RELATIVE_PATH = "src/main/resources/data/dungeontrain/stages.json";

    /** Live, id-sorted, unmodifiable snapshot (bundled defaults + config overrides). Replaced wholesale on every mutation. */
    private static volatile Map<String, Stage> current = Collections.emptyMap();

    /** The bundled defaults alone (from the jar) — the baseline {@link #configDelta} diffs against so
     *  the per-install config persists only user changes, never a stale snapshot of untouched defaults. */
    private static volatile Map<String, Stage> bundled = Collections.emptyMap();

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

    /**
     * Replace the in-memory snapshot: bundled defaults first, then the per-install config override on
     * top (config entries replace bundled by id). Safe to call outside event handlers.
     */
    public static synchronized void reload() {
        TreeMap<String, Stage> bundledMap = new TreeMap<>();
        parseInto(bundledMap, openBundled(), BUNDLED_RESOURCE);
        TreeMap<String, Stage> loaded = new TreeMap<>(bundledMap);
        int config = 0;
        Path file = configPath();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = parseInto(loaded, r, file.toString());
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] Failed to read stages from {}: {}", file, e.toString());
            }
        }
        bundled = Collections.unmodifiableMap(bundledMap);
        current = Collections.unmodifiableMap(loaded);
        WARNED_MISSING.clear();
        if (bundledMap.isEmpty() && config == 0) {
            LOGGER.warn("[DungeonTrain] No stages found — bundled resource {} is missing AND no config override. "
                + "Stage links will fall back to inline gates.", BUNDLED_RESOURCE);
        }
        LOGGER.info("[DungeonTrain] Stages loaded — {} preset(s) ({} bundled, {} config overrides).",
            loaded.size(), bundledMap.size(), config);
    }

    /** Parse a stages JSON object from {@code reader} into {@code into} (override by id). Returns count. */
    private static int parseInto(TreeMap<String, Stage> into, Reader reader, String label) {
        if (reader == null) return 0;
        try (Reader r = reader) {
            JsonElement root = JsonParser.parseReader(r);
            if (root == null || !root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] {} is not a JSON object — ignoring.", label);
                return 0;
            }
            int n = 0;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                Stage s = Stage.fromJson(e.getKey(), e.getValue());
                if (s != null) { into.put(s.id(), s); n++; }
            }
            return n;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to parse stages from {}: {}", label, e.toString());
            return 0;
        }
    }

    private static Reader openBundled() {
        InputStream in = StageStore.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    /** Drop the in-memory snapshot (server stop / package reload barrier). */
    public static synchronized void clearCache() {
        current = Collections.emptyMap();
        WARNED_MISSING.clear();
        StageBlockIndex.invalidateAll();
    }

    // ---------- mutators (write-through) ----------

    /**
     * Create a new Stage {@code id} (default gate, name = id). No-op returning the existing Stage if
     * the id is already taken. Returns the resulting Stage, or {@code null} for an invalid id —
     * enforcing the documented {@code ^[a-z0-9_]{1,32}$} contract here (command args arrive via
     * {@code StringArgumentType.word()}, which also admits {@code - . +}; those must be rejected or
     * downstream consumers that embed the id into part names inherit the violation).
     */
    public static synchronized Stage add(String id) throws IOException {
        String key = normalise(id);
        if (key == null) return null;
        if (!CarriagePartRegistry.NAME_PATTERN.matcher(key).matches()) return null;
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

    /**
     * Persist the per-install <em>delta</em> (added / modified vs bundled) to the config override, then
     * (dev mode) promote the <em>full</em> map to the source tree. Writing only the delta to config —
     * not a full snapshot — means a later build's changed bundled default is never masked by a stale
     * config entry the player never touched.
     */
    private static void write(Map<String, Stage> stages) throws IOException {
        writeJson(configPath(), configDelta(stages, bundled));
        StageBlockIndex.invalidateAll();
        trySaveToSource(stages);
    }

    /**
     * The subset of {@code stages} a player install must persist as overrides: entries new to the
     * install (absent from {@code bundledDefaults}) or differing from their bundled default. Untouched
     * bundled defaults are omitted. Pure — unit-tested ({@code StageStoreDeltaTest}).
     */
    static Map<String, Stage> configDelta(Map<String, Stage> stages, Map<String, Stage> bundledDefaults) {
        TreeMap<String, Stage> delta = new TreeMap<>();
        for (Map.Entry<String, Stage> e : stages.entrySet()) {
            if (!e.getValue().equals(bundledDefaults.get(e.getKey()))) {
                delta.put(e.getKey(), e.getValue());
            }
        }
        return delta;
    }

    /** Serialise the id→Stage map to {@code file} as pretty JSON, keys sorted for stable diffs. */
    private static void writeJson(Path file, Map<String, Stage> stages) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject out = new JsonObject();
        for (Stage s : new TreeMap<>(stages).values()) {
            out.add(s.id(), s.toJson());
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(out, w);
        }
    }

    /** {@code <config>/dungeontrain/stages.json} — the per-install override, outside the dtpack-exported {@code user/} tree. */
    public static Path configPath() {
        return UserContentPaths.legacyRoot().resolve(CONFIG_FILE);
    }

    // ---------- dev-mode source promotion (ships authored Stages with the next build) ----------

    /** {@code <project>/src/main/resources/data/dungeontrain/stages.json}, or null outside a checkout. */
    public static Path sourceFile() {
        Path root = projectRootOrNull();
        return root == null ? null : root.resolve(SOURCE_RELATIVE_PATH);
    }

    /** True when the source resources tree exists and is writable (a dev checkout, not a player jar). */
    public static boolean sourceTreeAvailable() {
        Path root = projectRootOrNull();
        if (root == null) return false;
        Path resources = root.resolve("src/main/resources");
        return Files.isDirectory(resources) && Files.isWritable(resources);
    }

    /** Write the full Stage map to the bundled source file so dev edits ship next build. Throws if not writable. */
    public static synchronized void saveToSource(Map<String, Stage> stages) throws IOException {
        Path file = sourceFile();
        if (file == null || !sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        writeJson(file, stages);
        LOGGER.info("[DungeonTrain] Wrote bundled stages to {} (devmode promote).", file);
    }

    /**
     * Best-effort source-tree write invoked from every mutator: only when editor dev mode is on
     * ({@link EditorDevMode#isEnabled()}, default on under {@code runClient} from a checkout) and the
     * source tree is writable. Silent no-op in a packaged jar; warns on a dev-mode write failure so the
     * (succeeded) config write isn't masked.
     */
    private static void trySaveToSource(Map<String, Stage> stages) {
        if (!EditorDevMode.isEnabled() || !sourceTreeAvailable()) return;
        try {
            saveToSource(stages);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled stages to source tree: {} (config write succeeded).",
                e.toString());
        }
    }

    private static Path projectRootOrNull() {
        Path gameDir = DtPlatform.get().gameDir();
        return gameDir == null ? null : gameDir.getParent();
    }

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        reload();
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        clearCache();
    }
}
