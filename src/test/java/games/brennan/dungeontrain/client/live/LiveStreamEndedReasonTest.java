package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stop reasons the client sends are the ones the relay's /live/stop accepts (dp-relay live.js STOP_REASONS). */
final class LiveStreamEndedReasonTest {

    private static final Set<String> RELAY_STOP_REASONS = Set.of("died", "removed", "left", "free_play", "disabled", "failed");

    @Test
    @DisplayName("every reason a clean stop can carry is one the relay knows (REPLACED is a cut-off: no stop is sent)")
    void reasonsMatchRelay() {
        for (LiveStreamEndedToast.Reason r : LiveStreamEndedToast.Reason.values()) {
            if (r == LiveStreamEndedToast.Reason.REPLACED) continue;
            assertTrue(RELAY_STOP_REASONS.contains(r.relayReason), r + " → " + r.relayReason);
        }
    }
}
