package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Train beds only, and the train's band place a nap keeps (old files without one still read). */
final class NapTrainPlaceTest {

    private static NapSnapshot nap() {
        CompoundTag player = new CompoundTag();
        player.putInt("XpLevel", 12);
        return new NapSnapshot(UUID.randomUUID(), 99L, "0.1850.0", "World 1700000000000", player,
            -40_321.25, -1176, "Nether (Better Nether)", -1, 1200, false, "normal");
    }

    @Test
    @DisplayName("only a DT carriage group's plot is a train bed")
    void trainCarriageRule() {
        assertTrue(NapEligibility.isTrainCarriage(true, true));
        assertFalse(NapEligibility.isTrainCarriage(true, false), "another mod's sub-level");
        assertFalse(NapEligibility.isTrainCarriage(false, false), "a world bed");
    }

    @Test
    @DisplayName("a nap with a band place survives NBT unchanged")
    void newFormatRoundTrip() {
        NapSnapshot n = nap().withPlace(new NapSnapshot.Place(
            new WorldGenCycle.BandPosition(true, 1, 4, 2_345L), 120L, -60_000L, 9_000L, 6_400L));
        assertEquals(n, NapSnapshot.fromTag(n.toTag()));
        NapSnapshot bound = n.boundTo("World 1800000000000");
        assertEquals(n.place(), bound.place(), "binding keeps the place");
        assertEquals(bound, NapSnapshot.fromTag(bound.toTag()));
    }

    @Test
    @DisplayName("a nap saved before band places reads with none, and wakes by X")
    void oldFormatReads() {
        CompoundTag tag = nap().toTag();
        assertFalse(tag.contains("place"));
        NapSnapshot read = NapSnapshot.fromTag(tag);
        assertNull(read.place());
        assertEquals(-40_321.25, read.x());
    }

    @Test
    @DisplayName("a damaged place reads as none rather than a wrong band")
    void damagedPlace() {
        CompoundTag tag = nap().withPlace(new NapSnapshot.Place(
            new WorldGenCycle.BandPosition(false, 0, 2, 10L), 0L, 0L, 0L, 0L)).toTag();
        tag.getCompound("place").remove("slot");
        assertNull(NapSnapshot.fromTag(tag).place());
    }
}
