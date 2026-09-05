package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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
 * <h2>Nothing is taken out of the terrain at the mouths</h2>
 * <p>The doorways are stood on the ground the sample landed ({@link PortalChunkDoors}), so there is
 * nothing to cut away to reach them, and cutting anyway is what made a chunk dimension read as a
 * room with two bites taken out of it. The only thing written at a mouth now is the floor row when
 * the sample left <b>air or water</b> there — ground is added under a doorway that would otherwise
 * open onto a drop, and no block the sample placed is ever removed.</p>
 *
 * <p>Every write skips {@link PortalCorridorMask}, which matters for the deferred path only: an
 * immediate fill runs before {@code stampCorridors} and could not reach a corridor if it tried,
 * while a fill that lands two ticks later would otherwise pour a hillside through a standing
 * twin.</p>
 */
public final class PortalChunkDimension {

    /** How many columns in from each end the doorway's own floor is answered for. */
    private static final int DOORWAY_DEPTH = 2;

    private PortalChunkDimension() {}

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
            for (int z = 1; z < size.getZ() - 1 && z < slice.size(); z++) {
                for (int x = 0; x < size.getX() && x < slice.size(); x++) {
                    // Null for a row the cube does not reach — a room stood up taller than 16 by a
                    // world with the space for it. Those rows keep whatever the template put there,
                    // which is a room rather than a hole.
                    BlockState state = slice.at(x, y, z);
                    if (state == null) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mask.covers(cursor)) continue;
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                }
            }
        }

        floorDoorway(level, structure, dims, origin, size, mask, slice, PortalCarriageRole.ENTRY);
        floorDoorway(level, structure, dims, origin, size, mask, slice, PortalCarriageRole.EXIT);
    }

    /**
     * Lay ground under one doorway wherever the sample left none.
     *
     * <p>Additive only: a cell holding anything solid is the terrain the door was fitted to and is
     * left exactly as it was sampled. Air is a step out into a hole and a fluid is a doorway that
     * pours into the corridor, and those two are the whole of what this repairs.</p>
     */
    private static void floorDoorway(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                     BlockPos origin, Vec3i size, PortalCorridorMask mask,
                                     PortalChunkSlice slice, PortalCarriageRole role) {
        boolean entry = role == PortalCarriageRole.ENTRY;
        BlockPos corridor = entry ? structure.origin() : structure.exitOrigin(dims);

        int roomMinX = origin.getX();
        int roomMaxX = roomMinX + size.getX() - 1;
        int fromX = entry ? roomMinX : Math.max(roomMinX, roomMaxX - (DOORWAY_DEPTH - 1));
        int toX = entry ? Math.min(roomMaxX, roomMinX + DOORWAY_DEPTH - 1) : roomMaxX;

        // Exactly the corridor's own cross-section. The ground either side of a doorway is terrain
        // and none of this is its business.
        int minZ = Math.max(origin.getZ() + 1, corridor.getZ());
        int maxZ = Math.min(origin.getZ() + size.getZ() - 2, corridor.getZ() + dims.width() - 1);
        // The corridor's origin row is its FLOOR, and the door offsets were fitted to the ground
        // block — so this row is normally already terrain and nothing is written at all.
        int floorY = corridor.getY();

        BlockState ground = slice.source().ground();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cursor.set(x, floorY, z);
                if (mask.covers(cursor)) continue;
                BlockState floor = level.getBlockState(cursor);
                if (floor.isAir() || !floor.getFluidState().isEmpty()) {
                    level.setBlock(cursor, ground, Block.UPDATE_ALL);
                }
            }
        }
    }

}
