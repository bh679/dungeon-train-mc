package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.LiveHeadpieceGuard.Verdict;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LiveHeadpieceGuard.GRACE_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A server's START alone never makes this client stream: the Live Eye has to be on its head. */
class LiveHeadpieceGuardTest {

    @Test
    void startRefusedWithoutHeadpiece() {
        LiveHeadpieceGuard g = new LiveHeadpieceGuard();
        g.reset();
        for (int i = 0; i < GRACE_TICKS; i++) assertEquals(Verdict.NONE, g.tick(false, false));
        assertEquals(Verdict.REFUSE, g.tick(false, false));
    }

    @Test
    void loginEquipLandsInsideGrace() {
        LiveHeadpieceGuard g = new LiveHeadpieceGuard();
        g.reset();
        for (int i = 0; i < 5; i++) assertEquals(Verdict.NONE, g.tick(false, false));
        for (int i = 0; i < 100; i++) assertEquals(Verdict.NONE, g.tick(true, i > 10));
    }

    @Test
    void stopOnHeadpieceRemoved() {
        LiveHeadpieceGuard g = new LiveHeadpieceGuard();
        g.reset();
        g.tick(true, true);
        for (int i = 0; i < GRACE_TICKS; i++) assertEquals(Verdict.NONE, g.tick(false, true));
        assertEquals(Verdict.REMOVED, g.tick(false, true));
    }

    @Test
    void removedBeforeGoingLiveIsStillRemoved() {
        LiveHeadpieceGuard g = new LiveHeadpieceGuard();
        g.reset();
        g.tick(true, false);
        for (int i = 0; i < GRACE_TICKS; i++) g.tick(false, false);
        assertEquals(Verdict.REMOVED, g.tick(false, false));
    }

    @Test
    void briefFlickerIsForgiven() {
        LiveHeadpieceGuard g = new LiveHeadpieceGuard();
        g.reset();
        g.tick(true, true);
        for (int i = 0; i < GRACE_TICKS; i++) g.tick(false, true);
        assertEquals(Verdict.NONE, g.tick(true, true));
        for (int i = 0; i < GRACE_TICKS; i++) assertEquals(Verdict.NONE, g.tick(false, true));
    }

    @Test
    void resetClearsOnNewStart() {
        LiveHeadpieceGuard g = new LiveHeadpieceGuard();
        g.reset();
        g.tick(true, true);
        g.reset();
        for (int i = 0; i < GRACE_TICKS; i++) g.tick(false, false);
        assertEquals(Verdict.REFUSE, g.tick(false, false));
    }
}
