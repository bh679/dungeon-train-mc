package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
    /** The room rows start at Z_BASELINE and only grow toward +Z; frames stand a gap short of it. */
    private static final int ROW_Z = TrackSidePlots.Z_BASELINE - STRIDE_PAD - ChunkFrame.SIZE.getZ() + 1;
    private static final BlockState OUTLINE = Blocks.BEDROCK.defaultBlockState();
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** What each player last entered, which {@code save} writes when they are not standing in a plot. */
    private static final Map<UUID, String> SESSIONS = new ConcurrentHashMap<>();

    private ChunkFrameEditor() {}

    // ---- layout --------------------------------------------------------------

    /** The plot origin of {@code name}, or the next free slot when it is not registered. */
    public static BlockPos plotOrigin(String name) {
        List<String> names = ChunkFrameRegistry.names();
        int index = names.indexOf(name);
        return slotOrigin(index < 0 ? names.size() : index);
    }

    private static BlockPos slotOrigin(int index) {
        return new BlockPos(FIRST_PLOT_X + index * (ChunkFrame.SIZE.getX() + STRIDE_PAD), EditorLayout.PLOT_Y, ROW_Z);
    }

    /** The first slot — where the Frames menu anchors. */
    public static BlockPos rowOrigin() {
        return slotOrigin(0);
    }

    /** The frame whose plot (cage included) holds {@code pos}, or empty. */
    public static Optional<String> plotContaining(BlockPos pos) {
        Vec3i s = ChunkFrame.SIZE;
        for (String name : ChunkFrameRegistry.names()) {
            BlockPos o = plotOrigin(name);
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + s.getX()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + s.getY() + 2
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + s.getZ()) {
                return Optional.of(name);
            }
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
        CarriageEditor.rememberReturn(player);
        ChunkFrameRegistry.register(name);
        SESSIONS.put(player.getUUID(), name);
        BlockPos origin = plotOrigin(name);
        stampPlot(level, origin, ChunkFrameStore.get(level, copyOf != null ? copyOf : name).orElse(null));
        EditorPlotArrival.land(player, level, origin, ChunkFrame.SIZE, true, EditorPlotArrival.Inside.CENTRE, null);
        LOGGER.info("[DungeonTrain] Chunk frame editor: {} entered {} at {}{}", player.getName().getString(),
            name, origin, copyOf != null ? " (copy of " + copyOf + ")" : "");
    }

    /**
     * Capture {@code name}'s plot and save it — to the source tree too in dev mode.
     *
     * @return true when the source tree was also written
     */
    public static boolean save(ServerPlayer player, ServerLevel level, String name) throws IOException {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, plotOrigin(name), ChunkFrame.SIZE, false, null);
        boolean toSource = EditorDevMode.isEnabled();
        ChunkFrameStore.save(name, template.save(new CompoundTag()), toSource);
        ChunkFrameRegistry.register(name);
        SESSIONS.put(player.getUUID(), name);
        return toSource;
    }

    // ---- stamping ------------------------------------------------------------

    /** Every frame plot, as queued jobs — what the Dimensions category adds to its fill. */
    public static List<EditorStampQueue.Job> stampAllPlotJobs(ServerLevel level) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (String name : ChunkFrameRegistry.names()) {
            jobs.add(new EditorStampQueue.Job("stamp chunk frame " + name,
                () -> stampPlot(level, plotOrigin(name), ChunkFrameStore.get(level, name).orElse(null))));
        }
        return jobs;
    }

    /** Erase every frame plot and its cage. */
    public static void clearAllPlots(ServerLevel level) {
        for (String name : ChunkFrameRegistry.names()) {
            BlockPos o = plotOrigin(name);
            fillBox(level, o.offset(-1, -1, -1), o.offset(ChunkFrame.SIZE), Blocks.AIR.defaultBlockState());
        }
    }

    private static void stampPlot(ServerLevel level, BlockPos origin, ChunkFrameTemplate frame) {
        Vec3i size = ChunkFrame.SIZE;
        fillBox(level, origin.offset(-1, -1, -1), origin.offset(size), Blocks.AIR.defaultBlockState());
        drawCage(level, origin, size);
        if (frame == null) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = frame.at(x, y, z);
                    if (state.isAir()) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    level.setBlock(cursor, state, QUIET);
                    CompoundTag nbt = frame.blockEntityAt(x, y, z);
                    BlockEntity be = nbt == null ? null : level.getBlockEntity(cursor);
                    if (be != null) be.loadWithComponents(nbt, level.registryAccess());
                }
            }
        }
    }

    /** The twelve edges of the box one block outside the plot. */
    private static void drawCage(ServerLevel level, BlockPos origin, Vec3i size) {
        int x0 = origin.getX() - 1, y0 = origin.getY() - 1, z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX(), y1 = origin.getY() + size.getY(), z1 = origin.getZ() + size.getZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int edges = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                    if (edges >= 2) level.setBlock(p.set(x, y, z), OUTLINE, QUIET);
                }
            }
        }
    }

    private static void fillBox(ServerLevel level, BlockPos min, BlockPos max, BlockState state) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    p.set(x, y, z);
                    BlockState current = level.getBlockState(p);
                    if (current == state) continue;
                    if (current.hasBlockEntity()) level.removeBlockEntity(p);
                    level.setBlock(p, state, QUIET);
                }
            }
        }
    }
}
