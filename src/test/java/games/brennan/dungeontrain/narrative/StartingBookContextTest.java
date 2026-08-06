package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link StartingBookContext} routing helpers:
 * <ul>
 *   <li>{@link StartingBookContext#forDimensionNbtId} maps the Nether/End
 *       starting-dimension nbt ids to their dimension-routed contexts, and
 *       everything else (Overworld, unknown, null) to empty.</li>
 *   <li>The new NETHER/END constants round-trip through
 *       {@link StartingBookContext#fromString} in both folder-name and
 *       enum-name forms, and expose the expected folder names.</li>
 * </ul>
 *
 * <p>Pure — no server / Minecraft bootstrap needed. The nbt-id string
 * literals mirror {@code StartingDimension.NETHER.nbtId()} /
 * {@code END.nbtId()} ({@code "the_nether"} / {@code "the_end"}).</p>
 */
final class StartingBookContextTest {

    @Test
    @DisplayName("forDimensionNbtId maps the_nether → NETHER")
    void dimensionNether() {
        assertEquals(Optional.of(StartingBookContext.NETHER),
            StartingBookContext.forDimensionNbtId("the_nether"));
    }

    @Test
    @DisplayName("forDimensionNbtId maps the_end → END")
    void dimensionEnd() {
        assertEquals(Optional.of(StartingBookContext.END),
            StartingBookContext.forDimensionNbtId("the_end"));
    }

    @Test
    @DisplayName("forDimensionNbtId returns empty for overworld / unknown / null")
    void dimensionFallthrough() {
        assertTrue(StartingBookContext.forDimensionNbtId("overworld").isEmpty(),
            "overworld → no dimension override (lifecycle context wins)");
        assertTrue(StartingBookContext.forDimensionNbtId("the_overworld").isEmpty());
        assertTrue(StartingBookContext.forDimensionNbtId("nether").isEmpty(),
            "folder-name form is NOT an nbt id — must not match");
        assertTrue(StartingBookContext.forDimensionNbtId("garbage").isEmpty());
        assertTrue(StartingBookContext.forDimensionNbtId("").isEmpty());
        assertTrue(StartingBookContext.forDimensionNbtId(null).isEmpty());
    }

    @Test
    @DisplayName("fromString round-trips the NETHER/END folder + enum names")
    void fromStringRoundTrip() {
        assertEquals(Optional.of(StartingBookContext.NETHER), StartingBookContext.fromString("nether"));
        assertEquals(Optional.of(StartingBookContext.END), StartingBookContext.fromString("end"));
        // Case-insensitive enum-name form.
        assertEquals(Optional.of(StartingBookContext.NETHER), StartingBookContext.fromString("NETHER"));
        assertEquals(Optional.of(StartingBookContext.END), StartingBookContext.fromString(" End "));
    }

    @Test
    @DisplayName("NETHER/END expose their content sub-folder names")
    void folderNames() {
        assertEquals("nether", StartingBookContext.NETHER.folderName());
        assertEquals("end", StartingBookContext.END.folderName());
    }

    @Test
    @DisplayName("achievementSetId partitions specially-routed vs lifecycle contexts")
    void achievementSetIdPartitionsDimensionRoutedContexts() {
        // A set id marks a folder that is NOT delivered by the ordinary lifecycle welcome — this is
        // the predicate AchievementEvents uses (both for the "Welcome Back" title check and the
        // grand-slam check) to keep such folders from holding those milestones hostage. Two kinds
        // qualify: dimension-routed (NETHER/END — their starting dimension is no longer selectable
        // at world creation, see #639) and the cursed folders (delivered only when one of this
        // player's Death Note curses lands in someone else's world).
        Set<StartingBookContext> speciallyRouted = EnumSet.noneOf(StartingBookContext.class);
        for (StartingBookContext ctx : StartingBookContext.values()) {
            if (ctx.achievementSetId().isPresent()) speciallyRouted.add(ctx);
        }
        assertEquals(EnumSet.of(StartingBookContext.NETHER, StartingBookContext.END,
            StartingBookContext.CURSED, StartingBookContext.CURSED_FULFILLED,
            StartingBookContext.CURSED_DEFIED), speciallyRouted);

        for (StartingBookContext ctx : EnumSet.of(StartingBookContext.DEFAULT,
                StartingBookContext.NEW_WORLD, StartingBookContext.JOINED_WORLD, StartingBookContext.RESPAWN)) {
            assertTrue(ctx.achievementSetId().isEmpty(),
                ctx + " is a lifecycle folder and must stay reachable for \"Welcome Back\"");
        }
    }

    @Test
    @DisplayName("the three cursed folders share one set id and are the only isCursed() contexts")
    void cursedContexts() {
        // One shared id: the cursed folders are three endings of the same thing, not three goals.
        for (StartingBookContext ctx : EnumSet.of(StartingBookContext.CURSED,
                StartingBookContext.CURSED_FULFILLED, StartingBookContext.CURSED_DEFIED)) {
            assertEquals(Optional.of("cursed_starting_books"), ctx.achievementSetId());
            assertTrue(ctx.isCursed(), ctx + " must be recognised as a cursed pool");
        }
        for (StartingBookContext ctx : EnumSet.of(StartingBookContext.DEFAULT, StartingBookContext.RESPAWN,
                StartingBookContext.NETHER, StartingBookContext.END)) {
            assertFalse(ctx.isCursed(), ctx + " is not a cursed pool");
        }
    }

    @Test
    @DisplayName("cursed folder names parse back, so /narrative startingbook fire can preview them")
    void cursedFolderNames() {
        assertEquals("cursed", StartingBookContext.CURSED.folderName());
        assertEquals("cursed_fulfilled", StartingBookContext.CURSED_FULFILLED.folderName());
        assertEquals("cursed_defied", StartingBookContext.CURSED_DEFIED.folderName());
        assertEquals(Optional.of(StartingBookContext.CURSED_DEFIED),
            StartingBookContext.fromString("cursed_defied"));
        assertEquals(Optional.of(StartingBookContext.CURSED_FULFILLED),
            StartingBookContext.fromString("CURSED_FULFILLED"));
    }
}
