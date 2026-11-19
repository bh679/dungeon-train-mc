package games.brennan.dungeontrain.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

    private static final ResourceLocation MINER = ResourceLocation.parse("luminous_monsters:miner_zombie");
    private static final ResourceLocation HUSK = ResourceLocation.parse("minecraft:husk");
    private static final ResourceLocation CREEPER = ResourceLocation.parse("minecraft:creeper");

    @Test
    @DisplayName("a ticked mob with no photo of its own borrows the fallback album's; its own photo always wins")
    void fallbackFillsOnlyMissingTickedEntries() {
        var ownHusk = new EarnedPhotos.Entry(HUSK, Path.of("family_photo/husk.png"));
        var wantedHusk = new EarnedPhotos.Entry(HUSK, Path.of("most_wanted/husk.png"));
        var wantedMiner = new EarnedPhotos.Entry(MINER, Path.of("most_wanted/miner.png"));
        var wantedCreeper = new EarnedPhotos.Entry(CREEPER, Path.of("most_wanted/creeper.png"));
        Set<ResourceLocation> ticked = Set.of(HUSK, MINER);

        List<EarnedPhotos.Entry> out = EarnedPhotos.withFallback(
                List.of(ownHusk), List.of(wantedHusk, wantedMiner, wantedCreeper), ticked::contains);

        assertEquals(List.of(ownHusk, wantedMiner), out);
    }

    @Test
    @DisplayName("nothing to borrow leaves the album as it was")
    void fallbackWithNothingToAdd() {
        var own = List.of(new EarnedPhotos.Entry(HUSK, Path.of("family_photo/husk.png")));
        assertSame(own, EarnedPhotos.withFallback(own, List.of(), id -> true));
    }

    @Test
    @DisplayName("a slot shows its photo, else its name once ticked, else ???")
    void slots() {
        var photo = new EarnedPhotos.Entry(MINER, Path.of("most_wanted/miner.png"));
        assertSame(photo, EarnedPhotos.slot(photo, MINER, true));
        assertEquals(new EarnedPhotos.Entry(MINER, null), EarnedPhotos.slot(null, MINER, true));
        assertEquals(new EarnedPhotos.Entry(null, null), EarnedPhotos.slot(null, MINER, false));
    }
}
