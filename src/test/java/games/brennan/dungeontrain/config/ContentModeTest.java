package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.event.ContentModeMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Adult / Kid mode's fail-safe direction, which is the whole security property of the
 * feature: every place a mode is UNKNOWN must resolve to {@link ContentMode#KID}, so a missing signal
 * withholds content rather than serving it.
 *
 * <p>The one deliberate exception is the CLIENT-side config read, which defaults to ADULT — there the
 * value is the player's own setting rather than an assumption about a stranger, and defaulting a
 * pre-load read to KID would flicker the title-screen chat affordance for every adult player. That
 * asymmetry is intentional and documented on {@code ClientDisplayConfig.getContentMode()}.</p>
 */
final class ContentModeTest {

    @Test
    @DisplayName("an unknown player reads as KID — a missing sync must never widen what is served")
    void mirrorFailsSafeForUnknownPlayer() {
        // A null player stands in for "no sync has arrived": a client on an older Dungeon Train, or one
        // whose login sync hasn't landed yet. Both must be treated as a child until proven otherwise.
        assertEquals(ContentMode.KID, ContentModeMirror.get(null));
        assertTrue(ContentModeMirror.isKid(null));
    }

    @Test
    @DisplayName("parse falls back rather than throwing, and the caller picks the fallback")
    void parseIsTotalAndCallerChoosesFallback() {
        assertEquals(ContentMode.KID, ContentMode.parse("KID", ContentMode.ADULT));
        assertEquals(ContentMode.ADULT, ContentMode.parse("adult", ContentMode.KID), "case-insensitive");
        assertEquals(ContentMode.ADULT, ContentMode.parse("  ADULT  ", ContentMode.KID), "trimmed");
        // A hand-edited TOML or a value from a future version must not throw; the caller's fallback wins,
        // which is why parse takes one instead of hardcoding a default.
        assertEquals(ContentMode.KID, ContentMode.parse("nonsense", ContentMode.KID));
        assertEquals(ContentMode.KID, ContentMode.parse(null, ContentMode.KID));
        assertEquals(ContentMode.ADULT, ContentMode.parse("nonsense", ContentMode.ADULT));
    }

    @Test
    @DisplayName("isKid reads the way the gate call sites use it")
    void isKid() {
        assertTrue(ContentMode.KID.isKid());
        assertFalse(ContentMode.ADULT.isKid());
    }

    @Test
    @DisplayName("ADULT is first, so the ordinal a pre-Kid-mode default lands on is ADULT")
    void adultIsTheZeroOrdinal() {
        // The consent card reports an index into ContentMode.values(), and the packet encodes an
        // ordinal. Pinning the order keeps both wire-ish surfaces honest against a reordering.
        assertEquals(0, ContentMode.ADULT.ordinal());
        assertEquals(1, ContentMode.KID.ordinal());
        assertEquals(2, ContentMode.values().length);
    }
}
