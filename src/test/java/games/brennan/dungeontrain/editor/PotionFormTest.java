package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Bottle-form ids round-trip, unknown ids fall back to Any, and the click cycle wraps. */
final class PotionFormTest {

    @Test
    void ids_roundTrip_andUnknownFallsBackToAny() {
        for (PotionForm f : PotionForm.values()) {
            assertEquals(f, PotionForm.parse(f.id()));
            assertEquals(f, PotionForm.byOrdinal(f.ordinal()));
        }
        assertEquals(PotionForm.ANY, PotionForm.parse(null));
        assertEquals(PotionForm.ANY, PotionForm.parse("bogus"));
        assertEquals(PotionForm.ANY, PotionForm.byOrdinal(-1));
        assertEquals(PotionForm.ANY, PotionForm.byOrdinal(99));
        assertNull(PotionForm.ANY.item());
    }

    @Test
    void cycle_wrapsThroughAllFour() {
        assertEquals(PotionForm.POTION, PotionForm.ANY.next());
        assertEquals(PotionForm.SPLASH, PotionForm.POTION.next());
        assertEquals(PotionForm.LINGERING, PotionForm.SPLASH.next());
        assertEquals(PotionForm.ANY, PotionForm.LINGERING.next());
    }

    @Test
    void entry_defaultsToAny_andCarriesFormThroughCopies() {
        ContainerContentsEntry e = new ContainerContentsEntry(
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "random_potion"), 1, 1);
        assertEquals(PotionForm.ANY, e.potionForm());
        ContainerContentsEntry splash = e.cyclePotionForm().cyclePotionForm();
        assertEquals(PotionForm.SPLASH, splash.potionForm());
        assertEquals(PotionForm.SPLASH, splash.withWeight(4).potionForm());
        assertEquals(PotionForm.SPLASH, splash.withScaleWithDistance(false).potionForm());
        assertEquals(PotionForm.SPLASH, splash.withPotion(null).potionForm());
        assertEquals(PotionForm.SPLASH, splash.cycleSlotOverride().potionForm());
    }
}
