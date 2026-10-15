package games.brennan.dungeontrain.editor.workbench;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A staged build's block-variant candidates, as the document the variant menu, clipboard and mirror
 * edit while it stands in the Workbench.
 *
 * <p>There is no variants file of its own: the candidates live in the build's sidecar document under
 * the {@code variants} role, exactly as they arrived from the relay, and every save writes them back
 * there ({@link WorkbenchStagingStore#replaceSidecars}). That is what makes an edit made here survive
 * a commit — {@code WorkbenchCommit} hands the same document to the install, retargeted to whatever
 * kind the build is committed as.</p>
 *
 * <p>The document is a {@link TrackVariantBlocks} loaded detached, as the Train Builder's is
 * ({@code BuilderVariantStore}): every kind's variants file shares that schema, and this is the one
 * flavour bounded by an arbitrary footprint. Cached per staged id <i>and</i> footprint, because
 * parsing crops to the footprint and a staged build can be resized.</p>
 */
public final class WorkbenchVariantStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record Cached(Vec3i footprint, TrackVariantBlocks doc) {}

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private WorkbenchVariantStore() {}

    /**
     * {@code stagedId}'s candidates, parsed on first use. Never null — a build that carried none, or
     * is no longer staged, reads as an empty document.
     */
    public static synchronized TrackVariantBlocks loadFor(String stagedId, Vec3i footprint) {
        String key = keyOf(stagedId);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.footprint().equals(footprint)) return cached.doc();
        String text = WorkbenchStagingStore.find(stagedId)
                .flatMap(b -> TemplateSidecars.fileTextOf(b.sidecars(), TemplateSidecars.ROLE_VARIANTS))
                .orElse(null);
        TrackVariantBlocks doc;
        try {
            doc = TrackVariantBlocks.fromJsonText(text, null, stagedId, footprint);
        } catch (RuntimeException e) {
            // An unreadable variants role reads as empty rather than failing every menu resolve; the
            // document on disk is left alone, so nothing is lost until something is saved over it.
            LOGGER.warn("[DungeonTrain] Workbench: '{}' carries variants that would not parse: {}", stagedId, e.toString());
            doc = TrackVariantBlocks.fromJsonText(null, null, stagedId, footprint);
        }
        CACHE.put(key, new Cached(footprint, doc));
        return doc;
    }

    /** Write {@code doc} back into {@code stagedId}'s sidecar document. */
    public static synchronized void save(String stagedId, TrackVariantBlocks doc, Vec3i footprint) throws IOException {
        write(stagedId, doc.isEmpty() && doc.isDefaultMirror() ? "" : doc.asJsonText());
        CACHE.put(keyOf(stagedId), new Cached(footprint, doc));
    }

    /**
     * Replace {@code stagedId}'s candidates with {@code json} — the text an undo step put aside. Blank
     * removes the role.
     */
    public static synchronized void replace(String stagedId, @Nullable String json) throws IOException {
        CACHE.remove(keyOf(stagedId));
        write(stagedId, json);
    }

    /** Forget {@code stagedId}'s parsed document — after it is removed, committed or re-staged. */
    public static synchronized void invalidate(String stagedId) {
        CACHE.remove(keyOf(stagedId));
    }

    /** Drop every cached document (world unload / test hook). */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static void write(String stagedId, @Nullable String json) throws IOException {
        WorkbenchStagedBuild build = WorkbenchStagingStore.find(stagedId)
                .orElseThrow(() -> new IOException("No staged build '" + stagedId + "'"));
        String doc = TemplateSidecars.withFileText(build.sidecars(), TemplateSidecars.ROLE_VARIANTS, json);
        WorkbenchStagingStore.replaceSidecars(stagedId, doc);
    }

    private static String keyOf(String stagedId) {
        return stagedId.toLowerCase(Locale.ROOT);
    }
}
