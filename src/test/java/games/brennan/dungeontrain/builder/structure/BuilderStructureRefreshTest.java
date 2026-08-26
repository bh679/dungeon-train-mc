package games.brennan.dungeontrain.builder.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three states of the refresh control, and the round trip through world data. */
final class BuilderStructureRefreshTest {

    @Test
    @DisplayName("Only Instant reads the open build; the other two read the store")
    void onlyInstantSubstitutes() {
        assertTrue(BuilderStructureRefresh.INSTANT.substitutes());
        assertFalse(BuilderStructureRefresh.ON_SAVE.substitutes());
        assertFalse(BuilderStructureRefresh.NEVER.substitutes());
    }

    @Test
    @DisplayName("A save re-reads for every state but Never, whose whole content is not to")
    void neverIsTheOnlyOneASaveDoesNotReach() {
        assertTrue(BuilderStructureRefresh.INSTANT.refreshesOnSave());
        assertTrue(BuilderStructureRefresh.ON_SAVE.refreshesOnSave());
        assertFalse(BuilderStructureRefresh.NEVER.refreshesOnSave());
    }

    @Test
    @DisplayName("Cycling visits every state and comes back")
    void cyclingIsAClosedLoop() {
        Set<BuilderStructureRefresh> seen = new HashSet<>();
        BuilderStructureRefresh refresh = BuilderStructureRefresh.DEFAULT;
        for (int i = 0; i < BuilderStructureRefresh.values().length; i++) {
            assertTrue(seen.add(refresh), "cycle repeated a state before visiting them all");
            refresh = refresh.next();
        }
        assertEquals(BuilderStructureRefresh.DEFAULT, refresh, "the cycle has to close");
        assertEquals(BuilderStructureRefresh.values().length, seen.size());
    }

    @Test
    @DisplayName("Instant is the default, because a room's copies were live before there was a control")
    void instantIsTheDefault() {
        assertEquals(BuilderStructureRefresh.INSTANT, BuilderStructureRefresh.DEFAULT);
        // A world saved before this existed has no token at all, and its rooms were following the
        // build. Coming up on any other state would be the upgrade quietly turning that off.
        assertEquals(BuilderStructureRefresh.DEFAULT, BuilderStructureRefresh.orDefault(null));
        assertEquals(BuilderStructureRefresh.DEFAULT, BuilderStructureRefresh.orDefault(""));
        assertEquals(BuilderStructureRefresh.DEFAULT, BuilderStructureRefresh.orDefault("nonsense"));
    }

    @Test
    @DisplayName("Ids round-trip, and the label key is built from the id")
    void idsRoundTrip() {
        for (BuilderStructureRefresh refresh : BuilderStructureRefresh.values()) {
            assertEquals(refresh, BuilderStructureRefresh.fromId(refresh.id()).orElseThrow());
            assertEquals(refresh, BuilderStructureRefresh.fromId(refresh.id().toUpperCase())
                    .orElseThrow());
            assertEquals(refresh, BuilderStructureRefresh.fromId(" " + refresh.id() + " ")
                    .orElseThrow());
            assertTrue(refresh.labelKey().endsWith(refresh.id()));
        }
    }

    @Test
    @DisplayName("The refresh keys do not share a prefix with the structures control's")
    void labelKeysDoNotReadAsOneControl() {
        // Two rows sitting on top of each other, answering different questions. Sharing a prefix
        // would have a translator reading them as one setting's states.
        for (BuilderStructureRefresh refresh : BuilderStructureRefresh.values()) {
            assertFalse(refresh.labelKey().startsWith("gui.dungeontrain.builder.structures."),
                    "refresh label key collides with the structure mode's namespace");
        }
    }
}
