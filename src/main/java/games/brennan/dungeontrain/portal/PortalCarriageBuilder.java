package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.HashSet;
import java.util.Set;

/**
 * Stamps the portal corridor — both the carriage copy and its static twin.
 *
 * <p><b>Identical by construction.</b> One pure function, {@link #stateAt}, answers "what block
 * belongs at this carriage-local cell". The carriage stamp and the twin stamp both drive it over
 * the same box, so the two copies cannot disagree: there is a single definition of the geometry, not
 * two that have to be kept in sync by review.</p>
 *
 * <p><b>Light saturation, not distance.</b> The crossing zone's floor is sea lanterns. Minecraft
 * block light is the <i>maximum</i> over sources, so with the local value pinned near 15, light
 * leaking in from the adjacent carriage through the near doorway cannot change what the player
 * sees — the two copies read identically however bright the rest of the train is. This is what lets
 * a 9-block carriage do a job that needs 32 blocks in the free-standing version, where a closed door
 * is no help ({@code BlockBehaviour.getLightBlock} returns 1 for anything not solid-render).</p>
 *
 * <p><b>Baffles, for sight rather than light.</b> Staggered walls just inside each door break every
 * straight line between the crossing zone and either doorway, so the player can never see the far
 * end — which is the one place the two copies genuinely differ (the next carriage on one side, the
 * pocket area on the other).</p>
 *
 * <p>Plan view of the interior at floor level, for a default 9×7×7 carriage — {@code D} door,
 * {@code ▓} baffle, {@code ░} lantern floor (the crossing zone), {@code ·} plain floor:</p>
 * <pre>
 *        x:  0  1  2  3  4  5  6  7  8
 *   z=5      ·  ·  ░  ░  ░  ░  ░  ·  ·
 *   z=4      ·  ·  ░  ░  ░  ░  ░  ·  ·
 *   z=3      D  ▓  ░  ░  ░  ░  ░  ▓  D
 *   z=2      ·  ▓  ░  ░  ░  ░  ░  ▓  ·
 *   z=1      ·  ▓  ░  ░  ░  ░  ░  ▓  ·
 * </pre>
 * <p>The walk is a dog-leg: in at {@code z=3}, around to {@code z=4/5} past the first baffle, across
 * the crossing zone, and back to {@code z=3} to leave. No straight line runs from either doorway to
 * the midpoint, because both baffles interrupt the walkway centre.</p>
 * <p>Both baffles block the <b>same</b> side deliberately, which makes the corridor mirror-symmetric
 * — an {@link PortalCarriageRole#ENTRY} carriage and an {@link PortalCarriageRole#EXIT} carriage are
 * then block-for-block identical, differing only in which half the rule treats as the train side.</p>
 *
 * <p>Follows {@code CarriagePlacer.legacyPlaceAt}'s contract for code-generated carriage geometry:
 * {@code null} means "leave this cell", and writes go through the section-local fast path on the
 * spawn side (Sable relights the sub-level afterwards) or the light engine in an editor plot.</p>
 */
public final class PortalCarriageBuilder {

    /** Corridor shell — walls, floor, ceiling, door planes and baffles. */
    private static final BlockState SHELL = Blocks.STONE_BRICKS.defaultBlockState();
    /** Crossing-zone floor. Light 15 at source, which is what makes external leakage irrelevant. */
    private static final BlockState CROSSING_LIGHT = Blocks.SEA_LANTERN.defaultBlockState();

    /** Pocket-area palette, beyond the twin's far door — deliberately shares nothing with the corridor. */
    private static final BlockState POCKET_SHELL = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    private static final BlockState POCKET_FLOOR = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState POCKET_LIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
    /** Solid fill behind the twin's dummy door. */
    private static final BlockState PLUG = Blocks.DEEPSLATE.defaultBlockState();

    private static final int POCKET_LENGTH = 11;
    private static final int POCKET_WIDTH = 11;
    private static final int POCKET_HEIGHT = 5;
    private static final int PLUG_DEPTH = 3;

    private PortalCarriageBuilder() {}

    /** The layout for a world's carriage dims. */
    public static PortalCarriageLayout layoutFor(CarriageDims dims) {
        return new PortalCarriageLayout(dims.length(), dims.height(), dims.width());
    }

