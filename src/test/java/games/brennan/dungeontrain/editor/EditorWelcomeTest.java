package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the editor-welcome "last seen online" duration formatter. */
class EditorWelcomeTest {

    @Test
    void formatsSecondsWithSingularPlural() {
        assertEquals("0 seconds", EditorWelcome.humanizeAgo(Duration.ofSeconds(0)));
        assertEquals("1 second", EditorWelcome.humanizeAgo(Duration.ofSeconds(1)));
        assertEquals("2 seconds", EditorWelcome.humanizeAgo(Duration.ofSeconds(2)));
        assertEquals("59 seconds", EditorWelcome.humanizeAgo(Duration.ofSeconds(59)));
    }

    @Test
    void rollsOverToMinutesAtSixtySeconds() {
        assertEquals("1 minute", EditorWelcome.humanizeAgo(Duration.ofSeconds(60)));
        assertEquals("1 minute", EditorWelcome.humanizeAgo(Duration.ofSeconds(119))); // truncates, not rounds
        assertEquals("2 minutes", EditorWelcome.humanizeAgo(Duration.ofMinutes(2)));
        assertEquals("59 minutes", EditorWelcome.humanizeAgo(Duration.ofSeconds(3599)));
    }

    @Test
    void rollsOverToHoursAtSixtyMinutes() {
        assertEquals("1 hour", EditorWelcome.humanizeAgo(Duration.ofMinutes(60)));
        assertEquals("1 hour", EditorWelcome.humanizeAgo(Duration.ofMinutes(90))); // truncates to the whole hour
        assertEquals("23 hours", EditorWelcome.humanizeAgo(Duration.ofHours(23)));
        assertEquals("23 hours", EditorWelcome.humanizeAgo(Duration.ofMinutes(1439))); // 23h59m
    }

    @Test
    void rollsOverToDaysAtTwentyFourHours() {
        assertEquals("1 day", EditorWelcome.humanizeAgo(Duration.ofHours(24)));
        assertEquals("2 days", EditorWelcome.humanizeAgo(Duration.ofDays(2)));
        assertEquals("45 days", EditorWelcome.humanizeAgo(Duration.ofDays(45)));
    }

    @Test
    void clampsZeroAndNegativeDurations() {
        assertEquals("0 seconds", EditorWelcome.humanizeAgo(Duration.ofSeconds(-100)));
        assertEquals("0 seconds", EditorWelcome.humanizeAgo(Duration.ofDays(-1)));
    }
}
