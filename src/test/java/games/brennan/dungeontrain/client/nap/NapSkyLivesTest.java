package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.client.nap.NapSkyLives.Kind;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkTreeLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which past lives draw their real perks, which draw random paths, and this life's path. */
class NapSkyLivesTest {

    private static NapSky sky() {
        return NapSky.parse(new InputStreamReader(Objects.requireNonNull(
            NapSkyLivesTest.class.getResourceAsStream("/assets/dungeontrain/nap/sky.json")), StandardCharsets.UTF_8));
    }

    private static List<Kind> times(Kind k, int n) {
        return Collections.nCopies(n, k);
    }

    @Test
    @DisplayName("perks off: the sky as it always was")
    void perksOff() {
        assertEquals(times(Kind.TRY, 7), NapSkyLives.plan(0, 3, false, 24, 7));
        assertEquals(times(Kind.PAST, 4), NapSkyLives.plan(4, 3, false, 24, 7));
        assertEquals(times(Kind.PAST, 24), NapSkyLives.plan(90, 0, false, 24, 7));
    }

    @Test
    @DisplayName("no past lives at all: passing tries")
    void firstLife() {
        assertEquals(times(Kind.TRY, 7), NapSkyLives.plan(0, 0, true, 24, 7));
    }

    @Test
    @DisplayName("older lives without a record are before perks; the newest are real")
    void mixed() {
        assertEquals(List.of(Kind.PRE_PERKS, Kind.PRE_PERKS, Kind.REAL), NapSkyLives.plan(3, 1, true, 24, 7));
        assertEquals(times(Kind.PRE_PERKS, 5), NapSkyLives.plan(5, 0, true, 24, 7));
    }

    @Test
    @DisplayName("a perks test world's deaths add records but no count: the records still draw")
    void recordsBeyondTheCount() {
        assertEquals(times(Kind.REAL, 2), NapSkyLives.plan(0, 2, true, 24, 7));
        assertEquals(List.of(Kind.PRE_PERKS, Kind.REAL, Kind.REAL), NapSkyLives.plan(3, 2, true, 24, 7));
    }

    @Test
    @DisplayName("capped at the newest lives")
    void capped() {
        List<Kind> plan = NapSkyLives.plan(100, 30, true, 24, 7);
        assertEquals(24, plan.size());
        assertTrue(plan.stream().allMatch(k -> k == Kind.REAL));
    }

    @Test
    @DisplayName("every perk on the map has a star, each its own")
    void everyPerkHasAStar() {
        NapSky sky = sky();
        PerkTree tree = PerkTreeLoader.get();
        NapSkyStars stars = NapSkyStars.match(sky, tree);
        Set<Integer> seen = new HashSet<>();
        for (PerkTree.Perk p : tree.perks()) {
            if (p.isRoot()) continue;
            int s = stars.star(p.id());
            assertTrue(s > 0, "no star for " + p.id());
            assertTrue(seen.add(s), "two perks on star " + s);
        }
        assertEquals(-1, stars.star("not_a_perk"));
    }

    @Test
    @DisplayName("built from perk ids: records drawn, this life only when something is active")
    void fromIds() {
        NapSky sky = sky();
        NapSkyStars stars = NapSkyStars.match(sky, PerkTreeLoader.get());
        String first = PerkTreeLoader.get().perks().stream().filter(p -> !p.isRoot())
            .filter(p -> sky.joined(0, stars.star(p.id()))).findFirst().orElseThrow().id();
        NapSkyLives some = NapSkyLives.of(sky, stars, List.of(List.of(first), List.of()), List.of(first), true);
        assertEquals(2, some.records().size());
        assertEquals(1, some.records().get(0).edges().size());
        assertTrue(some.records().get(1).edges().isEmpty());
        assertTrue(some.hasThisLife());

        NapSkyLives none = NapSkyLives.of(sky, stars, List.of(), List.of("not_a_perk"), true);
        assertNull(none.thisLife());
        assertFalse(none.hasThisLife());
        assertEquals(NapSkyLives.NONE, NapSkyLives.of(sky, stars, List.of(List.of(first)), List.of(first), false));
    }
}
