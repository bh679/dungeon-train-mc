package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.portal.PortalTwinRegion;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.worldgen.legacy.preset.AmplifiedDrop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantHorizonsSunkAtticTest {

    /** Stock overworld: build -64..320, terrain floor at the stock bedrock row 32. */
    private static final AmplifiedDrop DROP = AmplifiedDrop.compute(32, -64, 320, PortalTwinSpace.CEILING_MARGIN);
    private static final PortalTwinRegion ATTIC = PortalTwinSpace.amplifiedAttic(DROP, 320);

    @AfterEach
    void reset() {
        DistantHorizonsSunkAttic.setEnabled(() -> true);
        DistantHorizonsSunkAttic.setClientResolver((level, x) -> null);
    }

    @Test
    void atticBlocksAreHiddenAndEverythingUnderTheLidIsKept() {
        int lid = DROP.lidY();
        assertTrue(DistantHorizonsSunkAttic.hides(ATTIC, lid), "the barrier lid row itself");
        assertTrue(DistantHorizonsSunkAttic.hides(ATTIC, lid + 10), "a room in the attic");
        assertFalse(DistantHorizonsSunkAttic.hides(ATTIC, lid - 1), "terrain under the lid stays in DH");
        assertFalse(DistantHorizonsSunkAttic.hides(ATTIC, 64), "the valleys stay in DH");
    }

    @Test
    void noAtticKeepsEveryBlock() {
        assertFalse(DistantHorizonsSunkAttic.hides(null, 300));
    }

    @Test
    void aChunkIsFilteredWhenEitherEdgeColumnIsSunk() {
        // The zone starts at x = 100: chunk 6 (96..111) straddles the edge, chunk 5 (80..95) is outside.
        DistantHorizonsSunkAttic.setClientResolver((level, x) -> x >= 100 ? ATTIC : null);
        Object level = new Object();
        assertSame(ATTIC, DistantHorizonsSunkAttic.atticForChunk(level, 6));
        assertSame(ATTIC, DistantHorizonsSunkAttic.atticForChunk(level, 7));
        assertNull(DistantHorizonsSunkAttic.atticForChunk(level, 5));
    }

    @Test
    void theResolverIsAskedAboutTheChunksEdgeColumns() {
        List<Integer> asked = new ArrayList<>();
        DistantHorizonsSunkAttic.setClientResolver((level, x) -> {
            asked.add(x);
            return null;
        });
        DistantHorizonsSunkAttic.atticForChunk(new Object(), -2);
        assertEquals(List.of(-32, -17), asked);
    }

    @Test
    void theToggleTurnsTheFilterOff() {
        DistantHorizonsSunkAttic.setClientResolver((level, x) -> ATTIC);
        DistantHorizonsSunkAttic.setEnabled(() -> false);
        assertNull(DistantHorizonsSunkAttic.atticForChunk(new Object(), 0));
    }
}
