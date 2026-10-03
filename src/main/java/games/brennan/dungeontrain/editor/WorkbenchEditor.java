package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.workbench.WorkbenchPlacement;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Workbench: the editor category where relay builds of <em>any</em> kind stand side by side,
 * each where it was put, until the author commits each one as a kind.
 *
 * <p>Every other editor computes its plot origins from registry order and so re-lays itself out
 * whenever a template is added. This one does not: a staged build is given a slot the first time
 * it is stamped ({@link WorkbenchPlacement}), the slot is recorded in
 * {@link DungeonTrainWorldData#workbenchPlotBoxes()}, and the record is the layout from then on.
 * {@link #reconcile} is the only thing that touches the record outside a stamp: it drops boxes whose
 * staged build has gone and allocates boxes for staged builds that have none.</p>
 *
 * <p>Plots sit on the lower {@link WorkbenchPlacement#PLOT_Y} layer so a building as tall as the
 * Buildings category allows can be staged. Like every other category, nothing here answers to a
 * position unless the Workbench is the resident category ({@link EditorStampedCategoryState}).</p>
 */
public final class WorkbenchEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The model id every staged plot shares — the name tells them apart, as buildings do. */
    public static final String MODEL_ID = "staged";

    private WorkbenchEditor() {}

    // ---- where things are ----

    /** The recorded origin of {@code stagedId}'s plot, or null when it has none. */
    public static BlockPos plotOrigin(ServerLevel level, String stagedId) {
        int[] box = DungeonTrainWorldData.get(level.getServer().overworld()).workbenchPlotBox(stagedId);
        return box == null ? null : new BlockPos(box[0], box[1], box[2]);
    }

    /** {@code stagedId}'s footprint — the staged snapshot's size, or {@code (1,1,1)} for an unknown id. */
    public static Vec3i plotSize(String stagedId) {
        return WorkbenchStagingStore.find(stagedId).map(WorkbenchStagedBuild::size).orElse(new Vec3i(1, 1, 1));
    }

    /** {@link #plotContaining(ServerLevel, BlockPos)} against the running server's overworld. */
    public static Optional<String> plotContaining(BlockPos pos) {
        if (!EditorStampedCategoryState.isActive(EditorCategory.WORKBENCH)) return Optional.empty();
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? Optional.empty() : plotContaining(server.overworld(), pos);
    }

    /** The staged build whose plot (cage included) holds {@code pos}; empty unless the Workbench is resident. */
    public static Optional<String> plotContaining(ServerLevel level, BlockPos pos) {
        if (!EditorStampedCategoryState.isActive(EditorCategory.WORKBENCH)) return Optional.empty();
        for (Map.Entry<String, int[]> e : DungeonTrainWorldData.get(level.getServer().overworld())
                .workbenchPlotBoxes().entrySet()) {
            BoundingBox box = WorkbenchPlacement.boxOf(e.getValue());
            if (box != null && box.isInside(pos)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** Every recorded plot box, cage included. */
    public static List<BoundingBox> recordedBoxes(ServerLevel overworld) {
        List<BoundingBox> out = new ArrayList<>();
        for (int[] b : DungeonTrainWorldData.get(overworld).workbenchPlotBoxes().values()) {
            BoundingBox box = WorkbenchPlacement.boxOf(b);
            if (box != null) out.add(box);
        }
        return out;
    }

    /** One box around every recorded plot, or null when none is recorded. */
    public static BoundingBox allPlotsBox(ServerLevel overworld) {
        return EditorLayerSweep.unionOf(recordedBoxes(overworld));
    }

    /**
     * Bring the record in line with the shelf: forget boxes of builds no longer staged, and give a
     * slot to every staged build that has none. Run before any stamp-everything pass.
     */
    public static void reconcile(ServerLevel overworld) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        for (String id : data.workbenchPlotBoxes().keySet()) {
            if (WorkbenchStagingStore.find(id).isEmpty()) {
                LOGGER.info("[DungeonTrain] Workbench: '{}' is no longer staged — forgetting its plot", id);
                data.forgetWorkbenchPlotBox(id);
            }
        }
        for (WorkbenchStagedBuild build : WorkbenchStagingStore.list()) {
            int[] box = data.workbenchPlotBox(build.stagedId());
            if (box == null) {
                allocate(overworld, build, WorkbenchPlacement.FIRST_X);
            } else if (box[3] != build.size().getX() || box[4] != build.size().getY() || box[5] != build.size().getZ()) {
                // A save changed the footprint; the origin stays, the recorded size follows.
                data.recordWorkbenchPlotBox(build.stagedId(),
                    WorkbenchPlacement.record(new BlockPos(box[0], box[1], box[2]), build.size()));
            }
        }
    }

    /** Give {@code build} the first free slot at or past {@code anchorX} and record it. */
    public static BlockPos allocate(ServerLevel overworld, WorkbenchStagedBuild build, int anchorX) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        List<BoundingBox> taken = new ArrayList<>();
        for (Map.Entry<String, int[]> e : data.workbenchPlotBoxes().entrySet()) {
            if (e.getKey().equals(build.stagedId())) continue;
            BoundingBox box = WorkbenchPlacement.boxOf(e.getValue());
            if (box != null) taken.add(box);
        }
        // Free of recorded boxes AND free of blocks: the record can lag the world, and a build is
        // never stamped into something already standing on the layer.
        BlockPos origin = WorkbenchPlacement.nextFree(taken, build.size(), anchorX, box -> holdsBlocks(overworld, box));
        data.recordWorkbenchPlotBox(build.stagedId(), WorkbenchPlacement.record(origin, build.size()));
        return origin;
    }

    /** Whether any cell of {@code box} is not air. Loads the few chunks it spans; a plot is small. */
    static boolean holdsBlocks(ServerLevel level, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return true;
                }
            }
        }
        return false;
    }

    // ---- stamping ----

    /** Stamp {@code stagedId} at its recorded slot, allocating one first if it has none. */
    public static void stampPlot(ServerLevel overworld, String stagedId) {
        Optional<WorkbenchStagedBuild> staged = WorkbenchStagingStore.find(stagedId);
        if (staged.isEmpty()) return;
        BlockPos origin = plotOrigin(overworld, stagedId);
        if (origin == null) origin = allocate(overworld, staged.get(), WorkbenchPlacement.FIRST_X);
        stampAt(overworld, staged.get(), origin);
    }

    /** Erase {@code origin}'s box, place the staged snapshot, cage it, and record the plot. */
    public static void stampAt(ServerLevel overworld, WorkbenchStagedBuild build, BlockPos origin) {
        Vec3i size = build.size();
        Optional<CompoundTag> blocks = WorkbenchStagingStore.readBlocks(build.stagedId());
        CarriageStampGuard.run(() -> {
            PortalClear.clearBoxRelit(overworld, WorkbenchPlacement.boxAt(origin, size), PortalCorridorMask.NONE);
            if (blocks.isPresent()) {
                CarriageBlockSnapshot.place(overworld, origin, blocks.get());
            } else {
                LOGGER.warn("[DungeonTrain] Workbench: '{}' has no blocks file — stamping an empty plot", build.stagedId());
            }
            EditorPlotCage.setOutline(overworld, origin, size, EditorPlotCage.OUTLINE_BLOCK);
        });
        DungeonTrainWorldData.get(overworld).recordWorkbenchPlotBox(build.stagedId(),
            WorkbenchPlacement.record(origin, size));
        EditorPlotSnapshots.capture(snapshotKey(build.stagedId()), overworld, origin, size.getX(), size.getY(), size.getZ());
    }

    /** One erase job per recorded plot — what a category switch away from the Workbench queues. */
    public static List<EditorStampQueue.Job> clearAllPlotJobs(ServerLevel overworld) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (Map.Entry<String, int[]> e : DungeonTrainWorldData.get(overworld).workbenchPlotBoxes().entrySet()) {
            BoundingBox box = WorkbenchPlacement.boxOf(e.getValue());
            if (box == null) continue;
            String id = e.getKey();
            jobs.add(new EditorStampQueue.Job("erase workbench " + id,
                () -> eraseBox(overworld, box, id), box));
        }
        return jobs;
    }

    /** Erase every recorded plot now. The record is kept: the plots come back on the next entry. */
    public static void clearAllPlots(ServerLevel overworld) {
        for (EditorStampQueue.Job job : clearAllPlotJobs(overworld)) job.work().run();
    }

    /** Erase {@code stagedId}'s plot, cage included. Its record is kept. */
    public static void clearPlot(ServerLevel overworld, String stagedId) {
        int[] b = DungeonTrainWorldData.get(overworld).workbenchPlotBox(stagedId);
        BoundingBox box = WorkbenchPlacement.boxOf(b);
        if (box != null) eraseBox(overworld, box, stagedId);
    }

    /** Empty {@code stagedId}'s box, keeping its cage — the plot panel's Clear. Unsaved until a save. */
    public static void clearBlocks(ServerLevel overworld, String stagedId) {
        BlockPos origin = plotOrigin(overworld, stagedId);
        if (origin == null) return;
        Vec3i size = plotSize(stagedId);
        CarriageStampGuard.run(() -> {
            for (int dx = 0; dx < size.getX(); dx++) {
                for (int dy = 0; dy < size.getY(); dy++) {
                    for (int dz = 0; dz < size.getZ(); dz++) {
                        overworld.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        });
    }

    private static void eraseBox(ServerLevel overworld, BoundingBox box, String stagedId) {
        CarriageStampGuard.run(() -> PortalClear.clearBoxRelit(overworld, box, PortalCorridorMask.NONE));
        EditorPlotSnapshots.clear(snapshotKey(stagedId));
    }

    // ---- the author's actions ----

    /** Put {@code player} on (or in) {@code stagedId}'s plot. */
    public static void enter(ServerPlayer player, ServerLevel overworld, String stagedId, boolean onTop,
                             EditorPlotArrival.Inside inside) {
        BlockPos origin = plotOrigin(overworld, stagedId);
        if (origin == null) return;
        EditorPlotArrival.land(player, overworld, origin, plotSize(stagedId), onTop, inside, null);
    }

    /**
     * Re-capture {@code stagedId}'s plot into its staged snapshot. The Workbench's Save: the blocks
     * on disk become what stands in the plot, and nothing else — no store, no registry, no relay.
     *
     * @return false when the id is not staged or has no plot
     */
    public static boolean save(ServerLevel overworld, String stagedId) throws IOException {
        Optional<WorkbenchStagedBuild> staged = WorkbenchStagingStore.find(stagedId);
        BlockPos origin = plotOrigin(overworld, stagedId);
        if (staged.isEmpty() || origin == null) return false;
        Vec3i size = staged.get().size();
        CarriageBlockSnapshot.Captured captured = CarriageBlockSnapshot.captureLevel(
            overworld, origin, size, overworld.registryAccess(), 0);
        WorkbenchStagingStore.replaceBlocks(stagedId, captured.tag(), size);
        EditorPlotSnapshots.capture(snapshotKey(stagedId), overworld, origin, size.getX(), size.getY(), size.getZ());
        return true;
    }

    /** Erase {@code stagedId}'s plot, forget where it stood, and delete it from the shelf. */
    public static boolean remove(ServerLevel overworld, String stagedId) throws IOException {
        clearPlot(overworld, stagedId);
        DungeonTrainWorldData.get(overworld).forgetWorkbenchPlotBox(stagedId);
        return WorkbenchStagingStore.delete(stagedId);
    }

    /** The {@link EditorPlotSnapshots} key for {@code stagedId}'s plot. */
    public static String snapshotKey(String stagedId) {
        return EditorPlotSnapshots.key(EditorCategory.WORKBENCH.id(), stagedId);
    }
}
