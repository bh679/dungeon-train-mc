package games.brennan.dungeontrain.portal;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Severing is recorded once, against the pair.
 *
 * <p>It used to be two entries — the broken corridor and its partner — asked with whichever index
 * the caller happened to hold. Two records kept in step by arithmetic is a shape that can fall out
 * of step, and when it did the entrance was dead while the exit went on taking people in. These pin
 * the one-key shape, that a world saved under the old one still reads correctly, and that a repair
 * really does forget the pair.</p>
 */
final class PortalRegistrySeveredPairsTest {

    /** The default group: entry, cart, exit. */
    private static final int GROUP = 3;

    private static final String TAG_SEVERED = "severed";

    private static PortalRegistry withSevered(int... carriageIndices) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(TAG_SEVERED, carriageIndices);
        return PortalRegistry.load(tag);
    }

    @Test
    @DisplayName("a world saved under the old two-index shape reads back as one pair")
    void legacyPairCollapsesToItsAnchor() {
        int anchor = 24;
        PortalRegistry registry = withSevered(anchor, anchor + PortalCarriageSelection.SLOT_EXIT);

        assertEquals(List.of(anchor), registry.severed(),
            "the partner is dropped — it is a second name for the pair the anchor already names");
        assertTrue(registry.isPairSevered(anchor));
    }

    @Test
    @DisplayName("both corridors of a severed pair answer alike, because both resolve to its key")
    void bothEndsAnswerAlike() {
        int anchor = -9;                                   // behind the origin: floorMod territory
        PortalRegistry registry = withSevered(anchor, anchor + PortalCarriageSelection.SLOT_EXIT);

        int entry = anchor + PortalCarriageSelection.SLOT_ENTRY;
        int exit = anchor + PortalCarriageSelection.SLOT_EXIT;
        assertTrue(registry.isPairSevered(PortalCarriageRole.entryIndexOf(entry, GROUP)));
        assertTrue(registry.isPairSevered(PortalCarriageRole.entryIndexOf(exit, GROUP)));
    }

    @Test
    @DisplayName("severing is idempotent, so the effects fire once")
    void severingTwiceIsOneRecord() {
        PortalRegistry registry = withSevered();
        assertTrue(registry.severPair(12), "the first break is news");
        assertFalse(registry.severPair(12), "a second hole in the same pair is not");
        assertEquals(List.of(12), registry.severed());
    }

    @Test
    @DisplayName("a repair forgets the pair — and says so only when there was something to forget")
    void repairClearsThePair() {
        PortalRegistry registry = withSevered(24, 26);

        assertTrue(registry.repairPair(24), "the pair was severed until now");
        assertFalse(registry.isPairSevered(24));
        assertFalse(registry.repairPair(24), "nothing left to repair, so nothing to log");
        assertTrue(registry.severed().isEmpty());
    }

    @Test
    @DisplayName("a repair is per pair: the one beside it is left alone")
    void repairIsPerPair() {
        PortalRegistry registry = withSevered(24, 33);

        registry.repairPair(24);
        assertFalse(registry.isPairSevered(24));
        assertTrue(registry.isPairSevered(33), "a different group's portal is not this one's to fix");
    }
}
