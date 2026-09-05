package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
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

    /**
     * Clear both working copies — the wipe half of {@code BuilderWorldSetup.resetScene}, which is
     * what makes New, and switching what a builder world is building, start from nothing.
     */
    public static void reset(ServerLevel level) {
        try {
            BuilderVariantStore.replace(level, null);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder reset: could not clear variant sidecar: {}", e.toString());
        }
        Path file = BuilderStorePaths.contentsFile(level);
        ContainerContentsStore.setPathOverride(BuilderCarriagePlot.KEY, file);
        ContainerContentsStore.invalidate(BuilderCarriagePlot.KEY);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder reset: could not clear container contents {}: {}",
                    file, e.toString());
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
            case CARRIAGE, CARRIAGE_GROUP, TRACK, PORTAL_ROOM -> Vec3i.ZERO;
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
     * The build volume's extent, or null when this world holds no build. The first volume, for the
     * same reason {@link BuilderCarriagePlot} takes it: a build is one box, and the only case with
     * several is a carriage group, which has no sidecar to carry either way.
     */
    private static @Nullable Vec3i buildFootprint(ServerLevel level) {
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        return volumes.isEmpty() ? null : BuilderBounds.sizeOf(volumes.get(0));
    }

    /** This build's container-contents document, with its per-world path already registered. */
    private static ContainerContentsStore builderContents(ServerLevel level) {
        ContainerContentsStore.setPathOverride(BuilderCarriagePlot.KEY, BuilderStorePaths.contentsFile(level));
        return ContainerContentsStore.loadFor(BuilderCarriagePlot.KEY);
    }

    private static void saveContents(ServerLevel level) {
        try {
            builderContents(level).save();
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
        try {
            for (BlockPos pos : Set.copyOf(to.allPositions())) {
                to.clearLink(pos);
                to.removePool(pos);
            }
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
