package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <h2>The mobs land once</h2>
 * <p>A sample carries the entities it was generated with — a biome's animals, a structure's people —
 * and they are spawned when the room is first decorated, not when it is written. A room is rewritten
 * every time the train drifts far enough to re-stamp it, and mobs are not blocks: writing them again
 * would stack a second herd inside the first.</p>
 *
 * <h2>Two blocks are taken out of the terrain at each mouth, and no more</h2>
 * <p>The doorways are stood on the ground the sample landed ({@link PortalChunkDoors}), so there is
 * nothing to cut away to reach them, and cutting anyway is what made a chunk dimension read as a
 * room with two bites taken out of it. What remains is the door's own column: one block deep on the
 * walkway line, two blocks tall, cleared so a tree or a dune that grew in the doorway is not a wall
 * across it. Nothing is added: no floor is bridged in under a doorway the terrain left open.</p>
 *
 * <p>Every write skips {@link PortalCorridorMask}, which matters for the deferred path only: an
 * immediate fill runs before {@code stampCorridors} and could not reach a corridor if it tried,
 * while a fill that lands two ticks later would otherwise pour a hillside through a standing
 * twin.</p>
 */
public final class PortalChunkDimension {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * How many cells of each doorway are kept clear — the pair above the floor row, which is a
     * player's legs and head and nothing else.
     */
    private static final int DOOR_HEIGHT = 2;

    /** Pairs whose sampled mobs have already been spawned into their room. */
    private static final java.util.Set<Integer> POPULATED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            spawnOccupants(level, structure, dims, slice, pairKey);
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

