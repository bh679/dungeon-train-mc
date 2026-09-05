package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.IntFunction;

/**
 * Writing a sampled chunk of world generation into a {@link PortalRoomMode#CHUNK_DIMENSION} room —
 * the other half of {@link PortalChunkTerrain}, which is where the terrain comes from.
 *
 * <h2>Over the room's own template, never instead of it</h2>
 * <p>The variant is stamped first, exactly as any other room is: it clears the box and lays the
 * shell — the skybox floor, ceiling and side walls a chunk dimension is framed in. That shell is
 * what a room keeps if a sample ever fails, and it is what the seal ring at each mouth copies its
 * blocks from, so the terrain is poured into the box's <b>interior</b> and leaves it standing.</p>

 * <p>The cube is always in hand by the time anything is stamped: a pair is not planned at all until
 * its terrain has been sampled, because the doorways are stood on that terrain — see
 * {@link PortalChunkDoors} and {@code PortalCarriageBuilder.planStructure}.</p>
 *
 * <h2>Two blocks are taken out of the terrain at each mouth, and no more</h2>
 * <p>The doorways are stood on the ground the sample landed ({@link PortalChunkDoors}), so there is
 * nothing to cut away to reach them, and cutting anyway is what made a chunk dimension read as a
 * room with two bites taken out of it. What remains is the door's own column: one block deep on the
 * walkway line, two blocks tall, cleared so a tree or a dune that grew in the doorway is not a wall
 * across it — plus the floor row beneath, written to ground when the sample left air or water
 * there.</p>
 *
 * <p>Every write skips {@link PortalCorridorMask}, which matters for the deferred path only: an
 * immediate fill runs before {@code stampCorridors} and could not reach a corridor if it tried,
 * while a fill that lands two ticks later would otherwise pour a hillside through a standing
 * twin.</p>
 */
public final class PortalChunkDimension {

    /**
     * How many cells of each doorway are kept clear — the pair above the floor row, which is a
     * player's legs and head and nothing else.
     */
    private static final int DOOR_HEIGHT = 2;

    private PortalChunkDimension() {}

    /**
     * Rewrite the rooms whose cube has since grown its structure and its features.
     *
     * <p>The second half of the two-pass sample ({@link PortalChunkTerrain}): a room is built from
     * the ground alone so its pair can cross immediately, and this puts the trees, the grass and the
     * structure into it a second or two later. Nothing here moves a doorway or a wall, so a player
     * already inside sees a room growing rather than a room changing.</p>
     */
    public static void applyPendingDecoration(ServerLevel level, CarriageDims dims,
                                              IntFunction<PortalStructure> live) {
        Set<Integer> pending = PortalChunkTerrain.decorated();
        if (pending.isEmpty()) return;
        for (int pairKey : pending) {
            PortalStructure structure = live.apply(pairKey);
            // Not stamped yet — normal, since a cube is usually decorated before the pair that asked
            // for it has been planned. It stays pending and is written when the room exists.
            if (structure == null) continue;
            PortalChunkSlice slice = PortalChunkTerrain.peek(pairKey);
            if (slice == null) {
                PortalChunkTerrain.decorationApplied(pairKey);
                continue;
            }
            write(level, structure, dims, slice);
            PortalChunkTerrain.decorationApplied(pairKey);
        }
    }

    /**
     * Fill {@code structure}'s room with the chunk its pair sampled.
     *
     * <p>Called from {@code stampPairStructure} for a chunk-dimension room, after the room's own
     * template has been stamped and before the corridors go down. A missing sample is a no-op rather
     * than an error: the room then stands as its template, which is a room.</p>
     */
    public static void fill(ServerLevel level, PortalStructure structure, CarriageDims dims,
                            int pairKey) {
        PortalChunkSlice slice = PortalChunkTerrain.slice(level, pairKey, structure.roomName());
        if (slice == null) return;
        write(level, structure, dims, slice);
    }

    // ---- writing -------------------------------------------------------------

    private static void write(ServerLevel level, PortalStructure structure, CarriageDims dims,
                              PortalChunkSlice slice) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        PortalCorridorMask mask = PortalCarriageBuilder.corridorMask(structure, dims);

