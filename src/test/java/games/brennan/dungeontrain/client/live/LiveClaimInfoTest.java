package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The streamer line's inputs: survey freshness dot, version, and Live Chat (always off for Kids). */
final class LiveClaimInfoTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    @DisplayName("on the latest release → green dot; Live Chat follows the switch")
    void latestAndChat() {
        LiveClaimInfo on = LiveClaimInfo.of("0.1262.0", "0.1262.0", "2026-10-13", TODAY, true, false);
        assertEquals("🟢", on.versionDot());
        assertEquals("0.1262.0", on.version());
        assertTrue(on.chat());
        assertFalse(LiveClaimInfo.of("0.1262.0", "0.1262.0", "2026-10-13", TODAY, false, false).chat());
    }

    @Test
    @DisplayName("Kid mode means chat off even with the switch on")
    void kidForcesOff() {
        assertFalse(LiveClaimInfo.of("0.1262.0", "0.1262.0", "2026-10-13", TODAY, true, true).chat());
    }

    @Test
    @DisplayName("behind the latest: the dot comes from the build's age, same as surveys")
    void dotFromAge() {
        assertEquals("🟡", LiveClaimInfo.of("0.1250.0", "0.1262.0", "2026-10-08", TODAY, true, false).versionDot());
        assertEquals("🔴", LiveClaimInfo.of("0.1100.0", "0.1262.0", "2026-08-01", TODAY, true, false).versionDot());
        assertEquals("⚪", LiveClaimInfo.of("0.1250.0", "", "", TODAY, true, false).versionDot());
    }
}