        // Where the copy sits vertically is read off the DOOR, not off the box's own corner. The
        // two are the same number in an ordinary room — the doorways were fitted to this column's
        // ground before the structure was planned, so its ground row already is the door row — and
        // they come apart exactly when a room cannot spend the offset the fit asked for: a world too
        // shallow to stand a 32-tall box up holds it down (PortalCarriageBuilder#heldInRegion) and
        // the offset clamps with it. Aligning on the corner there leaves the doorway hanging in the
        // air above its own ground, or buried under it. Aligning on the door keeps a player's feet
        // on the terrain and spends the shortfall at the top of the column, which is sky.
        int shift = copyShift(structure, dims, size);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The interior only: the ±Z walls, the floor and the ceiling are the template's, because the
        // seal ring at each mouth is a copy of the room's own wall (PortalCarriageBuilder#sealFillFor)
        // and a wall of open sky would seal a mouth with nothing. The ±X ends are not walls — they
        // are the door planes — so terrain runs the full length.
        for (int y = 1; y < size.getY() - 1; y++) {
            for (int z = 1; z < size.getZ() - 1 && z < slice.width(); z++) {
                for (int x = 0; x < size.getX() && x < slice.width(); x++) {
                    // Null for a row the cube does not reach. Those rows keep whatever the template
                    // put there, which is a room rather than a hole.
                    BlockState state = slice.at(x, y + shift, z);
                    if (state == null) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mask.covers(cursor)) continue;
                    // Cheap, and it is what makes the decoration pass affordable: the second write
                    // of a room differs from the first only where something grew, so all but a
                    // handful of these cells are already the block being written.
                    if (level.getBlockState(cursor) == state) continue;
                    level.setBlock(cursor, state, Block.UPDATE_ALL);
                    applyBlockEntity(level, cursor, slice.blockEntityAt(x, y + shift, z));
                }
            }
        }

        openDoorway(level, structure, dims, layout, origin, size, mask, PortalCarriageRole.ENTRY);
        openDoorway(level, structure, dims, layout, origin, size, mask, PortalCarriageRole.EXIT);
    }

    /**
     * Forget which pairs have had their mobs spawned.
     *
     * <p>Pair-keyed like everything else here, so the next world opened must not inherit it: pair 12
     * there is a different room, and one that would silently never get its sheep.</p>
     */
    public static void clear() {
        POPULATED.clear();
    }

    /**
     * Spawn the mobs the sample was generated with, once per pair.
     *
     * <p>Once, and tracked rather than inferred: a room is rewritten whenever the train drifts far
     * enough to re-stamp its structure, and spawning on every write would leave a herd of sheep
     * standing inside the last herd of sheep. They are ordinary entities from the moment they land —
     * they wander, they can be killed, and a re-stamp leaves them where they are.</p>
     */
    private static void spawnOccupants(ServerLevel level, PortalStructure structure,
                                       CarriageDims dims, PortalChunkSlice slice, int pairKey) {
        if (slice.occupants().isEmpty() || !POPULATED.add(pairKey)) return;
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        int shift = copyShift(structure, dims, size);

        int spawned = 0;
        for (PortalChunkSlice.Occupant occupant : slice.occupants()) {
            double x = origin.getX() + occupant.x();
            double y = origin.getY() + occupant.y() - shift;
            double z = origin.getZ() + occupant.z();
            // Outside the room's own box once the copy has been aligned on the door: the column is
            // taller than a shallow world can stand up, and a sheep in the rows that were cut is a
            // sheep in the bedrock.
            if (y < origin.getY() + 1 || y > origin.getY() + size.getY() - 2) continue;
            Entity entity = EntityType.loadEntityRecursive(occupant.nbt(), level, spawning -> {
                spawning.moveTo(x, y, z, spawning.getYRot(), spawning.getXRot());
                return spawning;
            });
            if (entity == null) continue;
            if (level.addFreshEntity(entity)) spawned++;
        }
        if (spawned > 0) {
            LOGGER.info("[DungeonTrain] Chunk dimension pair {} spawned {} of its sample's {} mobs",
                pairKey, spawned, slice.occupants().size());
        }
    }

    /**
     * How far the sampled column slides against the room's own box, read off the door row.
     *
     * <p>Zero in an ordinary room: the doorways were fitted to this column's ground before the pair
     * was planned, so the ground row already <i>is</i> the door row. They come apart only when a room
     * cannot spend the offset the fit asked for — a world too shallow to stand a 32-tall box up holds
     * it down and the offset clamps with it — and then the shortfall is spent at the top of the
     * column, which is sky, rather than under a player's feet.</p>
     */
    private static int copyShift(PortalStructure structure, CarriageDims dims, Vec3i size) {
        int askedFor = structure.settings().doorHeightOffset().value();
        return askedFor - PortalRoomLayout.clampDoorHeightOffset(dims, size.getY(), askedFor);
    }

    /**
     * Open one doorway through the terrain: the two cells of the door itself, and nothing else.
     *
     * <p><b>Two blocks, and not one more.</b> The doorways are stood on the ground the sample landed
     * ({@link PortalChunkDoors}), so nothing has to be cut away to reach them — but a doorway is a
     * hole a player walks through, and a sample is free to have grown a tree trunk or piled a dune
     * in exactly that hole. What is cleared is the door's own column: one block deep at the room's
     * end face, on the walkway line, from the floor row up through the two cells
     * {@code PortalRoomDoorCells} calls a door. Everything either side of it, and everything behind
     * it, is the terrain as it was sampled.</p>
     *
     * <p><b>Nothing is added, either.</b> The floor row under those two cells used to be filled in
     * when the sample left air there, which put a stone block in the mouth of a doorway that opens
     * onto a slope or a stream — a bridge into the room that the terrain never had. The doorways are
     * fitted to the ground, so where that row is not ground it is because the sample says so, and
     * the room is left saying it.</p>
     */
    private static void openDoorway(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                    PortalCarriageLayout layout, BlockPos origin, Vec3i size,
                                    PortalCorridorMask mask, PortalCarriageRole role) {
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

    }

    /**
     * Give a freshly written block its sampled block entity, when it had one.
     *
     * <p>What makes a chest in a chunk dimension a chest rather than a box: the NBT carries
     * {@code LootTable} and {@code LootTableSeed}, so the container fills from the same vanilla table
     * it would have in the world the sample came from, rolled when a player first opens it.</p>
     */
    private static void applyBlockEntity(ServerLevel level, BlockPos pos, CompoundTag nbt) {
        if (nbt == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return;
        blockEntity.loadWithComponents(nbt, level.registryAccess());
        blockEntity.setChanged();
    }
}
