package games.brennan.dungeontrain.logrequest;

import games.brennan.dungeontrain.logrequest.DeliveryFailureReport.Reason;
import games.brennan.dungeontrain.logrequest.LiveRequestThrottle.Decision;
import games.brennan.dungeontrain.logrequest.LiveRequestThrottle.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRequestThrottleTest {

    private static final long GAP = 60_000L;

    @Test
    @DisplayName("too soon: the first refusal in a window is posted, the rest of the window is log-only")
    void tooSoonOncePerWindow() {
        Decision open = LiveRequestThrottle.arrive(State.NONE, 1_000, GAP);
        assertFalse(open.tooSoon(), "the first request opens a window");
        Decision first = LiveRequestThrottle.arrive(open.next(), 2_000, GAP);
        assertTrue(first.tooSoon());
        assertTrue(first.post(), "first too-soon is posted");
        Decision loop = LiveRequestThrottle.arrive(first.next(), 2_100, GAP);
        assertTrue(loop.tooSoon());
        assertFalse(loop.post(), "a looping client gets no more posts this window");
        assertFalse(LiveRequestThrottle.arrive(loop.next(), 50_000, GAP).post());
        Decision next = LiveRequestThrottle.arrive(loop.next(), 1_000 + GAP, GAP);
        assertFalse(next.tooSoon(), "a new window");
        assertTrue(LiveRequestThrottle.arrive(next.next(), 1_000 + GAP + 10, GAP).post(), "posted once again in the new window");
    }

    @Test
    @DisplayName("already pending: posted once per pending request id, again for a new one")
    void alreadyPendingOncePerRequest() {
        State s = LiveRequestThrottle.arrive(State.NONE, 0, GAP).next();
        Decision a = LiveRequestThrottle.blocked(s, Reason.ALREADY_PENDING, 7);
        assertTrue(a.post());
        Decision b = LiveRequestThrottle.blocked(a.next(), Reason.ALREADY_PENDING, 7);
        assertFalse(b.post(), "the same request a window later: not again");
        assertTrue(LiveRequestThrottle.blocked(b.next(), Reason.ALREADY_PENDING, 8).post(), "a new pending request");
    }

    @Test
    @DisplayName("Kid mode and Developer chat off are posted (they already come at most once a window)")
    void otherBlocksPost() {
        State s = LiveRequestThrottle.arrive(State.NONE, 0, GAP).next();
        assertTrue(LiveRequestThrottle.blocked(s, Reason.KID_MODE, -1).post());
        assertTrue(LiveRequestThrottle.blocked(s, Reason.DEV_CHAT_OFF, -1).post());
    }

    @Test
    @DisplayName("a decision never mutates the state it was given")
    void immutable() {
        State s = new State(5, false, -1);
        LiveRequestThrottle.arrive(s, 6, GAP);
        LiveRequestThrottle.blocked(s, Reason.ALREADY_PENDING, 3);
        assertFalse(s.tooSoonReported());
        assertTrue(s.reportedPendingId() == -1);
    }
}
