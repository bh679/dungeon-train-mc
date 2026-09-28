package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The debug screen's Y disguise rewrites printed text, so what it is really guarding is that it never
 * touches a line it did not recognise — a vanilla format this does not know about has to come out the
 * far side unchanged rather than mangled.
 *
 * <p>The line shapes here are vanilla 1.21.1's, copied from what {@code DebugScreenOverlay} prints.</p>
 */
class DebugScreenDepthLinesTest {

    /** A room stamped 180 blocks under a train running at 78 — the ordinary basement case. */
    private static final int SHIFT = 180;

    /** The camera's real block Y in that room, which the derived lines are recomputed from. */
    private static final int BLOCK_Y = -102;

    @Test
    @DisplayName("XYZ keeps X and Z and moves only Y")
    void movesOnlyY() {
        assertEquals("XYZ: 128.500 / 78.00000 / -64.312",
            DebugScreenDepthLines.shift("XYZ: 128.500 / -102.00000 / -64.312", BLOCK_Y, SHIFT));
    }

    @Test
    @DisplayName("Block moves the world Y and the section-local Y with it")
    void movesBlockAndLocalY() {
        // -102 → 78: local Y goes from floorMod(-102, 16) = 10 to floorMod(78, 16) = 14.
        assertEquals("Block: 128 78 -64 [0 14 0]",
            DebugScreenDepthLines.shift("Block: 128 -102 -64 [0 10 0]", BLOCK_Y, SHIFT));
    }

    @Test
    @DisplayName("Chunk's section index is recomputed rather than shifted")
    void recomputesSectionIndex() {
        // floorDiv(-102, 16) = -7 becomes floorDiv(78, 16) = 4 — not -7 + 180.
        assertEquals("Chunk: 8 4 -4 [8 12 in r.0.-1.mca]",
            DebugScreenDepthLines.shift("Chunk: 8 -7 -4 [8 12 in r.0.-1.mca]", BLOCK_Y, SHIFT));
    }

    @Test
    @DisplayName("the reduced-info screen's chunk-relative line moves too")
    void movesChunkRelative() {
        assertEquals("Chunk-relative: 0 14 0",
            DebugScreenDepthLines.shift("Chunk-relative: 0 10 0", BLOCK_Y, SHIFT));
    }

    @Test
    @DisplayName("targeted block and fluid lines move, formatting code and all")
    void movesTargeted() {
        assertEquals("§nTargeted Block: 128, 77, -64",
            DebugScreenDepthLines.shift("§nTargeted Block: 128, -103, -64", BLOCK_Y, SHIFT));
        assertEquals("§nTargeted Fluid: 128, 77, -64",
            DebugScreenDepthLines.shift("§nTargeted Fluid: 128, -103, -64", BLOCK_Y, SHIFT));
    }

    @Test
    @DisplayName("an attic room shifts the other way")
    void shiftsDownwards() {
        assertEquals("XYZ: 128.500 / 78.00000 / -64.312",
            DebugScreenDepthLines.shift("XYZ: 128.500 / 300.00000 / -64.312", 300, -222));
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
            assertEquals(line, DebugScreenDepthLines.shift(line, BLOCK_Y, SHIFT));
        }
    }

    @Test
    @DisplayName("a zero shift is not merely a no-op, it allocates nothing")
    void zeroShiftReturnsTheSameList() {
        List<String> lines = List.of("XYZ: 128.500 / -102.00000 / -64.312");
        assertSame(lines, DebugScreenDepthLines.shifted(lines, BLOCK_Y, 0));
    }

    @Test
    @DisplayName("a whole screenful is rewritten in place order")
    void rewritesTheWholeList() {
        List<String> shifted = DebugScreenDepthLines.shifted(List.of(
            "Minecraft 1.21.1",
            "XYZ: 128.500 / -102.00000 / -64.312",
            "Block: 128 -102 -64 [0 10 0]",
            "Chunk: 8 -7 -4 [8 12 in r.0.-1.mca]"), BLOCK_Y, SHIFT);

        assertEquals(List.of(
            "Minecraft 1.21.1",
            "XYZ: 128.500 / 78.00000 / -64.312",
            "Block: 128 78 -64 [0 14 0]",
            "Chunk: 8 4 -4 [8 12 in r.0.-1.mca]"), shifted);
    }
}
