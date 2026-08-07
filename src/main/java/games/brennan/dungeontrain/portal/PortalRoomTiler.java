package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Set;

/**
 * Keeps the copies of a portal room standing around it — the working half of
 * {@link PortalRoomMode#ENDLESS_REPETITION} and {@link PortalRoomMode#ENDLESS_OPEN}.
 *
 * <h2>One tile per tick, and only while somebody is about</h2>
 * <p>{@link #tick} does at most one thing: stamp a copy, or erase one. That holds the per-tick cost
 * at a single room stamp, which is what the base room already costs, and it is why the appending can
 * run on the server tick beside the swap logic without being felt.</p>
 *
 * <p>Copies are only ever added while somebody is near the pair — a ring of them
 * ({@link PortalRoomTiling#APPROACH_RADIUS}) for a player approaching the carriage, the full window
 * once one is actually inside. A structure with a player in it is already pinned against being
 * re-stamped by {@code PortalCarriageEvents}, so the expensive path (the train drifting far enough to
 * rebuild the whole thing) never meets a tiled structure. That is not a coincidence to be preserved
 * by review: {@link #drainedEnoughToRestamp} is the gate, and a structure that still has copies
 * standing is drained rather than rebuilt.</p>
 *
 * <h2>Why a copy might not appear</h2>
 * <p>Three reasons, all of which simply leave the tile unbuilt rather than failing:</p>
 * <ul>
 *   <li><b>The budget is spent</b> — see {@link PortalRoomTiling#budgetTiles}.</li>
 *   <li><b>Its chunks are not loaded.</b> Checked, never forced: a forced load here would be a
 *       {@code getChunk(FULL, true)} on the server tick, which is the shape of the Sable worldgen
 *       deadlock. A wide room simply stops tiling at the edge of what is loaded.</li>
 *   <li><b>It would land on another pair's structure.</b> X tiling reaches far enough to meet a pair
 *       sharing the same Y lane, and two structures overwriting each other is the bug the lanes
 *       exist to prevent — so the candidate is tested against every other live structure first.</li>
 * </ul>
 * <p>None of them can be seen, because the fog is clamped to what has actually been built rather
 * than to what was asked for.</p>
 *
 * <h2>Faces</h2>
 * <p>One rule covers both axes and any authored room: <b>a face with a neighbour is carved open, a
 * face without one is closed</b> — except in {@link PortalRoomMode#ENDLESS_OPEN}, which never closes,
 * because that is what "the walls are open" means. Appending therefore moves the boundary wall
 * outward, which is what makes walking to the edge of one room add another.</p>
 *
 * <p>Carving is driven by what is <i>behind</i> each wall rather than by where the wall is: a cell in
 * the seam opens only when the cell one step inside each room is already air. So the passage that
 * appears is exactly the passage that exists on both sides, whatever the author built, and solid
 * structure is never punched through. Closing is the same idea in reverse — only air is filled, so an
 * authored wall keeps its own blocks.</p>
 *
 * <p>Seams stop one column short of each corner, so the four rooms meeting at a point keep a pillar
 * between them. That is partly a look (it reads as a doorframe) and partly the thing that stops face
 * closing and seam carving from having to be ordered against each other.</p>
 */
