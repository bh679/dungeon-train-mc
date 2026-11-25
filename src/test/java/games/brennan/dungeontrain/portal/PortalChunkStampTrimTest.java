package games.brennan.dungeontrain.portal;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The box a generated room's stamp leaves to its pour: {minX, minY, minZ, maxX, maxY, maxZ}. */
class PortalChunkStampTrimTest {

    private static final Vec3i GRID = new Vec3i(48, 32, 48);
    private static final Vec3i ONE = new Vec3i(16, 32, 16);

    @Test
    @DisplayName("a 3x3 room with no shift leaves everything but its end columns to the pour")
    void unshiftedGrid() {
        assertArrayEquals(new int[] {1, 0, 0, 46, 31, 47}, PortalChunkStampTrim.localBox(GRID, 48, 32, 0));
    }

    @Test
    @DisplayName("a one-chunk room is trimmed the same way")
    void unshiftedOneChunk() {
        assertArrayEquals(new int[] {1, 0, 0, 14, 31, 15}, PortalChunkStampTrim.localBox(ONE, 16, 32, 0));
    }

    @Test
    @DisplayName("a positive shift reads past the slice's top, so the top rows stay with the template")
    void positiveShift() {
        // Room row y reads slice row y + 3: rows 29..31 fall outside a 32-row slice.
        assertArrayEquals(new int[] {1, 0, 0, 46, 28, 47}, PortalChunkStampTrim.localBox(GRID, 48, 32, 3));
    }

    @Test
    @DisplayName("a negative shift reads below the slice, so the bottom rows stay with the template")
    void negativeShift() {
        assertArrayEquals(new int[] {1, 2, 0, 46, 31, 47}, PortalChunkStampTrim.localBox(GRID, 48, 32, -2));
    }

    @Test
    @DisplayName("a box wider than its slice is trimmed only where the slice reaches")
    void boxWiderThanSlice() {
        assertArrayEquals(new int[] {1, 0, 0, 14, 31, 15},
            PortalChunkStampTrim.localBox(new Vec3i(20, 32, 20), 16, 32, 0));
    }

    @Test
    @DisplayName("a held-down room is trimmed to its own height")
    void heldDownRoom() {
        assertArrayEquals(new int[] {1, 0, 0, 46, 23, 47},
            PortalChunkStampTrim.localBox(new Vec3i(48, 24, 48), 48, 32, 0));
    }

    @Test
    @DisplayName("nothing to trim gives null, so the room is stamped whole")
    void empty() {
        assertNull(PortalChunkStampTrim.localBox(GRID, 48, 32, 32));
        assertNull(PortalChunkStampTrim.localBox(GRID, 48, 32, -32));
        assertNull(PortalChunkStampTrim.localBox(new Vec3i(2, 32, 48), 48, 32, 0));
    }
}
