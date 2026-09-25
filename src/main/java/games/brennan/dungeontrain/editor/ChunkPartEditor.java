package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPart;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartKind;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartRegistry;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartStore;
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
 * Authoring chunk parts — the two-thick frames a dimensional carriage can stand in
 * ({@link ChunkPartKind}).
 *
 * <p>Laid out beside the Dimensions rooms, on the same plot layer, but on the <b>−Z</b> side of them:
 * the rooms start at Z 0 and grow toward +Z as rooms are added and toward +X as they are lengthened,
 * so nothing a room does can reach a part. One row per kind, stacked away from the rooms along −Z,
 * one slot per part along +X. The plots are stamped with the Dimensions category and erased with it,
 * so like every other plot they only stand while their category is resident.</p>
 *
 * <p>A plot is the part at its exact size in a bedrock cage. Which side is the <b>outer</b> layer is
 * fixed by {@link ChunkPartKind}: local x = 0 of a door, local z = 0 of a wall, the bottom layer of a
 * floor and the top layer of a roof — the one that ends up outside the room.</p>
 */
public final class ChunkPartEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The rooms' own column, so the parts read as belonging to them. */
    public static final int FIRST_PLOT_X = TrackSidePlots.X_PORTALS;
    /** The room rows start here and only grow toward +Z; parts stand a gap short of it. */
    private static final int ROOMS_FIRST_Z = TrackSidePlots.Z_BASELINE;
    /** Plots are one block of cage on each side, then the gap. */
    private static final int STRIDE_PAD = EditorLayout.GAP + 2;
    private static final BlockState OUTLINE = Blocks.BEDROCK.defaultBlockState();
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** What each player last entered, which {@code save} writes when they are not standing in a plot. */
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public record Session(ChunkPartKind kind, String name) {}

    private ChunkPartEditor() {}

    // ---- layout --------------------------------------------------------------

    /** The plot origin of {@code name} in {@code kind}'s row, or the next free slot when unregistered. */
    public static BlockPos plotOrigin(ChunkPartKind kind, String name) {
        List<String> names = ChunkPartRegistry.names(kind);
        int index = names.indexOf(name);
        return slotOrigin(kind, index < 0 ? names.size() : index);
    }

    private static BlockPos slotOrigin(ChunkPartKind kind, int index) {
        return new BlockPos(FIRST_PLOT_X + index * (kind.size().getX() + STRIDE_PAD),
            EditorLayout.PLOT_Y, rowStartZ(kind));
    }

    /** Rows stack toward −Z from the rooms: each row's max Z sits a gap (and a cage) below the last. */
    private static int rowStartZ(ChunkPartKind kind) {
        int nextMaxZ = ROOMS_FIRST_Z - STRIDE_PAD;
        for (ChunkPartKind k : ChunkPartKind.values()) {
            int start = nextMaxZ - k.size().getZ() + 1;
            if (k == kind) return start;
            nextMaxZ = start - STRIDE_PAD;
        }
        return nextMaxZ;
    }

    /** The first slot of {@code kind}'s row — where its floating menu anchors. */
    public static BlockPos rowOrigin(ChunkPartKind kind) {
        return slotOrigin(kind, 0);
    }

    /** The part whose plot (cage included) holds {@code pos}, or empty. */
    public static Optional<Session> plotContaining(BlockPos pos) {
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            for (String name : ChunkPartRegistry.names(kind)) {
                BlockPos o = plotOrigin(kind, name);
                Vec3i s = kind.size();
                if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + s.getX()
                    && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + s.getY() + 2
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + s.getZ()) {
                    return Optional.of(new Session(kind, name));
                }
            }
        }
        return Optional.empty();
    }

    /** What {@code player} is working on: the plot they stand in, else the one they last entered. */
    public static Optional<Session> current(ServerPlayer player) {
        Optional<Session> here = plotContaining(player.blockPosition());
        return here.isPresent() ? here : Optional.ofNullable(SESSIONS.get(player.getUUID()));
    }

    // ---- enter / new / save --------------------------------------------------

    /**
     * Stamp {@code name}'s plot and put {@code player} on it. {@code copyOf} seeds a new part from an
     * existing one of the same kind; null stamps what is saved, or an empty plot for a new name.
     */
    public static void enter(ServerPlayer player, ServerLevel level, ChunkPartKind kind, String name,
                             String copyOf) {
        CarriageEditor.rememberReturn(player);
        ChunkPartRegistry.register(kind, name);
        SESSIONS.put(player.getUUID(), new Session(kind, name));
        BlockPos origin = plotOrigin(kind, name);
        stampPlot(level, kind, origin, ChunkPartStore.get(level, kind, copyOf != null ? copyOf : name).orElse(null));
        EditorPlotArrival.land(player, level, origin, kind.size(), true, EditorPlotArrival.Inside.CENTRE, null);
        LOGGER.info("[DungeonTrain] Chunk part editor: {} entered {}:{} at {}{}",
            player.getName().getString(), kind.id(), name, origin, copyOf != null ? " (copy of " + copyOf + ")" : "");
    }

    /**
     * Capture {@code session}'s plot and save it — to the source tree too in dev mode.
     *
     * @return true when the source tree was also written
     */
    public static boolean save(ServerPlayer player, ServerLevel level, Session session) throws IOException {
        BlockPos origin = plotOrigin(session.kind(), session.name());
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, origin, session.kind().size(), false, null);
        boolean toSource = EditorDevMode.isEnabled();
        ChunkPartStore.save(session.kind(), session.name(), template.save(new CompoundTag()), toSource);
        ChunkPartRegistry.register(session.kind(), session.name());
        SESSIONS.put(player.getUUID(), session);
        return toSource;
    }

    // ---- stamping ------------------------------------------------------------

    /** Every chunk part plot, as queued jobs — what the Dimensions category adds to its fill. */
    public static List<EditorStampQueue.Job> stampAllPlotJobs(ServerLevel level) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            for (String name : ChunkPartRegistry.names(kind)) {
                jobs.add(new EditorStampQueue.Job("stamp chunk part " + kind.id() + "/" + name,
                    () -> stampPlot(level, kind, plotOrigin(kind, name),
                        ChunkPartStore.get(level, kind, name).orElse(null))));
            }
        }
        return jobs;
    }

    /** Erase every chunk part plot and its cage. */
    public static void clearAllPlots(ServerLevel level) {
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            for (String name : ChunkPartRegistry.names(kind)) {
                BlockPos o = plotOrigin(kind, name);
                fillBox(level, o.offset(-1, -1, -1), o.offset(kind.size()), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void stampPlot(ServerLevel level, ChunkPartKind kind, BlockPos origin, ChunkPart part) {
        Vec3i size = kind.size();
        fillBox(level, origin.offset(-1, -1, -1), origin.offset(size), Blocks.AIR.defaultBlockState());
        drawCage(level, origin, size);
        if (part == null) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = part.at(x, y, z);
                    if (state.isAir()) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    level.setBlock(cursor, state, QUIET);
                    CompoundTag nbt = part.blockEntityAt(x, y, z);
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
                    if (level.getBlockState(p) == state) continue;
                    if (level.getBlockState(p).hasBlockEntity()) level.removeBlockEntity(p);
                    level.setBlock(p, state, QUIET);
                }
            }
        }
    }
}
