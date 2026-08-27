package games.brennan.dungeontrain.builder.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three states of the structure control, and the round trip through world data. */
final class BuilderStructureModeTest {

    @Test
    @DisplayName("Exactly one state stands blocks up, and exactly one draws them")
    void eachStateAnswersItsOwnQuestion() {
        // The two questions are separate on purpose: NONE and GHOST agree that nothing is in the
        // world and disagree about drawing, which is not the server's business.
        assertTrue(BuilderStructureMode.SOLID.stamps());
        assertFalse(BuilderStructureMode.GHOST.stamps());
        assertFalse(BuilderStructureMode.NONE.stamps());

        assertTrue(BuilderStructureMode.GHOST.draws());
        assertFalse(BuilderStructureMode.SOLID.draws(), "solid blocks are already visible");
        assertFalse(BuilderStructureMode.NONE.draws());
    }

    @Test
    @DisplayName("Cycling visits every state and comes back")
    void cyclingIsAClosedLoop() {
        Set<BuilderStructureMode> seen = new HashSet<>();
        BuilderStructureMode mode = BuilderStructureMode.DEFAULT;
        for (int i = 0; i < BuilderStructureMode.values().length; i++) {
            assertTrue(seen.add(mode), "cycle repeated a state before visiting them all");
            mode = mode.next();
        }
        assertEquals(BuilderStructureMode.DEFAULT, mode, "the cycle has to close");
        assertEquals(BuilderStructureMode.values().length, seen.size());
    }

    @Test
    @DisplayName("Ghosts are the default, so nothing you can reach is a block that gets thrown away")
    void ghostIsTheDefault() {
        assertEquals(BuilderStructureMode.GHOST, BuilderStructureMode.DEFAULT);
    }

    @Test
    @DisplayName("Ids round-trip, and anything unrecognised falls back rather than failing")
    void idsRoundTripAndDegradeSafely() {
        for (BuilderStructureMode mode : BuilderStructureMode.values()) {
            assertEquals(mode, BuilderStructureMode.fromId(mode.id()).orElseThrow());
            assertEquals(mode, BuilderStructureMode.fromId(mode.id().toUpperCase()).orElseThrow());
            assertTrue(mode.labelKey().endsWith(mode.id()));
        }
        // A world saved before this existed has no token at all, and must come up showing ghosts
        // rather than standing scenery up in a build somebody left.
        assertEquals(BuilderStructureMode.DEFAULT, BuilderStructureMode.orDefault(null));
        assertEquals(BuilderStructureMode.DEFAULT, BuilderStructureMode.orDefault(""));
        assertEquals(BuilderStructureMode.DEFAULT, BuilderStructureMode.orDefault("not_a_mode"));
        assertTrue(BuilderStructureMode.fromId("not_a_mode").isEmpty());
    }
}
