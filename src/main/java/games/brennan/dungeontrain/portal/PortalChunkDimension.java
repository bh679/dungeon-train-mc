package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Writing a sampled chunk of world generation into a {@link PortalRoomMode#CHUNK_DIMENSION} room —
 * the other half of {@link PortalChunkTerrain}, which is where the terrain comes from.
 *
 * <h2>Over the room's own template, never instead of it</h2>
 * <p>The variant is stamped first, exactly as any other room is: it clears the box, lays the side
 * walls, the floor and the ceiling, and leaves a flat stone shelf at the door line. That shelf is
 * what a player walks out onto in the seconds before the sample lands ({@link #fill} answers
 * {@code null} until then) and if a sample ever fails it is what they keep — a plain room rather
 * than a hole in the basement. The terrain is then poured into the box's <b>interior</b>, so the
 * shell the seal ring copies its blocks from stays the shell the author authored.</p>
 *
 * <h2>The two mouths are carved back open</h2>
 * <p>Terrain does not know about doors. A hillside sampled into the box lands across both corridor
 * mouths as readily as anywhere else, so {@link #carveApron} takes the corridor's own cross-section
 * back out of it at each end and lays ground under it where the sample left none — three columns
 * deep, which is enough to step out onto and short enough that the ground still reads as the
 * terrain's rather than as a platform.</p>
 *
 * <p>Every write skips {@link PortalCorridorMask}, which matters for the deferred path only: an
 * immediate fill runs before {@code stampCorridors} and could not reach a corridor if it tried,
 * while a fill that lands two ticks later would otherwise pour a hillside through a standing
 * twin.</p>
 */
public final class PortalChunkDimension {

    /** How many columns in from each end the corridor's cross-section is kept clear. */
    private static final int APRON_DEPTH = 3;

    /** How many deferred fills are drained per tick — a fill is a room-sized write. */
    private static final int DRAIN_PER_TICK = 2;

    /** Rooms whose terrain was not sampled yet when they were stamped, by pair key. */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(PortalStructure structure, CarriageDims dims) {}

    private PortalChunkDimension() {}

    /**
     * Fill {@code structure}'s room with its sampled chunk, or queue the fill for a later tick when
     * the sample is not ready.
     *
     * <p>Called from {@code stampPairStructure} for a chunk-dimension room, after the room's own
     * template has been stamped and before the corridors go down.</p>
     */
    public static void fill(ServerLevel level, PortalStructure structure, CarriageDims dims,
                            int pairKey) {
        PortalChunkSlice slice = PortalChunkTerrain.slice(level, pairKey);
        if (slice == null) {
            // Replaces any older entry for the same pair: a structure that has been re-stamped has
            // moved, and the queued fill has to land where it is now, not where it was.
            PENDING.put(pairKey, new Pending(structure, dims));
            return;
        }
        PENDING.remove(pairKey);
        write(level, structure, dims, slice);
    }

    /**
     * Complete up to {@link #DRAIN_PER_TICK} rooms whose samples have since landed.
     *
     * <p>Capped because a fill is a whole room's worth of block writes and several arriving in the
     * same tick is a hitch nobody asked for; the rooms are sealed and empty until they are filled,
     * and a player cannot reach one in the tick it was stamped.</p>
     */
    public static void drainPending(ServerLevel level, IntFunction<PortalStructure> live) {
        if (PENDING.isEmpty()) return;
        int done = 0;
        Iterator<Map.Entry<Integer, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext() && done < DRAIN_PER_TICK) {
            Map.Entry<Integer, Pending> entry = it.next();
            Pending pending = entry.getValue();
            // Against the pair's LIVE structure, not the one the fill was queued against. A pair
            // that drained, or drifted far enough to be re-stamped, in the ticks since is a room
            // that is no longer standing where this terrain would go — and pouring a hillside into
            // the basement at an address nothing owns is exactly the litter eraseTwin cannot sweep,
            // because it sweeps the box the structure says it has. Re-stamping re-queues the fill,
            // so nothing is lost by dropping it here.
            PortalStructure current = live.apply(entry.getKey());
            if (current == null || !current.origin().equals(pending.structure().origin())) {
                it.remove();
                continue;
            }
            PortalChunkSlice slice = PortalChunkTerrain.slice(level, entry.getKey());
            if (slice == null) continue;
            write(level, pending.structure(), pending.dims(), slice);
            it.remove();
            done++;
        }
    }

    /** Forget every queued fill — the next world's pair keys mean different rooms. */
    public static void clear() {
        PENDING.clear();
    }

    // ---- writing -------------------------------------------------------------

    private static void write(ServerLevel level, PortalStructure structure, CarriageDims dims,
                              PortalChunkSlice slice) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        PortalCorridorMask mask = PortalCarriageBuilder.corridorMask(structure, dims);

        // Where the door actually ended up, which is not always where the variant asked for it: a
        // world too shallow for a 16-tall room holds the box down (PortalCarriageBuilder#heldInRegion)
        // and the offset clamps with it. The slice is cut with its surface on
        // PortalChunkTerrain.SURFACE_ROW, so sliding it by the difference is what keeps the ground
        // under the doorway rather than above or below it.
        int doorRow = PortalRoomLayout.clampDoorHeightOffset(
            dims, size.getY(), structure.settings().doorHeightOffset().value());
        int shift = PortalChunkTerrain.SURFACE_ROW - doorRow;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The interior only: the ±Z walls, the floor and the ceiling are the template's, because the
        // seal ring at each mouth is a copy of the room's own wall (PortalCarriageBuilder#sealFillFor)
        // and a wall of open sky would seal a mouth with nothing. The ±X ends are not walls — they
        // are the door planes — so terrain runs the full length.
        for (int y = 1; y < size.getY() - 1; y++) {
            for (int z = 1; z < size.getZ() - 1 && z < slice.size(); z++) {
                for (int x = 0; x < size.getX() && x < slice.size(); x++) {
                    // Null for a row the slice does not reach — a room taller than the cube, or one
                    // whose door sits low enough to slide the cube off its ceiling. Those rows keep
                    // whatever the template put there, which is a room rather than a hole.
                    BlockState state = slice.at(x, y + shift, z);
                    if (state == null) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mask.covers(cursor)) continue;
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                }
            }
        }

        carveApron(level, structure, dims, origin, size, mask, slice, PortalCarriageRole.ENTRY);
        carveApron(level, structure, dims, origin, size, mask, slice, PortalCarriageRole.EXIT);
    }

    /**
     * Take the corridor's cross-section back out of the terrain at one mouth, and lay ground under
     * it wherever the sample left air.
     *
     * <p>One block wider and no taller than the corridor itself: wider so a player is never walking
     * out into a wall their shoulder clips, and exactly as tall because the corridor's own height is
     * what the doorway offers — carving above it would open a slot into the room's ceiling.</p>
     */
    private static void carveApron(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                   BlockPos origin, Vec3i size, PortalCorridorMask mask,
                                   PortalChunkSlice slice, PortalCarriageRole role) {
        boolean entry = role == PortalCarriageRole.ENTRY;
        BlockPos corridor = entry ? structure.origin() : structure.exitOrigin(dims);

        int roomMinX = origin.getX();
        int roomMaxX = roomMinX + size.getX() - 1;
        int fromX = entry ? roomMinX : Math.max(roomMinX, roomMaxX - (APRON_DEPTH - 1));
        int toX = entry ? Math.min(roomMaxX, roomMinX + APRON_DEPTH - 1) : roomMaxX;

        int minZ = Math.max(origin.getZ() + 1, corridor.getZ() - 1);
        int maxZ = Math.min(origin.getZ() + size.getZ() - 2, corridor.getZ() + dims.width());
        int floorY = corridor.getY();
        int topY = Math.min(origin.getY() + size.getY() - 2, floorY + dims.height() - 1);

        BlockState ground = groundOf(slice);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = floorY; y <= topY; y++) {
                    cursor.set(x, y, z);
                    if (mask.covers(cursor)) continue;
                    if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, air, Block.UPDATE_ALL);
                }
                // The row a player's feet land on. Air there is a hole in the doorway; a fluid there
                // is worse, and a fluid beside the apron would pour into the space just cleared.
                int standY = floorY - 1;
                if (standY < origin.getY() + 1) continue;
                cursor.set(x, standY, z);
                if (mask.covers(cursor)) continue;
                BlockState below = level.getBlockState(cursor);
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    level.setBlock(cursor, ground, Block.UPDATE_ALL);
                }
            }
        }
        plugApronWalls(level, mask, fromX, toX, minZ, maxZ, floorY, topY, ground);
    }

    /**
     * Replace the fluid cells standing against an apron with solid ground.
     *
     * <p>A lava lake in a Nether chunk or a pond in an Overworld one is exactly the terrain that was
     * asked for and stays where it is. What it may not do is drain into the doorway the moment the
     * apron is cut, which is what the one-block skin around the carve prevents.</p>
     */
    private static void plugApronWalls(ServerLevel level, PortalCorridorMask mask,
                                       int fromX, int toX, int minZ, int maxZ, int floorY, int topY,
                                       BlockState ground) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX - 1; x <= toX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int y = floorY - 1; y <= topY + 1; y++) {
                    boolean inside = x >= fromX && x <= toX && z >= minZ && z <= maxZ
                        && y >= floorY && y <= topY;
                    if (inside) continue;
                    cursor.set(x, y, z);
                    if (mask.covers(cursor)) continue;
                    if (!level.getBlockState(cursor).getFluidState().isEmpty()) {
                        level.setBlock(cursor, ground, Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    /** The solid block an apron is floored with — the sampled dimension's own bedrock-to-surface fill. */
    private static BlockState groundOf(PortalChunkSlice slice) {
        return switch (slice.source()) {
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case END -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }
}