public final class PortalRoomTiler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Copies erased per tick once nobody is inside.
     *
     * <p>Faster than they go up on purpose. A structure may not be re-stamped until it is drained,
     * and the train reaching {@code TWIN_MAX_DRIFT} is what asks for the re-stamp — so draining has
     * to outpace the train, or a pair would be stuck at a twin the carriage has rolled away from.</p>
     */
    private static final int ERASES_PER_TICK = 4;

    /** The four ways out of a room. Y is not among them — see {@link PortalRoomMode}. */
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private PortalRoomTiler() {}

    /**
     * True when {@code structure} may be erased and re-stamped somewhere else.
     *
     * <p>False while copies are still standing, so {@code eraseTwin} never has to sweep more than the
     * base structure's own box. Correctness does not depend on this — the erase reads the tiled
     * bounds anyway — only the cost does.</p>
     */
    public static boolean drainedEnoughToRestamp(PortalStructure structure) {
        return structure.tiling().isBaseOnly();
    }

    /**
     * Advance one structure's copies by at most one stamp or one erase, returning the structure as it
     * now stands.
     *
     * @param standingIn the tiles players are currently in — empty means nobody is inside, which is
     *                   the signal to drain. The first is where the window centres; the rest are
     *                   simply never erased, because clearing a copy takes its floor with it and
     *                   would drop whoever was standing there onto the rock at the world floor.
     * @param radius     how far the window reaches — {@link PortalRoomTiling#MAX_RADIUS} for somebody
     *                   actually in the room, {@link PortalRoomTiling#APPROACH_RADIUS} for somebody
     *                   merely near the carriage
     * @param neighbours every other live structure, so a copy is never stamped onto one
     */
    public static PortalStructure tick(ServerLevel level, CarriageDims dims,
                                       PortalStructure structure, Set<Tile> standingIn, int radius,
                                       Collection<PortalStructure> neighbours) {
        if (!structure.mode().tiles()) {
            // A room that does not tile should have nothing standing. It can still get here carrying
            // copies if its variant's mode was changed between one visit and the next.
            return structure.tiling().isBaseOnly() ? structure : drain(level, dims, structure, standingIn);
        }
        if (standingIn.isEmpty()) return drain(level, dims, structure, standingIn);

        // With two players walking opposite ways the window can only follow one of them. It follows
        // the first, and the other is held up by whatever budget is left — but never dropped, since
        // the tile they are in is spared below.
        Tile centre = standingIn.iterator().next();
        PortalRoomTiling tiling = structure.tiling();

        // Build ahead of the player before shedding what is behind them. What is ahead is what they
        // can see — the fog sits at the edge of what has been built — whereas what is behind is
        // already out of sight. When the budget is spent this finds nothing, and the retire below
        // frees a slot for the next tick, so a sliding window still slides.
        Tile next = tiling.nextToAdd(centre, radius, structure.tileBudget(),
            candidate -> canStamp(level, dims, structure, candidate, neighbours));
        if (next != null) return stampTile(level, dims, structure, next);

        Tile stale = tiling.nextToRemove(centre, radius,
            candidate -> !standingIn.contains(candidate));
        if (stale != null) return eraseTile(level, dims, structure, stale);

        return structure;
    }

    /** Shed copies from the outside in, several a tick — see {@link #ERASES_PER_TICK}. */
    private static PortalStructure drain(ServerLevel level, CarriageDims dims,
                                         PortalStructure structure, Set<Tile> standingIn) {
        PortalStructure current = structure;
        for (int i = 0; i < ERASES_PER_TICK; i++) {
            Tile farthest = current.tiling().farthestFrom(Tile.BASE,
                candidate -> !standingIn.contains(candidate));
            if (farthest == null) break;
            current = eraseTile(level, dims, current, farthest);
        }
        return current;
    }

    // ---------- stamping ----------

    /**
     * Put a copy of the room at {@code tile} and settle the faces around it.
     *
     * <p>{@link PortalRoomMode#ENDLESS_OPEN} repeats the floor and the roof, in the authored room's
     * own blocks, and nothing else. It gets there by masking the tile's interior out of the
     * <b>write</b> mask, so the stamp never puts anything between the two planes — the clear mask is
     * untouched, so the interior is still emptied of the rock the copy landed in.</p>
     *
     * <p>It used to stamp the whole room and strip the interior back out afterwards, on the reasoning
     * that the clear was already paid for. It was not: the strip ran a plain {@code setBlock} over
     * live block entities, so every chest the stamp had just placed <i>and filled</i> spilled its
     * loot across the floor — once per tile, up to the whole window, re-firing as the window slid.
     * Not writing the interior costs a mask box and skips the placement, the loot roll and the break
     * together. See {@link PortalClear} for the same hazard, found earlier on the erase paths.</p>
     */
    private static PortalStructure stampTile(ServerLevel level, CarriageDims dims,
                                             PortalStructure structure, Tile tile) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        BlockPos origin = structure.tileOrigin(dims, layout, tile);
        Vec3i size = structure.roomSize();

        PortalCorridorMask clearMask = maskFor(structure, dims, tile);
        PortalCorridorMask writeMask = writeMaskFor(structure, clearMask, origin, size);
        PortalCarriageBuilder.stampRoomAt(level, origin, dims, structure.roomName(), size,
            /*relight*/ true, clearMask, writeMask, structure.variantIndexFor(tile));

        PortalStructure grown = structure.withTiling(structure.tiling().with(tile));
        refreshFacesAround(level, dims, grown, tile);
        return grown;
    }

    /**
     * What this copy must not write into: whatever the clear mask already protects, plus — for
     * {@link PortalRoomMode#ENDLESS_OPEN} — everything strictly between the tile's floor and its
     * ceiling.
     *
     * <p>Tested against {@code ENDLESS_OPEN} rather than {@code !tilesWholeRoom()}, which is also
     * true of {@link PortalRoomMode#BEDROCK_LOCK} and is only unreachable for it because
     * {@link #tick} returns early for a mode that does not tile at all. That is a trap waiting for
     * the next mode to be added.</p>
     */
    // Package-private rather than private so it can be tested directly: it is pure, and it is the
    // whole of the ENDLESS_OPEN rule — getting it wrong either sprays loot again or fills the open
    // space with the rock the copy landed in.
    static PortalCorridorMask writeMaskFor(PortalStructure structure,
                                           PortalCorridorMask clearMask,
                                           BlockPos origin, Vec3i size) {
        if (structure.mode() != PortalRoomMode.ENDLESS_OPEN) return clearMask;
        return clearMask.plus(new BoundingBox(
            origin.getX(), origin.getY() + 1, origin.getZ(),
            origin.getX() + size.getX() - 1,
            origin.getY() + size.getY() - 2,
            origin.getZ() + size.getZ() - 1));
    }

    /**
     * What this copy must not write into: the corridors, when it sits on the row that runs through
     * them, and nothing at all on every other row.
     *
     * <p>This is what lets a twin be placed <b>once</b>, when its structure is built. The copy is
     * stamped around it rather than over it, so there is never anything to repair — no re-laying the
     * corridors each time a copy on that row appears, retires, or has a seam carved through it.</p>
     */
    private static PortalCorridorMask maskFor(PortalStructure structure, CarriageDims dims,
                                              Tile tile) {
        return tile.z() == 0
            ? PortalCarriageBuilder.corridorMask(structure, dims)
            : PortalCorridorMask.NONE;
    }

    // ---------- erasing ----------

    /**
     * Clear {@code tile} back to air, then settle the faces its neighbours are now left with.
     *
     * <p><b>Its own box and not a block more.</b> A margin here would reach into whatever is next
     * door, and next door is another copy of the room — so it would take a column out of that
     * neighbour's <i>floor and ceiling</i> as well as its wall, leaving a one-block trench along the
     * seam that the repair below cannot fill (face work only ever touches the rows between the floor
     * and the ceiling, which is the whole point of it).</p>
     *
     * <p>Nothing outside a copy's own box needs clearing anyway. Everything the tiler writes lands
     * inside the box it belongs to, with one exception: a seam carve opens the near column of the
     * <i>neighbour's</i> wall as well as its own. That column belongs to the neighbour, and the
     * refresh below is what puts it back — exactly the rows the carve opened, so the damage and the
     * repair are the same shape.</p>
     */
    private static PortalStructure eraseTile(ServerLevel level, CarriageDims dims,
                                             PortalStructure structure, Tile tile) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        BlockPos origin = structure.tileOrigin(dims, layout, tile);
        Vec3i size = structure.roomSize();

        PortalCorridorMask mask = maskFor(structure, dims, tile);
        PortalClear.clearBox(level, new BoundingBox(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1,
            origin.getY() + size.getY() - 1,
            origin.getZ() + size.getZ() - 1), mask);

        PortalStructure shrunk = structure.withTiling(structure.tiling().without(tile));
        // The neighbours that were open onto this copy now face nothing, so they close again — which
        // is also what restores the column this copy's seam carve took out of each of them.
        for (int[] d : DIRECTIONS) {
            Tile neighbour = tile.offset(d[0], d[1]);
            if (shrunk.tiling().has(neighbour)) refreshFace(level, dims, shrunk, neighbour, -d[0], -d[1]);
        }
        return shrunk;
    }

    // ---------- faces ----------

    /**
     * Settle every face of {@code tile} and the facing side of each neighbour.
     *
     * <p>Called after a copy appears. Both sides are needed: the new copy's wall toward an existing
     * neighbour and that neighbour's wall toward it are two separate columns, and the seam is only
     * open when both are.</p>
     */
    public static void refreshFacesAround(ServerLevel level, CarriageDims dims,
                                          PortalStructure structure, Tile tile) {
        for (int[] d : DIRECTIONS) {
            refreshFace(level, dims, structure, tile, d[0], d[1]);
        }
    }

    /**
     * Open or close one face of one copy.
     *
     * <p>Every direction is treated the same, the corridor row included: {@link #eachFaceCell} drops
     * the cells a corridor owns, and along X from the base room that is the whole face — both the door
     * plane and the room's own column in front of it. What the player walks through there is the
     * door, laid once by {@code stampCorridors}, and none of the three operations below may touch
     * it.</p>
     */
    private static void refreshFace(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                    Tile tile, int dx, int dz) {
        Tile neighbour = tile.offset(dx, dz);
        if (structure.tiling().has(neighbour)) {
            carveSeam(level, dims, structure, tile, dx, dz);
        } else if (structure.mode().closesOuterFaces()) {
            closeFace(level, dims, structure, tile, dx, dz);
        } else {
            openFace(level, dims, structure, tile, dx, dz);
        }
    }

    /**
     * Open the two wall columns between {@code tile} and its neighbour, wherever both rooms are
     * already open one step further in.
     */
    private static void carveSeam(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                 Tile tile, int dx, int dz) {
        eachFaceCell(level, dims, structure, tile, dx, dz, /*interiorOnly*/ true, (wall, inner) -> {
            // Open the seam only where both rooms are already open one step further in, so the
            // passage that appears is the passage that exists on both sides.
            BlockPos innerFar = wall.offset(dx * 2, 0, dz * 2);
            if (!level.getBlockState(inner).isAir() || !level.getBlockState(innerFar).isAir()) return;
            // Through clearCell, not setBlock: an authored chest standing against a face would
            // otherwise spill its contents when the seam opens through it.
            PortalClear.clearCell(level, wall);
            // The far column belongs to the neighbour; eachFaceCell has already dropped every cell
            // whose far side is a corridor's, so writing it here needs no second guard.
            PortalClear.clearCell(level, wall.offset(dx, 0, dz));
        });
    }

    /** Wall off a face that has nothing beyond it, filling only what the author left as air. */
    private static void closeFace(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                  Tile tile, int dx, int dz) {
        eachFaceCell(level, dims, structure, tile, dx, dz, /*interiorOnly*/ false, (wall, inner) -> {
            if (!level.getBlockState(wall).isAir()) return;
            level.setBlock(wall, PortalCarriageBuilder.POCKET_SHELL, Block.UPDATE_ALL);
        });
    }

    /** Take a face away — {@link PortalRoomMode#ENDLESS_OPEN} only. */
    private static void openFace(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                 Tile tile, int dx, int dz) {
        eachFaceCell(level, dims, structure, tile, dx, dz, /*interiorOnly*/ true, (wall, inner) -> {
            // Same test as the seam carve, for the same reason: only where the room behind it is
            // already open, so an authored pillar standing against the wall is not hollowed out.
            if (!level.getBlockState(inner).isAir()) return;
            PortalClear.clearCell(level, wall);
        });
    }

    /** What {@link #eachFaceCell} hands to each of the three face operations. */
    @FunctionalInterface
    private interface FaceCell {
        /**
         * @param wall  the cell in this room's own wall on that side
         * @param inner the cell one step further into this room
         */
        void accept(BlockPos wall, BlockPos inner);
    }

    /**
     * Walk one face of one copy: the wall plane on side {@code (dx, dz)}, over the rows between the
     * floor and the ceiling.
     *
     * <p>{@code interiorOnly} drops the two extreme columns as well, which is what leaves a pillar
     * where four rooms meet — and, more usefully, what stops carving a seam and closing a face from
     * having to be ordered against each other, since they then never touch the same cell.</p>
     */
    private static void eachFaceCell(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                     Tile tile, int dx, int dz, boolean interiorOnly, FaceCell body) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        BlockPos origin = structure.tileOrigin(dims, layout, tile);
        Vec3i size = structure.roomSize();
        // Masked here rather than in each of the three face operations, so none of them can forget:
        // a face along X from the base room runs straight into a door plane, and the door is placed
        // once and must survive. Two cells short of it, not one — see the facedBy check below.
        PortalCorridorMask mask = PortalCarriageBuilder.corridorMask(structure, dims);

        int x0 = origin.getX();
        int x1 = x0 + size.getX() - 1;
        int z0 = origin.getZ();
        int z1 = z0 + size.getZ() - 1;
        // Floor and ceiling are never touched: they are what a room stands on, and in Endless Open
        // they are the only thing that repeats.
        int yLow = origin.getY() + 1;
        int yHigh = origin.getY() + size.getY() - 2;

        int inset = interiorOnly ? 1 : 0;

        if (dz != 0) {
            int wallZ = dz > 0 ? z1 : z0;
            for (int x = x0 + inset; x <= x1 - inset; x++) {
                for (int y = yLow; y <= yHigh; y++) {
                    BlockPos wall = new BlockPos(x, y, wallZ);
                    if (mask.covers(wall) || mask.facedBy(wall, 0, dz)) continue;
                    body.accept(wall, wall.offset(0, 0, -dz));
                }
            }
            return;
        }
        int wallX = dx > 0 ? x1 : x0;
        for (int z = z0 + inset; z <= z1 - inset; z++) {
            for (int y = yLow; y <= yHigh; y++) {
                BlockPos wall = new BlockPos(wallX, y, z);
                // The room's own end column is not masked — the mask stops at the door plane — but
                // the door is what stands in front of it, so it is the corridor's all the same.
                // Without this the base room's end plane is bricked up right across its doorway.
                if (mask.covers(wall) || mask.facedBy(wall, dx, 0)) continue;
                body.accept(wall, wall.offset(-dx, 0, 0));
            }
        }
    }

    // ---------- whether a copy may be built at all ----------

    private static boolean canStamp(ServerLevel level, CarriageDims dims, PortalStructure structure,
                                    Tile tile, Collection<PortalStructure> neighbours) {
        BoundingBox box = tileBox(dims, structure, tile);
        if (!chunksLoaded(level, box)) return false;
        for (PortalStructure other : neighbours) {
            if (other == structure) continue;
            if (box.intersects(PortalCarriageBuilder.footprintOf(level, other, dims))) {
                LOGGER.debug("[DungeonTrain] Portal room copy at {} skipped — it would land on another pair",
                    tile);
                return false;
            }
        }
        return true;
    }

    /** A copy's world box, with the block of margin its closed faces and skin occupy. */
    private static BoundingBox tileBox(CarriageDims dims, PortalStructure structure, Tile tile) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        BlockPos origin = structure.tileOrigin(dims, layout, tile);
        Vec3i size = structure.roomSize();
        return new BoundingBox(
            origin.getX() - 1, origin.getY(), origin.getZ() - 1,
            origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
    }

    /**
     * True when every chunk column the copy would touch is already loaded.
     *
     * <p>Asked, never forced. Forcing here would be a {@code getChunk(FULL, true)} on the server tick
     * from inside the portal loop, which is the shape of the Sable worldgen deadlock — and the cost of
     * saying no is only that a copy does not appear, which the fog clamp already hides.</p>
     */
    private static boolean chunksLoaded(ServerLevel level, BoundingBox box) {
        int minChunkX = SectionPos.blockToSectionCoord(box.minX());
        int maxChunkX = SectionPos.blockToSectionCoord(box.maxX());
        int minChunkZ = SectionPos.blockToSectionCoord(box.minZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(box.maxZ());
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }
}
