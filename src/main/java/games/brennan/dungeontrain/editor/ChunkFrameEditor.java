package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameTemplate;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoring chunk frames — the one template that dresses a dimensional carriage room ({@link ChunkFrame}).
 *
 * <p>Laid out beside the Dimensions rooms, on the same plot layer, but on the <b>−Z</b> side of them:
 * the rooms start at Z 0 and grow toward +Z as rooms are added and toward +X as they are lengthened,
 * so nothing a room does can reach a frame. One row, one slot per frame along +X. The plots are
 * stamped with the Dimensions category and erased with it, so like every other plot they only stand
 * while their category is resident.</p>
 *
 * <p>A plot is the frame at its exact size in a bedrock cage: the room's 16 × 32 × 16 box plus the
 * one-block shell around it. The shell is where the skybox would be; everything inside is the room.</p>
 */
public final class ChunkFrameEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The id every frame row carries as its model id — frames have no kinds. */
    public static final String MODEL_ID = "chunk_frame";

    /** The rooms' own column, so the frames read as belonging to them. */
    public static final int FIRST_PLOT_X = TrackSidePlots.X_PORTALS;
    /** Plots are one block of cage on each side, then the gap. */
    private static final int STRIDE_PAD = EditorLayout.GAP + 2;
    /**
     * The room rows start at Z_BASELINE and only grow toward +Z; frames stand a gap short of it. Every
     * plot ends on this Z, so a bigger frame reaches further toward -Z.
     */
    private static final int ROW_END_Z = TrackSidePlots.Z_BASELINE - STRIDE_PAD;

    /**
     * Sizes of frames entered as a copy and not yet saved: a frame has no file to read its size from
     * until its first save, and its plot is the size of what it was copied from.
     */
    private static final Map<String, Vec3i> PENDING_SIZES = new ConcurrentHashMap<>();

    /** What each player last entered, which {@code save} writes when they are not standing in a plot. */
    private static final Map<UUID, String> SESSIONS = new ConcurrentHashMap<>();

    private ChunkFrameEditor() {}

    // ---- layout --------------------------------------------------------------

    /**
     * The size of {@code name}'s plot: the frame's own size — a one-chunk frame's or a 3x3 room's —
     * or, for a frame entered as a copy and not saved yet, the size of the frame it copies.
     */
    public static Vec3i plotSize(String name) {
        Vec3i pending = name == null ? null : PENDING_SIZES.get(name);
        return pending != null ? pending : ChunkFrameStore.sizeOf(name);
    }

    /** The plot origin of {@code name}, or the next free slot when it is not registered. */
    public static BlockPos plotOrigin(String name) {
        List<String> names = ChunkFrameRegistry.names();
        int index = names.indexOf(name);
        return slotOrigin(names, index < 0 ? names.size() : index, plotSize(name));
    }

    /** The plot origin of registered frame {@code name}, or null when no such frame is registered. */
    public static BlockPos registeredPlotOrigin(String name) {
        List<String> names = ChunkFrameRegistry.names();
        int index = names.indexOf(name);
        return index < 0 ? null : slotOrigin(names, index, plotSize(name));
    }

    /** Slot {@code index} of the row {@code names}, for a plot of {@code size}. */
    private static BlockPos slotOrigin(List<String> names, int index, Vec3i size) {
        List<Integer> widths = new ArrayList<>();
        for (int i = 0; i < index && i < names.size(); i++) widths.add(plotSize(names.get(i)).getX());
        return slotOrigin(widths, size);
    }

    /**
     * The origin of a plot of {@code size} standing after plots {@code widthsBefore} wide: each plot a
     * gap past the one before it along +X, and every plot ending on the same Z. Pure, for tests.
     */
    static BlockPos slotOrigin(List<Integer> widthsBefore, Vec3i size) {
        int x = FIRST_PLOT_X;
        for (int width : widthsBefore) x += width + STRIDE_PAD;
        return new BlockPos(x, EditorLayout.PLOT_Y, ROW_END_Z - size.getZ() + 1);
    }

    /** The first slot — where the Frames menu anchors. */
    public static BlockPos rowOrigin() {
        List<String> names = ChunkFrameRegistry.names();
        return slotOrigin(List.of(), names.isEmpty() ? ChunkFrame.SIZE : plotSize(names.get(0)));
    }

    /** The first plot's size — what the Frames menu anchors against. */
    public static Vec3i rowSize() {
        List<String> names = ChunkFrameRegistry.names();
        return names.isEmpty() ? ChunkFrame.SIZE : plotSize(names.get(0));
    }

    /**
     * The frame whose plot (cage included) holds {@code pos}, or empty. Resident category only: the
     * frames stand with the Dimensions rooms, and every category lays out from the same origin.
     */
    public static Optional<String> plotContaining(BlockPos pos) {
        if (!EditorStampedCategoryState.isActive(EditorCategory.PORTALS)) return Optional.empty();
        for (String name : ChunkFrameRegistry.names()) {
            if (EditorPlotBox.contains(pos, plotOrigin(name), plotSize(name))) return Optional.of(name);
        }
        return Optional.empty();
    }

    /** What {@code player} is working on: the plot they stand in, else the one they last entered. */
    public static Optional<String> current(ServerPlayer player) {
        Optional<String> here = plotContaining(player.blockPosition());
        return here.isPresent() ? here : Optional.ofNullable(SESSIONS.get(player.getUUID()));
    }

    // ---- enter / save --------------------------------------------------------

    /**
     * Stamp {@code name}'s plot and put {@code player} on it. {@code copyOf} seeds a new frame from an
     * existing one; null stamps what is saved, or an empty plot for a new name.
     */
    public static void enter(ServerPlayer player, ServerLevel level, String name, String copyOf) {
        EditorSessions.rememberReturn(player);
        // A copy starts at the size of what it copies, until its first save gives it a file of its own.
        if (copyOf != null && ChunkFrameStore.readTag(name).isEmpty()) {
            PENDING_SIZES.put(name, ChunkFrameStore.sizeOf(copyOf));
        }
        if (copyOf != null && !copyOf.equals(name) && ChunkFrameStore.readTag(name).isEmpty()) {
            // A copy is the frame's blocks AND its sidecars: without them the duplicate came up with
            // none of its variants, room selection or chest links. Before the plot is stamped, so the
            // plot loads the copied variants rather than an empty sidecar.
            try {
                copySidecars(copyOf, name, EditorDevMode.isEnabled());
            } catch (java.io.IOException e) {
                LOGGER.warn("[DungeonTrain] Chunk frame editor: could not copy {}'s sidecars to {}: {}",
                    copyOf, name, e.toString());
            }
        }
        ChunkFrameRegistry.register(name);
        SESSIONS.put(player.getUUID(), name);
        BlockPos origin = plotOrigin(name);
        stampPlot(level, name, origin, ChunkFrameStore.get(level, copyOf != null ? copyOf : name).orElse(null));
        EditorPlotArrival.land(player, level, origin, plotSize(name), true, EditorPlotArrival.Inside.CENTRE, null);
        LOGGER.info("[DungeonTrain] Chunk frame editor: {} entered {} at {}{}", player.getName().getString(),
            name, origin, copyOf != null ? " (copy of " + copyOf + ")" : "");
        // A frame exists from the moment it is made: a new frame, or a copy, is written to disk now,
        // as stamped. Until then it had only its settings and variants, no structure, so it vanished
        // on the next restart — and with nothing changed in its blocks there was nothing to Save.
        if (ChunkFrameStore.readTag(name).isEmpty()) {
            try {
                save(player, level, name);
                LOGGER.info("[DungeonTrain] Chunk frame editor: wrote new frame {} on creation", name);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Chunk frame editor: could not write new frame {}: {}", name, e.toString());
            }
        }
    }

    /**
     * Copy every sidecar of frame {@code from} onto {@code to}: its block variants, its meta (which
     * rooms it dresses, its weight, its gate and its dimension) and its chest links. The {@code .nbt}
     * is the caller's to write.
     */
    public static void copySidecars(String from, String to, boolean toSource) throws java.io.IOException {
        var variants = games.brennan.dungeontrain.track.variant.TrackVariantBlocks.copyOf(ChunkFrameVariants.loadFor(from));
        ChunkFrameVariants.save(to, variants, toSource);
        games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore.save(to,
            games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore.get(from), toSource);
        TemplateCopy.copy(new games.brennan.dungeontrain.template.Template.ChunkFrame(from),
            new games.brennan.dungeontrain.template.Template.ChunkFrame(to));
    }

    /**
     * Put {@code player} at {@code name}'s plot as it stands — nothing restamped, so unsaved edits
     * stay: on the roof in front of its menu, or in the middle of it when {@code centre}.
     */
    public static void walkTo(ServerPlayer player, ServerLevel level, String name, boolean centre) {
        EditorSessions.rememberReturn(player);
        SESSIONS.put(player.getUUID(), name);
        EditorPlotArrival.land(player, level, plotOrigin(name), plotSize(name), !centre,
            EditorPlotArrival.Inside.CENTRE, null);
    }

    /**
     * Capture {@code name}'s plot and save it — to the source tree too in dev mode.
     *
     * @return true when the source tree was also written
     */
    public static boolean save(ServerPlayer player, ServerLevel level, String name) throws IOException {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, plotOrigin(name), plotSize(name), false, null);
        boolean toSource = EditorDevMode.isEnabled();
        CompoundTag captured = template.save(new CompoundTag());
        // No flowing fluid in a saved frame; this capture writes air cells, so flow becomes air.
        games.brennan.dungeontrain.template.TemplateFluids.dropFlowing(captured, true);
        ChunkFrameStore.save(name, captured, toSource);
        // Saved: its file says its size from now on.
        PENDING_SIZES.remove(name);
        ChunkFrameRegistry.register(name);
        // The variant sidecar travels with its frame, as every other template's does.
        ChunkFramePlot plot = ChunkFramePlot.of(name);
        if (plot != null) plot.save();
        // After the sidecar save, which marks the sidecar edited: this is the new clean baseline.
        captureSnapshot(level, name, plotOrigin(name));
        SESSIONS.put(player.getUUID(), name);
        return toSource;
    }

    // ---- stamping ------------------------------------------------------------

    /** Every frame plot, as queued jobs — what the Dimensions category adds to its fill. */
    public static List<EditorStampQueue.Job> stampAllPlotJobs(ServerLevel level) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (String name : ChunkFrameRegistry.names()) {
            jobs.add(new EditorStampQueue.Job("stamp chunk frame " + name,
                () -> stampPlot(level, name, plotOrigin(name), ChunkFrameStore.get(level, name).orElse(null))));
        }
        return jobs;
    }

    /** Put {@code name}'s plot back to what is saved — {@code /dt reset}. */
    public static void restamp(ServerLevel level, String name) {
        BlockPos origin = registeredPlotOrigin(name);
        if (origin == null) return;
        ChunkFrameStore.invalidate(name);
        stampPlot(level, name, origin, ChunkFrameStore.get(level, name).orElse(null));
    }

    /** Erase {@code name}'s plot back to an empty cage — {@code editor clear}. */
    public static void clearPlot(ServerLevel level, String name) {
        BlockPos origin = registeredPlotOrigin(name);
        if (origin != null) stampBlocks(level, origin, plotSize(name), null);
    }

    /**
     * Delete {@code name}: its user files (and, in dev mode, its source-tree files) — structure,
     * variant sidecar and meta — and its plot, re-laying the row since the slots after it move up.
     *
     * @return false when the frame is only bundled and there is nothing of the author's to delete
     */
    public static boolean delete(ServerLevel level, String name, boolean fromSource) throws IOException {
        boolean deleted = ChunkFrameStore.deleteFiles(name, fromSource);
        deleted |= ChunkFrameVariants.delete(name, fromSource);
        deleted |= ChunkFrameMetaStore.delete(name, fromSource);
        // Nothing on disk and nothing shipped: a frame entered but never saved. Its plot still goes.
        if (!deleted && ChunkFrameStore.isBundled(name)) return false;
        relayRow(level, () -> ChunkFrameStore.invalidate(name));
        SESSIONS.values().removeIf(name::equals);
        PENDING_SIZES.remove(name);
        return true;
    }

    /**
     * Rename {@code name} to {@code newName}: its structure, variant sidecar and meta (which rooms it
     * dresses, and its weight) all move with it. The player is put on the renamed plot.
     */
    public static void rename(ServerPlayer player, ServerLevel level, String name, String newName,
                              boolean toSource) throws IOException {
        CompoundTag tag = ChunkFrameStore.readTag(name)
            .orElseThrow(() -> new IOException("'" + name + "' has no saved frame to rename — save it first."));
        var variants = games.brennan.dungeontrain.track.variant.TrackVariantBlocks.copyOf(ChunkFrameVariants.loadFor(name));
        ChunkFrameMeta meta = ChunkFrameMetaStore.get(name);
        ChunkFrameStore.save(newName, tag, toSource);
        ChunkFrameVariants.save(newName, variants, toSource);
        ChunkFrameMetaStore.save(newName, meta, toSource);
        ChunkFrameStore.deleteFiles(name, toSource);
        ChunkFrameVariants.delete(name, toSource);
        ChunkFrameMetaStore.delete(name, toSource);
        relayRow(level, () -> ChunkFrameStore.invalidate(name));
        enter(player, level, newName, null);
    }

    /** Erase the whole row, run {@code change}, rescan the frame list from disk, and stamp the row again. */
    private static void relayRow(ServerLevel level, Runnable change) {
        clearAllPlots(level);
        change.run();
        ChunkFrameRegistry.reload();
        for (EditorStampQueue.Job job : stampAllPlotJobs(level)) job.work().run();
    }

    /** Erase every frame plot and its cage. */
    public static void clearAllPlots(ServerLevel level) {
        for (String name : ChunkFrameRegistry.names()) {
            BlockPos o = plotOrigin(name);
            EditorPlotBox.erase(level, o, plotSize(name), EditorPlotCage.QUIET);
            // Nothing stands to compare against any more.
            EditorPlotSnapshots.clear(snapshotKey(name));
        }
    }

    /** The dirty-check baseline key for {@code name}'s plot. */
    public static String snapshotKey(String name) {
        return new Template.ChunkFrame(name).snapshotKey().orElseThrow();
    }

    /** Stamp {@code frame} on {@code name}'s plot and take that as the plot's saved baseline. */
    private static void stampPlot(ServerLevel level, String name, BlockPos origin, ChunkFrameTemplate frame) {
        stampBlocks(level, origin, plotSize(name), frame);
        captureSnapshot(level, name, origin);
    }

    private static void captureSnapshot(ServerLevel level, String name, BlockPos origin) {
        Vec3i size = plotSize(name);
        EditorPlotSnapshots.capture(snapshotKey(name), level, origin, size.getX(), size.getY(), size.getZ());
    }

    private static void stampBlocks(ServerLevel level, BlockPos origin, Vec3i size, ChunkFrameTemplate frame) {
        EditorPlotBox.erase(level, origin, size, EditorPlotCage.QUIET);
        EditorPlotCage.setOutline(level, origin, size, EditorPlotCage.OUTLINE_BLOCK, EditorPlotCage.QUIET);
        if (frame == null) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = frame.at(x, y, z);
                    if (state.isAir()) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    level.setBlock(cursor, state, EditorPlotCage.QUIET);
                    CompoundTag nbt = frame.blockEntityAt(x, y, z);
                    BlockEntity be = nbt == null ? null : level.getBlockEntity(cursor);
                    if (be != null) be.loadWithComponents(nbt, level.registryAccess());
                }
            }
        }
    }

}
