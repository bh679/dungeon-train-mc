package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The rename rewrites printed text, so what it guards is never touching a line it did not recognise. */
class DebugScreenDimensionLineTest {

    private static final String REAL = "minecraft:overworld";

    @Test
    @DisplayName("names the band and keeps the forced-chunk count")
    void renamesTheDimensionLine() {
        List<String> out = DebugScreenDimensionLine.renamed(
            List.of("Minecraft 1.21.1", REAL + " FC: 12", "XYZ: 1.000 / 2.00000 / 3.000"),
            REAL, "dungeontrain:spheres");
        assertEquals(List.of("Minecraft 1.21.1", "dungeontrain:spheres FC: 12", "XYZ: 1.000 / 2.00000 / 3.000"), out);
    }

    @Test
    @DisplayName("leaves the input list itself alone")
    void doesNotMutateTheInput() {
        List<String> lines = new java.util.ArrayList<>(List.of(REAL + " FC: 0"));
        DebugScreenDimensionLine.renamed(lines, REAL, "dungeontrain:spheres");
        assertEquals(List.of(REAL + " FC: 0"), lines);
    }

    @Test
    @DisplayName("nothing to say returns the same list")
    void noNameIsANoOp() {
        List<String> lines = List.of(REAL + " FC: 0");
        assertSame(lines, DebugScreenDimensionLine.renamed(lines, REAL, ""));
        assertSame(lines, DebugScreenDimensionLine.renamed(lines, REAL, null));
        assertSame(lines, DebugScreenDimensionLine.renamed(lines, REAL, REAL));
    }

    @Test
    @DisplayName("a line that is not vanilla's exact shape tells the truth")
    void unknownShapesPassThrough() {
        for (String line : List.of(REAL, REAL + " FC: ", REAL + " FC: 1 extra", "minecraft:the_end FC: 0", "")) {
            List<String> lines = List.of(line);
            assertSame(lines, DebugScreenDimensionLine.renamed(lines, REAL, "dungeontrain:spheres"), line);
        }
    }
}
