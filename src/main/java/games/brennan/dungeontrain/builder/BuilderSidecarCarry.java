package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Moves the two authoring documents — block-variant pools (Z menu) and container contents
 * (C menu) — between a template and the Train Builder's own per-world working copies.
 *
 * <p>The builder never edits a template's documents in place: it is authoring a build that may end
 * up saved under a different name, or discarded. So the flow is copy in on open, copy out on save,
 * with {@link BuilderStorePaths} holding the working copies in between.</p>
 *
 * <p>Both directions go through {@link BlockVariantPlot#resolveByKey}, which is the one place that
 * knows which of the four sidecar flavours a template plot key names. It resolves fine from a
 * builder world: the plot origins it computes are arithmetic over the template registries, and
 * nothing here reads them anyway.</p>
 *
 * <h2>Coordinates</h2>
 * The working copies are relative to the <b>build volume</b> — the whole carriage box for every
 * carriage-side build ({@link BuilderBounds#buildVolumes}), which is what the author is standing in
 * and what {@link BuilderCarriagePlot} hands the menus. A template's document is relative to the
 * template, and for two of the kinds those are not the same corner: a room's is the carriage
 * interior, a part's is wherever that part is stamped. Hence {@code offset} — the template's origin
 * expressed in build-volume coordinates. Cells that fall outside the template are dropped, which is
 * the honest answer for a pool authored on a wall that is not part of the thing being saved.
 */
public final class BuilderSidecarCarry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderSidecarCarry() {}

    /**
     * Copy {@code templatePlotKey}'s documents into this build's working copies, replacing whatever
     * was there. The open path — what makes an existing template's variant pools and chest contents
     * editable in the builder rather than invisible until they are silently overwritten.
     */
    public static void seedFromTemplate(ServerLevel level, String templatePlotKey,
                                        CarriageDims dims, Vec3i offset) {
        Vec3i footprint = buildFootprint(level);
        if (footprint == null) return;

        BlockVariantPlot source = BlockVariantPlot.resolveByKey(templatePlotKey, dims);
        if (source != null) {
            TrackVariantBlocks target = BuilderVariantStore.loadFor(level, footprint);
            try {
                clearCells(target);
                for (BlockPos pos : source.allFlaggedPositions()) {
                    List<VariantState> states = source.statesAt(pos);
                    if (states == null) continue;
                    BlockPos local = pos.offset(offset);
                    if (!inBounds(local, footprint)) continue;
                    target.put(local, states);
                    int lockId = source.lockIdAt(pos);
                    if (lockId > 0) target.setLockId(local, lockId);
                    // Carried like the lock-id, and for the same reason: they are authored on the
                    // cell, so a room opened in the builder and saved back must come out holding
                    // what it went in with.
                    target.setCopyRoll(local, source.copyRollAt(pos));
                    target.setCopyScope(local, source.copyScopeAt(pos));
                    target.setSpan(local, source.spanAt(pos));
                }
                BuilderVariantStore.save(level, target, footprint);
            } catch (Throwable t) {
                // Loud, and only cosmetic against the geometry — the build stamps either way. But
                // starting from an empty sidecar is exactly the loss that only shows up at save
                // time, so it must not pass quietly.
                LOGGER.warn("[DungeonTrain] Builder open: could not seed variant pools from {}: {}",
                        templatePlotKey, t.toString());
            }
        }

        copyContents(ContainerContentsStore.loadFor(templatePlotKey), builderContents(level), offset,
                footprint, templatePlotKey);
        saveContents(level);
    }

    /**
     * Write this build's documents onto {@code templatePlotKey}, which a save has just registered.
     *
     * <p>Cells are cleared and refilled rather than the file being overwritten wholesale, so the
     * target keeps the mirror axes it already had — those are carried separately, and for a track
     * template they are not carried at all.</p>
     */
    public static void carryToTemplate(ServerLevel level, String templatePlotKey,
                                       CarriageDims dims, Vec3i offset) {
        Vec3i footprint = buildFootprint(level);
        if (footprint == null) return;

        TrackVariantBlocks doc = BuilderVariantStore.loadFor(level, footprint);
        BlockVariantPlot target = BlockVariantPlot.resolveByKey(templatePlotKey, dims);
        if (target == null) {
            LOGGER.warn("[DungeonTrain] Builder save: no plot for {} — variant pools not carried.",
                    templatePlotKey);
        } else {
            try {
                for (BlockPos pos : target.allFlaggedPositions()) {
                    target.remove(pos);
                }
                for (CarriageVariantBlocks.Entry entry : doc.entries()) {
                    BlockPos local = entry.localPos().subtract(offset);
                    if (!target.inBounds(local)) continue;
                    target.put(local, entry.states());
                    int lockId = doc.lockIdAt(entry.localPos());
                    if (lockId > 0) target.setLockId(local, lockId);
                    target.setCopyRoll(local, doc.copyRollAt(entry.localPos()));
                    target.setCopyScope(local, doc.copyScopeAt(entry.localPos()));
                    target.setSpan(local, doc.spanAt(entry.localPos()));
                }
                target.save();
            } catch (Throwable t) {
                // The geometry is already written. Losing the pools is bad, but not a reason to fail
                // a save that otherwise worked — and the working copy still holds them.
                LOGGER.warn("[DungeonTrain] Builder save: could not carry variant pools to {}: {}",
                        templatePlotKey, t.toString());
            }
        }

        ContainerContentsStore carried = ContainerContentsStore.detached(templatePlotKey);
        copyContents(builderContents(level), carried, negate(offset),
                templateFootprint(target, footprint), templatePlotKey);
        try {
            carried.save();
            ContainerContentsStore.invalidate(templatePlotKey);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder save: could not carry container contents to {}: {}",
                    templatePlotKey, e.toString());
        }
    }

    // ---- carriage groups: one template, several parked carriages ----

    /**
     * Copy carriage group {@code groupId}'s documents into the working copies, one parked carriage
     * at a time. The group's cells are relative to the run's first corner; each carriage's working
     * copy is relative to its own, so carriage {@code i} takes the slice {@code i} carriage-lengths
     * along and nothing else.
     */
    public static void seedGroupFromTemplate(ServerLevel level, String groupId, CarriageDims dims) {
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        if (volumes.isEmpty()) return;
        String plotKey = BlockVariantPlot.wholeKey(WholeKind.GROUP, groupId);
        ContainerContentsStore templateContents = ContainerContentsStore.loadFor(plotKey);
        BlockVariantPlot source = null;
        try {
            source = BlockVariantPlot.wholeGroupDocument(groupId, CarriageGroupPlacer.sizeOf(dims, volumes.size()));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder open: could not read variant pools of {}: {}", plotKey, t.toString());
        }
        for (int i = 0; i < volumes.size(); i++) {
            Vec3i footprint = BuilderBounds.sizeOf(volumes.get(i));
            Vec3i toVolume = negate(runOffset(i, dims));
            if (source != null) {
                try {
                    TrackVariantBlocks target = BuilderVariantStore.loadFor(level, i, footprint);
                    clearCells(target);
                    for (BlockPos pos : source.allFlaggedPositions()) {
                        BlockPos local = pos.offset(toVolume);
                        if (!inBounds(local, footprint)) continue;
                        copyCell(source, pos, target, local);
                    }
                    BuilderVariantStore.save(level, i, target, footprint);
                } catch (Throwable t) {
                    LOGGER.warn("[DungeonTrain] Builder open: could not seed variant pools from {}: {}",
                            plotKey, t.toString());
                }
            }
            ContainerContentsStore working = builderContents(level, i);
            clearContents(working, plotKey);
            addContents(templateContents, working, toVolume, footprint, plotKey);
            saveContents(working);
        }
    }

    /**
     * Write every parked carriage's documents onto carriage group {@code groupId}, which a save has
     * just written — the reverse of {@link #seedGroupFromTemplate}, bounded by the run that was
     * actually saved rather than by the configured group size.
     */
    public static void carryGroupToTemplate(ServerLevel level, String groupId, CarriageDims dims) {
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        if (volumes.isEmpty()) return;
        String plotKey = BlockVariantPlot.wholeKey(WholeKind.GROUP, groupId);
        Vec3i run = CarriageGroupPlacer.sizeOf(dims, volumes.size());
        try {
            BlockVariantPlot target = BlockVariantPlot.wholeGroupDocument(groupId, run);
            for (BlockPos pos : target.allFlaggedPositions()) {
                target.remove(pos);
            }
            for (int i = 0; i < volumes.size(); i++) {
                TrackVariantBlocks doc = BuilderVariantStore.loadFor(level, i, BuilderBounds.sizeOf(volumes.get(i)));
                Vec3i toRun = runOffset(i, dims);
                for (CarriageVariantBlocks.Entry entry : doc.entries()) {
                    BlockPos local = entry.localPos().offset(toRun);
                    if (!target.inBounds(local)) continue;
                    target.put(local, entry.states());
                    int lockId = doc.lockIdAt(entry.localPos());
                    if (lockId > 0) target.setLockId(local, lockId);
                    target.setCopyRoll(local, doc.copyRollAt(entry.localPos()));
                    target.setCopyScope(local, doc.copyScopeAt(entry.localPos()));
                    target.setSpan(local, doc.spanAt(entry.localPos()));
                }
            }
            target.save();
        } catch (Throwable t) {
            // As for a single carriage: the geometry is written and the working copies still hold
            // the pools, so this is loud rather than fatal.
            LOGGER.warn("[DungeonTrain] Builder save: could not carry variant pools to {}: {}",
                    plotKey, t.toString());
        }

        ContainerContentsStore carried = ContainerContentsStore.detached(plotKey);
        for (int i = 0; i < volumes.size(); i++) {
            addContents(builderContents(level, i), carried, runOffset(i, dims), run, plotKey);
        }
        try {
            carried.save();
            ContainerContentsStore.invalidate(plotKey);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder save: could not carry container contents to {}: {}",
                    plotKey, e.toString());
        }
    }

    /** Where parked carriage {@code volume}'s corner sits inside the run it is part of. */
    static Vec3i runOffset(int volume, CarriageDims dims) {
        return new Vec3i(Math.max(0, volume) * dims.length(), 0, 0);
    }

    private static void copyCell(BlockVariantPlot source, BlockPos from, TrackVariantBlocks target, BlockPos to) {
        List<VariantState> states = source.statesAt(from);
        if (states == null) return;
        target.put(to, states);
        int lockId = source.lockIdAt(from);
        if (lockId > 0) target.setLockId(to, lockId);
        target.setCopyRoll(to, source.copyRollAt(from));
        target.setCopyScope(to, source.copyScopeAt(from));
        target.setSpan(to, source.spanAt(from));
    }

    /**
     * Clear every working copy — the wipe half of {@code BuilderWorldSetup.resetScene}, which is
     * what makes New, and switching what a builder world is building, start from nothing.
     *
     * <p>Every carriage's, not just the ones parked now: the count is about to change, and a third
     * carriage's pools left behind would resurface the next time this world parks three.</p>
     */
    public static void reset(ServerLevel level) {
        for (int i = 0; i < BuilderStorePaths.MAX_VOLUMES; i++) {
            try {
                BuilderVariantStore.replace(level, i, null);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Builder reset: could not clear variant sidecar: {}", e.toString());
            }
            Path file = BuilderStorePaths.contentsFile(level, i);
            String key = BuilderCarriagePlot.keyFor(i);
            ContainerContentsStore.setPathOverride(key, file);
            ContainerContentsStore.invalidate(key);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Builder reset: could not clear container contents {}: {}",
                        file, e.toString());
            }
        }
    }

    /**
     * Where {@code kind}'s template origin sits inside the build volume, which for every
     * carriage-side build is the whole carriage box.
     *
     * <p>Zero for the three kinds whose template <i>is</i> the volume — a whole carriage, a track
     * template, a portal room. A carriage room starts one block in on every axis (the shell around
     * it is not part of it), and a part sits wherever its first placement puts it: the unmirrored
     * one, which is the copy {@code BuilderSave.savePart} captures.</p>
     */
    public static Vec3i offsetFor(BuilderPhotoPaths.Kind kind, @Nullable CarriagePartKind partKind,
                                  CarriageDims dims) {
        return switch (kind) {
            case CONTENTS -> CONTENTS_OFFSET;
            case PART -> partOffset(partKind, dims);
            case CARRIAGE, CARRIAGE_GROUP, TRACK, PORTAL_ROOM, CHUNK_FRAME -> Vec3i.ZERO;
        };
    }

    /** {@code CarriageContentsPlacer.interiorOrigin} is the carriage origin offset by one on each axis. */
    private static final Vec3i CONTENTS_OFFSET = new Vec3i(1, 1, 1);

    private static Vec3i partOffset(@Nullable CarriagePartKind partKind, CarriageDims dims) {
        if (partKind == null) return Vec3i.ZERO;
        List<CarriagePartKind.Placement> placements = partKind.placements(dims);
        return placements.isEmpty() ? Vec3i.ZERO : placements.get(0).originOffset();
    }

    // ---- helpers ----

    /**
     * The build volume's extent, or null when this world holds no build. The first volume: a build
     * is one box, and the only case with several is a carriage group, which goes through
     * {@link #seedGroupFromTemplate} and {@link #carryGroupToTemplate} instead.
     */
    private static @Nullable Vec3i buildFootprint(ServerLevel level) {
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        return volumes.isEmpty() ? null : BuilderBounds.sizeOf(volumes.get(0));
    }

    /** This build's container-contents document, with its per-world path already registered. */
    private static ContainerContentsStore builderContents(ServerLevel level) {
        return builderContents(level, 0);
    }

    /** Parked carriage {@code volume}'s container-contents document, path registered. */
    private static ContainerContentsStore builderContents(ServerLevel level, int volume) {
        String key = BuilderCarriagePlot.keyFor(volume);
        ContainerContentsStore.setPathOverride(key, BuilderStorePaths.contentsFile(level, volume));
        return ContainerContentsStore.loadFor(key);
    }

    private static void saveContents(ServerLevel level) {
        saveContents(builderContents(level));
    }

    private static void saveContents(ContainerContentsStore store) {
        try {
            store.save();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder: could not write container contents store: {}", e.toString());
        }
    }

    /**
     * Copy every authored position from {@code from} to {@code to}, shifted by {@code offset} and
     * bounded by {@code bounds}. A linked position carries its link rather than the pool the link
     * currently resolves to, so the copy stays live against the prefab.
     */
    private static void copyContents(ContainerContentsStore from, ContainerContentsStore to,
                                     Vec3i offset, Vec3i bounds, String context) {
        clearContents(to, context);
        addContents(from, to, offset, bounds, context);
    }

    private static void clearContents(ContainerContentsStore to, String context) {
        try {
            for (BlockPos pos : Set.copyOf(to.allPositions())) {
                to.clearLink(pos);
                to.removePool(pos);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder: could not clear container contents for {}: {}",
                    context, t.toString());
        }
    }

    /** {@link #copyContents} without the clear — what lets several carriages land in one document. */
    private static void addContents(ContainerContentsStore from, ContainerContentsStore to,
                                    Vec3i offset, Vec3i bounds, String context) {
        try {
            for (BlockPos pos : from.allPositions()) {
                BlockPos local = pos.offset(offset);
                if (bounds != null && !inBounds(local, bounds)) continue;
                String link = from.linkAt(pos);
                if (link != null) {
                    to.setLink(local, link);
                    continue;
                }
                ContainerContentsPool pool = from.poolAt(pos);
                if (!pool.isEmpty()) to.putPool(local, pool);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder: could not copy container contents for {}: {}",
                    context, t.toString());
        }
    }

    /** Remove every cell from the builder's working sidecar, leaving its mirror flags alone. */
    private static void clearCells(TrackVariantBlocks doc) {
        for (CarriageVariantBlocks.Entry entry : List.copyOf(doc.entries())) {
            doc.remove(entry.localPos());
        }
    }

    /** The target plot's footprint, falling back to the build's when there is no plot to ask. */
    private static Vec3i templateFootprint(@Nullable BlockVariantPlot target, Vec3i fallback) {
        return target == null ? fallback : target.footprint();
    }

    private static Vec3i negate(Vec3i v) {
        return new Vec3i(-v.getX(), -v.getY(), -v.getZ());
    }

    private static boolean inBounds(BlockPos pos, Vec3i size) {
        return pos.getX() >= 0 && pos.getX() < size.getX()
                && pos.getY() >= 0 && pos.getY() < size.getY()
                && pos.getZ() >= 0 && pos.getZ() < size.getZ();
    }
}
