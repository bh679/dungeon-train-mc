package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.portal.PortalCorridorSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentsSizeTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("Room is one carriage; Half is the long portal corridor; Full is the whole group")
    void boxes() {
        assertEquals(DIMS, ContentsSize.ROOM.shellDims(DIMS, 3).orElseThrow());
        assertEquals(PortalCorridorSize.corridorDims(DIMS, PortalCorridorKind.LONG),
            ContentsSize.HALF.shellDims(DIMS, 3).orElseThrow());
        CarriageDims full = ContentsSize.FULL.shellDims(DIMS, 3).orElseThrow();
        assertEquals(3 * DIMS.length(), full.length());
        assertEquals(DIMS.width(), full.width());
        assertEquals(DIMS.height(), full.height());
    }

    @Test
    @DisplayName("A Full box past MAX_LENGTH is unavailable, and boxOrRoom falls back to a carriage")
    void fullPastMaxLengthIsOff() {
        // 4 × 9 = 36 > 32.
        assertEquals(Optional.empty(), ContentsSize.FULL.shellDims(DIMS, 4));
        assertFalse(ContentsSize.FULL.available(DIMS, 4));
        assertEquals(DIMS, ContentsSize.FULL.boxOrRoom(DIMS, 4));
        assertTrue(ContentsSize.ROOM.available(DIMS, 16));
        assertTrue(ContentsSize.HALF.available(DIMS, 16));
    }

    @Test
    @DisplayName("Sizes parse by their lowercase key, case-insensitively; anything else is empty")
    void parse() {
        assertEquals(Optional.of(ContentsSize.ROOM), ContentsSize.parse("room"));
        assertEquals(Optional.of(ContentsSize.HALF), ContentsSize.parse(" Half "));
        assertEquals(Optional.of(ContentsSize.FULL), ContentsSize.parse("FULL"));
        assertEquals(Optional.empty(), ContentsSize.parse("huge"));
        assertEquals(Optional.empty(), ContentsSize.parse(null));
        for (ContentsSize s : ContentsSize.values()) {
            assertEquals(Optional.of(s), ContentsSize.parse(s.key()));
        }
    }

    @Test
    @DisplayName("The Full lottery is off at 0, deterministic, and roughly one in N")
    void fullLottery() {
        assertFalse(FullCarriageSelection.isFullGroup(0, 3, 0, 42L));
        for (int anchor = 0; anchor < 300; anchor += 3) {
            assertEquals(FullCarriageSelection.isFullGroup(anchor, 3, 7, 42L),
                FullCarriageSelection.isFullGroup(anchor, 3, 7, 42L));
            // Every anchor within one group answers the same.
            assertEquals(FullCarriageSelection.isFullGroup(anchor, 3, 7, 42L),
                FullCarriageSelection.isFullGroup(anchor + 2, 3, 7, 42L));
        }
        int hits = 0;
        int groups = 7000;
        for (int g = 0; g < groups; g++) {
            if (FullCarriageSelection.isFullGroup(g * 3, 3, 7, 1234L)) hits++;
        }
        assertTrue(hits > groups / 7 / 2 && hits < groups / 7 * 2, "hits=" + hits);
    }
}
