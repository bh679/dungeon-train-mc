package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFramePlacer;
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
 * <p>The variant is stamped first, exactly as any other room is: it clears the box and lays a plain
 * floor of ground — what a room keeps if a sample ever fails. The terrain is then poured into the
 * <b>whole</b> box. There is no skybox frame inside it: the sky a chunk dimension stands in is its
 * lock skin, one block outside the box ({@link PortalRoomLock}, set to the variant's skybox block
 * in {@code weights.json}), so the chunk is all sixteen blocks wide.</p>

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
            // The decoration pass runs from the level tick, outside the stamp's stage scope — open it
            // again so the frame's stage placeholders resolve as they did on the first write.
            games.brennan.dungeontrain.train.StagePlacementScope.run(
                PortalCarriageBuilder.stageIdFor(level, pairKey, dims), () -> write(level, structure, dims, slice, pairKey));
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
        write(level, structure, dims, slice, pairKey);
    }

    /**
     * Dress {@code structure}'s room in a frame — one of the frames whose selection names this room,
     * picked by weight for this pair (see {@link ChunkFramePlacer}). A room no frame dresses, or one
     * that is not a chunk box, is left as its terrain in its lock skin.
     */
    public static void frame(ServerLevel level, PortalStructure structure, CarriageDims dims, int pairKey) {
        if (!structure.roomSize().equals(ChunkFrame.ROOM_SIZE)) return;
        // The room's own variant index, so a test's reseed (the structure's salt) re-rolls the frame
        // too; unsalted it is a pure function of the pair, as in play.
        int rollIndex = structure.variantIndexFor(PortalRoomTiling.Tile.BASE, pairKey);
        java.util.Optional<ChunkFramePlacer.Picked> frame =
            ChunkFramePlacer.frameFor(level, structure.roomName(), pairKey, rollIndex);
        if (frame.isEmpty()) return;
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        // Without the seal planes: the frame may dress the mouth's plane, and only the corridor and
        // its plug are kept — which is what cuts the doorway through it.
        ChunkFramePlacer.place(level, frame.get(), structure.roomOrigin(dims, layout),
            PortalCarriageBuilder.corridorMask(structure, dims, /*withSeals*/ false), rollIndex);
    }

    // ---- writing -------------------------------------------------------------

    private static void write(ServerLevel level, PortalStructure structure, CarriageDims dims,
                              PortalChunkSlice slice, int pairKey) {
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
        // The whole box, faces included: the room has no shell of its own — its skybox is the lock
        // skin one block outside it — so the chunk runs all 16 blocks to the sky. Where the end
        // column is open, the mouth's seal ring falls back to that same skin rather than to the
        // floor row (PortalCarriageBuilder#sealFillFor), so an open face still seals with sky.
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ() && z < slice.width(); z++) {
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
                    replaceQuietly(level, cursor, state);
                    applyBlockEntity(level, cursor, slice.blockEntityAt(x, y + shift, z));
                }
            }
        }

        // Before the doorways: the frame's inner layer is written over the terrain's edge row, and
        // a rewrite of the terrain (the decoration pass) would otherwise bury it.
        frame(level, structure, dims, pairKey);

        openDoorway(level, structure, dims, layout, origin, size, mask, PortalCarriageRole.ENTRY);
        openDoorway(level, structure, dims, layout, origin, size, mask, PortalCarriageRole.EXIT);

        if (slice.source().levelKey().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            paintBiomes(level, origin, size, shift, slice);
        }
    }

    /**
     * Give the room the sampled chunk's biomes, so it is tinted as the place it was cut from.
     *
     * <p>Grass, leaves and water take their colour from the biome of the cell they stand in, and the
     * cells a room is stamped into belong to the world it is stamped into — an editor world's plains,
     * or whatever the live world has down there. So a BoP redwood forest came out plains green. The
     * room's interior is painted with the sample's own biomes, the way {@code /fillbiome} does it,
     * and the chunks are resent so a player already standing there sees it change.</p>
     *
     * <p>Overworld rooms only. A Nether or End room is lit and fogged by the sky its variant
     * authors, and a Nether biome in a sealed dark room would also bring the Nether's spawns with it.
     * A room shares a quart (4×4×4) with whatever is beside its walls, so a biome edge can reach a
     * block past them — the price of painting at the grain biomes are stored at.</p>
     */
    private static void paintBiomes(ServerLevel level, BlockPos origin, Vec3i size, int shift,
                                    PortalChunkSlice slice) {
        // The whole box: the terrain runs to its faces now, so the outermost grass is sampled grass
        // too and wants the sample's tint.
        int minX = origin.getX();
        int minY = origin.getY();
        int minZ = origin.getZ();
        int maxX = origin.getX() + size.getX() - 1;
        int maxY = origin.getY() + size.getY() - 1;
        int maxZ = origin.getZ() + size.getZ() - 1;
        java.util.List<net.minecraft.world.level.chunk.ChunkAccess> changed = new java.util.ArrayList<>();
        net.minecraft.world.level.biome.Climate.Sampler sampler =
            level.getChunkSource().randomState().sampler();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                boolean[] touched = {false};
                chunk.fillBiomesFromNoise((qx, qy, qz, s) -> {
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> here =
                        chunk.getNoiseBiome(qx, qy, qz);
                    int bx = net.minecraft.core.QuartPos.toBlock(qx);
                    int by = net.minecraft.core.QuartPos.toBlock(qy);
                    int bz = net.minecraft.core.QuartPos.toBlock(qz);
                    // A quart counts when its middle is inside the room's box.
                    int mx = bx + 2, my = by + 2, mz = bz + 2;
                    if (mx < minX || mx > maxX || my < minY || my > maxY || mz < minZ || mz > maxZ) {
                        return here;
                    }
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> sampled =
                        slice.biomeAt(mx - origin.getX(), my - origin.getY() + shift, mz - origin.getZ());
                    if (sampled == null || sampled.equals(here)) return here;
                    touched[0] = true;
                    return sampled;
                }, sampler);
                if (touched[0]) {
                    chunk.setUnsaved(true);
                    changed.add(chunk);
                }
            }
        }
        if (!changed.isEmpty()) level.getChunkSource().chunkMap.resendBiomesForChunks(changed);
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
            Entity entity = EntityType.loadEntityRecursive(
                games.brennan.dungeontrain.editor.FrozenMobs.prepareForSpawn(
                    occupant.nbt(), level, BlockPos.containing(x, y, z)),
                level, spawning -> {
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
        // Then on inward, for as long as the walkway is still rock: a doorway whose mouth the sample
        // buried — a Nether cave whose floor is further in than the end face — is dug through to the
        // open cave rather than left as a door onto a wall. Where the terrain is already open, which
        // is every hillside and most caves, the next column is clear and this stops at the door.
        int step = entry ? 1 : -1;
        int deepest = size.getX() / 2;
        for (int depth = 0; depth <= deepest; depth++) {
            int cx = x + depth * step;
            if (depth > 0 && walkwayOpen(level, cursor, cx, floorY, z)) break;
            for (int dy = 1; dy <= DOOR_HEIGHT; dy++) {
                cursor.set(cx, floorY + dy, z);
                if (mask.covers(cursor)) continue;
                if (!level.getBlockState(cursor).isAir()) replaceQuietly(level, cursor, air);
            }
        }
    }

    /**
     * Overwrite one cell of the room without spilling what was in it.
     *
     * <p>A sampled structure's chest carries its loot table unrolled, and replacing a container rolls
     * it and drops the lot on the floor: a ruined portal's chest standing in a doorway came out as a
     * pile of gold nuggets and flint and steel in front of the door. Taking the block entity away
     * first leaves nothing to roll. The room is being rewritten from the sample, so whatever the
     * chest held belongs to the chunk being replaced, not to the player.</p>
     */
    private static void replaceQuietly(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).hasBlockEntity()) level.removeBlockEntity(pos);
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** Whether a player could already stand in the walkway at {@code cx}: its door cells are open. */
    private static boolean walkwayOpen(ServerLevel level, BlockPos.MutableBlockPos cursor, int cx,
                                       int floorY, int z) {
        for (int dy = 1; dy <= DOOR_HEIGHT; dy++) {
            if (level.getBlockState(cursor.set(cx, floorY + dy, z)).blocksMotion()) return false;
        }
        return true;
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
