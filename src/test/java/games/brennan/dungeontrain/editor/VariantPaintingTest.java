package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link VariantPainting} — the sidecar / wire token and the size rules. */
final class VariantPaintingTest {

    private static final ResourceLocation KEBAB = ResourceLocation.withDefaultNamespace("kebab");

    @Test
    @DisplayName("tokens round-trip through id() and parse()")
    void tokensRoundTrip() {
        for (VariantPainting p : new VariantPainting[] {
            VariantPainting.ANY,
            VariantPainting.of(2, 1, null),
            VariantPainting.of(4, 3, ResourceLocation.withDefaultNamespace("skeleton")),
            VariantPainting.of(0, 0, KEBAB),
        }) {
            assertEquals(p, VariantPainting.parse(p.id()), p.id());
        }
        assertEquals("any random", VariantPainting.ANY.id());
        assertEquals("2x1 minecraft:kebab", VariantPainting.of(2, 1, KEBAB).id());
    }

    @Test
    @DisplayName("blank parses to off; malformed tokens parse to null")
    void parseEdges() {
        assertSame(VariantPainting.NONE, VariantPainting.parse(""));
        assertSame(VariantPainting.NONE, VariantPainting.parse(null));
        assertNull(VariantPainting.parse("big random"));
        assertNull(VariantPainting.parse("0x1 random"));
        assertNull(VariantPainting.parse("17x1 random"));
        assertNull(VariantPainting.parse("2x1"));
        assertNull(VariantPainting.parse("2x1 random extra"));
    }

    @Test
    @DisplayName("off is the default; on is not")
    void defaultIsOff() {
        assertTrue(VariantPainting.NONE.isDefault());
        assertFalse(VariantPainting.ANY.isDefault());
    }

    @Test
    @DisplayName("Any size matches every size; a fixed size only its own")
    void sizeMatches() {
        assertTrue(VariantPainting.ANY.sizeMatches(4, 4));
        assertTrue(VariantPainting.of(2, 1, null).sizeMatches(2, 1));
        assertFalse(VariantPainting.of(2, 1, null).sizeMatches(1, 2));
    }
}
