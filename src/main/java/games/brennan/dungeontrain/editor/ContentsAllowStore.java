package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;
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
 * Three-tier store for {@code <id>.contents-allow.json} sidecars — the allow-list of contents ids
 * that may spawn inside <i>something</i>. Which something is the caller's business: this class knows
 * only an id and the three directories that id is looked up in.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/<subdir>/<id>.contents-allow.json}.
 *       Per-install override; the editor's toggle subcommands write here.</li>
 *   <li><b>Bundled resource</b> — {@code <resourcePrefix><id>.contents-allow.json} on the
 *       classpath. Shipped defaults.</li>
 *   <li><b>Absent</b> — no sidecar in either tier. The caller treats that as
 *       {@link CarriageContentsAllowList#EMPTY} (everything allowed).</li>
 * </ol>
 *
 * <h2>Why this is generic and its callers are not</h2>
 * <p>There are two of these on disk and they differ by nothing but their directory: carriage shells
 * ({@link CarriageVariantContentsAllowStore}, {@code templates/}) and dimensional carriages
 * ({@link DimensionalCarriageContentsAllowStore}, {@code portals/room/}). The loading, caching, JSON and
 * dev-mode source-tree promote are the same problem in both, and were worth having once. The typed
 * facades stay because a {@code CarriageVariant} and a room name are not interchangeable and callers
 * should not be able to pass one where the other belongs.</p>
 *
 * <p>Each instance owns its own cache, so invalidating one kind cannot disturb the other.</p>
 */
final class ContentsAllowStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The suffix. Chosen so a {@code *.nbt}-only template scan cannot pick these up as templates. */
    static final String EXT = ".contents-allow.json";

    private final String subdir;
    private final String resourcePrefix;
    private final String sourceRelPath;

    /** Per-id cache; {@code Optional.empty()} means "both tiers missing", and short-circuits. */
    private final Map<String, Optional<CarriageContentsAllowList>> cache = new HashMap<>();

    ContentsAllowStore(String subdir, String resourcePrefix, String sourceRelPath) {
        this.subdir = subdir;
        this.resourcePrefix = resourcePrefix;
        this.sourceRelPath = sourceRelPath;
    }

    Path directory() {
        return UserContentPaths.dir(subdir);
    }

    Path fileFor(String id) {
        return directory().resolve(id + EXT);
    }

    Path sourceFileFor(String id) {
        return sourceDirectory().resolve(id + EXT);
    }

    boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    synchronized void clearCache() {
        cache.clear();
    }

    /**
     * Drop {@code id}'s cache entry.
     *
     * <p><b>Lower-cases the key, where {@link #get} and {@link #save} do not.</b> Carried over
     * verbatim from the carriage store this was extracted from: every id in play is already
     * lower-case, so the asymmetry is inert, and quietly "fixing" it here would be a behaviour
     * change to the shipping carriage path riding along inside a refactor. Left as it was, named so
     * the next reader does not have to rediscover it.</p>
     */
    synchronized void invalidate(String id) {
        cache.remove(id.toLowerCase(Locale.ROOT));
    }

    /**
     * The allow-list for {@code id}. {@link Optional#empty()} means "no sidecar exists" — callers
     * should treat that the same as {@link CarriageContentsAllowList#EMPTY}.
     */
    synchronized Optional<CarriageContentsAllowList> get(String id) {
        if (id == null) return Optional.empty();
        Optional<CarriageContentsAllowList> cached = cache.get(id);
        if (cached != null) return cached;
        Optional<CarriageContentsAllowList> loaded = loadFromConfig(id);
        if (loaded.isEmpty()) loaded = loadFromResource(id);
        cache.put(id, loaded);
        return loaded;
    }

    /**
     * Persist the allow-list. An empty allow-list is still written as a file — a present-but-empty
     * record is meaningfully different from no file at all once a player has explicitly toggled
     * everything back on, and it keeps the write idempotent with the toggle round-trip.
     */
    synchronized void save(String id, CarriageContentsAllowList allow) throws IOException {
        Files.createDirectories(directory());
        Path file = fileFor(id);
        write(file, allow);
        cache.put(id, Optional.of(allow));
        LOGGER.info("[DungeonTrain] Saved contents allow-list for {} to {}", id, file);
        trySaveToSource(id, allow);
    }

    synchronized void saveToSource(String id, CarriageContentsAllowList allow) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(id);
        Files.createDirectories(file.getParent());
        write(file, allow);
        LOGGER.info("[DungeonTrain] Wrote bundled contents allow-list {} to {}", id, file);
    }

    synchronized boolean delete(String id) throws IOException {
        Path file = fileFor(id);
        boolean existed = Files.deleteIfExists(file);
        cache.put(id, Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted contents allow-list for {} ({})", id, file);
        tryDeleteFromSource(id);
        return existed;
    }

    boolean exists(String id) {
        return Files.isRegularFile(fileFor(id));
    }

    // ---------- source-tree promote ----------

    /**
     * Best-effort source-tree write invoked from {@link #save}. Silent no-op outside dev mode
     * (production installs have no writable {@code src/main/resources/}) or for ids that don't ship
     * with the game. Warn-level on dev-mode failures so a source-tree problem doesn't mask the
     * config write having succeeded.
     */
    private void trySaveToSource(String id, CarriageContentsAllowList allow) {
        if (!shipsWithGame(id)) return;
        try {
            Path file = sourceFileFor(id);
            Files.createDirectories(file.getParent());
            write(file, allow);
            LOGGER.info("[DungeonTrain] Wrote bundled contents allow-list for {} to {}", id, file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled contents allow-list for {} to source tree: {} (config write succeeded).",
                id, e.toString());
        }
    }

    /**
     * Best-effort source-tree delete invoked from {@link #delete}. Removes the promoted sidecar so a
     * Clear from the in-game menu propagates to the bundled copy in dev — otherwise the next load
     * would fall back to the stale promoted file.
     */
    private void tryDeleteFromSource(String id) {
        if (!shipsWithGame(id)) return;
        try {
            Path file = sourceFileFor(id);
            if (Files.deleteIfExists(file)) {
                LOGGER.info("[DungeonTrain] Deleted bundled contents allow-list {} (devmode promote).", file);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to delete bundled contents allow-list for {} from source tree: {} (config delete succeeded).",
                id, e.toString());
        }
    }

    /**
     * True when {@code <id>.nbt} is present in the writable source-tree dir — the signal that this
     * id ships with the game and its sidecars should be promoted alongside it. False outside a dev
     * checkout, and for per-install ids with no shipped NBT.
     */
    private boolean shipsWithGame(String id) {
        if (!sourceTreeAvailable()) return false;
        return Files.exists(sourceDirectory().resolve(id + ".nbt"));
    }

    // ---------- io ----------

    private static void write(Path file, CarriageContentsAllowList allow) throws IOException {
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(allow.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
    }

    private Optional<CarriageContentsAllowList> loadFromConfig(String id) {
        Path file = UserContentPaths.findFile(subdir, id + EXT);
        if (file == null) return Optional.empty();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Contents allow-list {} is not a JSON object — ignoring", file);
                return Optional.empty();
            }
            CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded contents allow-list {} from config {}", id, file);
            return Optional.of(a);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read contents allow-list {} at {}: {}",
                id, file, e.toString());
            return Optional.empty();
        }
    }

    private Optional<CarriageContentsAllowList> loadFromResource(String id) {
        String resource = resourcePrefix + id + EXT;
        try (InputStream in = ContentsAllowStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Bundled contents allow-list {} is not a JSON object — ignoring", resource);
                return Optional.empty();
            }
            CarriageContentsAllowList a = CarriageContentsAllowList.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded contents allow-list {} from bundled {}", id, resource);
            return Optional.of(a);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled contents allow-list {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    // ---------- paths ----------

    private Path sourceDirectory() {
        Path dir = sourceDirectoryOrNull();
        if (dir == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return dir;
    }

    private Path sourceDirectoryOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve(sourceRelPath);
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
