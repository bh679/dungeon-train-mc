package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.editor.pool.PoolScratch;
import games.brennan.dungeontrain.editor.pool.PoolTier;
import games.brennan.dungeontrain.editor.workbench.WorkbenchPlacement;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.editor.workbench.WorkbenchTestScratch;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ContentsSize;
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
        if (box == null) return null;
        int inset = WorkbenchStagingStore.find(stagedId).map(WorkbenchEditor::frameInset).orElse(0);
        return new BlockPos(box[0] + inset, box[1] + inset, box[2] + inset);
    }

    // ---- the frame: what the record holds and the cage goes round ----

    /**
     * How far the build stands inside its recorded frame. A contents build is stamped inside its
     * carriage shell ({@link #shellFor}), so its frame is the shell box one block out on every axis
     * and the bedrock cage goes round that, as the Contents editor's does; every other kind's frame
     * is the build itself.
     */
    static int frameInset(WorkbenchStagedBuild build) {
        return BuilderRelayKinds.kindOf(build.relayKind()) == BuilderPhotoPaths.Kind.CONTENTS ? 1 : 0;
    }

    /** The frame's origin for a build standing at {@code origin}. */
    static BlockPos frameOrigin(WorkbenchStagedBuild build, BlockPos origin) {
        int inset = frameInset(build);
        return origin.offset(-inset, -inset, -inset);
    }

    /** The frame's size: the build's, grown by the inset on every side. */
    static Vec3i frameSize(WorkbenchStagedBuild build) {
        int inset = frameInset(build);
        return build.size().offset(2 * inset, 2 * inset, 2 * inset);
    }

    /** The world-data record of {@code build} standing at {@code origin}: its frame. */
    static int[] record(WorkbenchStagedBuild build, BlockPos origin) {
        return WorkbenchPlacement.record(frameOrigin(build, origin), frameSize(build));
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
                .workbenchPlotBoxesView().entrySet()) {
            BoundingBox box = WorkbenchPlacement.boxOf(e.getValue());
            if (box != null && box.isInside(pos)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** Every recorded plot box, cage included. */
    public static List<BoundingBox> recordedBoxes(ServerLevel overworld) {
        List<BoundingBox> out = new ArrayList<>();
        for (int[] b : DungeonTrainWorldData.get(overworld).workbenchPlotBoxesView().values()) {
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
            } else if (frameInset(build) > 0 && box[3] == build.size().getX() && box[4] == build.size().getY()
                    && box[5] == build.size().getZ()) {
                // A record from before contents builds had a shell: it holds the interior, so the frame
                // goes round it where it already stands.
                data.recordWorkbenchPlotBox(build.stagedId(), record(build, new BlockPos(box[0], box[1], box[2])));
            } else if (box[3] != frameSize(build).getX() || box[4] != frameSize(build).getY()
                    || box[5] != frameSize(build).getZ()) {
                // A save changed the footprint; the frame's origin stays, the recorded size follows.
                data.recordWorkbenchPlotBox(build.stagedId(),
                    WorkbenchPlacement.record(new BlockPos(box[0], box[1], box[2]), frameSize(build)));
            }
        }
    }

    /** Give {@code build} the first free slot at or past {@code anchorX} and record it. */
    public static BlockPos allocate(ServerLevel overworld, WorkbenchStagedBuild build, int anchorX) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        List<BoundingBox> taken = new ArrayList<>();
        for (Map.Entry<String, int[]> e : data.workbenchPlotBoxesView().entrySet()) {
            if (e.getKey().equals(build.stagedId())) continue;
            BoundingBox box = WorkbenchPlacement.boxOf(e.getValue());
            if (box != null) taken.add(box);
        }
        // Free of recorded boxes AND free of blocks: the record can lag the world, and a build is
        // never stamped into something already standing on the layer.
        BlockPos frame = WorkbenchPlacement.nextFree(taken, frameSize(build), anchorX, box -> holdsBlocks(overworld, box));
        data.recordWorkbenchPlotBox(build.stagedId(), WorkbenchPlacement.record(frame, frameSize(build)));
        int inset = frameInset(build);
        return frame.offset(inset, inset, inset);
    }

    /**
     * Whether any cell of {@code box} is not air. Section first: a 16-cube the chunk already knows is
     * all air is skipped in one flag, and only a section holding something is walked block by block
     * — the slot scan asks this up to {@link WorkbenchPlacement#MAX_OCCUPIED_STEPS} times per
     * allocation, and a staged building can span a dozen sections.
     */
    static boolean holdsBlocks(ServerLevel level, BoundingBox box) {
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                for (int sy = box.minY() >> 4; sy <= box.maxY() >> 4; sy++) {
                    int index = chunk.getSectionIndex(sy << 4);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    if (chunk.getSection(index).hasOnlyAir()) continue;
                    if (holdsBlocksIn(level, box, cx, cz, sy)) return true;
                }
            }
        }
        return false;
    }

    /** The block-by-block check of {@code box} within one chunk section, for a section that is not all air. */
    private static boolean holdsBlocksIn(ServerLevel level, BoundingBox box, int cx, int cz, int sy) {
        int x0 = Math.max(box.minX(), cx << 4), x1 = Math.min(box.maxX(), (cx << 4) + 15);
        int z0 = Math.max(box.minZ(), cz << 4), z1 = Math.min(box.maxZ(), (cz << 4) + 15);
        int y0 = Math.max(box.minY(), sy << 4), y1 = Math.min(box.maxY(), (sy << 4) + 15);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) return true;
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

    /**
     * Erase the plot's frame, cage it, place the staged snapshot, and record the plot. A contents build
     * stands inside its tier's default carriage shell, as it does in the Contents editor: the frame is
     * the shell box, the bedrock cage goes round that, and the interior is placed last so the build's
     * own blocks win inside it. Save still captures only the interior, so the shell never becomes part
     * of the build.
     */
    public static void stampAt(ServerLevel overworld, WorkbenchStagedBuild build, BlockPos origin) {
        Vec3i size = build.size();
        Optional<CompoundTag> blocks = WorkbenchStagingStore.readBlocks(build.stagedId());
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        Optional<CarriageVariant> shell = shellFor(build, dims);
        BlockPos frameOrigin = frameOrigin(build, origin);
        Vec3i frameSize = frameSize(build);
        CarriageStampGuard.run(() -> {
            PortalClear.clearBoxRelit(overworld, WorkbenchPlacement.boxAt(frameOrigin, frameSize), PortalCorridorMask.NONE);
            // The bedrock cage goes round the frame — outside the shell, as in the Contents editor.
            EditorPlotCage.setOutline(overworld, frameOrigin, frameSize, EditorPlotCage.OUTLINE_BLOCK);
            if (shell.isPresent()) {
                CarriagePlacer.placeAt(overworld, origin.offset(-1, -1, -1), shell.get(), dims);
            }
            if (blocks.isPresent()) {
                CarriageBlockSnapshot.place(overworld, origin, blocks.get());
            } else {
                LOGGER.warn("[DungeonTrain] Workbench: '{}' has no blocks file — stamping an empty plot", build.stagedId());
            }
        });
        DungeonTrainWorldData.get(overworld).recordWorkbenchPlotBox(build.stagedId(), record(build, origin));
        EditorPlotSnapshots.capture(snapshotKey(build.stagedId()), overworld, origin, size.getX(), size.getY(), size.getZ());
    }

    /**
     * The carriage shell a staged contents build stands inside: its size tier's default shell
     * ({@code standard}, {@code standard_half} or {@code carriage}), as the Contents editor shows it.
     * Empty for every other kind, for a contents build whose size is no tier's interior, and when this
     * install lacks the shell — those keep the plain cage.
     */
    static Optional<CarriageVariant> shellFor(WorkbenchStagedBuild build, CarriageDims dims) {
        if (BuilderRelayKinds.kindOf(build.relayKind()) != BuilderPhotoPaths.Kind.CONTENTS) return Optional.empty();
        ContentsSize contentsSize = WorkbenchTestScratch.contentsSizeFor(build.size(), dims);
        if (contentsSize == null) {
            LOGGER.info("[DungeonTrain] Workbench: contents build '{}' is {} — no tier's interior, so no shell around it",
                build.stagedId(), build.size());
            return Optional.empty();
        }
        return CarriageVariantRegistry.find(PoolScratch.defaultShellId(PoolTier.of(contentsSize)));
    }

    /**
     * The shell box (cage excluded) round every staged contents build that stands in one — what the
     * client fades the shell's stage placeholders inside. Empty off the server.
     */
    public static List<BoundingBox> shellBoxes(ServerLevel overworld) {
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        List<BoundingBox> out = new ArrayList<>();
        for (WorkbenchStagedBuild build : WorkbenchStagingStore.list()) {
            BlockPos origin = plotOrigin(overworld, build.stagedId());
            if (origin == null || shellFor(build, dims).isEmpty()) continue;
            BlockPos shell = origin.offset(-1, -1, -1);
            Vec3i size = build.size().offset(2, 2, 2);
            out.add(new BoundingBox(shell.getX(), shell.getY(), shell.getZ(),
                shell.getX() + size.getX() - 1, shell.getY() + size.getY() - 1, shell.getZ() + size.getZ() - 1));
        }
        return out;
    }

    /** One erase job per recorded plot — what a category switch away from the Workbench queues. */
    public static List<EditorStampQueue.Job> clearAllPlotJobs(ServerLevel overworld) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (Map.Entry<String, int[]> e : DungeonTrainWorldData.get(overworld).workbenchPlotBoxesView().entrySet()) {
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
        games.brennan.dungeontrain.editor.workbench.WorkbenchSidecarFold.forget(stagedId);
        return WorkbenchStagingStore.delete(stagedId);
    }

    /** The {@link EditorPlotSnapshots} key for {@code stagedId}'s plot. */
    public static String snapshotKey(String stagedId) {
        return EditorPlotSnapshots.key(EditorCategory.WORKBENCH.id(), stagedId);
    }
}