        // The cube goes in exactly as it was sampled — no sliding it onto the door. It is the other
        // way round now: the pair's two doorways were stood on this cube's own ground before the
        // structure was planned, so moving the terrain here would undo the fit (PortalChunkDoors).

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The interior only: the ±Z walls, the floor and the ceiling are the template's, because the
        // seal ring at each mouth is a copy of the room's own wall (PortalCarriageBuilder#sealFillFor)
        // and a wall of open sky would seal a mouth with nothing. The ±X ends are not walls — they
        // are the door planes — so terrain runs the full length.
        for (int y = 1; y < size.getY() - 1; y++) {
            for (int z = 1; z < size.getZ() - 1 && z < slice.width(); z++) {
                for (int x = 0; x < size.getX() && x < slice.width(); x++) {
                    // Null for a row the cube does not reach — a room stood up taller than 16 by a
                    // world with the space for it. Those rows keep whatever the template put there,
                    // which is a room rather than a hole.
                    BlockState state = slice.at(x, y, z);
                    if (state == null) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mask.covers(cursor)) continue;
                    // Cheap, and it is what makes the decoration pass affordable: the second write
                    // of a room differs from the first only where something grew, so all but a
                    // handful of these cells are already the block being written.
                    if (level.getBlockState(cursor) == state) continue;
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                }
            }
        }

        openDoorway(level, structure, dims, layout, origin, size, mask, slice,
            PortalCarriageRole.ENTRY);
        openDoorway(level, structure, dims, layout, origin, size, mask, slice,
            PortalCarriageRole.EXIT);
    }

    /**
     * Open one doorway through the terrain: the two cells of the door itself, and solid ground under
     * them.
     *
     * <p><b>Two blocks, and not one more.</b> The doorways are stood on the ground the sample landed
     * ({@link PortalChunkDoors}), so nothing has to be cut away to reach them — but a doorway is a
     * hole a player walks through, and a sample is free to have grown a tree trunk or piled a dune
     * in exactly that hole. What is cleared is the door's own column: one block deep at the room's
     * end face, on the walkway line, from the floor row up through the two cells
     * {@code PortalRoomDoorCells} calls a door. Everything either side of it, and everything behind
     * it, is the terrain as it was sampled.</p>
     *
     * <p>The floor row under those two cells is the one thing written rather than cleared: air there
     * is a step out into a hole and a fluid there pours into the corridor, so either becomes ground.
     * Solid ground is what the door was fitted to and is left alone.</p>
     */
    private static void openDoorway(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                    PortalCarriageLayout layout, BlockPos origin, Vec3i size,
                                    PortalCorridorMask mask, PortalChunkSlice slice,
                                    PortalCarriageRole role) {
        boolean entry = role == PortalCarriageRole.ENTRY;
        BlockPos corridor = entry ? structure.origin() : structure.exitOrigin(dims);

        // The room's end face on this side — the column a player steps into off the door plane.
        int x = entry ? origin.getX() : origin.getX() + size.getX() - 1;
        // The walkway line of THIS corridor, read off the corridor itself rather than off the room:
        // a pair's two doorways may sit on different lines, and the corridor is where each one is.
        int z = corridor.getZ() + layout.doorZ();
        // The corridor's origin row is its floor, and the door is the two cells above it — the same
        // pair PortalRoomDoorCells cuts for every other room's doorway.
        int floorY = corridor.getY();

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= DOOR_HEIGHT; dy++) {
            cursor.set(x, floorY + dy, z);
            if (mask.covers(cursor)) continue;
            if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, air, Block.UPDATE_ALL);
        }

        cursor.set(x, floorY, z);
        if (mask.covers(cursor)) return;
        BlockState floor = level.getBlockState(cursor);
        // Anything a player would fall through or wade into — air, water, and equally the grass or
        // flower a decorated sample grew on the row the doorway stands on.
        if (!floor.blocksMotion()) {
            level.setBlock(cursor, slice.source().ground(), Block.UPDATE_ALL);
        }
    }
}
