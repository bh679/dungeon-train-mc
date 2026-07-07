package games.brennan.dungeontrain.echo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The origin/id gate of {@link EchoUsageReporter}: only DP-imported lives carry a relay record id,
 * and only a positive integer key is one. Everything else must stay silent — a wrong "used" report
 * would corrupt the data explorer's echo counts.
 */
class EchoUsageReporterTest {

    @Test
    void relayRecordFromDiscordPresenceParses() {
        assertEquals(42, EchoUsageReporter.relayRecordId("discordpresence", "42"));
        assertEquals(1, EchoUsageReporter.relayRecordId("discordpresence", " 1 "));
    }

    @Test
    void nonRelaySourcesAreIgnored() {
        assertNull(EchoUsageReporter.relayRecordId("playermob", "42"));
        assertNull(EchoUsageReporter.relayRecordId("dttest", "42"));
        assertNull(EchoUsageReporter.relayRecordId(null, "42"));
    }

    @Test
    void malformedKeysAreIgnored() {
        assertNull(EchoUsageReporter.relayRecordId("discordpresence", null));
        assertNull(EchoUsageReporter.relayRecordId("discordpresence", ""));
        assertNull(EchoUsageReporter.relayRecordId("discordpresence", "abc"));
        assertNull(EchoUsageReporter.relayRecordId("discordpresence", "0"));
        assertNull(EchoUsageReporter.relayRecordId("discordpresence", "-5"));
        // A death sequence number that overflows int must not wrap into a bogus id.
        assertNull(EchoUsageReporter.relayRecordId("discordpresence", "99999999999999"));
    }
}