    /**
     * What belongs at carriage-local {@code (dx, dy, dz)}, or {@code null} for "leave this cell"
     * — the same convention {@code CarriagePlacer.stateAt} uses for legacy carriage geometry.
     *
     * <p>This is the single definition of the corridor's shape. Both copies are stamped from it,
     * which is what makes them identical by construction rather than by inspection.</p>
     */
    public static BlockState stateAt(PortalCarriageLayout layout, int dx, int dy, int dz) {
        boolean shellCell = dy == layout.floorY() || dy == layout.ceilingY()
            || dz < layout.interiorMinZ() || dz > layout.interiorMaxZ();

        if (dy == layout.floorY() && layout.isCrossingZone(dx)
            && dz >= layout.interiorMinZ() && dz <= layout.interiorMaxZ()) {
            return CROSSING_LIGHT;
        }
        if (shellCell) return SHELL;

        // Door planes: the cross-section is walled off apart from the doorway column.
        if (dx == layout.nearDoorX() || dx == layout.farDoorX()) {
            boolean doorway = dz == layout.doorZ() && dy <= layout.floorY() + 2;
            return doorway ? doorState(dy == layout.floorY() + 1) : SHELL;
        }

        // Baffles: both on the same side, so no straight line survives from either doorway to the
        // crossing zone AND the corridor is mirror-symmetric — an entry and an exit carriage stamp
        // identical blocks, differing only in which half the swap rule treats as the train side.
        if ((dx == layout.nearBaffleX() || dx == layout.farBaffleX()) && dz <= layout.baffleZ()) {
            return SHELL;
        }

        return null;
    }

    private static BlockState doorState(boolean lower) {
        return Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false)
            .setValue(DoorBlock.HALF, lower ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
    }

