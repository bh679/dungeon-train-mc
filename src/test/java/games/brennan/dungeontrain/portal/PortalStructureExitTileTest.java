package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where a pair stands its exit corridor.
 *
 * <p>{@link PortalStructure}'s own javadoc is a warning: the exit twin's position feeds
 * {@code PortalFrames}, and shifting it under a player standing in it maps them onto a corridor that
 * is not there. That warning is about shifting it <i>per tick</i>, as the tiling grows. Moving it
 * once, when the pair is planned, is safe only if every reader still derives it from one place and it
 * never changes afterwards — which is exactly what these pin.</p>
 */
class PortalStructureExitTileTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    private static PortalStructure structure(Tile exitTile) {
        return new PortalStructure(ORIGIN, "labrynth", PortalRoomLayout.builtInSize(DIMS),
            PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_REPETITION),
            PortalRoomTiling.base(), PortalExitCopies.NONE, exitTile);
    }

    @Test
    @DisplayName("An unmoved exit is exactly where it has always been")
    void baseIsUnchanged() {
        PortalStructure s = structure(Tile.BASE);
        assertEquals(ORIGIN.offset(s.exitTwinOffsetX(DIMS), 0, 0), s.exitOrigin(DIMS));
        // …and the no-exit-tile constructors agree with it, so nothing that predates this moved.
        assertEquals(s.exitOrigin(DIMS),
            new PortalStructure(ORIGIN, "labrynth", PortalRoomLayout.builtInSize(DIMS))
                .exitOrigin(DIMS));
    }

    @Test
    @DisplayName("A moved exit is the same point translated by exactly that tile's delta")
    void movedExitIsAPureTranslation() {
        PortalStructure base = structure(Tile.BASE);
        for (Tile tile : new Tile[]{new Tile(1, 0), new Tile(0, -3), new Tile(4, 2), new Tile(-2, 5)}) {
            PortalStructure moved = structure(tile);
            assertEquals(
                base.exitOrigin(DIMS).offset(
                    tile.x() * base.roomLength(), 0, tile.z() * base.roomWidth()),
                moved.exitOrigin(DIMS), tile.toString());
            // The entry never moves — you always arrive in the same place.
            assertEquals(base.origin(), moved.origin(), tile.toString());
        }
    }

    @Test
    @DisplayName("The exit's shadow puts the corridor and its own room on the exit's tile")
    void exitShadowLinesUpWithItsTile() {
        Tile tile = new Tile(3, -2);
        PortalStructure moved = structure(tile);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);

        // The shadow's own exit is where the real one is — that is what makes stampCorridorHalf and
        // the mask land on the same blocks without either learning that exits can move.
        assertEquals(moved.exitOrigin(DIMS), moved.exitShadow().exitOrigin(DIMS));
        // And its room is the exit's tile, which is the cross-section the seal ring has to fill.
        assertEquals(moved.tileOrigin(DIMS, layout, tile),
            moved.exitShadow().roomOrigin(DIMS, layout));
    }

    @Test
    @DisplayName("A shadow carries no exit offset, so a copy is never doubly displaced")
    void shadowClearsTheExitOffset() {
        Tile exitTile = new Tile(3, -2);
        PortalStructure moved = structure(exitTile);
        PortalStructure straight = structure(Tile.BASE);

        // The bug this exists for: shadowAt is a coordinate frame built from the BASE arrangement.
        // If it let exitTile through, every extra corridor in a pair that had moved its exit would
        // land a tile's worth away from where its mask says it is — and only in that one setting.
        for (Tile at : new Tile[]{new Tile(1, 1), new Tile(-4, 0), new Tile(0, 5)}) {
            assertEquals(Tile.BASE, moved.shadowAt(at).exitTile(), at.toString());
            assertEquals(straight.shadowAt(at).origin(), moved.shadowAt(at).origin(), at.toString());
            assertEquals(straight.shadowAt(at).exitOrigin(DIMS),
                moved.shadowAt(at).exitOrigin(DIMS), at.toString());
        }

        // Which is to say: an exit COPY lands in the same world position whether or not this pair
        // moved its own exit.
        Site copy = new Site(new Tile(2, 2), PortalCarriageRole.EXIT);
        assertEquals(straight.exitCopyOrigin(DIMS, copy), moved.exitCopyOrigin(DIMS, copy));
    }

    @Test
    @DisplayName("Relocating the whole pair keeps its exit where it stood relative to the room")
    void movedToKeepsTheExitTile() {
        Tile exitTile = new Tile(4, 1);
        PortalStructure moved = structure(exitTile);
        PortalStructure elsewhere = moved.movedTo(ORIGIN.offset(4096, 0, 0));

        // Where a pair stands its exit is part of what it IS, decided when it was planned. A pair
        // re-stamped further down the track has to be the same portal the player walked out of.
        assertEquals(exitTile, elsewhere.exitTile());
        assertEquals(moved.exitOrigin(DIMS).offset(4096, 0, 0), elsewhere.exitOrigin(DIMS));

        // And the tiling withers keep it too, since they run every tick.
        assertEquals(exitTile, moved.withTiling(PortalRoomTiling.base()).exitTile());
        assertEquals(exitTile, moved.withExitCopies(PortalExitCopies.NONE).exitTile());
    }

    @Test
    @DisplayName("The mask follows the exit, and leaves the old spot to the room")
    void maskFollowsTheMovedExit() {
        Tile exitTile = new Tile(0, 3);
        PortalStructure moved = structure(exitTile);
        PortalStructure straight = structure(Tile.BASE);
        int y = ORIGIN.getY() + 1;

        BlockPos was = straight.exitOrigin(DIMS);
        BlockPos now = moved.exitOrigin(DIMS);
        assertNotEquals(was, now);

        PortalCorridorMask mask = PortalCarriageBuilder.corridorMask(moved, DIMS);
        // Covered where it now stands…
        assertEquals(true, mask.covers(now.getX(), y, now.getZ() + 1));
        // …and the place it used to be is the room's again, not reserved for a corridor that has
        // moved. That is the whole point: directly opposite is just another tile.
        assertEquals(false, mask.covers(was.getX() + 2, y, was.getZ() + 1));
    }
}
