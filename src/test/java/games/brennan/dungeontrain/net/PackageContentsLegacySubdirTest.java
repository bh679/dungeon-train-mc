package games.brennan.dungeontrain.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages are shared between players as zips, so one authored before the portal-room →
 * dimensional-carriage rename can arrive at any time — long after the server-start migration
 * that would have normalised it. The contents pane therefore has to recognise the old slug.
 */
final class PackageContentsLegacySubdirTest {

    private static PackageContents.Section carriages() {
        return PackageContents.SECTIONS.stream()
            .filter(s -> "portals/dimensional_carriage".equals(s.subdir()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no dimensional-carriage section"));
    }

    @Test
    @DisplayName("the dimensional-carriage section still knows its pre-rename slug")
    void declaresLegacySlug() {
        PackageContents.Section section = carriages();
        assertEquals("portals/room", section.legacySubdir());
        assertEquals("Dimensional Carriages", section.label());
        assertEquals("PORTALS", section.category(),
            "the editor category id survived the rename and is what teleport keys off");
    }

    @Test
    @DisplayName("matches() accepts both the current and the pre-rename slug")
    void matchesBothSlugs() {
        PackageContents.Section section = carriages();
        assertTrue(section.matches("portals/dimensional_carriage"));
        assertTrue(section.matches("portals/room"));
        assertFalse(section.matches("portals"));
        assertFalse(section.matches("templates"));
    }

    @Test
    @DisplayName("sections whose slug never changed declare no legacy slug")
    void unchangedSectionsHaveNoLegacySlug() {
        for (PackageContents.Section s : PackageContents.SECTIONS) {
            if ("portals/dimensional_carriage".equals(s.subdir())) continue;
            assertNull(s.legacySubdir(),
                s.subdir() + " should not claim a legacy slug it never had");
            assertTrue(s.matches(s.subdir()));
        }
    }

    @Test
    @DisplayName("every section still has a label and a slug")
    void sectionsAreWellFormed() {
        for (PackageContents.Section s : PackageContents.SECTIONS) {
            assertNotNull(s.label(), "label");
            assertNotNull(s.subdir(), "subdir");
            assertFalse(s.label().isBlank(), "label blank for " + s.subdir());
        }
    }
}