    /**
     * Stamp the corridor as a carriage at {@code origin} (minimum corner), returning the filled
     * positions for {@code ShipAssembler.assembleToShip} — same contract as
     * {@code CarriagePlacer.legacyPlaceAt}.
     *
     * @param relight {@code true} in an editor plot, which is never lifted into a sub-level;
     *                {@code false} on the spawn path, where Sable relights the plot afterwards
     */
    public static Set<BlockPos> stampCarriage(ServerLevel level, BlockPos origin, CarriageDims dims, boolean relight) {
        PortalCarriageLayout layout = layoutFor(dims);
        Set<BlockPos> placed = new HashSet<>();

        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockState state = stateAt(layout, dx, dy, dz);
                    if (state == null) continue;

                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (relight) {
                        level.setBlock(pos, state, Block.UPDATE_ALL);
                    } else {
                        SilentBlockOps.setBlockSectionLocal(level, pos, state);
                    }
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    /**
     * Stamp the static twin at {@code origin}: the same corridor, plus the surroundings the carriage
     * gets from the train — a plug behind its dummy near door and the pocket area beyond its real
     * far door.
     *
     * <p>Unlike the carriage, this lands in existing terrain, so the box is cleared to air first and
     * written through the light engine — nothing lifts these blocks into a sub-level to relight
     * them.</p>
     */
    public static void stampTwin(ServerLevel level, BlockPos origin, CarriageDims dims) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockState state = stateAt(layout, dx, dy, dz);
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    level.setBlock(pos, state != null ? state : Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * X offset from the entry twin's origin to the exit twin's — one corridor plus the room between
     * them.
     */
    public static int exitTwinOffsetX(CarriageDims dims) {
        return dims.length() + POCKET_LENGTH;
    }

    /**
     * Stamp a whole pair structure at {@code entryOrigin}:
     *
     * <pre>
     *   [plug] [entry twin] [room] [exit twin] [plug]
     * </pre>
     *
     * <p>Each twin's dead side is plugged: the entry twin's near door has nothing behind it (its
     * near half maps to the entry carriage), and the exit twin's far door likewise (its far half
     * maps to the exit carriage). The room opens onto both corridors, so a player walks in from the
     * train through one and out to the train through the other without turning round.</p>
     */
    public static void stampPairStructure(ServerLevel level, BlockPos entryOrigin, CarriageDims dims) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos exitOrigin = entryOrigin.offset(exitTwinOffsetX(dims), 0, 0);

        stampTwin(level, entryOrigin, dims);
        stampTwin(level, exitOrigin, dims);
        stampRoom(level, entryOrigin, exitOrigin, dims, layout);

        // Dead space behind the door that leads nowhere, at each outer end.
        plugBeyond(level, entryOrigin.offset(-PLUG_DEPTH, 0, 0), PLUG_DEPTH, dims);
        plugBeyond(level, exitOrigin.offset(dims.length(), 0, 0), PLUG_DEPTH, dims);
    }

    /**
     * Clear a twin back to air — corridor, plug and pocket alike.
     *
     * <p>Called when a twin is superseded because the train has rolled away from it. Without it the
     * train would trail a line of abandoned corridors hanging in the sky, one for every time a
     * portal carriage drifted out of range.</p>
     */
    public static void eraseTwin(ServerLevel level, BlockPos origin, CarriageDims dims) {
        PortalCarriageLayout layout = layoutFor(dims);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();

        int zCentre = origin.getZ() + layout.doorZ();
        int minX = origin.getX() - PLUG_DEPTH;
        // Both corridors, the room between them, and the plug past the far end.
        int maxX = origin.getX() + exitTwinOffsetX(dims) + dims.length() + PLUG_DEPTH;
        int minZ = Math.min(origin.getZ() - 1, zCentre - POCKET_WIDTH / 2 - 1);
        int maxZ = Math.max(origin.getZ() + dims.width(), zCentre + POCKET_WIDTH / 2 + 1);
        int maxY = origin.getY() + Math.max(dims.height(), POCKET_HEIGHT + 2);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = origin.getY(); y <= maxY; y++) {
                    level.setBlock(pos.set(x, y, z), air, Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * Solid rock filling {@code depth} blocks from {@code from} along +X, across the corridor's
     * cross-section — the dead space behind a twin's door that leads nowhere, so nothing is reachable
     * or visible through it if it is ever forced open.
     */
    private static void plugBeyond(ServerLevel level, BlockPos from, int depth, CarriageDims dims) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < depth; dx++) {
            for (int dz = -1; dz <= dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    pos.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    level.setBlock(pos, PLUG, Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * The pocket room between the two twins — a sealed space in a palette nothing in the corridors
     * shares, so stepping out of either one reads unmistakably as arriving somewhere else.
     *
     * <p>Open at <b>both</b> ends: the entry twin's far door feeds it and the exit twin's near door
     * leads out of it, which is what lets a player cross the room and rejoin the train facing the
     * same way they set off.</p>
     */
    private static void stampRoom(ServerLevel level, BlockPos entryOrigin, BlockPos exitOrigin,
                                  CarriageDims dims, PortalCarriageLayout layout) {
        int x0 = entryOrigin.getX() + dims.length();
        int x1 = exitOrigin.getX() - 1;
        int zCentre = entryOrigin.getZ() + layout.doorZ();
        int z0 = zCentre - POCKET_WIDTH / 2;
        int z1 = z0 + POCKET_WIDTH - 1;
        int floorY = entryOrigin.getY();
        int ceilingY = floorY + POCKET_HEIGHT + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Interior, side walls, floor and ceiling. Neither end plane is written here — both are the
        // corridors' own door planes, and writing over them would delete the doors.
        for (int x = x0; x <= x1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = floorY; y <= ceilingY; y++) {
                    boolean shell = z < z0 || z > z1 || y == floorY || y == ceilingY;
                    BlockState state = !shell ? Blocks.AIR.defaultBlockState()
                        : (y == floorY ? POCKET_FLOOR : POCKET_SHELL);
                    level.setBlock(pos.set(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }

        // Seal the ring around each corridor mouth: the room is wider and taller than a corridor, so
        // everything its shell does not already cover has to be walled off, leaving that shell — and
        // the door hanging in it — untouched.
        sealCorridorMouth(level, x0 - 1, entryOrigin, dims, z0, z1, floorY, ceilingY);
        sealCorridorMouth(level, x1 + 1, exitOrigin, dims, z0, z1, floorY, ceilingY);

        for (int dx = 2; dx <= POCKET_LENGTH - 3; dx += POCKET_LENGTH - 5) {
            for (int dz = 2; dz <= POCKET_WIDTH - 3; dz += POCKET_WIDTH - 5) {
                level.setBlock(pos.set(x0 + dx, ceilingY, z0 + dz), POCKET_LIGHT, Block.UPDATE_ALL);
            }
        }
    }

    private static void sealCorridorMouth(ServerLevel level, int planeX, BlockPos corridorOrigin,
                                          CarriageDims dims, int z0, int z1, int floorY, int ceilingY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = z0 - 1; z <= z1 + 1; z++) {
            for (int y = floorY; y <= ceilingY; y++) {
                boolean coveredByCorridor = z >= corridorOrigin.getZ()
                    && z < corridorOrigin.getZ() + dims.width()
                    && y < floorY + dims.height();
                if (coveredByCorridor) continue;
                level.setBlock(pos.set(planeX, y, z), y == floorY ? POCKET_FLOOR : POCKET_SHELL,
                    Block.UPDATE_ALL);
            }
        }
    }
}
