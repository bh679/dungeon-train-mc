package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.track.variant.VisitRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ordered sub-variants through the real pick, {@link TrackVariantRegistry#pickName} with a visits
 * view: worlds without visit rules keep their rooms, and a ruled group serves its lobby first.
 */
final class PortalRoomOrderedPickTest {

    private static final TrackKind KIND = TrackKind.PORTAL_ROOM;
    private static final int PAIRS = 400;

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
        TrackVariantWeights.clear();
        TrackVariantGroupStore.injectForTesting(KIND, TrackKind.DEFAULT_NAME, TrackVariantGroup.EMPTY);
        for (String n : List.of("crma", "cmra_lobby", "cmra_hallway_basic", "cmra_hallway_alcove_01")) {
            TrackVariantRegistry.register(KIND, n);
        }
    }

    private static TrackVariantGroup cmra(boolean ruled) {
        VisitRules lobby = ruled ? VisitRules.NONE.withOrder(1).withSeenAgain(false) : VisitRules.NONE;
        VisitRules hall = ruled ? VisitRules.NONE.withOrder(2) : VisitRules.NONE;
        return new TrackVariantGroup(2, List.of(
            new TrackVariantGroup.Member("cmra_lobby", 1).withVisits(lobby),
            new TrackVariantGroup.Member("cmra_hallway_basic", 5).withVisits(hall),
            new TrackVariantGroup.Member("cmra_hallway_alcove_01", 3).withVisits(hall)));
    }

    @Test
    @DisplayName("no visits view, or a group without rules: the pick is exactly the old one")
    void unruledPickUnchanged() {
        TrackVariantGroupStore.injectForTesting(KIND, "crma", cmra(false));
        for (int pair = 0; pair < PAIRS; pair++) {
            String legacy = TrackVariantRegistry.pickName(KIND, 99L, pair, null, null);
            assertEquals(legacy, TrackVariantRegistry.pickName(KIND, 99L, pair, null, null, null));
            assertEquals(legacy, TrackVariantRegistry.pickName(KIND, 99L, pair, null, null, id -> 3));
        }
        TrackVariantGroupStore.injectForTesting(KIND, "crma", cmra(true));
        for (int pair = 0; pair < PAIRS; pair++) {
            assertEquals(TrackVariantRegistry.pickName(KIND, 99L, pair, null, null),
                TrackVariantRegistry.pickName(KIND, 99L, pair, null, null, null));
        }
    }

    @Test
    @DisplayName("a ruled group: lobby first, then only hallways, and the lobby never again")
    void ruledGroupServesLobbyFirst() {
        TrackVariantGroupStore.injectForTesting(KIND, "crma", cmra(true));
        assertEquals(Set.of("cmra_lobby"), cmraPicks(Map.of()));
        assertEquals(Set.of("cmra_hallway_basic", "cmra_hallway_alcove_01"), cmraPicks(Map.of("cmra_lobby", 1)));
        Set<String> later = cmraPicks(Map.of("cmra_lobby", 1, "cmra_hallway_basic", 2));
        assertEquals(Set.of("crma", "cmra_hallway_basic", "cmra_hallway_alcove_01"), later);
    }

    /** Every pick that lands in the crma group, for a player with {@code counts}. */
    private static Set<String> cmraPicks(Map<String, Integer> counts) {
        Set<String> out = new HashSet<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            String name = TrackVariantRegistry.pickName(KIND, 7L, pair, null, null,
                id -> counts.getOrDefault(id, 0));
            if (name.startsWith("cmra") || name.equals("crma")) out.add(name);
        }
        return out;
    }
}
