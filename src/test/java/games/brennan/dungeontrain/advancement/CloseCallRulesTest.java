package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.advancement.CloseCallPace.Sample;
import games.brennan.dungeontrain.advancement.CloseCallRules.Found;
import games.brennan.dungeontrain.advancement.CloseCallRules.Stage;
import games.brennan.dungeontrain.item.LiveHeadpieceItem;
import net.minecraft.world.item.ArmorItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseCallRulesTest {

    private static final long MIN = 60_000L;

    /** {@code perMin} carriages and {@code metresPerMin} metres for {@code minutes} minutes, ending at the given reading. */
    private static CloseCallPace pace(double endCarriages, double perMin, double endMetres, double metresPerMin, int minutes) {
        CloseCallPace p = CloseCallPace.EMPTY;
        for (int m = 0; m <= minutes; m++) {
            int back = minutes - m;
            p = p.with(new Sample(m * MIN, endCarriages - back * perMin, endMetres - back * metresPerMin));
        }
        return p;
    }

    private static Sample last(CloseCallPace p) {
        return p.samples().get(p.samples().size() - 1);
    }

    @Test
    @DisplayName("the stage: 20 minutes or less is early, 5 or less is final, further or reached is none")
    void stages() {
        assertEquals(Stage.EARLY, CloseCallRules.stageOf(100, 1000, OptionalLong.of(20 * MIN)), "no carriage floor");
        assertEquals(Stage.EARLY, CloseCallRules.stageOf(800, 1000, OptionalLong.of(6 * MIN)));
        assertEquals(Stage.FINAL, CloseCallRules.stageOf(950, 1000, OptionalLong.of(5 * MIN)));
        assertNull(CloseCallRules.stageOf(800, 1000, OptionalLong.of(21 * MIN)));
        assertNull(CloseCallRules.stageOf(1000, 1000, OptionalLong.of(1)));
        assertNull(CloseCallRules.stageOf(800, 1000, OptionalLong.empty()));
    }

    @Test
    @DisplayName("each advancement alerts at 20 minutes, then once more at 5, then never again")
    void twiceALife() {
        Set<String> alerted = new HashSet<>();
        CloseCallPace early = pace(800, 20, 0, 1, 6); // 10 min out
        Found first = CloseCallRules.find(early, last(early), t -> t == CloseCallRules.THE_CHALLENGE, alerted);
        assertEquals(Stage.EARLY, first.stage());
        assertEquals(List.of(CloseCallRules.THE_CHALLENGE.key(Stage.EARLY)), first.keys());
        alerted.addAll(first.keys());
        assertFalse(CloseCallRules.find(early, last(early), t -> t == CloseCallRules.THE_CHALLENGE, alerted).any(),
            "no repeat inside the early stage");

        CloseCallPace close = pace(920, 20, 0, 1, 6); // 4 min out
        Found second = CloseCallRules.find(close, last(close), t -> t == CloseCallRules.THE_CHALLENGE, alerted);
        assertEquals(Stage.FINAL, second.stage());
        assertEquals(List.of(CloseCallRules.THE_CHALLENGE.key(Stage.FINAL)), second.keys());
        alerted.addAll(second.keys());
        assertFalse(CloseCallRules.find(close, last(close), t -> t == CloseCallRules.THE_CHALLENGE, alerted).any());
    }

    @Test
    @DisplayName("reaching 5 minutes first sends one alert and marks both stages")
    void skipsEarly() {
        CloseCallPace p = pace(920, 20, 0, 1, 6);
        Found f = CloseCallRules.find(p, last(p), t -> t == CloseCallRules.THE_CHALLENGE, Set.of());
        assertEquals(Stage.FINAL, f.stage());
        assertEquals(List.of(CloseCallRules.THE_CHALLENGE.key(Stage.EARLY), CloseCallRules.THE_CHALLENGE.key(Stage.FINAL)), f.keys());
    }

    @Test
    @DisplayName("several close at once: one alert, rarest first, all marked, soonest ETA")
    void rarestFirst() {
        CloseCallPace q = pace(900, 20, 248_000, 500, 6); // Challenge 5 min, Long Run 4 min
        Found both = CloseCallRules.find(q, last(q), t -> t != CloseCallRules.NEARING_INFINITY, Set.of());
        assertEquals(CloseCallRules.THE_CHALLENGE, both.close().get(0));
        assertEquals(2, both.close().size());
        assertEquals(4 * MIN, both.etaMs());
        assertTrue(both.keys().contains(CloseCallRules.THE_LONG_RUN.key(Stage.FINAL)));
    }

    @Test
    @DisplayName("an ineligible target is skipped")
    void skipped() {
        CloseCallPace q = pace(900, 20, 1_000, 10, 6);
        assertFalse(CloseCallRules.find(q, last(q), t -> false, Set.of()).any());
    }

    @Test
    @DisplayName("minutes sent round up and stay within 1..20")
    void minutes() {
        assertEquals(1, CloseCallRules.minutes(5_000));
        assertEquals(15, CloseCallRules.minutes(14 * MIN + 1));
        assertEquals(20, CloseCallRules.minutes(40 * MIN));
    }

    @Test
    @DisplayName("wearing the Live Eye never breaks the no-armor rules of The Challenge")
    void liveEyeIsNotArmor() {
        assertFalse(ArmorItem.class.isAssignableFrom(LiveHeadpieceItem.class));
    }
}
