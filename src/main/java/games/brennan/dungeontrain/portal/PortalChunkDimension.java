package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

        BlockState ground = slice.source().ground();
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

}
