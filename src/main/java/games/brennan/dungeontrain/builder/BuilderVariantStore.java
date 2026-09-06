package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The Train Builder's block-variant sidecar — the document the Z menu reads and writes while a
 * build is being authored, held in the builder world's own save folder
 * ({@link BuilderStorePaths#variantsFile}).
 *
 * <p>The document itself is a {@link TrackVariantBlocks}: the four sidecar flavours share one
 * on-disk schema, and this is the one bounded by an arbitrary {@link Vec3i} footprint rather than
 * by {@code CarriageDims} — which is what a portal room build needs, its box being the author's
 * size. It is loaded detached (null kind, no bundled-resource fallback, never in that class's
 * name-keyed cache) because no track template stands behind it.</p>
 *
 * <p>Cached per file path <i>and</i> footprint: entries outside the footprint are dropped at parse
 * time, so a build whose box has since grown has to re-read rather than keep serving a document
 * that was cropped to the old one.</p>
 */
public final class BuilderVariantStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The {@code name} the document reports in log lines — it has no template name of its own. */
    private static final String DOC_NAME = "build";

    private record Cached(Vec3i footprint, TrackVariantBlocks doc) {}

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private BuilderVariantStore() {}

    /**
     * This build's sidecar, read from disk on first use. Never null — an absent or unreadable file
     * reads as an empty document, which is also the state a New build starts in.
     */
    public static synchronized TrackVariantBlocks loadFor(ServerLevel level, Vec3i footprint) {
        Path file = BuilderStorePaths.variantsFile(level);
        String key = file.toString();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.footprint().equals(footprint)) {
            return cached.doc();
        }
        TrackVariantBlocks doc = TrackVariantBlocks.fromJsonText(readOrNull(file), null, DOC_NAME, footprint);
        CACHE.put(key, new Cached(footprint, doc));
        return doc;
    }

    /** Persist {@code doc} as this build's sidecar. */
    public static synchronized void save(ServerLevel level, TrackVariantBlocks doc, Vec3i footprint)
            throws IOException {
        Path file = BuilderStorePaths.variantsFile(level);
        Files.createDirectories(file.getParent());
        Files.writeString(file, doc.asJsonText(), StandardCharsets.UTF_8);
        CACHE.put(file.toString(), new Cached(footprint, doc));
    }

    /**
     * Replace this build's sidecar with {@code json} — the document of the template a build was
     * opened from, or the text an undo step put aside. A null or blank document clears the file,
     * which is what a New build wants.
     */
    public static synchronized void replace(ServerLevel level, @Nullable String json) throws IOException {
        Path file = BuilderStorePaths.variantsFile(level);
        CACHE.remove(file.toString());
        if (json == null || json.isBlank()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /**
     * This build's sidecar as JSON, for carrying onto the template a save writes — or the empty
     * string when nothing has been authored, so callers can skip the write entirely.
     */
    public static synchronized String snapshotJson(ServerLevel level, Vec3i footprint) {
        TrackVariantBlocks doc = loadFor(level, footprint);
        return doc.isEmpty() ? "" : doc.asJsonText();
    }

    /** Drop the cached document (world unload / test hook). */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static @Nullable String readOrNull(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Best-effort: an unreadable draft sidecar reads as empty rather than failing the
            // resolve every menu open goes through. The file is left alone so it can be recovered.
            LOGGER.error("[DungeonTrain] Failed to read builder variant sidecar {}: {}", file, e.toString());
            return null;
        }
    }
}
