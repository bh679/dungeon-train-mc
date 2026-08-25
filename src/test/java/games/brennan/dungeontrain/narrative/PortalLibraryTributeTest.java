package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The note left on a library room's lectern. */
class PortalLibraryTributeTest {

    @Test
    @DisplayName("There are twenty ways of saying it, and they are all different")
    void twentyDistinctVariants() {
        assertEquals(20, PortalLibraryTribute.variantCount());
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 4000; seed++) {
            seen.add(PortalLibraryTribute.variantFor(seed));
        }
        assertEquals(20, seen.size(), "every variant should be reachable, and none repeated as another");
        for (String variant : seen) assertFalse(variant.isBlank());
    }

    @Test
    @DisplayName("The page names the author under the note, on its own line")
    void thePageSignsItself() {
        String page = PortalLibraryTribute.pageFor("Faulthurst", 7L);
        assertTrue(page.endsWith("\n\n* Faulthurst"), page);
        assertTrue(page.length() > "\n\n* Faulthurst".length(), "the note itself must be there too");
    }

    @Test
    @DisplayName("A room whose author has no name still reads as a sentence")
    void anUnnamedAuthorStillReads() {
        // The relay can hand back an author whose signature is blank — a book signed with nothing.
        // "* " on its own would read as a mistake rather than as a fact about the author.
        for (String blank : new String[]{null, "", "   "}) {
            String page = PortalLibraryTribute.pageFor(blank, 3L);
            assertTrue(page.endsWith("* an unknown hand"), page);
        }
    }

    @Test
    @DisplayName("The same room gets the same note every time it is stamped")
    void theNoteIsStablePerRoom() {
        for (long seed = 0; seed < 50; seed++) {
            String first = PortalLibraryTribute.variantFor(seed);
            for (int again = 0; again < 5; again++) {
                assertEquals(first, PortalLibraryTribute.variantFor(seed));
            }
        }
    }

    @Test
    @DisplayName("Consecutive rooms do not walk the list in order")
    void consecutiveRoomsDoNotReadAlike() {
        // Pair keys are consecutive carriage indices, so an unmixed seed would hand three rooms in a
        // row variants 1, 2, 3 — which reads as a list rather than as a coincidence.
        int inOrder = 0;
        for (long seed = 0; seed < 200; seed++) {
            int a = indexOf(PortalLibraryTribute.variantFor(seed));
            int b = indexOf(PortalLibraryTribute.variantFor(seed + 1));
            if (b == (a + 1) % PortalLibraryTribute.variantCount()) inOrder++;
        }
        assertTrue(inOrder < 30, "variants walked in order " + inOrder + " times out of 200");
    }

    private static int indexOf(String variant) {
        for (long seed = 0; seed < 4000; seed++) {
            if (PortalLibraryTribute.variantFor(seed).equals(variant)) return (int) (seed % 20);
        }
        return -1;
    }
}
