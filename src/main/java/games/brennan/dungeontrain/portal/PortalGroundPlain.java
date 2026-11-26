package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFramePlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The plain an {@link PortalRoomMode#CHUNK_ENDLESS_OPEN Endless open} chunk stands on: its frame's
 * ground block, laid one block deep across the swept clearance, level with the chunk box's bottom
 * row, so the chunk reads as a tower in a field and its doors stand out on the same grass.
 *
 * <h2>Where</h2>
 * <p>The bottom row of {@code voidHaloOf} — the whole swept disc, out to the fog — minus the chunk's
 * own box (its bottom row is sampled terrain) and minus every corridor and plug the pair owns
 * ({@link PortalCarriageBuilder#allCorridorMask}), whose floors are their own. The halo starts at the
 * structure's origin row, which is the room's bottom row because an Endless open chunk's doors are
 * pinned to height zero ({@code PortalCarriageBuilder.framedEnclosure}).</p>
 *
 * <h2>Columns that are not loaded yet</h2>
 * <p>Writing into a column still generating would park the server ({@code PortalClear}'s javadoc), so
 * such a column is queued and laid once it is ready, a few per tick. The void sweep spares this row
 * ({@code clearVoidAround}), so a deferred clear landing later can never take the grass back out.
 * The queue is not saved; a column still waiting at a restart is left as the basement had it, out at
 * the edge of the fog.</p>
 *
 * <h2>Rolling</h2>
 * <p>{@link CarriageVariantBlocks#pickIndexWeighted} on the cell's position relative to the room, the
 * pair's variant index and the world seed: the same pure roll every other plane uses, so a re-stamp
 * lays the same field.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalGroundPlain {

    /** Columns laid per tick from the queue, so a burst of chunks finishing together spreads out. */
    private static final int COLUMNS_PER_TICK = 4;

    /** One waiting column of plain: the row within one chunk column, and what to lay it with. */
    record Pending(BoundingBox row, BoundingBox chunkBox, PortalCorridorMask corridors,
                   List<VariantState> ground, BlockPos anchor, long seed, int variantIndex) {}

    private static final Map<ResourceKey<Level>, Deque<Pending>> PENDING = new HashMap<>();

    private PortalGroundPlain() {}

    /**
     * The row the plain fills — {@code halo}'s bottom row at {@code y}. Pure, so the shape can be
     * pinned without a level.
     */
    static BoundingBox groundRow(BoundingBox halo, int y) {
        return new BoundingBox(halo.minX(), y, halo.minZ(), halo.maxX(), y, halo.maxZ());
    }

    /** {@code structure}'s room box, the chunk the plain stops at. */
    static BoundingBox roomBox(BlockPos roomOrigin, Vec3i size) {
        return new BoundingBox(roomOrigin.getX(), roomOrigin.getY(), roomOrigin.getZ(),
            roomOrigin.getX() + size.getX() - 1, roomOrigin.getY() + size.getY() - 1,
            roomOrigin.getZ() + size.getZ() - 1);
    }

    /** The row the plain fills for {@code structure}, or null for a room that lays none. */
    static BoundingBox rowFor(ServerLevel level, PortalStructure structure, CarriageDims dims) {
        if (!structure.mode().laysGroundPlain()) return null;
        BlockPos roomOrigin = structure.roomOrigin(dims, PortalCarriageBuilder.layoutFor(dims, structure.kind()));
        return groundRow(PortalCarriageBuilder.voidHaloOf(level, structure, dims), roomOrigin.getY());
    }

    /**
     * The candidates the plain under {@code pairKey} is laid in: the planned frame's ground, or
     * {@link ChunkFrameMeta#DEFAULT_GROUND_BLOCK} when it has none (or the frame is unknown).
     */
    public static List<VariantState> groundFor(int pairKey) {
        String frame = ChunkFramePlacer.plannedName(pairKey);
        List<VariantState> ground = frame == null ? List.of() : ChunkFrameMetaStore.get(frame).ground();
        return ground.isEmpty() ? defaultGround() : ground;
    }

    /** One grass block — what a frame that was never given a ground lays. */
    public static List<VariantState> defaultGround() {
        return PortalRoomSinglePlanes.stateFor(ChunkFrameMeta.DEFAULT_GROUND_BLOCK)
            .map(state -> List.of(new VariantState(state, null)))
            .orElseGet(() -> List.of(new VariantState(Blocks.GRASS_BLOCK.defaultBlockState(), null)));
    }

    /** Lay {@code structure}'s plain: every loaded column now, the rest as they load. */
    public static void lay(ServerLevel level, PortalStructure structure, CarriageDims dims, int pairKey) {
        BoundingBox row = rowFor(level, structure, dims);
        if (row == null) return;
        BlockPos roomOrigin = structure.roomOrigin(dims, PortalCarriageBuilder.layoutFor(dims, structure.kind()));
        BoundingBox chunkBox = roomBox(roomOrigin, structure.roomSize());
        PortalCorridorMask corridors = PortalCarriageBuilder.allCorridorMask(structure, dims);
        List<VariantState> ground = groundFor(pairKey);
        int variantIndex = structure.variantIndexFor(PortalRoomTiling.Tile.BASE, pairKey);
        long seed = level.getSeed();
        for (BoundingBox column : columnsOf(row)) {
            Pending p = new Pending(column, chunkBox, corridors, ground, roomOrigin, seed, variantIndex);
            if (ready(level, column)) {
                write(level, p);
            } else {
                PENDING.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>()).addLast(p);
            }
        }
    }

    /**
     * Take {@code structure}'s plain back out when its twin is erased — the erase only reaches the
     * footprint, and the plain runs out to the fog. Anything of it still waiting is dropped.
     */
    public static void erase(ServerLevel level, PortalStructure structure, CarriageDims dims) {
        BoundingBox row = rowFor(level, structure, dims);
        if (row == null) return;
        Deque<Pending> queue = PENDING.get(level.dimension());
        if (queue != null) queue.removeIf(p -> row.intersects(p.row()));
        PortalClear.clearBoxDeferring(level, row, PortalCorridorMask.NONE);
    }

    /** {@code row} cut at chunk-column boundaries, one box per column. Pure. */
    static List<BoundingBox> columnsOf(BoundingBox row) {
        List<BoundingBox> out = new ArrayList<>();
        for (int cx = row.minX() >> 4; cx <= row.maxX() >> 4; cx++) {
            for (int cz = row.minZ() >> 4; cz <= row.maxZ() >> 4; cz++) {
                out.add(new BoundingBox(
                    Math.max(row.minX(), cx << 4), row.minY(), Math.max(row.minZ(), cz << 4),
                    Math.min(row.maxX(), (cx << 4) + 15), row.maxY(), Math.min(row.maxZ(), (cz << 4) + 15)));
            }
        }
        return out;
    }

    private static boolean ready(ServerLevel level, BoundingBox column) {
        return level.getChunkSource().getChunkNow(column.minX() >> 4, column.minZ() >> 4) != null;
    }

    private static void write(ServerLevel level, Pending p) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = p.row().minY();
        for (int x = p.row().minX(); x <= p.row().maxX(); x++) {
            for (int z = p.row().minZ(); z <= p.row().maxZ(); z++) {
                if (p.chunkBox().isInside(x, y, z) || p.corridors().covers(x, y, z)) continue;
                pos.set(x, y, z);
                BlockState state = stateAt(p, pos);
                PortalQuietFill.set(level, pos.immutable(), state, List.of());
            }
        }
    }

    /** The block one cell of the plain rolls. Air for an air or mob candidate — a hole in the field. */
    static BlockState stateAt(Pending p, BlockPos pos) {
        List<VariantState> ground = p.ground();
        BlockPos local = pos.subtract(p.anchor());
        VariantState picked = ground.get(
            CarriageVariantBlocks.pickIndexWeighted(local, p.seed(), p.variantIndex(), ground));
        if (picked.isMob() || CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = RotationApplier.apply(picked.state(), picked, local, p.seed(), p.variantIndex(), 0);
        return games.brennan.dungeontrain.block.ForcedAir.writesAir(state) ? Blocks.AIR.defaultBlockState() : state;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Deque<Pending> queue = PENDING.get(level.dimension());
        if (queue == null || queue.isEmpty()) return;
        int laid = 0;
        int looked = 0;
        int size = queue.size();
        while (laid < COLUMNS_PER_TICK && looked < size && !queue.isEmpty()) {
            Pending p = queue.pollFirst();
            looked++;
            if (ready(level, p.row())) {
                write(level, p);
                laid++;
            } else {
                queue.addLast(p);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }
}
