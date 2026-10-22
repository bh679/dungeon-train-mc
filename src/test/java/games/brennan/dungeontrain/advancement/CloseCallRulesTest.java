package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.advancement.CloseCallPace.Sample;
import games.brennan.dungeontrain.advancement.CloseCallRules.Found;
import games.brennan.dungeontrain.item.LiveHeadpieceItem;
import net.minecraft.world.item.ArmorItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("close = past the floor, short of the threshold, due within twenty minutes")
    void isClose() {
        assertTrue(CloseCallRules.isClose(800, 1000, 750, OptionalLong.of(19 * MIN)));
        assertFalse(CloseCallRules.isClose(740, 1000, 750, OptionalLong.of(5 * MIN)), "under The Challenge's 750");
        assertFalse(CloseCallRules.isClose(800, 1000, 750, OptionalLong.of(21 * MIN)));
        assertFalse(CloseCallRules.isClose(1000, 1000, 750, OptionalLong.of(1)));
        assertFalse(CloseCallRules.isClose(800, 1000, 750, OptionalLong.empty()));
    }

    @Test
    @DisplayName("The Challenge outranks the others when both are close; both count as alerted")
    void rarestFirst() {
        CloseCallPace p = pace(9_900, 20, 1_000, 10, 6); // 5 min from Nearing Infinity; The Challenge ruled out
        Found none = CloseCallRules.find(p, last(p), t -> t != CloseCallRules.THE_CHALLENGE, Set.of());
        assertEquals(CloseCallRules.NEARING_INFINITY, none.close().get(0));

        CloseCallPace q = pace(900, 20, 248_000, 500, 6);
        Found both = CloseCallRules.find(q, last(q), t -> true, Set.of());
        assertEquals(CloseCallRules.THE_CHALLENGE, both.close().get(0));
        assertEquals(2, both.close().size(), "The Long Run is close too");
        assertEquals(4 * MIN, both.etaMs(), "the soonest ETA is shown");
    }

    @Test
    @DisplayName("an ineligible or already-alerted target is skipped")
    void skipped() {
        CloseCallPace q = pace(900, 20, 1_000, 10, 6);
        assertFalse(CloseCallRules.find(q, last(q), t -> false, Set.of()).any());
        assertFalse(CloseCallRules.find(q, last(q), t -> true,
            Set.of(CloseCallRules.THE_CHALLENGE.id().toString())).any());
    }

    @Test
    @DisplayName("minutes shown round up and stay within 1..20")
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
