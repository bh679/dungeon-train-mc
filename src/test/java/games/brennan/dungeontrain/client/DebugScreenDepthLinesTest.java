package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The debug screen's coordinate disguise rewrites printed text, so what it is really guarding is that it never
 * touches a line it did not recognise — a vanilla format this does not know about has to come out the
 * far side unchanged rather than mangled.
 *
 * <p>The line shapes here are vanilla 1.21.1's, copied from what {@code DebugScreenOverlay} prints.</p>
 */
class DebugScreenDepthLinesTest {

    /** A room stamped 180 blocks under a train running at 78, the ordinary basement case; Y only. */
    private static final DebugScreenDepthLines.Shift SHIFT = new DebugScreenDepthLines.Shift(0, 180, 0);

    /** The camera's real block position in that room, which the derived lines are recomputed from. */
    private static final int BLOCK_X = 128;
    private static final int BLOCK_Y = -102;
    private static final int BLOCK_Z = -64;

    /** The same room with its centre at X 136, Z -70 moved to 0, 0. */
    private static final DebugScreenDepthLines.Shift CENTRED = new DebugScreenDepthLines.Shift(-136, 180, 70);

    private static String shift(String line, DebugScreenDepthLines.Shift shift) {
        return DebugScreenDepthLines.shift(line, BLOCK_X, BLOCK_Y, BLOCK_Z, shift);
    }

    @Test
    @DisplayName("XYZ keeps X and Z and moves only Y")
    void movesOnlyY() {
        assertEquals("XYZ: 128.500 / 78.00000 / -64.312",
            shift("XYZ: 128.500 / -102.00000 / -64.312", SHIFT));
    }

    @Test
    @DisplayName("Block moves the world Y and the section-local Y with it")
    void movesBlockAndLocalY() {
        // -102 → 78: local Y goes from floorMod(-102, 16) = 10 to floorMod(78, 16) = 14.
        assertEquals("Block: 128 78 -64 [0 14 0]",
            shift("Block: 128 -102 -64 [0 10 0]", SHIFT));
    }

    @Test
    @DisplayName("Chunk's section index is recomputed rather than shifted")
    void recomputesSectionIndex() {
        // floorDiv(-102, 16) = -7 becomes floorDiv(78, 16) = 4 — not -7 + 180.
        assertEquals("Chunk: 8 4 -4 [0 0 in r.0.-1.mca]",
            shift("Chunk: 8 -7 -4 [8 12 in r.0.-1.mca]", SHIFT));
    }

    @Test
    @DisplayName("the reduced-info screen's chunk-relative line moves too")
    void movesChunkRelative() {
        assertEquals("Chunk-relative: 0 14 0",
            shift("Chunk-relative: 0 10 0", SHIFT));
    }

    @Test
    @DisplayName("targeted block and fluid lines move, formatting code and all")
    void movesTargeted() {
        assertEquals("§nTargeted Block: 128, 77, -64",
            shift("§nTargeted Block: 128, -103, -64", SHIFT));
        assertEquals("§nTargeted Fluid: 128, 77, -64",
            shift("§nTargeted Fluid: 128, -103, -64", SHIFT));
    }

    @Test
    @DisplayName("an attic room shifts the other way")
    void shiftsDownwards() {
        assertEquals("XYZ: 128.500 / 78.00000 / -64.312",
            DebugScreenDepthLines.shift("XYZ: 128.500 / 300.00000 / -64.312", BLOCK_X, 300, BLOCK_Z,
                new DebugScreenDepthLines.Shift(0, -222, 0)));
    }

    @Test
    @DisplayName("lines that carry no Y, and shapes this does not know, are passed through untouched")
    void leavesEverythingElseAlone() {
        for (String line : List.of(
                "Minecraft 1.21.1 (dungeon-train/neoforge)",
                "Facing: south (Towards positive Z) (-12.3 / 4.5)",
                "Biome: minecraft:plains",
                "Client Light: 0 (0 sky, 0 block)",
                "XYZ: not / a / position",
                "Block: 128 -102 -64",
                "")) {
            assertEquals(line, shift(line, CENTRED));
        }
    }

    @Test
    @DisplayName("a zero shift is not merely a no-op, it allocates nothing")
    void zeroShiftReturnsTheSameList() {
        List<String> lines = List.of("XYZ: 128.500 / -102.00000 / -64.312");
        assertSame(lines, DebugScreenDepthLines.shifted(lines, BLOCK_X, BLOCK_Y, BLOCK_Z,
            DebugScreenDepthLines.Shift.NONE));
    }

    @Test
    @DisplayName("a whole screenful is rewritten in place order")
    void rewritesTheWholeList() {
        List<String> shifted = DebugScreenDepthLines.shifted(List.of(
            "Minecraft 1.21.1",
            "XYZ: 128.500 / -102.00000 / -64.312",
            "Block: 128 -102 -64 [0 10 0]",
            "Chunk: 8 -7 -4 [8 12 in r.0.-1.mca]"), BLOCK_X, BLOCK_Y, BLOCK_Z, SHIFT);

        assertEquals(List.of(
            "Minecraft 1.21.1",
            "XYZ: 128.500 / 78.00000 / -64.312",
            "Block: 128 78 -64 [0 14 0]",
            "Chunk: 8 4 -4 [0 0 in r.0.-1.mca]"), shifted);
    }

    @Test
    @DisplayName("XYZ centres X and Z on the room, keeping vanilla's precision per axis")
    void centresXyz() {
        assertEquals("XYZ: -7.500 / 78.00000 / 5.688",
            shift("XYZ: 128.500 / -102.00000 / -64.312", CENTRED));
    }

    @Test
    @DisplayName("Block moves all three and recomputes every section-local figure")
    void centresBlock() {
        // 128 → -8: local X floorMod(-8, 16) = 8. -64 → 6: local Z 6.
        assertEquals("Block: -8 78 6 [8 14 6]",
            shift("Block: 128 -102 -64 [0 10 0]", CENTRED));
    }

    @Test
    @DisplayName("Chunk rebuilds chunk, local and region figures across negative boundaries")
    void centresChunk() {
        // Block -8, 78, 6: chunk -1 / section 4 / chunk 0, local 8 6, region r.-1.0.
        assertEquals("Chunk: -1 4 0 [8 6 in r.-1.0.mca]",
            shift("Chunk: 8 -7 -4 [0 0 in r.0.-1.mca]", CENTRED));
    }

    @Test
    @DisplayName("a Chunk line of unknown shape is left true when X or Z would move")
    void unknownChunkShapeTellsTheTruth() {
        String odd = "Chunk: 8 -7 -4 [something new]";
        assertEquals(odd, shift(odd, CENTRED));
        assertEquals("Chunk: 8 4 -4 [something new]", shift(odd, SHIFT));
    }

    @Test
    @DisplayName("chunk-relative and targeted lines centre too")
    void centresRelativeAndTargeted() {
        assertEquals("Chunk-relative: 8 14 6", shift("Chunk-relative: 0 10 0", CENTRED));
        assertEquals("§nTargeted Block: -7, 77, 5",
            shift("§nTargeted Block: 129, -103, -65", CENTRED));
    }

    @Test
    @DisplayName("standing at the room's centre reads 0, 0")
    void centreReadsZero() {
        assertEquals("Block: 0 78 0 [0 14 0]",
            DebugScreenDepthLines.shift("Block: 136 -102 -70 [8 10 10]", 136, BLOCK_Y, -70, CENTRED));
    }
}
