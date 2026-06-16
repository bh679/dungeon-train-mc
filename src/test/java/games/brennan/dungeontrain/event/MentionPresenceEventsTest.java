package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for the {@code @}-tag detector: case-insensitive SUBSTRING, mirroring the bundled Discord
 * Presence mod's {@code MentionTrigger.matches} so the in-game reply fires exactly when the real ping
 * does. Guards against a future "fix" to word-boundary matching, which would silently desync the two.
 */
class MentionPresenceEventsTest {

    @Test
    void matchesTokensCaseInsensitively() {
        assertTrue(MentionPresenceEvents.mentionsBrennan("@dev where do I report a bug?"));
        assertTrue(MentionPresenceEvents.mentionsBrennan("hey @BrennanHatton, thanks!"));
        assertTrue(MentionPresenceEvents.mentionsBrennan("@DEV"));
    }

    @Test
    void matchesAsSubstringLikeDiscordPresence() {
        // DP pings on substring (e.g. "@developer" contains "@dev"); mirror it, do not tighten.
        assertTrue(MentionPresenceEvents.mentionsBrennan("ask @developer about it"));
    }

    @Test
    void ignoresTextWithoutTheAtToken() {
        assertFalse(MentionPresenceEvents.mentionsBrennan("the dev team is great"));
        assertFalse(MentionPresenceEvents.mentionsBrennan("brennan is cool"));
    }

    @Test
    void ignoresEmptyAndNull() {
        assertFalse(MentionPresenceEvents.mentionsBrennan(""));
        assertFalse(MentionPresenceEvents.mentionsBrennan(null));
    }
}
