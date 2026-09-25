package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static games.brennan.dungeontrain.client.DhCapPolicy.RELEASED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DhCapPolicy}: few reloads, never above the player's setting, never past the cap. */
final class DhCapPolicyTest {

    private static final int USER = 256;
    private static final int MIN = 32;
    private static final long NONE = Long.MAX_VALUE;

    @Test
    @DisplayName("nothing to hide: the player's own setting")
    void noCap() {
        assertEquals(RELEASED, DhCapPolicy.next(RELEASED, NONE, USER, MIN));
        assertEquals(RELEASED, DhCapPolicy.next(64, NONE, USER, MIN));
    }

    @Test
    @DisplayName("lowering snaps to the largest tier that fits, straight away")
    void lowersToTier() {
        assertEquals(128, DhCapPolicy.next(RELEASED, 200, USER, MIN));
        assertEquals(64, DhCapPolicy.next(128, 100, USER, MIN));
        assertEquals(32, DhCapPolicy.next(128, 40, USER, MIN));
    }

    @Test
    @DisplayName("raising waits for headroom, so a cap hovering at a tier edge does not flap")
    void raiseNeedsHeadroom() {
        assertEquals(64, DhCapPolicy.next(64, 130, USER, MIN));   // 128 fits, but not with 25% spare
        assertEquals(128, DhCapPolicy.next(64, 160, USER, MIN));  // 160 / 1.25 = 128
        assertEquals(128, DhCapPolicy.next(128, 300, USER, MIN)); // 256 needs 320
        assertEquals(RELEASED, DhCapPolicy.next(128, 320, USER, MIN));
    }

    @Test
    @DisplayName("below DH's minimum DH is hidden, so the distance holds still")
    void holdsWhileHidden() {
        assertEquals(64, DhCapPolicy.next(64, 10, USER, MIN));
        assertEquals(RELEASED, DhCapPolicy.next(RELEASED, 10, USER, MIN));
    }

    @Test
    @DisplayName("never above the player's setting — even one lowered mid-cap")
    void neverAboveUser() {
        assertEquals(RELEASED, DhCapPolicy.next(128, 200, 100, MIN));   // 128 > 100: back to the player's 100
        assertEquals(64, DhCapPolicy.next(RELEASED, 90, 100, MIN));      // tiers 32, 64, then 100
        assertEquals(RELEASED, DhCapPolicy.next(RELEASED, 10, MIN, MIN)); // nothing below the minimum
    }

    @Test
    @DisplayName("a whole approach to a void from 256 chunks reloads at most three times, and never overshoots")
    void approachReloads() {
        int current = RELEASED;
        Set<Integer> applied = new LinkedHashSet<>();
        for (long cap = 600; cap >= 0; cap--) {
            int next = DhCapPolicy.next(current, cap, USER, MIN);
            if (next != current) applied.add(next);
            current = next;
            if (cap >= MIN) assertTrue((current == RELEASED ? USER : current) <= cap, "overshoot at " + cap);
        }
        assertEquals(Set.of(128, 64, 32), applied);
        for (long cap = 0; cap <= 600; cap++) {                          // and back out again
            int next = DhCapPolicy.next(current, cap, USER, MIN);
            if (next != current) applied.add(next);
            current = next;
        }
        assertEquals(RELEASED, current);
    }
}
