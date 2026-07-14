package games.brennan.dungeontrain.util;

import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the in-game {@code @}-tag presence reply text and its 30-minute window gate. Asserts on
 * {@link PresenceLine#recentPhrase} (a pure String, no Minecraft needed); the {@code Component} colouring
 * in {@link PresenceLine#recentLine} is left to in-game verification.
 */
class PresenceLineTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    private static String phrase(Optional<Boolean> online, Optional<Instant> lastSeen) {
        return PresenceLine.recentPhrase(online, lastSeen, NOW);
    }

    @Test
    void onlineNowRendersOnlinePhrase() {
        assertEquals("Brennan is online on Discord right now!", phrase(Optional.of(true), Optional.empty()));
    }

    @Test
    void onlineNowWinsOverStaleLastSeen() {
        assertEquals("Brennan is online on Discord right now!",
                phrase(Optional.of(true), Optional.of(NOW.minus(Duration.ofDays(5)))));
    }

    @Test
    void seenSevenMinutesAgoRendersWasOnline() {
        assertEquals("Brennan was online 7 minutes ago.",
                phrase(Optional.of(false), Optional.of(NOW.minus(Duration.ofMinutes(7)))));
    }

    @Test
    void seenOneMinuteAgoIsSingular() {
        assertEquals("Brennan was online 1 minute ago.",
                phrase(Optional.of(false), Optional.of(NOW.minus(Duration.ofMinutes(1)))));
    }

    @Test
    void exactlyThirtyMinutesStillRenders() {
        assertEquals("Brennan was online 30 minutes ago.",
                phrase(Optional.of(false), Optional.of(NOW.minus(Duration.ofMinutes(30)))));
    }

    @Test
    void overThirtyMinutesIsSilent() {
        assertNull(phrase(Optional.of(false), Optional.of(NOW.minus(Duration.ofMinutes(31)))));
    }

    @Test
    void unknownPresenceIsSilent() {
        assertNull(phrase(Optional.empty(), Optional.empty()));
    }

    @Test
    void offlineWithNoLastSeenIsSilent() {
        assertNull(phrase(Optional.of(false), Optional.empty()));
    }

    @Test
    void futureLastSeenFromClockSkewIsSilent() {
        assertNull(phrase(Optional.of(false), Optional.of(NOW.plus(Duration.ofMinutes(5)))));
    }

    /** Reads back the {@code chat.dungeontrain.time.*} key + count the localized duration component encodes. */
    private static void assertAgo(Duration d, String expectedKey, long expectedCount) {
        var contents = PresenceLine.agoComponent(d).getContents();
        var tc = assertInstanceOf(TranslatableContents.class, contents);
        assertEquals("chat.dungeontrain.time." + expectedKey, tc.getKey());
        assertEquals(1, tc.getArgs().length);
        assertEquals(expectedCount, tc.getArgs()[0]);
    }

    @Test
    void agoComponentPicksSingularKeys() {
        assertAgo(Duration.ofSeconds(1), "second", 1L);
        assertAgo(Duration.ofMinutes(1), "minute", 1L);
        assertAgo(Duration.ofHours(1), "hour", 1L);
        assertAgo(Duration.ofDays(1), "day", 1L);
    }

    @Test
    void agoComponentPicksPluralKeys() {
        assertAgo(Duration.ofSeconds(0), "seconds", 0L);
        assertAgo(Duration.ofSeconds(7), "seconds", 7L);
        assertAgo(Duration.ofMinutes(7), "minutes", 7L);
        assertAgo(Duration.ofHours(5), "hours", 5L);
        assertAgo(Duration.ofDays(3), "days", 3L);
    }

    @Test
    void agoComponentPicksLargestWholeUnit() {
        assertAgo(Duration.ofSeconds(119), "minute", 1L);   // truncates, not rounds
        assertAgo(Duration.ofSeconds(3599), "minutes", 59L);
        assertAgo(Duration.ofMinutes(60), "hour", 1L);
    }
}
