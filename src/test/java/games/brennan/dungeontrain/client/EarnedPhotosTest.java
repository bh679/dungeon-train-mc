package games.brennan.dungeontrain.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Album file names round-trip to the entity or biome they were kept for. */
final class EarnedPhotosTest {

    @Test
    @DisplayName("an entry's file name reads back as its id")
    void roundTrip() {
        for (String id : new String[] {"minecraft:cow", "biomesoplenty:lavender_field", "minecraft:warm_ocean"}) {
            ResourceLocation rl = ResourceLocation.parse(id);
            assertEquals(rl, EarnedPhotos.entryId(EarnedPhotos.fileName(rl)));
        }
    }

    @Test
    @DisplayName("anything else is not an entry")
    void notEntries() {
        assertNull(EarnedPhotos.entryId("notes.txt"));
        assertNull(EarnedPhotos.entryId("__cow.png"));
    }

    @Test
    @DisplayName("biome photos group by mod, alphabetically")
    void biomeOrderGroupsByMod() {
        var shuffled = new java.util.ArrayList<EarnedPhotos.Entry>();
        for (String id : new String[] {"minecraft:plains", "wythersoverhauled:old_growth_taiga",
                "biomesoplenty:lavender_field", "minecraft:desert", "biomesoplenty:bayou"}) {
            shuffled.add(new EarnedPhotos.Entry(ResourceLocation.parse(id), null));
        }
        shuffled.sort(EarnedPhotos.BIOME_ORDER);
        assertEquals(java.util.List.of("biomesoplenty:bayou", "biomesoplenty:lavender_field",
                        "minecraft:desert", "minecraft:plains", "wythersoverhauled:old_growth_taiga"),
                shuffled.stream().map(e -> e.id().toString()).toList());
    }

    @Test
    void slotsEarnedByOneShotShareOnePhoto() {
        var regular = ResourceLocation.fromNamespaceAndPath("exposure", "regular");
        var a = new EarnedPhotos.Pending("AshSeven_1", regular, 100, 0);
        var b = new EarnedPhotos.Pending("AshSeven_1", regular, 90, 0);
        var other = new EarnedPhotos.Pending("AshSeven_2", regular, 100, 0);
        assertEquals(EarnedPhotos.photoOf(a), EarnedPhotos.photoOf(b));
        assertNotEquals(EarnedPhotos.photoOf(a), EarnedPhotos.photoOf(other));
    }

    @Test
    void missingPaperTypeMeansRegular() {
        var regular = ResourceLocation.fromNamespaceAndPath("exposure", "regular");
        var untyped = new EarnedPhotos.Pending("AshSeven_1", null, 100, 0);
        var typed = new EarnedPhotos.Pending("AshSeven_1", regular, 100, 0);
        assertEquals(EarnedPhotos.photoOf(typed), EarnedPhotos.photoOf(untyped));
    }
}
