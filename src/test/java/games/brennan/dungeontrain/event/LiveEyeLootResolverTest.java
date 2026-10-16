package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.LiveEyeLootResolver.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a loot-rolled Live Eye becomes: a Live Eye, or a TV whenever the finder can't have one. */
final class LiveEyeLootResolverTest {

    private static final float LOW = 0.0f;
    private static final float HIGH = 0.99f;

    @Test
    @DisplayName("a player banned from the Live Eye always finds a TV, whatever else is true")
    void bannedAlwaysTv() {
        assertEquals(Outcome.TV, LiveEyeLootResolver.decide(true, false, false, false, true, HIGH));
        assertEquals(Outcome.TV, LiveEyeLootResolver.decide(true, false, false, true, true, LOW));
        assertEquals(Outcome.LIVE_EYE, LiveEyeLootResolver.decide(true, false, false, false, false, HIGH));
    }

    @Test
    @DisplayName("no one live: an eligible finder always gets the Live Eye")
    void idleAlwaysEye() {
        assertEquals(Outcome.LIVE_EYE, LiveEyeLootResolver.decide(true, false, false, false, HIGH));
        assertEquals(Outcome.LIVE_EYE, LiveEyeLootResolver.decide(true, false, false, false, LOW));
    }

    @Test
    @DisplayName("someone live: only rolls under the keep chance stay a Live Eye")
    void liveIsRare() {
        assertEquals(Outcome.LIVE_EYE, LiveEyeLootResolver.decide(true, false, false, true, LOW));
        assertEquals(Outcome.TV,
            LiveEyeLootResolver.decide(true, false, false, true, LiveEyeLootResolver.LIVE_KEEP_CHANCE));
        assertEquals(Outcome.TV, LiveEyeLootResolver.decide(true, false, false, true, HIGH));
    }

    @Test
    @DisplayName("Kid with Livestreaming off, already holding one, or on cooldown: always a TV")
    void blockedBecomesTv() {
        assertEquals(Outcome.TV, LiveEyeLootResolver.decide(false, false, false, false, LOW));
        assertEquals(Outcome.TV, LiveEyeLootResolver.decide(true, true, false, false, LOW));
        assertEquals(Outcome.TV, LiveEyeLootResolver.decide(true, false, true, false, LOW));
    }

    @Test
    @DisplayName("cooldown lasts 15 minutes from the sighting; never-seen and backwards clocks are clear")
    void cooldownWindow() {
        long seen = 1_000L;
        assertEquals(18_000L, LiveEyeLootResolver.COOLDOWN_TICKS);
        assertFalse(LiveEyeLootResolver.onCooldown(0L, 5_000L));
        assertTrue(LiveEyeLootResolver.onCooldown(seen, seen));
        assertTrue(LiveEyeLootResolver.onCooldown(seen, seen + 17_999L));
        assertFalse(LiveEyeLootResolver.onCooldown(seen, seen + 18_000L));
        assertFalse(LiveEyeLootResolver.onCooldown(seen, seen - 1L));
    }

    @Test
    @DisplayName("one Live Eye per player: every eye past the first is an extra")
    void oneEyeCap() {
        assertEquals(0, LiveEyeLootResolver.extrasOver(0));
        assertEquals(0, LiveEyeLootResolver.extrasOver(1));
        assertEquals(1, LiveEyeLootResolver.extrasOver(2));
        assertEquals(4, LiveEyeLootResolver.extrasOver(5));
    }
}
