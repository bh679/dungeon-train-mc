package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The bed carriage's number a nap keeps, which the woken train continues from. */
final class NapCarriageNumberTest {

    private static NapSnapshot nap() {
        return new NapSnapshot(UUID.randomUUID(), 7L, "0.2056.0", "World 1700000000000", new CompoundTag(),
            8_612.5, 251, "Overworld", 1, 251, false, "normal");
    }

    @Test
    @DisplayName("a nap's carriage number survives NBT and every copy")
    void roundTrip() {
        NapSnapshot n = nap().withPIdx(957);
        assertEquals(n, NapSnapshot.fromTag(n.toTag()));
        assertEquals(957, n.boundTo("World 1800000000000").pIdx());
        assertEquals(957, n.withX(9_000).pIdx());
        assertEquals(957, n.asFreePlay().pIdx());
        assertEquals(957, n.withPlace(null).pIdx());
    }

    @Test
    @DisplayName("a nap saved before the number existed reads as unknown and wakes from its X estimate")
    void oldNapFallsBack() {
        CompoundTag tag = nap().toTag();
        NapSnapshot old = NapSnapshot.fromTag(tag);
        assertNull(old.pIdx());
        assertEquals(251, NapWake.pIdxBaseOf(old));
    }

    @Test
    @DisplayName("a known number wins over the X estimate, negative included")
    void knownNumberWins() {
        assertEquals(957, NapWake.pIdxBaseOf(nap().withPIdx(957)));
        assertEquals(-40, NapWake.pIdxBaseOf(nap().withPIdx(-40)));
        assertEquals(0, NapWake.pIdxBaseOf(nap().withPIdx(0)));
    }
}
