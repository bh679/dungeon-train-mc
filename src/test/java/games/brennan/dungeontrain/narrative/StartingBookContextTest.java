package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("achievementSetId partitions dimension-routed vs lifecycle contexts")
    void achievementSetIdPartitionsDimensionRoutedContexts() {
        // Exactly NETHER/END declare a set id — this is the predicate
        // AchievementEvents uses (both for the "Welcome Back" title check and
        // the grand-slam check) to tell dimension-routed folders, which
        // require a starting dimension no longer selectable at world
        // creation (Nether/End dropped from #minecraft:normal — see #639),
        // apart from the always-reachable lifecycle folders.
        Set<StartingBookContext> dimensionRouted = EnumSet.noneOf(StartingBookContext.class);
        for (StartingBookContext ctx : StartingBookContext.values()) {
            if (ctx.achievementSetId().isPresent()) dimensionRouted.add(ctx);
        }
        assertEquals(EnumSet.of(StartingBookContext.NETHER, StartingBookContext.END), dimensionRouted);

        for (StartingBookContext ctx : EnumSet.of(StartingBookContext.DEFAULT,
                StartingBookContext.NEW_WORLD, StartingBookContext.JOINED_WORLD, StartingBookContext.RESPAWN)) {
            assertTrue(ctx.achievementSetId().isEmpty(),
                ctx + " is a lifecycle folder and must stay reachable for \"Welcome Back\"");
        }
    }
}
