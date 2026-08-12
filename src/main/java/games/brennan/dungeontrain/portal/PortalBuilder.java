package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Stamps a {@link PortalGeometry} into the world: the corridor twice (once per copy) plus the
 * end treatments that differ between them.
 *
 * <p><b>Identical by construction.</b> Everything a player can see from inside the corridor is
 * written by a single {@link #stampCorridor} call, invoked once per copy with only the floor Y
 * differing. The copies cannot drift apart through a reviewer missing a line, because there is only
 * one line to miss. The end treatments — which genuinely differ, and which the doors keep out of
 * sight — are separate methods.</p>
 *
 * <pre>
 *   FAR  copy   [sealed plug] │D│ ─────────── X ─────────── │D│ [pocket area]
 *   NEAR copy   [approach   ] │D│ ─────────── X ─────────── │D│ [sealed plug]
 *                              ^ near door                    ^ far door
 * </pre>
 *
 * <p>Writes go through {@link ServerLevel#setBlock} on the normal path so the light engine runs —
 * deliberately <i>not</i> the section-local fast path {@code CarriagePlacer} uses, which skips
 * lighting. A mismatch in block light between the copies would be exactly the kind of tell the
 * whole design is trying to avoid.</p>
 */
public final class PortalBuilder {

    /** Shell palette. Both copies share it; the pocket area deliberately does not. */
    private static final BlockState SHELL = Blocks.STONE_BRICKS.defaultBlockState();
    /** Ceiling light, embedded flush so the shell stays sealed against skylight. */
    private static final BlockState CEILING_LIGHT = Blocks.SEA_LANTERN.defaultBlockState();
    /** Solid fill for the dead side of a dummy door. */
    private static final BlockState PLUG = Blocks.DEEPSLATE.defaultBlockState();

    /** Pocket-area palette — visibly "somewhere else" the moment the far door opens. */
    private static final BlockState POCKET_SHELL = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
    private static final BlockState POCKET_FLOOR = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState POCKET_LIGHT = Blocks.SHROOMLIGHT.defaultBlockState();

    /** Ceiling lights every N blocks along the corridor. */
    private static final int LIGHT_SPACING = 6;

    /** Interior dimensions of the pocket room beyond the far door. */
    private static final int POCKET_LENGTH = 15;
    private static final int POCKET_WIDTH = 13;
    private static final int POCKET_HEIGHT = 6;

    /** Length of the open-air walk-in carved in front of the NEAR copy's real door. */
    private static final int APPROACH_LENGTH = 4;

    /** Thickness of the solid plug behind a dummy door. */
    private static final int PLUG_DEPTH = 4;

    private PortalBuilder() {}

    /** Stamp both copies, both end treatments and the pocket area. */
    public static void build(ServerLevel level, PortalGeometry geo) {
        int nearFloor = geo.floorYOf(PortalGeometry.COPY_NEAR);
        int farFloor = geo.floorYOf(PortalGeometry.COPY_FAR);

        // The identical part — one method, called twice.
        stampCorridor(level, geo, nearFloor);
        stampCorridor(level, geo, farFloor);

        // The ends, which differ. Each copy's dummy door is backed by solid rock; the doors keep
        // both dead sides out of sight, and PortalTransitEvents keeps the dummy doors shut.
        carveApproach(level, geo, nearFloor);
        plugBehindDoor(level, geo, farFloor, true);    // FAR copy: dead space behind its near door
        plugBehindDoor(level, geo, nearFloor, false);  // NEAR copy: dead space behind its far door
        stampPocket(level, geo, farFloor);
    }

    /**
     * Write one copy of the corridor at {@code floorY}: floor, ceiling, both side walls, the
     * interior air, the two door planes and the ceiling lights. Everything visible from inside.
     */
    private static void stampCorridor(ServerLevel level, PortalGeometry geo, int floorY) {
        int x0 = geo.originX();
        int x1 = geo.originX() + geo.length() - 1;
        int z0 = geo.originZ();
        int z1 = geo.originZ() + geo.width() - 1;
        int ceilingY = floorY + geo.height() + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = x0; x <= x1; x++) {
            boolean lightColumn = (x - x0) % LIGHT_SPACING == LIGHT_SPACING / 2;

            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean wallColumn = z < z0 || z > z1;

                for (int y = floorY; y <= ceilingY; y++) {
                    BlockState state;
                    if (y == floorY || y == ceilingY || wallColumn) {
                        state = (y == ceilingY && lightColumn && !wallColumn) ? CEILING_LIGHT : SHELL;
                    } else {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    level.setBlock(pos.set(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }

        // Door planes: the full cross-section walled off except one doorway column holding a door.
        stampDoorPlane(level, geo, floorY, geo.nearDoorX());
        stampDoorPlane(level, geo, floorY, geo.farDoorX());
    }

    /**
     * Wall off the cross-section at {@code doorX} apart from the centre column, and hang a closed
     * two-block door in it. The wall is what breaks the sight-line across the midpoint: from before
     * the line you see a closed door, which is exactly what you would see in the other copy.
     */
    private static void stampDoorPlane(ServerLevel level, PortalGeometry geo, int floorY, int doorX) {
        int z0 = geo.originZ();
        int z1 = geo.originZ() + geo.width() - 1;
        int doorZ = geo.doorZ();
        int ceilingY = floorY + geo.height() + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int z = z0; z <= z1; z++) {
            for (int y = floorY + 1; y < ceilingY; y++) {
                boolean doorway = z == doorZ && y <= floorY + 2;
                if (doorway) continue;
                level.setBlock(pos.set(doorX, y, z), SHELL, Block.UPDATE_ALL);
            }
        }

        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);

        level.setBlock(new BlockPos(doorX, floorY + 1, doorZ), lower, Block.UPDATE_ALL);
        level.setBlock(new BlockPos(doorX, floorY + 2, doorZ),
            lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    /**
     * Carve the open-air walk-in in front of the NEAR copy's real door, so the portal can be
     * entered on foot. Only the floor is laid; everything above is cleared to air and left open to
     * the sky — this is outside the sealed corridor, so it cannot leak light past the door.
     */
    private static void carveApproach(ServerLevel level, PortalGeometry geo, int floorY) {
        int z0 = geo.originZ();
        int z1 = geo.originZ() + geo.width() - 1;
        int ceilingY = floorY + geo.height() + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = geo.originX() - APPROACH_LENGTH; x < geo.originX(); x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(pos.set(x, floorY, z), SHELL, Block.UPDATE_ALL);
                for (int y = floorY + 1; y <= ceilingY; y++) {
                    level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * Fill the dead space behind a copy's dummy door with solid rock, so nothing can be reached or
     * seen through it if it is ever forced open.
     *
     * @param behindNearDoor {@code true} to plug behind the near door (the FAR copy's dummy),
     *                       {@code false} to plug behind the far door (the NEAR copy's dummy)
     */
    private static void plugBehindDoor(ServerLevel level, PortalGeometry geo, int floorY, boolean behindNearDoor) {
        int z0 = geo.originZ() - 1;
        int z1 = geo.originZ() + geo.width();
        int ceilingY = floorY + geo.height() + 1;

        int from = behindNearDoor ? geo.nearDoorX() - PLUG_DEPTH : geo.farDoorX() + 1;
        int to = behindNearDoor ? geo.nearDoorX() - 1 : geo.farDoorX() + PLUG_DEPTH;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = from; x <= to; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = floorY; y <= ceilingY; y++) {
                    level.setBlock(pos.set(x, y, z), PLUG, Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * Stamp the pocket area beyond the FAR copy's real door — a sealed room in a palette nothing
     * in the corridor shares, so stepping through the far door reads unmistakably as arriving
     * somewhere else. Sealed on all six sides: no skylight, no view back out.
     */
    private static void stampPocket(ServerLevel level, PortalGeometry geo, int floorY) {
        int doorZ = geo.doorZ();
        int x0 = geo.farDoorX() + 1;
        int x1 = x0 + POCKET_LENGTH - 1;
        int z0 = doorZ - POCKET_WIDTH / 2;
        int z1 = z0 + POCKET_WIDTH - 1;
        int ceilingY = floorY + POCKET_HEIGHT + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Interior, side walls, floor, ceiling and far wall. The near wall plane is handled
        // separately below — it is the corridor's own far-door plane, and writing over it here
        // would delete the door.
        for (int x = x0; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = floorY; y <= ceilingY; y++) {
                    boolean shell = x > x1 || z < z0 || z > z1 || y == floorY || y == ceilingY;
                    BlockState state;
                    if (!shell) {
                        state = Blocks.AIR.defaultBlockState();
                    } else if (y == floorY) {
                        state = POCKET_FLOOR;
                    } else {
                        state = POCKET_SHELL;
                    }
                    level.setBlock(pos.set(x, y, z), state, Block.UPDATE_ALL);
                }
            }
        }

        // Near wall: the pocket is wider and taller than the corridor, so seal the ring of its
        // opening that the corridor's shell does not already cover, leaving that shell — and the
        // far door hanging in it — untouched.
        int corridorZ0 = geo.originZ() - 1;
        int corridorZ1 = geo.originZ() + geo.width();
        int corridorCeilingY = floorY + geo.height() + 1;

        for (int z = z0 - 1; z <= z1 + 1; z++) {
            for (int y = floorY; y <= ceilingY; y++) {
                boolean coveredByCorridor = z >= corridorZ0 && z <= corridorZ1 && y <= corridorCeilingY;
                if (coveredByCorridor) continue;
                level.setBlock(pos.set(x0 - 1, y, z), y == floorY ? POCKET_FLOOR : POCKET_SHELL,
                    Block.UPDATE_ALL);
            }
        }

        // Four ceiling lights, inset from the corners.
        for (int dx = 3; dx <= POCKET_LENGTH - 4; dx += POCKET_LENGTH - 7) {
            for (int dz = 3; dz <= POCKET_WIDTH - 4; dz += POCKET_WIDTH - 7) {
                level.setBlock(pos.set(x0 + dx, ceilingY, z0 + dz), POCKET_LIGHT, Block.UPDATE_ALL);
            }
        }
    }
}
