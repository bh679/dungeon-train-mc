package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * The one store behind every kind's block-variant sidecar: loads a {@link VariantDoc} for a
 * {@link VariantAddress} (user tier across packages, then the bundled resource, then empty), caches
 * the whole document per address, hands callers bounded views, and writes it back — the user tier on
 * save, the source tree on the dev-mode write-through, a move-or-carry on rename.
 *
 * <p>Per-kind facades ({@code CarriageVariantBlocks.loadFor}, {@code TrackVariantBlocks.loadFor}, …)
 * build the address and call here; {@link BlockVariantPlot}'s adapters hold the address directly.</p>
 *
 * <p><b>Empty means delete.</b> A document with no cells and default mirror has nothing to persist,
 * so a save removes the file at whichever tier it is writing rather than leaving a stale
 * {@code "variants": {}} behind — a mirror-only sidecar (no cells, an axis toggled off its default)
 * is still written. The cache keeps the (empty) document either way.</p>
 */
public final class VariantSidecarStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Session cache keyed on {@link VariantAddress#cacheKey()}. Holds whole documents, never views. */
    private static final Map<String, VariantDoc> CACHE = new HashMap<>();

    private VariantSidecarStore() {}

    // ---------- Load ----------

    /**
     * The sidecar at {@code address}, bounded to {@code footprint} — a memoised view when any cell
     * lies outside it, the cached whole document otherwise. Never null: a missing sidecar is an
     * empty document carrying the kind's default mirror.
     */
    public static synchronized VariantDoc loadFor(VariantAddress address, @Nullable Vec3i footprint) {
        String key = address.cacheKey();
        VariantDoc cached = CACHE.get(key);
        if (cached == null) {
            cached = loadFromDisk(address);
            CACHE.put(key, cached);
        }
        return cached.croppedTo(footprint);
    }

    /** The whole sidecar at {@code address} as it is on disk right now, bypassing the cache. */
    static VariantDoc loadFromDisk(VariantAddress address) {
        Path cfg = address.findUserFile();
        if (cfg != null) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, address, "config " + cfg);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read variant sidecar {}: {}", cfg, e.toString());
            }
        }
        String resource = address.bundledResource();
        try (InputStream in = VariantSidecarStore.class.getResourceAsStream(resource)) {
            if (in == null) return VariantDoc.emptyFor(address);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, address, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled variant sidecar {}: {}", resource, e.toString());
            return VariantDoc.emptyFor(address);
        }
    }

    private static VariantDoc parse(Reader reader, VariantAddress address, String origin) {
        return VariantDoc.parse(reader, address.defaultMirror(), address.pick(), address.contextId(), address, origin);
    }

    // ---------- Save ----------

    /**
     * Persist {@code doc} as {@code address}'s user-tier sidecar and cache it under that address.
     * A view saves the whole document it was cut from.
     */
    public static synchronized void save(VariantAddress address, VariantDoc doc) throws IOException {
        VariantDoc whole = wholeOf(doc);
        writeUser(address, whole);
        address.afterWrite();
    }

    /** The user-tier write behind {@link #save}, shared with {@link #rename}'s carry path. */
    private static void writeUser(VariantAddress address, VariantDoc whole) throws IOException {
        Path file = address.userPath();
        if (whole.isBlank()) {
            Files.deleteIfExists(file);
            CACHE.put(address.cacheKey(), whole);
            LOGGER.info("[DungeonTrain] Cleared variant sidecar for {} (no entries, default mirror)", address.contextId());
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, whole.toJsonText(), StandardCharsets.UTF_8);
        CACHE.put(address.cacheKey(), whole);
        LOGGER.info("[DungeonTrain] Saved variant sidecar for {} ({} entries) to {}",
            address.contextId(), whole.size(), file);
    }

    /**
     * Dev-mode write-through: copy the sidecar into the project source tree so it ships with the
     * next build. Throws when the checkout is not present or not writable.
     */
    public static synchronized void saveToSource(VariantAddress address, VariantDoc doc) throws IOException {
        VariantDoc whole = wholeOf(doc);
        Path file = address.sourcePath();
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        if (whole.isBlank()) {
            Files.deleteIfExists(file);
            LOGGER.info("[DungeonTrain] Cleared bundled variant sidecar for {} (no entries, default mirror)",
                address.contextId());
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, whole.toJsonText(), StandardCharsets.UTF_8);
        LOGGER.info("[DungeonTrain] Wrote bundled variant sidecar for {} to {}", address.contextId(), file);
    }

    /**
     * Overwrite the user-tier file with {@code json} verbatim and drop the cached document, so the
     * next load reads it back — the editor undo history's restore. Callers re-resolve and save
     * afterwards so the empty-means-delete rule and the dev write-through apply.
     */
    public static synchronized void restore(VariantAddress address, String json) throws IOException {
        Path file = address.userPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        invalidate(address);
    }

    /** A view's whole document, or {@code doc} itself. */
    private static VariantDoc wholeOf(VariantDoc doc) {
        return doc.whole();
    }

    // ---------- Delete / rename / invalidate ----------

    /** Delete the user-tier sidecar and forget the cached document. True if a file existed. */
    public static synchronized boolean delete(VariantAddress address) throws IOException {
        Path file = address.userPath();
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(address.cacheKey());
        VariantDoc.forgetWarnings(address.contextId());
        address.afterWrite();
        if (existed) LOGGER.info("[DungeonTrain] Deleted variant sidecar {} ({})", address.contextId(), file);
        return existed;
    }

    /**
     * Carry the sidecar from {@code from} to {@code to} so a save-as / rename keeps its variants. A
     * user-tier file is moved; a sidecar that only exists in the session cache, an imported package
     * or the bundled resource is written out under the new address instead — a save-as of a bundled
     * template used to lose every entry here, because there was no file to move and the cache entry
     * was dropped. Returns false when there was nothing to carry.
     */
    public static synchronized boolean rename(VariantAddress from, VariantAddress to) throws IOException {
        Path src = from.userPath();
        Path dst = to.userPath();
        VariantDoc.forgetWarnings(from.contextId());
        if (Files.isRegularFile(src)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            VariantDoc cached = CACHE.remove(from.cacheKey());
            if (cached != null) CACHE.put(to.cacheKey(), cached);
            from.afterWrite();
            to.afterWrite();
            LOGGER.info("[DungeonTrain] Renamed variant sidecar {} -> {}", src, dst);
            return true;
        }
        VariantDoc carried = CACHE.remove(from.cacheKey());
        if (carried == null) carried = loadFromDisk(from);
        if (carried.isBlank()) return false;
        writeUser(to, carried);
        from.afterWrite();
        to.afterWrite();
        LOGGER.info("[DungeonTrain] Carried {} variant entries {} -> {} (no user-tier file to move)",
            carried.size(), from.contextId(), to.contextId());
        return true;
    }

    /** Forget the cached document so the next load re-reads disk (editor enter, manual edit, undo restore). */
    public static synchronized void invalidate(VariantAddress address) {
        CACHE.remove(address.cacheKey());
        VariantDoc.forgetWarnings(address.contextId());
        address.afterWrite();
    }

    /** Drop every cached document — server stop / test hook. */
    public static synchronized void clearCache() {
        CACHE.clear();
        VariantDoc.clearWarnings();
        StageBlockIndex.invalidateAll();
    }

    /** The cached document under {@code key}, for tests. */
    static @Nullable VariantDoc cached(String key) {
        return CACHE.get(key);
    }
}
