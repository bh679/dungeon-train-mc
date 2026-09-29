package games.brennan.dungeontrain.block.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Accent picking: first non-neutral concrete slot, then the next, falling back to the background. */
final class StageAccentColourTest {

    private static List<String> concrete(String... dyes) {
        return java.util.Arrays.stream(dyes).map(d -> "minecraft:" + d + "_concrete").toList();
    }

    @Test
    @DisplayName("a colourful primary is the accent; the secondary is the second accent")
    void primaryThenSecondary() {
        List<String> c = concrete("red", "light_blue", "black");
        assertEquals("red", StageAccentColour.accent(c, 0));
        assertEquals("light_blue", StageAccentColour.accent(c, 1));
    }

    @Test
    @DisplayName("a neutral primary is skipped for the secondary, then the background")
    void skipsNeutrals() {
        assertEquals("orange", StageAccentColour.accent(concrete("black", "orange", "cyan"), 0));
        assertEquals("cyan", StageAccentColour.accent(concrete("black", "orange", "cyan"), 1));
        assertEquals("green", StageAccentColour.accent(concrete("gray", "green", "black"), 0));
        assertEquals("cyan", StageAccentColour.accent(concrete("cyan", "gray", "black"), 0));
    }

    @Test
    @DisplayName("with only one colourful slot both accents are that colour")
    void singleColour() {
        List<String> c = concrete("pink", "black", "white");
        assertEquals("pink", StageAccentColour.accent(c, 0));
        assertEquals("pink", StageAccentColour.accent(c, 1));
    }

    @Test
    @DisplayName("all-neutral concrete falls back to the background colour")
    void allNeutral() {
        List<String> c = concrete("white", "light_gray", "gray");
        assertEquals("gray", StageAccentColour.accent(c, 0));
        assertEquals("gray", StageAccentColour.accent(c, 1));
    }

    @Test
    @DisplayName("a non-concrete id reads as white")
    void nonConcrete() {
        assertEquals("white", StageAccentColour.dyeOf("minecraft:stone"));
        assertEquals("white", StageAccentColour.dyeOf(null));
    }
}
