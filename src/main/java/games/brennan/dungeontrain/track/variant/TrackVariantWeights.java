package games.brennan.dungeontrain.track.variant;
import games.brennan.dungeontrain.platform.DtPlatform;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-{@link TrackKind} weight maps biasing the seeded random pick in
 * {@link TrackVariantRegistry}. Mirrors
 * {@link games.brennan.dungeontrain.train.CarriageWeights} but indexed by
 * kind — each kind has its own {@code weights.json} living next to its
 * templates so weights for the tunnel section don't bleed into the pillar
 * top.
 *
 * <p>Storage: {@code config/dungeontrain/user/<kind.subdir>/weights.json} —
 * a flat JSON object mapping name to integer weight, e.g.
 * {@code {"default": 1, "stone_section": 3}}. Bundled defaults at
 * {@code /data/dungeontrain/<kind.subdir>/weights.json} on the classpath.
 * Both files optional; missing or empty = uniform pick.</p>
 */
public final class TrackVariantWeights {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int MIN = 0;
    public static final int MAX = 100;
    public static final int DEFAULT = 1;

    /** Per-kind cache, populated on {@link #reload()}. */
    private static final Map<TrackKind, Map<String, TemplateMeta>> CURRENT = new EnumMap<>(TrackKind.class);
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
        TemplateMeta m = CURRENT.get(kind).get(name.toLowerCase(Locale.ROOT));
        if (m == null) return DEFAULT;
        return clamp(m.weight());
    }

    /**
     * The <b>effective</b> spawn {@link TemplateGate gate} for {@code (kind, name)}: the linked
     * Stage's gate when this entry is Stage-linked, else its inline gate ({@link TemplateGate#DEFAULT}
     * if none). Resolution is an O(1) {@link games.brennan.dungeontrain.editor.StageStore} lookup.
     */
    public static synchronized TemplateGate gateFor(TrackKind kind, String name) {
        if (name == null) return TemplateGate.DEFAULT;
        TemplateMeta m = CURRENT.get(kind).get(name.toLowerCase(Locale.ROOT));
        if (m == null) return TemplateGate.DEFAULT;
        return games.brennan.dungeontrain.editor.StageStore.effectiveGate(m.gate(), m.stageId());
    }

    /** The Stage id {@code (kind, name)} is linked to, or {@code null} when its gate is Custom. */
    public static synchronized String stageIdFor(TrackKind kind, String name) {
        if (name == null) return null;
        TemplateMeta m = CURRENT.get(kind).get(name.toLowerCase(Locale.ROOT));
        return m == null ? null : m.stageId();
    }

    /** Update one weight on disk and in memory, keeping the entry's gate. Returns the clamped value. */
    public static synchronized int set(TrackKind kind, String name, int value) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        int clamped = clamp(value);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        TemplateMeta prev = next.get(key);
        next.put(key, new TemplateMeta(clamped, prev == null ? TemplateGate.DEFAULT : prev.gate()));
        CURRENT.put(kind, next);
        writeConfig(kind, next);
        trySaveToSource(kind, next);
        LOGGER.info("[DungeonTrain] Set track weight {}:{}={} (persisted to {}).",
            kind.id(), key, clamped, configPath(kind));
        return clamped;
    }

    /**
     * Update the spawn {@link TemplateGate gate} for {@code (kind, name)}, preserving its weight, and
     * persist. Returns the stored gate.
     */
    public static synchronized TemplateGate setGate(TrackKind kind, String name, TemplateGate gate) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        TemplateMeta prev = next.get(key);
        int weight = prev == null ? DEFAULT : prev.weight();
        next.put(key, new TemplateMeta(weight, gate));
        CURRENT.put(kind, next);
        writeConfig(kind, next);
        trySaveToSource(kind, next);
        LOGGER.info("[DungeonTrain] Set track gate {}:{}={} (persisted to {}).",
            kind.id(), key, gate, configPath(kind));
        return gate;
    }

    /**
     * Link {@code (kind, name)} to the named Stage ({@code stageId}), or detach to Custom when
     * {@code stageId} is null (snapshotting the Stage's current gate inline). Weight preserved.
     * Persists. See {@link CarriageWeights#setStage}.
     */
    public static synchronized String setStage(TrackKind kind, String name, String stageId) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        String link = (stageId == null || stageId.isBlank()) ? null : stageId.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> next = new HashMap<>(CURRENT.get(kind));
        TemplateMeta prev = next.get(key);
        int weight = prev == null ? DEFAULT : prev.weight();
        TemplateGate inline = prev == null ? TemplateGate.DEFAULT : prev.gate();
        if (link == null && prev != null && prev.stageId() != null) {
            inline = games.brennan.dungeontrain.editor.StageStore.effectiveGate(inline, prev.stageId());
        }
        next.put(key, new TemplateMeta(weight, inline, link));
        CURRENT.put(kind, next);
        writeConfig(kind, next);
        trySaveToSource(kind, next);
        LOGGER.info("[DungeonTrain] Set track stage {}:{}={} (persisted to {}).",
            kind.id(), key, link == null ? "<custom>" : link, configPath(kind));
        return link;
    }

    /** Remove the entry for {@code (kind, name)}. Returns true if removed. */
    public static synchronized boolean unset(TrackKind kind, String name) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, TemplateMeta> cur = CURRENT.get(kind);
        if (!cur.containsKey(key)) return false;
        Map<String, TemplateMeta> next = new HashMap<>(cur);
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
            Map<String, TemplateMeta> merged = new HashMap<>();
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
        return DtPlatform.get().configDir().resolve(kind.configSubdir()).resolve(TrackKind.WEIGHTS_FILE);
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
    public static synchronized void saveToSource(TrackKind kind, Map<String, TemplateMeta> weights) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFile(kind);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create()
                .toJson(TemplateWeightCodec.toJson(weights), w);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled track weights for {} to {} (devmode promote).", kind.id(), file);
    }

    /** Best-effort source-tree write invoked from {@link #set} / {@link #unset}. */
    private static void trySaveToSource(TrackKind kind, Map<String, TemplateMeta> weights) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(kind, weights);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled track weights for {} to source tree: {} (config write succeeded).",
                kind.id(), e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path gameDir = DtPlatform.get().gameDir();
        Path projectRoot = gameDir.getParent();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    private static void writeConfig(TrackKind kind, Map<String, TemplateMeta> weights) throws IOException {
        Path file = configPath(kind);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create()
                .toJson(TemplateWeightCodec.toJson(weights), w);
        }
    }

    /**
     * Pull weight entries for {@code kind} into {@code into}. When
     * {@code fromResource} is true, reads the classpath resource; otherwise
     * the per-install config file. Returns the count of entries added.
     */
    private static int loadInto(TrackKind kind, Map<String, TemplateMeta> into, boolean fromResource) {
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
                TemplateMeta parsed = TemplateWeightCodec.parseEntry(e.getValue(), TrackVariantWeights::clamp);
                if (parsed == null) {
                    LOGGER.warn("[DungeonTrain] Track weight entry for {}:{} in {} is invalid — ignoring.",
                        kind.id(), name, fromResource ? bundledResource(kind) : configPath(kind));
                    continue;
                }
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

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        reload();
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        clear();
    }
}
