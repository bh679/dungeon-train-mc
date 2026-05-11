package games.brennan.dungeontrain.template;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.UserContentImporter;
import org.slf4j.Logger;

import java.util.List;

/**
 * Central reload barrier for every per-store template / sidecar cache and
 * variant registry that depends on {@code dtpacks/} contents.
 *
 * <p>Three entry points:
 * <ul>
 *   <li>{@link #reloadAll(boolean)} — full pipeline: optionally re-run the
 *       importer (extract any new zips), clear all caches, reload every
 *       registry, reload weights, and finally clear editor plot snapshots
 *       so the dirty-check baseline rebuilds cleanly on the next re-stamp.</li>
 *   <li>{@link #reloadCachesOnly()} — same minus the importer. Used by the
 *       package mutators when switching active or toggling enable, where
 *       no new zips have been dropped but every store needs to re-query
 *       the (now-different) {@code searchDirs}.</li>
 *   <li>{@link #reloadAfterImport()} — same as full but the caller has
 *       already invoked the importer separately and wants just the
 *       cache/registry refresh on top.</li>
 * </ul>
 *
 * <p>Ordering rationale — must run in this sequence:
 * <ol>
 *   <li>Importer (extract zips) — produces files that the registries
 *       below will enumerate.</li>
 *   <li>Cache clears — drop stale unpacked cells / sidecar entries that
 *       would shadow the freshly-imported / re-routed files.</li>
 *   <li>Variant registries — rebuild the id → metadata maps that template
 *       loads index against.</li>
 *   <li>Template stores — actually load each variant's NBT.</li>
 *   <li>Weight registries — last, because weight lookups index on ids
 *       the previous step just rebuilt.</li>
 *   <li>Plot snapshots — wipe the dirty-check baseline so a switched
 *       active package doesn't false-positive every visible plot on next
 *       inspection (snapshots are post-stamp; the next re-stamp produces
 *       a fresh baseline).</li>
 * </ol>
 *
 * <p>All methods are synchronized on the class monitor so the registry
 * mutators in {@link games.brennan.dungeontrain.editor.PackageRegistry}
 * can coalesce a state change + reload into one atomic step.</p>
 */
public final class TemplateStores {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TemplateStores() {}

    /**
     * Full reload. When {@code runImporter} is {@code true}, scans the
     * import drop-zone first and extracts any new zips before refreshing
     * registries. When {@code false}, skips straight to the cache /
     * registry / weights refresh.
     *
     * <p>Returns the import summary so callers can surface counts in chat;
     * empty when {@code runImporter == false}.</p>
     */
    public static synchronized Summary reloadAll(boolean runImporter) {
        ImportTotals totals = runImporter
            ? runImporterPass()
            : new ImportTotals(0, 0, 0, 0);

        clearCaches();
        reloadRegistries();
        reloadWeights();

        // Plot snapshots are post-stamp baselines. Active-package switches
        // (and enable/disable) change what's loaded, so a stale snapshot
        // would compare the next re-stamp against block state from the
        // previous package's template and read as "unsaved". Wipe so the
        // next visit captures a fresh baseline.
        EditorPlotSnapshots.clearAll();

        LOGGER.info(
            "[DungeonTrain] Reload complete — {} package(s) processed, {} new file(s), {} skipped, {} rejected.",
            totals.packagesProcessed(), totals.filesImported(), totals.filesSkipped(), totals.filesRejected());
        return new Summary(totals.packagesProcessed(), totals.filesImported(),
            totals.filesSkipped(), totals.filesRejected());
    }

    /** Skip the importer — clear caches, reload registries + weights, clear snapshots. */
    public static synchronized Summary reloadCachesOnly() {
        return reloadAll(false);
    }

    /** Aggregate counts returned by reload — mirrors the legacy importer Summary shape. */
    public record Summary(int packagesProcessed, int filesImported, int filesSkipped, int filesRejected) {}

    private record ImportTotals(int packagesProcessed, int filesImported, int filesSkipped, int filesRejected) {}

    private static ImportTotals runImporterPass() {
        List<UserContentImporter.ZipResult> imports = UserContentImporter.importAll();
        int imported = 0;
        int skipped = 0;
        int rejected = 0;
        for (UserContentImporter.ZipResult r : imports) {
            imported += r.imported();
            skipped += r.skipped();
            rejected += r.rejected();
        }
        return new ImportTotals(imports.size(), imported, skipped, rejected);
    }

    private static void clearCaches() {
        // Order doesn't matter inside this group — each clearCache() only
        // touches its own static map. Kept in the order they were added so
        // future cache additions can append here without rethinking.
        games.brennan.dungeontrain.editor.CarriageTemplateStore.clearCache();
        games.brennan.dungeontrain.editor.CarriageContentsStore.clearCache();
        games.brennan.dungeontrain.editor.CarriageVariantBlocks.clearCache();
        games.brennan.dungeontrain.editor.CarriageVariantPartsStore.clearCache();
        games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore.clearCache();
        games.brennan.dungeontrain.editor.CarriagePartTemplateStore.clearCache();
        games.brennan.dungeontrain.editor.CarriagePartVariantBlocks.clearCache();
        games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks.clearCache();
        games.brennan.dungeontrain.editor.PillarTemplateStore.clearCache();
        games.brennan.dungeontrain.editor.ContainerContentsStore.clearCache();
        games.brennan.dungeontrain.track.variant.TrackVariantStore.clearCache();
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks.clearCache();
    }

    private static void reloadRegistries() {
        // Track variant registry has no dependencies on the others, but is
        // listed first to match the legacy ordering in UserContentImporter.
        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.reload();
        games.brennan.dungeontrain.train.CarriageVariantRegistry.reload();
        games.brennan.dungeontrain.train.CarriageContentsRegistry.reload();
        games.brennan.dungeontrain.editor.CarriagePartRegistry.reload();
        games.brennan.dungeontrain.editor.CarriageTemplateStore.reload();
        games.brennan.dungeontrain.editor.CarriageContentsStore.reload();
        games.brennan.dungeontrain.editor.LootPrefabStore.reload();
        games.brennan.dungeontrain.editor.BlockVariantPrefabStore.reload();
    }

    private static void reloadWeights() {
        // Weights run last because their reload paths cross-reference the
        // variant ids that the registry passes just rebuilt — earlier
        // sequencing would cause spurious "unknown id" log warnings.
        games.brennan.dungeontrain.train.CarriageWeights.reload();
        games.brennan.dungeontrain.train.CarriageContentsWeights.reload();
        games.brennan.dungeontrain.track.variant.TrackVariantWeights.reload();
    }
}
