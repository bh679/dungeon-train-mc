package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.StoreCodec.DecodeContext;
import games.brennan.dungeontrain.editor.StoreDescriptor.SaveCachePolicy;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The three-tier lookup every editor store shares, once.
 *
 * <ol>
 *   <li><b>User tier</b> — the active package's {@code <subdir>/<fileName>}, read across every
 *       enabled package via {@link StoreDescriptor.UserTier#findFile}; writes land in the active
 *       package's directory.</li>
 *   <li><b>Bundled resource</b> — the classpath copy that ships in the jar.</li>
 *   <li><b>Absent</b> — {@link Optional#empty()}; the caller's fallback is its own business.</li>
 * </ol>
 *
 * <p>Built from a {@link StoreDescriptor}; the typed facades ({@code WholeCarriageTemplateStore},
 * {@code CarriageVariantPartsStore}, …) keep their static signatures and delegate here, so a
 * {@code CarriageVariant} and a room name stay un-interchangeable at the call site.</p>
 *
 * <h2>What is, and is not, in here</h2>
 * <ul>
 *   <li><b>The cache holds what is on disk.</b> {@link #get} is ungated: a footprint check against
 *       the caller's dims is the facade's per-call filter, never baked into the id-keyed cache —
 *       the poisoning {@code CarriageTemplateStore#get}'s javadoc describes.</li>
 *   <li><b>Source-tree mirroring is a policy</b>, {@link SourcePolicy}, applied identically on save
 *       and delete and always best-effort: a source problem is a warning, never a failed config
 *       write. {@link #saveToSource} and {@link #promote} are the explicit, throwing paths.</li>
 *   <li><b>Delete leaves the cache per {@link DeleteCachePolicy}</b>; the default lets a shipped
 *       id resolve to its bundled copy on the very next read.</li>
 *   <li>{@link #existsInActive} and {@link #existsInPackages} are two different questions —
 *       "may I overwrite / promote this?" and "does any enabled package have it?" — and are named
 *       so a facade has to pick one.</li>
 * </ul>
 *
 * <p>Each instance owns its cache and is registered with {@link StoreRegistry} under its
 * descriptor's name. All public methods are {@code synchronized} on the instance.</p>
 */
public final class ThreeTierStore<K, V> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NOT_WRITABLE =
        "Source tree not writable — are you running ./gradlew runClient from a checkout?";

    private final StoreDescriptor<K, V> d;

    /** Per-id cache; {@code Optional.empty()} means "both tiers missing" and short-circuits. */
    private final Map<String, Optional<V>> cache = new HashMap<>();

    ThreeTierStore(StoreDescriptor<K, V> descriptor) {
        this.d = descriptor;
        StoreRegistry.register(descriptor.name, this::clearCache);
    }

    // ---- paths ---------------------------------------------------------------------------------

    /** The active package's write directory for {@code key}. */
    public Path directory(K key) {
        return d.userTier.writeDir(d.subdir.apply(key));
    }

    /** The active package's file for {@code key} — the write target. */
    public Path fileFor(K key) {
        return directory(key).resolve(d.fileName.apply(key));
    }

    /** The source-tree file for {@code key}; throws outside a dev checkout. */
    public Path sourceFileFor(K key) {
        return sourceDirectory(key).resolve(d.fileName.apply(key));
    }

    public boolean sourceTreeAvailable() {
        return SourceTree.available(d.sourceTree);
    }

    public String bundledResourceFor(K key) {
        return d.bundledResource.apply(key);
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** {@link #get(Object, DecodeContext)} with no context — for JSON codecs. */
    public synchronized Optional<V> get(K key) {
        return get(key, DecodeContext.NONE);
    }

    /** {@link #get(Object, DecodeContext)} against {@code level}'s registries — for NBT codecs. */
    public synchronized Optional<V> get(K key, ServerLevel level) {
        return get(key, DecodeContext.of(level));
    }

    /**
     * The value for {@code key}: user tier first, then bundled, cached either way (an absent key
     * caches as empty). <b>Ungated</b> — apply any footprint filter to the result, per call.
     */
    public synchronized Optional<V> get(K key, DecodeContext ctx) {
        if (key == null) return Optional.empty();
        String id = d.id.apply(key);
        Optional<V> cached = cache.get(id);
        if (cached != null) return cached;
        Optional<V> loaded;
        try {
            loaded = loadFromUser(key, id, ctx);
            if (loaded.isEmpty()) loaded = loadFromResource(key, id, ctx);
        } catch (RuntimeException e) {
            // A unit test without Forge paths, or a registry missing mid-shutdown: degrade to absent
            // so the read path stays robust, as the group stores always did.
            LOGGER.warn("[DungeonTrain] {} load for {} failed defensively: {}", d.logNoun, id, e.toString());
            loaded = Optional.empty();
        }
        cache.put(id, loaded);
        return loaded;
    }

    /** The bundled tier only, uncached — what a reset-to-default re-stamps from. */
    public Optional<V> getBundled(K key, DecodeContext ctx) {
        return loadFromResource(key, d.id.apply(key), ctx);
    }

    public Optional<V> getBundled(K key) {
        return getBundled(key, DecodeContext.NONE);
    }

    public Optional<V> getBundled(K key, ServerLevel level) {
        return getBundled(key, DecodeContext.of(level));
    }

    /** True iff the jar bundles a copy of {@code key}. */
    public boolean bundled(K key) {
        try (InputStream in = ThreeTierStore.class.getResourceAsStream(d.bundledResource.apply(key))) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** True iff the active package's write directory holds {@code key} — the overwrite / promote question. */
    public boolean existsInActive(K key) {
        return Files.isRegularFile(fileFor(key));
    }

    /** True iff any enabled package holds {@code key} — the "is there a user copy at all" question. */
    public boolean existsInPackages(K key) {
        return d.userTier.findFile(d.subdir.apply(key), d.fileName.apply(key)) != null;
    }

    /** True iff {@code key} has a cache entry — present or a cached absence. */
    public synchronized boolean isCached(K key) {
        return cache.containsKey(d.id.apply(key));
    }

    /** The cached value, if the key has been loaded or injected; never loads. */
    public synchronized Optional<V> peekCached(K key) {
        Optional<V> cached = cache.get(d.id.apply(key));
        return cached == null ? Optional.empty() : cached;
    }

    // ---- writes --------------------------------------------------------------------------------

    /**
     * Write {@code value} to the active package's file, update the cache per the save policy, run
     * {@code onSave}, then mirror to the source tree per {@link SourcePolicy} (best-effort).
     */
    public synchronized void save(K key, V value) throws IOException {
        String id = d.id.apply(key);
        Path file = fileFor(key);
        Files.createDirectories(file.getParent());
        d.codec.encode(value, file);
        if (d.saveCachePolicy == SaveCachePolicy.PUT) cache.put(id, Optional.of(value));
        else cache.remove(id);
        d.onSave.accept(key);
        LOGGER.info("[DungeonTrain] Saved {} {} to {}", d.logNoun, id, file);
        if (shouldMirror(key)) {
            try {
                writeSource(key, value);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Failed to write bundled {} {} to source tree: {} (config write succeeded).",
                    d.logNoun, id, e.toString());
            }
        }
    }

    /** Write {@code value} straight into the source tree; throws when there is no writable tree. */
    public synchronized void saveToSource(K key, V value) throws IOException {
        if (!sourceTreeAvailable()) throw new IOException(NOT_WRITABLE);
        writeSource(key, value);
    }

    /** Copy the active package's file into the source tree; throws when either side is missing. */
    public synchronized void promote(K key) throws IOException {
        String id = d.id.apply(key);
        Path src = fileFor(key);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved " + d.logNoun + " for " + id + " in " + src);
        }
        if (!sourceTreeAvailable()) throw new IOException(NOT_WRITABLE);
        Path dst = sourceFileFor(key);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted {} {} from {} to {}", d.logNoun, id, src, dst);
    }

    /**
     * Delete the active package's file, update the cache per {@link DeleteCachePolicy}, run
     * {@code onDelete}, then remove the source-tree mirror per {@link SourcePolicy} (best-effort).
     *
     * @return whether the active package's file existed
     */
    public synchronized boolean delete(K key) throws IOException {
        if (key == null) return false;
        String id = d.id.apply(key);
        Path file = fileFor(key);
        boolean existed = Files.deleteIfExists(file);
        if (d.deleteCachePolicy == DeleteCachePolicy.TOMBSTONE) cache.put(id, Optional.empty());
        else cache.remove(id);
        d.onDelete.accept(key);
        if (existed) LOGGER.info("[DungeonTrain] Deleted {} {} ({})", d.logNoun, id, file);
        if (shouldMirror(key)) {
            try {
                if (SourceTreeFiles.deleteWithClasspathTwin(sourceFileFor(key))) {
                    LOGGER.info("[DungeonTrain] Deleted bundled {} {} (devmode promote).", d.logNoun, id);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[DungeonTrain] Failed to delete bundled {} {} from source tree: {} (config delete succeeded).",
                    d.logNoun, id, e.toString());
            }
        }
        return existed;
    }

    /**
     * Move the active package's file from {@code source} to {@code target}, carrying the cache
     * entry along, then run {@code onRename}. False when there is no active file to move (a
     * bundled-only key is shadowed, not renamed).
     */
    public synchronized boolean rename(K source, K target) throws IOException {
        Path src = fileFor(source);
        Path dst = fileFor(target);
        if (!Files.isRegularFile(src)) return false;
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<V> cached = cache.remove(d.id.apply(source));
        if (cached != null) cache.put(d.id.apply(target), cached);
        d.onRename.accept(source, target);
        LOGGER.info("[DungeonTrain] Renamed {} file {} -> {}", d.logNoun, src, dst);
        return true;
    }

    // ---- cache ---------------------------------------------------------------------------------

    public synchronized void invalidate(K key) {
        if (key == null) return;
        cache.remove(d.id.apply(key));
    }

    /** Drop the entry under this raw id — for the facades whose {@code invalidate(String)} predates typed keys. */
    public synchronized void invalidateId(String id) {
        if (id == null) return;
        cache.remove(id);
    }

    public synchronized void clearCache() {
        cache.clear();
        d.onClear.run();
    }

    /** Test seam: put {@code value} in the cache without touching the filesystem. */
    public synchronized void injectForTesting(K key, V value) {
        if (key == null || value == null) return;
        cache.put(d.id.apply(key), Optional.of(value));
    }

    // ---- source policy -------------------------------------------------------------------------

    private boolean shouldMirror(K key) {
        return switch (d.sourcePolicy) {
            case NEVER -> false;
            case ALWAYS -> sourceTreeAvailable();
            case DEV_MODE -> d.devMode.getAsBoolean() && sourceTreeAvailable();
            case SHIPS_WITH_GAME -> sourceTreeAvailable()
                && Files.exists(sourceDirectory(key).resolve(d.shipMarker.apply(key)));
        };
    }

    private void writeSource(K key, V value) throws IOException {
        Path file = sourceFileFor(key);
        Files.createDirectories(file.getParent());
        d.codec.encode(value, file);
        LOGGER.info("[DungeonTrain] Wrote bundled {} {} to {}", d.logNoun, d.id.apply(key), file);
    }

    private Path sourceDirectory(K key) {
        return SourceTree.resolve(d.sourceTree, d.sourceRelDir.apply(key));
    }

    // ---- loaders -------------------------------------------------------------------------------

    private Optional<V> loadFromUser(K key, String id, DecodeContext ctx) {
        Path file = d.userTier.findFile(d.subdir.apply(key), d.fileName.apply(key));
        if (file == null) return Optional.empty();
        try (InputStream in = Files.newInputStream(file)) {
            V v = d.codec.decode(in, d.logNoun + " " + id, ctx);
            if (v == null) return Optional.empty();
            LOGGER.info("[DungeonTrain] Loaded {} {} from config {}", d.logNoun, id, file);
            return Optional.of(v);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Failed to read {} {} at {}: {}", d.logNoun, id, file, e.toString());
            return Optional.empty();
        }
    }

    private Optional<V> loadFromResource(K key, String id, DecodeContext ctx) {
        String resource = d.bundledResource.apply(key);
        try (InputStream in = ThreeTierStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            V v = d.codec.decode(in, "bundled " + d.logNoun + " " + id, ctx);
            if (v == null) return Optional.empty();
            LOGGER.info("[DungeonTrain] Loaded {} {} from bundled {}", d.logNoun, id, resource);
            return Optional.of(v);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled {} {} at {}: {}", d.logNoun, id, resource, e.toString());
            return Optional.empty();
        }
    }
}
