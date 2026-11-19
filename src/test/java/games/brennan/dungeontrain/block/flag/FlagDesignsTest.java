package games.brennan.dungeontrain.block.flag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped design list parses whole, and only uses vanilla 1.21.1 banner patterns. */
class FlagDesignsTest {

    /** Every banner pattern id in vanilla 1.21.1 (data/minecraft/banner_pattern). */
    private static final Set<String> VANILLA_PATTERNS = Set.of(
        "base", "border", "bricks", "circle", "creeper", "cross", "curly_border", "diagonal_left",
        "diagonal_right", "diagonal_up_left", "diagonal_up_right", "flow", "flower", "globe", "gradient",
        "gradient_up", "guster", "half_horizontal", "half_horizontal_bottom", "half_vertical",
        "half_vertical_right", "mojang", "piglin", "rhombus", "skull", "small_stripes",
        "square_bottom_left", "square_bottom_right", "square_top_left", "square_top_right",
        "straight_cross", "stripe_bottom", "stripe_center", "stripe_downleft", "stripe_downright",
        "stripe_left", "stripe_middle", "stripe_right", "stripe_top", "triangle_bottom", "triangle_top",
        "triangles_bottom", "triangles_top");

    @Test
    @DisplayName("All 34 designs load, with unique ids, vanilla patterns and at most 16 layers")
    void designListIsWhole() {
        List<FlagDesigns.Design> designs = FlagDesigns.all();
        assertEquals(34, designs.size());
        Set<String> ids = new HashSet<>();
        for (FlagDesigns.Design d : designs) {
            assertTrue(ids.add(d.id()), "duplicate design id " + d.id());
            assertFalse(d.layers().isEmpty(), "design " + d.id() + " has no layers");
            assertTrue(d.layers().size() <= 16, "design " + d.id() + " has more layers than a banner shows");
            for (FlagDesigns.Layer layer : d.layers()) {
                assertTrue(VANILLA_PATTERNS.contains(layer.pattern()),
                    "design " + d.id() + " uses unknown pattern " + layer.pattern());
            }
        }
    }

    @Test
    @DisplayName("There are more designs than flag numbers, so a roll never has to repeat one")
    void enoughDesignsForEveryFlag() {
        assertTrue(FlagDesigns.all().size() >= FlagPlaceholders.COUNT);
    }
}
