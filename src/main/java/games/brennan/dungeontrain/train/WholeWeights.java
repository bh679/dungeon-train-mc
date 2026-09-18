package games.brennan.dungeontrain.train;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.template.BuilderCredit;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.template.TemplateWeightCodec;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-{@link WholeKind} weight maps for the Whole section — which room the room pick lands on,
 * which group the group pick lands on.
 *
 * <p>Mirrors {@link games.brennan.dungeontrain.track.variant.TrackVariantWeights}: one
 * {@code weights.json} per kind, bundled at {@code /data/dungeontrain/whole/<kind>/weights.json}
 * and overlaid by {@code config/dungeontrain/user/<subdir>/weights.json}. Only entries that differ
 * from the bundled record are written to the overlay ({@link TemplateWeightOverlay#diff}), and the
 * overlay is not read at all while the world has disabled custom content.</p>
 *
 * <p>Weights here decide <em>which</em> template is picked once the train has decided a slot or a
 * group is a whole one. <em>Whether</em> it is one is decided elsewhere: the {@code whole} entry
 * in the carriage weights for rooms, {@link WholeGroupSettings} for groups.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WholeWeights {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int MIN = 0;
    public static final int MAX = 100;
    public static final int DEFAULT = 1;

    private static final Map<WholeKind, Map<String, TemplateMeta>> CURRENT = new EnumMap<>(WholeKind.class);
    private static final Map<WholeKind, Map<String, TemplateMeta>> BUNDLED = new EnumMap<>(WholeKind.class);
    static {
        for (WholeKind k : WholeKind.values()) {
            CURRENT.put(k, Map.of());
            BUNDLED.put(k, Map.of());
        }
    }

    private WholeWeights() {}

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    private static TemplateMeta metaFor(WholeKind kind, String id) {
        if (id == null) return null;
        return CURRENT.get(kind).get(id.toLowerCase(Locale.ROOT));
    }

    /** Clamped weight for {@code (kind, id)}; {@link #DEFAULT} when there is no entry. */
    public static synchronized int weightFor(WholeKind kind, String id) {
        TemplateMeta m = metaFor(kind, id);
        return m == null ? DEFAULT : clamp(m.weight());
    }

    /** The effective spawn gate — the linked Stage's when Stage-linked, else the inline one. */
    public static synchronized TemplateGate gateFor(WholeKind kind, String id) {
        TemplateMeta m = metaFor(kind, id);
        if (m == null) return TemplateGate.DEFAULT;
        return StageStore.effectiveGate(m.gate(), m.stageId());
    }

    /** The Stage id linked to, or null when the gate is Custom. */
    public static synchronized String stageIdFor(WholeKind kind, String id) {
        TemplateMeta m = metaFor(kind, id);
        return m == null ? null : m.stageId();
    }

    /** Editor label — the display name when set, else the id. Never null. */
    public static synchronized String nameFor(WholeKind kind, String id) {
        if (id == null) return "";
        TemplateMeta m = metaFor(kind, id);
        return m == null || m.name() == null ? id : m.name();
    }

    /** Who originally built {@code (kind, id)}, or null when nobody is credited. */
    public static synchronized BuilderCredit builderFor(WholeKind kind, String id) {
        TemplateMeta m = metaFor(kind, id);
        return m == null ? null : m.builder();
    }

    /** Every entry of {@code kind} as loaded — merged bundled + overlay view. */
    public static synchronized Map<String, TemplateMeta> all(WholeKind kind) {
        return CURRENT.get(kind);
    }

    /** Update one weight, keeping the entry's gate, Stage link, label and credit. */
    public static synchronized int set(WholeKind kind, String id, int value) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        int clamped = clamp(value);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        next.put(key, TemplateMeta.mergeWeight(next.get(key), clamped));
        commit(kind, next);
        LOGGER.info("[DungeonTrain] Set whole {} weight {}={} (persisted to {}).", kind.id(), key, clamped, configPath(kind));
        return clamped;
    }

    /** Set the inline gate, preserving weight and Stage link. */
    public static synchronized TemplateGate setGate(WholeKind kind, String id, TemplateGate gate) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        next.put(key, TemplateMeta.mergeGate(next.get(key), gate, DEFAULT));
        commit(kind, next);
        return gate;
    }

    /** Link to a Stage, or detach to Custom when {@code stageId} is blank. Weight preserved. */
    public static synchronized String setStage(WholeKind kind, String id, String stageId) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        String link = (stageId == null || stageId.isBlank()) ? null : stageId.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        TemplateMeta prev = next.get(key);
        int weight = prev == null ? DEFAULT : prev.weight();
        TemplateGate inline = prev == null ? TemplateGate.DEFAULT : prev.gate();
        if (link == null && prev != null && prev.stageId() != null) {
            inline = StageStore.effectiveGate(inline, prev.stageId());
        }
        next.put(key, prev == null ? new TemplateMeta(weight, inline, link) : prev.withGate(inline).withStage(link));
        commit(kind, next);
        return link;
    }

    /** Set the display label (blank clears), preserving everything else. */
    public static synchronized String setName(WholeKind kind, String id, String label) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        String stored = TemplateMeta.normaliseName(label);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        next.put(key, TemplateMeta.mergeName(next.get(key), stored, DEFAULT));
        commit(kind, next);
        return stored;
    }

    /** Credit a builder (null clears), preserving everything else. */
    public static synchronized BuilderCredit setBuilder(WholeKind kind, String id, BuilderCredit builder) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        BuilderCredit stored = builder == null || !builder.known() ? null : builder;
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        next.put(key, TemplateMeta.mergeBuilder(next.get(key), stored, DEFAULT));
        commit(kind, next);
        return stored;
    }

    /** Remove the entry. True when one was removed. */
    public static synchronized boolean unset(WholeKind kind, String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> cur = CURRENT.get(kind);
        if (!cur.containsKey(key)) return false;
        Map<String, TemplateMeta> next = new HashMap<>(cur);
        next.remove(key);
        commit(kind, next);
        return true;
    }

    /** Move {@code from}'s whole entry to {@code to}. False when there was nothing to carry. */
    public static synchronized boolean rename(WholeKind kind, String from, String to) throws IOException {
        String src = from.toLowerCase(Locale.ROOT);
        String dst = to.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> cur = CURRENT.get(kind);
        TemplateMeta meta = cur.get(src);
        if (meta == null) return false;
        Map<String, TemplateMeta> next = new HashMap<>(cur);
        next.remove(src);
        next.put(dst, meta);
        commit(kind, next);
        return true;
    }

    /** Give {@code to} a copy of {@code from}'s entry (label excluded — see {@link TemplateMeta#asCopy}). */
    public static synchronized boolean copy(WholeKind kind, String from, String to) throws IOException {
        String src = from.toLowerCase(Locale.ROOT);
        String dst = to.toLowerCase(Locale.ROOT);
        TemplateMeta meta = CURRENT.get(kind).get(src);
        if (meta == null) return false;
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        next.put(dst, meta.asCopy());
        commit(kind, next);
        return true;
    }

    private static void commit(WholeKind kind, Map<String, TemplateMeta> next) throws IOException {
        CURRENT.put(kind, Map.copyOf(next));
        writeConfig(kind, next);
        trySaveToSource(kind, next);
    }

    /** Reload every kind from disk. Wired to {@link ServerStartingEvent}. */
    public static synchronized void reload() {
        for (WholeKind kind : WholeKind.values()) {
            Map<String, TemplateMeta> merged = new HashMap<>();
            int bundled = loadInto(kind, merged, true);
            BUNDLED.put(kind, Map.copyOf(merged));
            int config = loadInto(kind, merged, false);
            CURRENT.put(kind, Map.copyOf(merged));
            LOGGER.info("[DungeonTrain] Whole {} weights loaded — {} entries ({} bundled, {} config).",
                kind.id(), merged.size(), bundled, config);
        }
    }

    public static synchronized void clear() {
        for (WholeKind k : WholeKind.values()) {
            CURRENT.put(k, Map.of());
            BUNDLED.put(k, Map.of());
        }
    }

    /** Test-only seam: set one entry in memory without touching disk. */
    public static synchronized void injectForTesting(WholeKind kind, String id, TemplateMeta meta) {
        if (kind == null || id == null || meta == null) return;
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        next.put(id.toLowerCase(Locale.ROOT), meta);
        CURRENT.put(kind, Map.copyOf(next));
    }

    /** Active package's overlay file for {@code kind}. */
    public static Path configPath(WholeKind kind) {
        return UserContentPaths.activeSubDir(kind.userSubdir()).resolve(WholeKind.WEIGHTS_FILE);
    }

    public static String bundledResource(WholeKind kind) {
        return kind.bundledResourcePrefix() + WholeKind.WEIGHTS_FILE;
    }

    /** Source-tree path of the bundled weights file; null outside a dev checkout. */
    public static Path sourceFile(WholeKind kind) {
        Path resources = resourcesRootOrNull();
        return resources == null ? null : resources.resolve(bundledResource(kind).substring(1));
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    /** Write the merged map for {@code kind} into the source tree so a dev tweak ships. */
    public static synchronized void saveToSource(WholeKind kind, Map<String, TemplateMeta> weights) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFile(kind);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(TemplateWeightCodec.toJson(weights), w);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled whole {} weights to {} (devmode promote).", kind.id(), file);
    }

    private static void trySaveToSource(WholeKind kind, Map<String, TemplateMeta> weights) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(kind, weights);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled whole {} weights to source tree: {} (config write succeeded).",
                kind.id(), e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        return projectRoot == null ? null : projectRoot.resolve("src/main/resources");
    }

    private static void writeConfig(WholeKind kind, Map<String, TemplateMeta> weights) throws IOException {
        Path file = configPath(kind);
        Files.createDirectories(file.getParent());
        Map<String, TemplateMeta> overlay = TemplateWeightOverlay.diff(weights, BUNDLED.get(kind));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(TemplateWeightCodec.toJson(overlay), w);
        }
    }

    private static int loadInto(WholeKind kind, Map<String, TemplateMeta> into, boolean fromResource) {
        String where = fromResource ? bundledResource(kind) : configPath(kind).toString();
        try (Reader reader = fromResource ? openResource(kind) : openConfig(kind)) {
            if (reader == null) return 0;
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Whole weights {} is not a JSON object — ignoring.", where);
                return 0;
            }
            int added = 0;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                String id = e.getKey().toLowerCase(Locale.ROOT);
                TemplateMeta parsed = TemplateWeightCodec.parseEntry(e.getValue(), WholeWeights::clamp);
                if (parsed == null) {
                    LOGGER.warn("[DungeonTrain] Whole weight entry {}:{} in {} is invalid — ignoring.", kind.id(), id, where);
                    continue;
                }
                into.put(id, parsed);
                added++;
            }
            return added;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read whole weights for {} from {}: {}", kind.id(), where, e.toString());
            return 0;
        }
    }

    private static Reader openResource(WholeKind kind) {
        InputStream in = WholeWeights.class.getResourceAsStream(bundledResource(kind));
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openConfig(WholeKind kind) {
        if (!TemplateWeightOverlay.overlayReadable()) return null;
        Path file = configPath(kind);
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not open whole weights file {}: {}", file, e.toString());
            return null;
        }
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
