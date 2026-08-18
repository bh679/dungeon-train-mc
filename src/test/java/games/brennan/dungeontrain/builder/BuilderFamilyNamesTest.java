package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the carriages of one build are named, and — more importantly — how they are recognised again.
 *
 * <p>Save writes the members and Open looks for them. If the two ever disagree about what counts as a
 * member, a family reopens as copies of its first carriage and the next save writes those copies over
 * the originals. Every case here is one where that could happen.</p>
 */
final class BuilderFamilyNamesTest {

    @Test
    @DisplayName("Slot 0 is the bare name, so a one-carriage save is unchanged")
    void firstSlotIsTheBaseName() {
        assertEquals("cabin", BuilderFamilyNames.memberName("cabin", 0));
        assertEquals("cabin", BuilderFamilyNames.memberName("cabin", -1));
    }

    @Test
    @DisplayName("Later slots are numbered from 2 — the name reads as the carriage's position")
    void laterSlotsAreNumberedFromTwo() {
        assertEquals("cabin_2", BuilderFamilyNames.memberName("cabin", 1));
        assertEquals("cabin_3", BuilderFamilyNames.memberName("cabin", 2));
    }

    @Test
    @DisplayName("Names round-trip: every member resolves back to the slot that wrote it")
    void roundTrips() {
        for (int slot = 0; slot < 8; slot++) {
            String name = BuilderFamilyNames.memberName("cabin", slot);
            assertEquals(slot, BuilderFamilyNames.memberIndex("cabin", name), name);
        }
    }

    @Test
    @DisplayName("A template that merely starts with the base name is not a member")
    void neighboursAreNotMembers() {
        // These are the ones that would be silently overwritten if the match were a prefix test.
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabin_2x"));
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabinet"));
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabin_"));
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabin_old"));
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "other_2"));
        // There is no `_1`: slot 0 is the bare name, so a `_1` is somebody's own template.
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabin_1"));
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabin_0"));
    }

    @Test
    @DisplayName("A number too long to parse is not a member either")
    void absurdNumbersAreNotMembers() {
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", "cabin_99999999999999999999"));
    }

    @Test
    @DisplayName("isExtraMember covers the slots beyond the first, and not the base itself")
    void extraMembers() {
        assertFalse(BuilderFamilyNames.isExtraMember("cabin", "cabin"));
        assertTrue(BuilderFamilyNames.isExtraMember("cabin", "cabin_2"));
        assertFalse(BuilderFamilyNames.isExtraMember("cabin", "cabin_1"));
    }

    @Test
    @DisplayName("An absent name is nobody's member and names nothing")
    void emptyIsInert() {
        assertEquals("", BuilderFamilyNames.memberName("", 2));
        assertEquals("", BuilderFamilyNames.memberName(null, 2));
        assertEquals(-1, BuilderFamilyNames.memberIndex("", "cabin_2"));
        assertEquals(-1, BuilderFamilyNames.memberIndex("cabin", null));
    }
}
