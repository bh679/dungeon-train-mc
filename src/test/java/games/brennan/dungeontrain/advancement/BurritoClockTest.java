package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Everything Burrito's play-time clock: since first played, or since the last reset. */
class BurritoClockTest {

    @Test
    @DisplayName("never reset: the whole time aboard counts, since first played")
    void noBaselineCountsEverything() {
        BurritoClock.Completion c = BurritoClock.Profile.FRESH.completionAt(72_000L, 5L, "normal", "1.0");
        assertEquals(72_000L, c.ticks());
        assertEquals(1, c.attempt());
    }

    @Test
    @DisplayName("after a reset only the time aboard since the reset counts, as the next attempt")
    void resetStartsANewAttempt() {
        BurritoClock.Profile p = BurritoClock.Profile.FRESH.reset(50_000L);
        BurritoClock.Completion c = p.completionAt(80_000L, 5L, "hard", "1.0");
        assertEquals(30_000L, c.ticks());
        assertEquals(2, c.attempt());
    }

    @Test
    @DisplayName("a counter below its baseline (stats wiped since) counts in full")
    void counterBelowBaseline() {
        assertEquals(400L, BurritoClock.elapsed(400L, 10_000L));
        assertEquals(0L, BurritoClock.elapsed(-5L, 0L));
    }

    @Test
    @DisplayName("each difficulty profile keeps its own clock")
    void profilesAreIsolated() {
        BurritoClock.Data d = BurritoClock.Data.EMPTY.with("hard", BurritoClock.Profile.FRESH.reset(1_000L));
        assertEquals(1_000L, d.profile("hard").baseline());
        assertEquals(0L, d.profile("normal").baseline());
        assertEquals(1, d.profile("normal").attempt());
    }

    @Test
    @DisplayName("a whole-profile reset restarts every known profile and keeps old completions")
    void resetAllKeepsHistory() {
        BurritoClock.Profile done = BurritoClock.Profile.FRESH.with(
            BurritoClock.Profile.FRESH.completionAt(900L, 1L, "normal", "1.0"));
        BurritoClock.Data d = BurritoClock.Data.EMPTY.with("normal", done).resetAll(2_000L);
        assertEquals(2_000L, d.profile("normal").baseline());
        assertEquals(2, d.profile("normal").attempt());
        assertEquals(1, d.profile("normal").completions().size());
    }

    @Test
    @DisplayName("the file round-trips through its codec")
    void codecRoundTrip() {
        BurritoClock.Profile p = new BurritoClock.Profile(10L, 3,
            List.of(new BurritoClock.Completion(99L, 123L, 2, "hard", "0.1344.0")));
        BurritoClock.Data d = BurritoClock.Data.EMPTY.with("hard", p);
        var json = BurritoClock.Data.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
        assertEquals(d, BurritoClock.Data.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    @DisplayName("an empty file reads as no clocks at all")
    void emptyFile() {
        var parsed = BurritoClock.Data.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
        assertTrue(parsed.profiles().isEmpty());
    }
}
