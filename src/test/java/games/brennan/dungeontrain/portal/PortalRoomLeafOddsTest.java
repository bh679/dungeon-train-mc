package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link TrackVariantRegistry#leafOdds} against the pick it describes: the odds it reports must be
 * the odds {@link TrackVariantRegistry#pickName} actually draws at, or the own-books correction
 * built on them ({@link PortalOwnShelves}) is aiming at the wrong number.
 *
 * <p>Fixtures as in {@link PortalRoomSubVariantTest} — injected groups, no filesystem.</p>
 */
final class PortalRoomLeafOddsTest {

    private static final TrackKind KIND = TrackKind.PORTAL_ROOM;
    private static final long SEED = 2026L;
    private static final int PAIRS = 40_000;
    private static final double TOLERANCE = 0.01;

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
        TrackVariantWeights.clear();
        TrackVariantGroupStore.injectForTesting(KIND, TrackKind.DEFAULT_NAME, TrackVariantGroup.EMPTY);
    }

    private static TrackVariantGroup group(int selfWeight, String... idsAndWeights) {
        List<TrackVariantGroup.Member> members = new java.util.ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            members.add(new TrackVariantGroup.Member(
                idsAndWeights[i], Integer.parseInt(idsAndWeights[i + 1])));
        }
        return new TrackVariantGroup(members).withSelfWeight(selfWeight);
    }

    private static Map<String, Double> drawn() {
        Map<String, Double> seen = new HashMap<>();
        for (int pair = 0; pair < PAIRS; pair++) {
            seen.merge(TrackVariantRegistry.pickName(KIND, SEED, pair, null), 1.0 / PAIRS, Double::sum);
        }
        return seen;
    }

    @Test
    @DisplayName("The reported odds are the odds the pick draws at, through a group and beside it")
    void oddsMatchThePick() {
        TrackVariantRegistry.register(KIND, "hall");
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_small");
        TrackVariantRegistry.register(KIND, "library_tall");
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group(2, "library_small", "5", "library_tall", "3"));

        Map<String, Double> odds = TrackVariantRegistry.leafOdds(KIND, null);
        Map<String, Double> seen = drawn();

        assertEquals(1.0, odds.values().stream().mapToDouble(Double::doubleValue).sum(), 1.0e-9);
        assertEquals(seen.keySet(), odds.keySet());
        for (Map.Entry<String, Double> leaf : odds.entrySet()) {
            assertEquals(leaf.getValue(), seen.get(leaf.getKey()), TOLERANCE, leaf.getKey());
        }
    }

    @Test
    @DisplayName("A member nobody registered gives its share back to the parent, as the pick does")
    void unknownMemberFallsToParent() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantGroupStore.injectForTesting(KIND, "library", group(0, "library_missing", "1"));

        Map<String, Double> odds = TrackVariantRegistry.leafOdds(KIND, null);

        assertFalse(odds.containsKey("library_missing"));
        assertEquals(drawn().get("library"), odds.get("library"), TOLERANCE);
    }
}
