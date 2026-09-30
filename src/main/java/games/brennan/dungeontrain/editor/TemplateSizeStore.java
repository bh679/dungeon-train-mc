package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.TemplateWeightOverlay;
import games.brennan.dungeontrain.train.ContentsSize;
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
import java.util.function.Supplier;

/**
 * The declared {@link ContentsSize} of each template id — one {@code sizes.json} manifest per kind.
 *
 * <p>Two instances: {@link #CONTENTS} (what a contents template is authored against) and
 * {@link #SHELLS} (how long a carriage template is). An id absent from both tiers is
 * {@link ContentsSize#ROOM}, which is every template made before sizes existed.</p>
 *
 * <p>Two tiers, merged per id with the config dir winning: the bundled resource, then the user file.
 * A size is chosen when a template is created and never edited afterwards — a template captured at
 * one box fails the size gate at any other — so the only writes are create, copy and forget.</p>
 *
 * <pre>{"schemaVersion": 1, "sizes": {"portal": "half"}}</pre>
 */
public final class TemplateSizeStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int SCHEMA_VERSION = 1;

    public static final TemplateSizeStore CONTENTS = new TemplateSizeStore("contents",
        "/data/dungeontrain/contents/sizes.json",
        () -> UserContentPaths.dir("contents").resolve("sizes.json"));

    public static final TemplateSizeStore SHELLS = new TemplateSizeStore("carriage",
        "/data/dungeontrain/templates/sizes.json",
        () -> UserContentPaths.root().resolve("template-sizes.json"));

    private final String label;
    private final String bundledResource;
    private final Supplier<Path> configPath;

    private Map<String, ContentsSize> bundled;
    private Map<String, ContentsSize> config;
    /** Bumped on every change, so layouts memoised on sizes can tell they are stale. */
    private int version;

    private TemplateSizeStore(String label, String bundledResource, Supplier<Path> configPath) {
        this.label = label;
        this.bundledResource = bundledResource;
        this.configPath = configPath;
    }

    /** {@code id}'s declared size, {@link ContentsSize#ROOM} when none is declared. */
    public synchronized ContentsSize sizeOf(String id) {
        return explicit(id).orElse(ContentsSize.ROOM);
    }

    /** {@code id}'s declared size, empty when neither tier names it. */
    public synchronized Optional<ContentsSize> explicit(String id) {
        ensureLoaded();
        ContentsSize fromConfig = config.get(id);
        if (fromConfig != null) return Optional.of(fromConfig);
        return Optional.ofNullable(bundled.get(id));
    }

    /**
     * Declare {@code id} as {@code size} in the config tier, and in the source tree when this is a dev
     * checkout, so a bundled template made in dev mode ships its size with it.
     */
    public synchronized void set(String id, ContentsSize size) throws IOException {
        ensureLoaded();
        Map<String, ContentsSize> next = new TreeMap<>(config);
        if (size == ContentsSize.ROOM && !bundled.containsKey(id)) next.remove(id);
        else next.put(id, size);
        write(configPath.get(), next);
        config = Map.copyOf(next);
        version++;
        trySaveToSource(id, size);
        LOGGER.info("[DungeonTrain] {} size of '{}' = {}", label, id, size.key());
    }

    /** Give {@code to} whatever size {@code from} has — a duplicate is the same box as its source. */
    public synchronized void copy(String from, String to) throws IOException {
        ContentsSize size = sizeOf(from);
        if (size != ContentsSize.ROOM || explicit(to).isPresent()) set(to, size);
    }

    /** Drop the config-tier entry for a deleted {@code id}, so a later template of that name starts as Room. */
    public synchronized void forget(String id) throws IOException {
        ensureLoaded();
        if (!config.containsKey(id)) return;
        Map<String, ContentsSize> next = new TreeMap<>(config);
        next.remove(id);
        write(configPath.get(), next);
        config = Map.copyOf(next);
        version++;
    }

    /** A counter that changes whenever any size may have. */
    public synchronized int version() {
        return version;
    }

    /** Drop both caches so the next read goes back to disk. */
    public synchronized void invalidate() {
        bundled = null;
        config = null;
        version++;
    }

    private void ensureLoaded() {
        if (bundled == null) bundled = loadBundled();
        if (config == null) config = loadConfig();
    }

    private Map<String, ContentsSize> loadBundled() {
        try (InputStream in = TemplateSizeStore.class.getResourceAsStream(bundledResource)) {
            if (in == null) return Map.of();
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8), bundledResource);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read bundled {} sizes {}: {}", label, bundledResource, e.toString());
            return Map.of();
        }
    }

    private Map<String, ContentsSize> loadConfig() {
        // A world that disabled custom content gets the bundled catalogue and nothing else.
        Path file;
        try {
            if (!TemplateWeightOverlay.overlayReadable()) return Map.of();
            file = configPath.get();
        } catch (RuntimeException e) {
            // No game directory yet (unit tests, very early boot): nothing authored to read.
            return Map.of();
        }
        if (!Files.isRegularFile(file)) return Map.of();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r, file.toString());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read {} sizes {}: {}", label, file, e.toString());
            return Map.of();
        }
    }

    /**
     * Parse one manifest. Unknown sizes are skipped with a warning rather than failing the file — one
     * bad row must not resize every other template back to Room.
     */
    static Map<String, ContentsSize> parse(Reader reader, String source) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) throw new IllegalArgumentException("root is not an object");
        JsonElement sizes = root.getAsJsonObject().get("sizes");
        if (sizes == null || !sizes.isJsonObject()) return Map.of();
        Map<String, ContentsSize> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : sizes.getAsJsonObject().entrySet()) {
            String raw = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
            Optional<ContentsSize> size = ContentsSize.parse(raw);
            if (size.isEmpty()) {
                LOGGER.warn("[DungeonTrain] {}: unknown size '{}' for '{}' — treating as room", source, raw, e.getKey());
                continue;
            }
            out.put(e.getKey(), size.get());
        }
        return Map.copyOf(out);
    }

    static JsonObject toJson(Map<String, ContentsSize> sizes) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject body = new JsonObject();
        new TreeMap<>(sizes).forEach((id, size) -> body.addProperty(id, size.key()));
        root.add("sizes", body);
        return root;
    }

    private static void write(Path file, Map<String, ContentsSize> sizes) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(toJson(sizes), w);
        }
    }

    /** Best-effort dev-checkout write-through of one id into the bundled manifest. */
    private void trySaveToSource(String id, ContentsSize size) {
        if (!EditorDevMode.isEnabled()) return;
        Path resources = resourcesRootOrNull();
        if (resources == null || !Files.isDirectory(resources) || !Files.isWritable(resources)) return;
        Path file = resources.resolve(bundledResource.substring(1));
        try {
            Map<String, ContentsSize> next = new TreeMap<>(bundled);
            if (size == ContentsSize.ROOM) next.remove(id);
            else next.put(id, size);
            write(file, next);
            bundled = Map.copyOf(next);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled {} sizes to {}: {} (config write succeeded).",
                label, file, e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        return projectRoot == null ? null : projectRoot.resolve("src/main/resources");
    }
}
