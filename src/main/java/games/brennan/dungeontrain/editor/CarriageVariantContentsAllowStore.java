package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier store for {@code <variant-id>.contents-allow.json} sidecars —
 * the per-carriage-variant allow-list of contents ids that may spawn inside
 * the shell. Mirrors {@link CarriageVariantPartsStore} so the loading,
 * caching, and dev-mode source-tree write-through behaviours are identical.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/templates/<id>.contents-allow.json}.
 *       Per-install override; the editor's {@code carriage-contents} subcommand
 *       writes here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<id>.contents-allow.json}
 *       on the classpath. Shipped defaults for built-in variants (e.g. the
 *       {@code black} carriage's no-themed-content rule).</li>
 *   <li><b>Absent</b> — no sidecar in either tier. Caller treats as
 *       {@link CarriageContentsAllowList#EMPTY} (all contents allowed).</li>
 * </ol>
 *
 * <p>Lives in the same {@code config/dungeontrain/templates/} directory as
 * the carriage NBTs and parts sidecars; the
 * {@code .contents-allow.json} suffix keeps
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s
 * {@code *.nbt}-only scan from picking these up as custom carriages.</p>
 *
 * <p>Promote behaviour: when {@link #save} or {@link #delete} runs from a
 * gradle checkout (i.e. {@code ./gradlew runClient}, where
 * {@code src/main/resources/} is writable), the change is also mirrored into
 * the source tree at {@link #sourceFileFor(CarriageType)} for built-in
 * variants — so editor toggles ship in the next build. Custom variants and
 * production installs silently skip the source-tree step.</p>
 */
public final class CarriageVariantContentsAllowStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".contents-allow.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    // Per-id cache; Optional.empty() means "both tiers missing", short-circuits future lookups.
    private static final Map<String, Optional<CarriageContentsAllowList>> CACHE = new HashMap<>();

    private CarriageVariantContentsAllowStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(CarriageVariant variant) {
        return directory().resolve(variant.id() + EXT);
    }

    public static Path sourceFileFor(CarriageType type) {
        return sourceDirectory().resolve(type.name().toLowerCase(Locale.ROOT) + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized void invalidate(String id) {
        CACHE.remove(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the per-variant allow-list. {@link Optional#empty()} means
     * "no sidecar exists" — callers should treat that the same as
     * {@link CarriageContentsAllowList#EMPTY} (every content allowed).
     */
    public static synchronized Optional<CarriageContentsAllowList> get(CarriageVariant variant) {
        if (variant == null) return Optional.empty();
        String key = variant.id();
        Optional<CarriageContentsAllowList> cached = CACHE.get(key);
        if (cached != null) return cached;
        Optional<CarriageContentsAllowList> loaded = loadFromConfig(variant);
        if (loaded.isEmpty()) loaded = loadFromResource(variant);
        CACHE.put(key, loaded);
        return loaded;
    }

    /**
     * Persist the allow-list. An empty allow-list is still written as a file
     * (a present-but-empty record is meaningfully different from no file at
     * all when a player explicitly toggled everything back on after exclusions
     * — keeps the write idempotent with the toggle round-trip).
     */
    public static synchronized void save(CarriageVariant variant, CarriageContentsAllowList allow) throws IOException {
        Files.createDirectories(directory());
        Path file = fileFor(variant);
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(allow.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        CACHE.put(variant.id(), Optional.of(allow));
        LOGGER.info("[DungeonTrain] Saved contents allow-list for {} to {}", variant.id(), file);
        trySaveToSource(variant, allow);
    }

    public static synchronized void saveToSource(CarriageType type, CarriageContentsAllowList allow) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(type);
        Files.createDirectories(file.getParent());
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(allow.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled contents allow-list {} to {}", type, file);
    }

    public static synchronized boolean delete(CarriageVariant variant) throws IOException {
        Path file = fileFor(variant);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(variant.id(), Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted contents allow-list for {} ({})", variant.id(), file);
        tryDeleteFromSource(variant);
        return existed;
    }

    /**
     * Best-effort source-tree write invoked from {@link #save}. Silent no-op
     * outside dev mode (production installs have no writable
     * {@code src/main/resources/}) or for non-builtin variants (custom
     * carriages have no bundled-resource home). Warn-level log on dev-mode
     * failures so the config write isn't masked by a source-tree problem.
     * Mirrors {@link CarriageVariantPartsStore#trySaveToSource}.
     */
    private static void trySaveToSource(CarriageVariant variant, CarriageContentsAllowList allow) {
        if (!sourceTreeAvailable()) return;
        if (!(variant instanceof CarriageVariant.Builtin b)) return;
        try {
            saveToSource(b.type(), allow);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled contents allow-list for {} to source tree: {} (config write succeeded).",
                variant.id(), e.toString());
        }
    }

    /**
     * Best-effort source-tree delete invoked from {@link #delete}. Removes the
     * promoted {@code <type>.contents-allow.json} so a Clear from the in-game
     * menu also propagates to the bundled copy in dev — otherwise the next
     * load tier would fall back to the stale promoted bundled file. Silent
     * no-op outside dev mode or for non-builtin variants.
     */
    private static void tryDeleteFromSource(CarriageVariant variant) {
        if (!sourceTreeAvailable()) return;
        if (!(variant instanceof CarriageVariant.Builtin b)) return;
        try {
            Path file = sourceFileFor(b.type());
            if (Files.deleteIfExists(file)) {
                LOGGER.info("[DungeonTrain] Deleted bundled contents allow-list {} (devmode promote).", file);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to delete bundled contents allow-list for {} from source tree: {} (config delete succeeded).",
                variant.id(), e.toString());
        }
    }

    public static boolean exists(CarriageVariant variant) {
        return Files.isRegularFile(fileFor(variant));
    }

    private static Optional<CarriageContentsAllowList> loadFromConfig(CarriageVariant variant) {
        Path file = fileFor(variant);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Contents allow-list {} is not a JSON object — ignoring", file);
                return Optional.empty();
            }
            CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded contents allow-list {} from config {}", variant.id(), file);
            return Optional.of(a);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read contents allow-list {} at {}: {}",
                variant.id(), file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<CarriageContentsAllowList> loadFromResource(CarriageVariant variant) {
        String resource = RESOURCE_PREFIX + variant.id() + EXT;
        try (InputStream in = CarriageVariantContentsAllowStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Bundled contents allow-list {} is not a JSON object — ignoring", resource);
                return Optional.empty();
            }
            CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded contents allow-list {} from bundled {}", variant.id(), resource);
            return Optional.of(a);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled contents allow-list {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    private static Path sourceDirectory() {
        Path dir = sourceDirectoryOrNull();
        if (dir == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return dir;
    }

    private static Path sourceDirectoryOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve(SOURCE_REL_PATH);
    }

    private static Path resourcesRootOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    private static Path projectRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        return gameDir.getParent();
    }
}
