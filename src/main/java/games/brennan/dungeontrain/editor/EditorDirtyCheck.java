package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side dirty / unpromoted scanner for editor plots. Used by the
 * worldspace command menu's category-switch confirmation screen so the
 * player can see which templates are about to be re-stamped (and lose
 * in-world edits) before {@link EditorCategory#clearAllPlots} runs.
 *
 * <p>"Unsaved" means the live block geometry of a plot differs from
 * the snapshot {@link EditorPlotSnapshots} took right after the plot
 * was last stamped (or saved). Comparing against a snapshot — rather
 * than against the saved NBT — is what makes the dirty check stable
 * against stamp-time differences: parts overlay drift, sidecar variant
 * additions, parts template changes from another worktree, etc. all
 * produce the same stamped state on every entry, so a freshly-stamped
 * plot reads as clean even when the saved NBT no longer reflects what
 * the stamp pass produces.
 *
 * <p>Variant cells (positions tracked by the per-category sidecar —
 * {@link CarriageVariantBlocks}, {@link CarriageContentsVariantBlocks})
 * are skipped because the {@link VariantEditorPreviewTicker} cycles
 * them every 1–3 seconds; comparing the live state at those positions
 * to the snapshot's frozen frame would false-positive on every cycle.
 *
 * <p>"Unpromoted" means the config-dir copy of the NBT differs from
 * the bundled source-tree copy — only meaningful in DevMode and only
 * for built-in models that have a bundled tier.
 *
 * <p>Plots that have never been stamped this session (e.g. immediately
 * after a server restart, or for a variant the player hasn't entered)
 * have no snapshot and are reported as clean. The next {@code /dt
 * editor &lt;cat&gt;} stamp will populate the snapshot and make
 * subsequent checks accurate.
 */
public final class EditorDirtyCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorDirtyCheck() {}

    /** One row's worth of dirty / unpromoted state for the UI list. */
    public record DirtyEntry(String categoryId, String modelId, String displayName,
                             boolean isUnsaved, boolean isUnpromoted) {}

    /**
     * Scan every model in CARRIAGES and CONTENTS. Returns rows that have
     * unsaved edits relative to their post-stamp snapshot, plus rows
     * whose config-dir NBT differs from the source-tree copy when DevMode
     * is on.
     */
    public static List<DirtyEntry> findDirty(ServerLevel overworld, CarriageDims dims) {
        List<DirtyEntry> out = new ArrayList<>();
        boolean devmode = EditorDevMode.isEnabled();

        scanCarriages(overworld, dims, devmode, out);
        scanContents(overworld, dims, devmode, out);
        scanTrackTiles(overworld, dims, devmode, out);
        scanPillarSections(overworld, dims, devmode, out);
        scanAdjuncts(overworld, dims, devmode, out);
        scanTunnels(overworld, dims, devmode, out);

        return out;
    }

    private static void scanCarriages(ServerLevel level, CarriageDims dims, boolean devmode,
                                      List<DirtyEntry> out) {
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            BlockPos origin = CarriageEditor.plotOrigin(v, dims);
            if (origin == null) continue;
            String key = EditorPlotSnapshots.key("carriages", v.id());
            Map<BlockPos, BlockState> snapshot = EditorPlotSnapshots.get(key);

            Set<BlockPos> skip = variantCellPositions(CarriageVariantBlocks.loadFor(v, dims).entries());
            boolean unsaved = snapshot != null
                && !regionMatchesSnapshot(level, origin,
                    dims.length(), dims.height(), dims.width(),
                    snapshot, skip);

            boolean unpromoted = devmode
                && (v instanceof CarriageVariant.Builtin builtin)
                && CarriageTemplateStore.sourceTreeAvailable()
                && !filesEqualOrAbsent(CarriageTemplateStore.fileFor(v),
                    CarriageTemplateStore.sourceFileFor(builtin.type()));

            if (unsaved || unpromoted) {
                out.add(new DirtyEntry("carriages", v.id(), v.id(), unsaved, unpromoted));
            }
        }
    }

    private static void scanContents(ServerLevel level, CarriageDims dims, boolean devmode,
                                     List<DirtyEntry> out) {
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            BlockPos origin = CarriageContentsEditor.plotOrigin(c, dims);
            if (origin == null) continue;

            // Snapshots for contents are keyed to the INTERIOR region so the
            // shell isn't part of the diff (matches the save's capture region).
            BlockPos interiorOrigin = origin.offset(1, 1, 1);
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
            String key = EditorPlotSnapshots.key("contents", c.id());
            Map<BlockPos, BlockState> snapshot = EditorPlotSnapshots.get(key);

            Set<BlockPos> skip = variantCellPositions(
                CarriageContentsVariantBlocks.loadFor(c, interior).entries());
            boolean unsaved = snapshot != null
                && !regionMatchesSnapshot(level, interiorOrigin,
                    interior.getX(), interior.getY(), interior.getZ(),
                    snapshot, skip);

            // Contents has no separate bundled tier — the editor's save command
            // write-throughs handle source promotion in one step.
            if (unsaved) {
                out.add(new DirtyEntry("contents", c.id(), c.id(), true, false));
            }
        }
    }

    private static void scanTrackTiles(ServerLevel level, CarriageDims dims, boolean devmode,
                                       List<DirtyEntry> out) {
        for (String name : TrackVariantRegistry.namesFor(TrackKind.TILE)) {
            BlockPos origin = games.brennan.dungeontrain.editor.TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            String key = TrackEditor.snapshotKey(name);
            java.util.Map<BlockPos, BlockState> snapshot = EditorPlotSnapshots.get(key);

            Vec3i fp = new Vec3i(TrackPlacer.TILE_LENGTH, TrackPlacer.HEIGHT, dims.width());
            Set<BlockPos> skip = variantCellPositions(
                TrackVariantBlocks.loadFor(TrackKind.TILE, name, fp).entries());
            boolean unsaved = snapshot != null
                && !regionMatchesSnapshot(level, origin, fp.getX(), fp.getY(), fp.getZ(), snapshot, skip);

            boolean unpromoted = devmode
                && games.brennan.dungeontrain.track.variant.TrackVariantStore.sourceTreeAvailable()
                && !filesEqualOrAbsent(
                    games.brennan.dungeontrain.track.variant.TrackVariantStore.fileFor(TrackKind.TILE, name),
                    games.brennan.dungeontrain.track.variant.TrackVariantStore.sourceFileFor(TrackKind.TILE, name));

            if (unsaved || unpromoted) {
                String display = TrackKind.DEFAULT_NAME.equals(name) ? "track" : "track / " + name;
                out.add(new DirtyEntry("tracks", "track." + name, display, unsaved, unpromoted));
            }
        }
    }

    private static void scanPillarSections(ServerLevel level, CarriageDims dims, boolean devmode,
                                           List<DirtyEntry> out) {
        for (PillarSection section : PillarSection.values()) {
            TrackKind kind = PillarTemplateStore.pillarKind(section);
            for (String name : TrackVariantRegistry.namesFor(kind)) {
                BlockPos origin = PillarEditor.plotOrigin(new games.brennan.dungeontrain.template.PillarTemplateId(section, name), dims);
                String key = PillarEditor.sectionSnapshotKey(section, name);
                java.util.Map<BlockPos, BlockState> snapshot = EditorPlotSnapshots.get(key);

                Vec3i fp = new Vec3i(1, section.height(), dims.width());
                Set<BlockPos> skip = variantCellPositions(
                    TrackVariantBlocks.loadFor(kind, name, fp).entries());
                boolean unsaved = snapshot != null
                    && !regionMatchesSnapshot(level, origin, fp.getX(), fp.getY(), fp.getZ(), snapshot, skip);

                boolean unpromoted = devmode
                    && games.brennan.dungeontrain.track.variant.TrackVariantStore.sourceTreeAvailable()
                    && !filesEqualOrAbsent(
                        games.brennan.dungeontrain.track.variant.TrackVariantStore.fileFor(kind, name),
                        games.brennan.dungeontrain.track.variant.TrackVariantStore.sourceFileFor(kind, name));

                if (unsaved || unpromoted) {
                    String display = TrackKind.DEFAULT_NAME.equals(name)
                        ? "pillar / " + section.id()
                        : "pillar / " + section.id() + " / " + name;
                    out.add(new DirtyEntry("tracks",
                        "pillar_" + section.id() + "." + name, display, unsaved, unpromoted));
                }
            }
        }
    }

    private static void scanAdjuncts(ServerLevel level, CarriageDims dims, boolean devmode,
                                     List<DirtyEntry> out) {
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            TrackKind kind = PillarTemplateStore.adjunctKind(adjunct);
            for (String name : TrackVariantRegistry.namesFor(kind)) {
                BlockPos origin = PillarEditor.plotOriginAdjunct(new games.brennan.dungeontrain.template.PillarAdjunctTemplateId(adjunct, name), dims);
                String key = PillarEditor.adjunctSnapshotKey(adjunct, name);
                java.util.Map<BlockPos, BlockState> snapshot = EditorPlotSnapshots.get(key);

                Vec3i fp = new Vec3i(adjunct.xSize(), adjunct.ySize(), adjunct.zSize());
                Set<BlockPos> skip = variantCellPositions(
                    TrackVariantBlocks.loadFor(kind, name, fp).entries());
                boolean unsaved = snapshot != null
                    && !regionMatchesSnapshot(level, origin, fp.getX(), fp.getY(), fp.getZ(), snapshot, skip);

                boolean unpromoted = devmode
                    && games.brennan.dungeontrain.track.variant.TrackVariantStore.sourceTreeAvailable()
                    && !filesEqualOrAbsent(
                        games.brennan.dungeontrain.track.variant.TrackVariantStore.fileFor(kind, name),
                        games.brennan.dungeontrain.track.variant.TrackVariantStore.sourceFileFor(kind, name));

                if (unsaved || unpromoted) {
                    String display = TrackKind.DEFAULT_NAME.equals(name)
                        ? adjunct.id()
                        : adjunct.id() + " / " + name;
                    out.add(new DirtyEntry("tracks",
                        "adjunct_" + adjunct.id() + "." + name, display, unsaved, unpromoted));
                }
            }
        }
    }

    private static void scanTunnels(ServerLevel level, CarriageDims dims, boolean devmode,
                                    List<DirtyEntry> out) {
        for (TunnelVariant variant : TunnelVariant.values()) {
            TrackKind kind = games.brennan.dungeontrain.editor.TunnelTemplateStore.tunnelKind(variant);
            for (String name : TrackVariantRegistry.namesFor(kind)) {
                BlockPos origin = TunnelEditor.plotOrigin(new games.brennan.dungeontrain.template.TunnelTemplateId(variant, name));
                String key = TunnelEditor.tunnelSnapshotKey(variant, name);
                java.util.Map<BlockPos, BlockState> snapshot = EditorPlotSnapshots.get(key);

                Vec3i fp = new Vec3i(TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH);
                Set<BlockPos> skip = variantCellPositions(
                    TrackVariantBlocks.loadFor(kind, name, fp).entries());
                boolean unsaved = snapshot != null
                    && !regionMatchesSnapshot(level, origin, fp.getX(), fp.getY(), fp.getZ(), snapshot, skip);

                if (unsaved) {
                    String tunnelLabel = variant.name().toLowerCase(java.util.Locale.ROOT);
                    String display = TrackKind.DEFAULT_NAME.equals(name)
                        ? "tunnel / " + tunnelLabel
                        : "tunnel / " + tunnelLabel + " / " + name;
                    out.add(new DirtyEntry("tracks",
                        "tunnel_" + tunnelLabel + "." + name, display, true, false));
                }
            }
        }
    }

    /**
     * Position-by-position compare of the live world region against
     * {@code snapshot}. Skips {@code skip} positions (variant cells —
     * preview ticker cycles them) and treats absent snapshot keys as
     * "expected = AIR".
     */
    private static boolean regionMatchesSnapshot(ServerLevel level, BlockPos origin,
                                                 int length, int height, int width,
                                                 Map<BlockPos, BlockState> snapshot,
                                                 Set<BlockPos> skip) {
        for (int dx = 0; dx < length; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < width; dz++) {
                    BlockPos local = new BlockPos(dx, dy, dz);
                    if (skip.contains(local)) continue;
                    BlockState live = level.getBlockState(origin.offset(dx, dy, dz));
                    BlockState exp = snapshot.get(local);
                    if (exp == null) {
                        if (!live.isAir()) return false;
                    } else if (!exp.equals(live)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Per-template diff used by the changes-list drilldown. Returns the
     * (relative {@link BlockPos}, expected, live) triples where the live
     * world differs from the post-stamp snapshot. Empty when the template
     * is clean or when no snapshot exists. Walks the same regions /
     * variant-cell skip rules as {@link #findDirty}.
     */
    public static List<DiffEntry> findChanges(ServerLevel overworld, CarriageDims dims,
                                              String categoryId, String modelId) {
        List<DiffEntry> out = new ArrayList<>();
        if ("carriages".equals(categoryId)) {
            CarriageVariant variant = CarriageVariantRegistry.find(modelId).orElse(null);
            if (variant == null) return out;
            BlockPos origin = CarriageEditor.plotOrigin(variant, dims);
            if (origin == null) return out;
            String key = EditorPlotSnapshots.key("carriages", modelId);
            Set<BlockPos> skip = variantCellPositions(CarriageVariantBlocks.loadFor(variant, dims).entries());
            collectDiffs(overworld, origin, dims.length(), dims.height(), dims.width(),
                EditorPlotSnapshots.get(key), skip, out);
            return out;
        }
        if ("contents".equals(categoryId)) {
            CarriageContents contents = CarriageContentsRegistry.allContents().stream()
                .filter(c -> c.id().equals(modelId)).findFirst().orElse(null);
            if (contents == null) return out;
            BlockPos origin = CarriageContentsEditor.plotOrigin(contents, dims);
            if (origin == null) return out;
            BlockPos interiorOrigin = origin.offset(1, 1, 1);
            Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
            String key = EditorPlotSnapshots.key("contents", modelId);
            Set<BlockPos> skip = variantCellPositions(
                CarriageContentsVariantBlocks.loadFor(contents, interior).entries());
            collectDiffs(overworld, interiorOrigin, interior.getX(), interior.getY(), interior.getZ(),
                EditorPlotSnapshots.get(key), skip, out);
            return out;
        }
        if ("tracks".equals(categoryId) && modelId.contains(".")) {
            int sep = modelId.indexOf('.');
            String prefix = modelId.substring(0, sep);
            String name = modelId.substring(sep + 1);
            if ("track".equals(prefix)) {
                BlockPos origin = games.brennan.dungeontrain.editor.TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
                Vec3i fp = new Vec3i(TrackPlacer.TILE_LENGTH, TrackPlacer.HEIGHT, dims.width());
                Set<BlockPos> skip = variantCellPositions(TrackVariantBlocks.loadFor(TrackKind.TILE, name, fp).entries());
                collectDiffs(overworld, origin, fp.getX(), fp.getY(), fp.getZ(),
                    EditorPlotSnapshots.get(TrackEditor.snapshotKey(name)), skip, out);
            } else if (prefix.startsWith("pillar_")) {
                PillarSection sec;
                try {
                    sec = PillarSection.valueOf(prefix.substring("pillar_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) { return out; }
                BlockPos origin = PillarEditor.plotOrigin(new games.brennan.dungeontrain.template.PillarTemplateId(sec, name), dims);
                Vec3i fp = new Vec3i(1, sec.height(), dims.width());
                TrackKind kind = PillarTemplateStore.pillarKind(sec);
                Set<BlockPos> skip = variantCellPositions(TrackVariantBlocks.loadFor(kind, name, fp).entries());
                collectDiffs(overworld, origin, fp.getX(), fp.getY(), fp.getZ(),
                    EditorPlotSnapshots.get(PillarEditor.sectionSnapshotKey(sec, name)), skip, out);
            } else if (prefix.startsWith("adjunct_")) {
                PillarAdjunct adj;
                try {
                    adj = PillarAdjunct.valueOf(prefix.substring("adjunct_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) { return out; }
                BlockPos origin = PillarEditor.plotOriginAdjunct(new games.brennan.dungeontrain.template.PillarAdjunctTemplateId(adj, name), dims);
                Vec3i fp = new Vec3i(adj.xSize(), adj.ySize(), adj.zSize());
                TrackKind kind = PillarTemplateStore.adjunctKind(adj);
                Set<BlockPos> skip = variantCellPositions(TrackVariantBlocks.loadFor(kind, name, fp).entries());
                collectDiffs(overworld, origin, fp.getX(), fp.getY(), fp.getZ(),
                    EditorPlotSnapshots.get(PillarEditor.adjunctSnapshotKey(adj, name)), skip, out);
            } else if (prefix.startsWith("tunnel_")) {
                TunnelVariant tv;
                try {
                    tv = TunnelVariant.valueOf(prefix.substring("tunnel_".length()).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) { return out; }
                BlockPos origin = TunnelEditor.plotOrigin(new games.brennan.dungeontrain.template.TunnelTemplateId(tv, name));
                Vec3i fp = new Vec3i(TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH);
                TrackKind kind = games.brennan.dungeontrain.editor.TunnelTemplateStore.tunnelKind(tv);
                Set<BlockPos> skip = variantCellPositions(TrackVariantBlocks.loadFor(kind, name, fp).entries());
                collectDiffs(overworld, origin, fp.getX(), fp.getY(), fp.getZ(),
                    EditorPlotSnapshots.get(TunnelEditor.tunnelSnapshotKey(tv, name)), skip, out);
            }
        }
        return out;
    }

    /** One row in the per-template changes drilldown. {@code expected} is the snapshot's state, blank for "was air". */
    public record DiffEntry(BlockPos localPos, String expectedDescription, String liveDescription) {}

    private static void collectDiffs(ServerLevel level, BlockPos origin,
                                     int length, int height, int width,
                                     java.util.Map<BlockPos, BlockState> snapshot,
                                     Set<BlockPos> skip,
                                     List<DiffEntry> out) {
        if (snapshot == null) return;
        for (int dx = 0; dx < length; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < width; dz++) {
                    BlockPos local = new BlockPos(dx, dy, dz);
                    if (skip.contains(local)) continue;
                    BlockState live = level.getBlockState(origin.offset(dx, dy, dz));
                    BlockState exp = snapshot.get(local);
                    if (exp == null) {
                        if (!live.isAir()) {
                            out.add(new DiffEntry(local, "air", describe(live)));
                        }
                    } else if (!exp.equals(live)) {
                        out.add(new DiffEntry(local, describe(exp), describe(live)));
                    }
                }
            }
        }
    }

    /** Compact human-friendly state description (registry id, no namespace). */
    private static String describe(BlockState state) {
        if (state.isAir()) return "air";
        net.minecraft.resources.ResourceLocation rl =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = rl == null ? state.getBlock().toString() : rl.getPath();
        return path;
    }

    private static Set<BlockPos> variantCellPositions(List<CarriageVariantBlocks.Entry> entries) {
        if (entries == null || entries.isEmpty()) return java.util.Collections.emptySet();
        Set<BlockPos> out = new HashSet<>(entries.size());
        for (CarriageVariantBlocks.Entry e : entries) {
            out.add(e.localPos());
        }
        return out;
    }

    /**
     * True iff both files exist with identical bytes, OR both are absent.
     */
    private static boolean filesEqualOrAbsent(Path a, Path b) {
        boolean aExists = Files.isRegularFile(a);
        boolean bExists = Files.isRegularFile(b);
        if (!aExists && !bExists) return true;
        if (!aExists || !bExists) return false;
        try {
            return java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] EditorDirtyCheck: file compare failed for {} vs {}", a, b, e);
            return false;
        }
    }
}
