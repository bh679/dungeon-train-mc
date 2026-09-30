package games.brennan.dungeontrain.tunnel;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.template.TemplateWeightOverlay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
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
import java.util.Map;
import java.util.TreeMap;

/**
 * The tunnel group registry: which template groups exist and how likely a tunnel is to roll each —
 * plus the weight of the implicit ungrouped pool. Membership itself lives on each template's
 * {@code weights.json} entry ({@link TemplateMeta#groups()}); this store only weights the groups.
 *
 * <p>Storage: {@code config/dungeontrain/user/tunnels/groups.json}, falling back to the bundled
 * {@code /data/dungeontrain/tunnels/groups.json}, falling back to empty (every group at
 * {@link #DEFAULT_WEIGHT}):</p>
 *
 * <pre>{ "groups": { "stone": 3, "brick": { "weight": 1, "minLevel": 3, "phases": ["NETHER"] } },
 *   "ungroupedWeight": 1 }</pre>
 *
 * <p>Each group is a {@link TemplateMeta} in the template weight format — a bare weight, or an object
 * once it has a spawn gate or a Stage link, the same spawn rules a template carries.</p>
 *
 * <p>The config copy, when present, is the whole registry rather than an overlay — a group deleted
 * in the editor must stay deleted. A group a template names but this registry does not is still a
 * real group, rolled at {@link #DEFAULT_WEIGHT}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TunnelGroupStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int MIN = 0;
    public static final int MAX = 100;
    public static final int DEFAULT_WEIGHT = 1;

    static final String FILE = "groups.json";
    static final String SUBDIR = "tunnels";
    static final String BUNDLED_RESOURCE = "/data/dungeontrain/" + SUBDIR + "/" + FILE;
    static final String K_GROUPS = "groups";
    static final String K_UNGROUPED = "ungroupedWeight";

    /**
     * Immutable snapshot of the registry: each group's {@link TemplateMeta} — its roll weight, its
     * inline spawn gate (Diff-Level band and phases) and its optional Stage link, exactly the spawn
     * rules a template carries — plus the ungrouped pool's weight.
     */
    public record Registry(Map<String, TemplateMeta> entries, int ungroupedWeight) {
        public static final Registry EMPTY = new Registry(Map.of(), DEFAULT_WEIGHT);

        public Registry {
            entries = Map.copyOf(entries);
            ungroupedWeight = clamp(ungroupedWeight);
        }

        /** A registry of weight-only groups (no gates) — tests and simple callers. */
        public static Registry ofWeights(Map<String, Integer> weights, int ungroupedWeight) {
            Map<String, TemplateMeta> out = new TreeMap<>();
            weights.forEach((id, w) -> out.put(id, TemplateMeta.of(clamp(w))));
            return new Registry(out, ungroupedWeight);
        }

        /** Every registered group's roll weight, by id. */
        public Map<String, Integer> groups() {
            Map<String, Integer> out = new TreeMap<>();
            entries.forEach((id, m) -> out.put(id, m.weight()));
            return out;
        }

        /** Weight a tunnel rolls {@code id} at — {@link #DEFAULT_WEIGHT} for a group not listed. */
        public int weightOf(String id) {
            TemplateMeta m = entries.get(id);
            return m == null ? DEFAULT_WEIGHT : m.weight();
        }

        /** {@code id}'s entry, or a default-gated one at {@link #DEFAULT_WEIGHT} for a group not listed. */
        public TemplateMeta metaOf(String id) {
            TemplateMeta m = entries.get(id);
            return m != null ? m : TemplateMeta.of(DEFAULT_WEIGHT);
        }

        /** {@code id}'s effective spawn gate: its linked Stage's when linked, else its inline one. */
        public games.brennan.dungeontrain.template.TemplateGate gateOf(String id) {
            TemplateMeta m = metaOf(id);
            return games.brennan.dungeontrain.editor.StageStore.effectiveGate(m.gate(), m.stageId());
        }

        Registry withMeta(String id, TemplateMeta meta) {
            Map<String, TemplateMeta> next = new TreeMap<>(entries);
            next.put(id, meta.withWeight(clamp(meta.weight())));
            return new Registry(next, ungroupedWeight);
        }

        Registry withGroup(String id, int weight) {
            return withMeta(id, metaOf(id).withWeight(weight));
        }

        Registry withoutGroup(String id) {
            Map<String, TemplateMeta> next = new TreeMap<>(entries);
            next.remove(id);
            return new Registry(next, ungroupedWeight);
        }

        Registry withUngroupedWeight(int weight) {
            return new Registry(entries, weight);
        }
    }

    private static volatile Registry current = Registry.EMPTY;

    private TunnelGroupStore() {}

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    public static Registry current() {
        return current;
    }

    /** Register {@code id} at {@link #DEFAULT_WEIGHT} unless it already exists. Returns false if it did. */
    public static synchronized boolean create(String id) throws IOException {
        if (current.entries().containsKey(id)) return false;
        save(current.withGroup(id, DEFAULT_WEIGHT));
        return true;
    }

    /** Set (registering if needed) the roll weight of {@code id}. Returns the clamped value. */
    public static synchronized int setWeight(String id, int weight) throws IOException {
        save(current.withGroup(id, weight));
        return current.weightOf(id);
    }

    /** Set the roll weight of the ungrouped pool. Returns the clamped value. */
    public static synchronized int setUngroupedWeight(int weight) throws IOException {
        save(current.withUngroupedWeight(weight));
        return current.ungroupedWeight();
    }

    /**
     * Re-key {@code from} to {@code to}, keeping its weight (a group only templates named is
     * registered at the default). Memberships are the caller's to move. Returns false when
     * {@code to} is already registered.
     */
    public static synchronized boolean rename(String from, String to) throws IOException {
        if (current.entries().containsKey(to)) return false;
        TemplateMeta meta = current.metaOf(from);
        save(current.withoutGroup(from).withMeta(to, meta));
        return true;
    }

    /** Drop {@code id} from the registry. Memberships are the caller's to strip. Returns false if absent. */
    public static synchronized boolean delete(String id) throws IOException {
        if (!current.entries().containsKey(id)) return false;
        save(current.withoutGroup(id));
        return true;
    }

    /**
     * Change {@code id}'s inline spawn gate (registering it if needed), keeping its weight and Stage
     * link. Returns the stored gate. Inert while the group is Stage-linked, as a template's is.
     */
    public static synchronized games.brennan.dungeontrain.template.TemplateGate setGate(
            String id, java.util.function.UnaryOperator<games.brennan.dungeontrain.template.TemplateGate> op)
            throws IOException {
        TemplateMeta meta = current.metaOf(id);
        TemplateMeta next = meta.withGate(op.apply(meta.gate()));
        save(current.withMeta(id, next));
        return next.gate();
    }

    /**
     * Link {@code id} to the Stage {@code stageId}, or detach it to Custom when null — snapshotting the
     * Stage's gate inline, as a template's detach does. Keeps the weight. Returns the stored link.
     */
    public static synchronized String setStage(String id, String stageId) throws IOException {
        TemplateMeta meta = current.metaOf(id);
        String link = stageId == null || stageId.isBlank() ? null : stageId.toLowerCase(java.util.Locale.ROOT);
        games.brennan.dungeontrain.template.TemplateGate inline = meta.gate();
        if (link == null && meta.stageId() != null) {
            inline = games.brennan.dungeontrain.editor.StageStore.effectiveGate(inline, meta.stageId());
        }
        save(current.withMeta(id, meta.withGate(inline).withStage(link)));
        return link;
    }

    /** Test-only seam: replace the in-memory registry without touching disk. */
    public static synchronized void injectForTesting(Registry registry) {
        current = registry == null ? Registry.EMPTY : registry;
    }

    public static synchronized void reload() {
        Registry fromConfig = TemplateWeightOverlay.overlayReadable() ? read(openConfig(), configPath().toString()) : null;
        Registry loaded = fromConfig != null ? fromConfig : read(openResource(), BUNDLED_RESOURCE);
        current = loaded != null ? loaded : Registry.EMPTY;
        LOGGER.info("[DungeonTrain] Tunnel groups loaded — {} group(s), ungrouped weight {}.",
            current.groups().size(), current.ungroupedWeight());
    }

    public static synchronized void clear() {
        current = Registry.EMPTY;
    }

    public static Path configPath() {
        return games.brennan.dungeontrain.editor.UserContentPaths.activeSubDir(SUBDIR).resolve(FILE);
    }

    // ---- JSON ----

    static JsonObject toJson(Registry r) {
        JsonObject root = new JsonObject();
        // Each group in the template weight format: a bare weight while it has no gate or Stage, an
        // object once it has — so registries written before groups had gates read unchanged.
        root.add(K_GROUPS, games.brennan.dungeontrain.template.TemplateWeightCodec.toJson(r.entries()));
        root.addProperty(K_UNGROUPED, r.ungroupedWeight());
        return root;
    }

    /** Parse a registry document; malformed entries are skipped, a non-object root yields null. */
    static Registry fromJson(JsonElement root) {
        if (root == null || !root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        Map<String, TemplateMeta> groups = new TreeMap<>();
        JsonElement g = o.get(K_GROUPS);
        if (g != null && g.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : g.getAsJsonObject().entrySet()) {
                String id = TemplateMeta.normaliseGroupId(e.getKey());
                TemplateMeta meta = id == null ? null
                    : games.brennan.dungeontrain.template.TemplateWeightCodec.parseEntry(e.getValue(), TunnelGroupStore::clamp);
                if (meta == null) {
                    LOGGER.warn("[DungeonTrain] Tunnel group entry '{}' is invalid — ignoring.", e.getKey());
                    continue;
                }
                groups.put(id, meta);
            }
        }
        JsonElement u = o.get(K_UNGROUPED);
        int ungrouped = u != null && u.isJsonPrimitive() && u.getAsJsonPrimitive().isNumber()
            ? u.getAsInt() : DEFAULT_WEIGHT;
        return new Registry(groups, ungrouped);
    }

    // ---- IO ----

    private static void save(Registry next) throws IOException {
        write(configPath(), next);
        current = next;
        Path source = sourceFileOrNull();
        if (source != null) {
            try {
                write(source, next);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Could not write tunnel groups to the source tree: {} (config write succeeded).",
                    e.toString());
            }
        }
    }

    private static void write(Path file, Registry r) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(toJson(r), w);
        }
    }

    private static Registry read(Reader reader, String where) {
        if (reader == null) return null;
        try (Reader r = reader) {
            Registry parsed = fromJson(JsonParser.parseReader(r));
            if (parsed == null) LOGGER.warn("[DungeonTrain] Tunnel groups {} is not a JSON object — ignoring.", where);
            return parsed;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read tunnel groups from {}: {}", where, e.toString());
            return null;
        }
    }

    private static Reader openConfig() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Could not open tunnel groups file {}: {}", file, e.toString());
            return null;
        }
    }

    private static Reader openResource() {
        InputStream in = TunnelGroupStore.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    /** The bundled copy in a dev checkout (so an in-game edit ships), or null outside one. */
    private static Path sourceFileOrNull() {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return resources.resolve(BUNDLED_RESOURCE.substring(1));
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
