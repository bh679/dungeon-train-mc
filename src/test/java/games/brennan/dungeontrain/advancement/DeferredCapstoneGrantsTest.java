package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredCapstoneGrantsTest {

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);

    @Test
    @DisplayName("nothing is checked before its tick, then exactly once")
    void drainsOnceWhenDue() {
        DeferredCapstoneGrants.Due due = new DeferredCapstoneGrants.Due();
        due.schedule(A, 10);
        assertTrue(due.drainDue(9).isEmpty());
        assertEquals(List.of(A), due.drainDue(10));
        assertTrue(due.drainDue(11).isEmpty());
        assertTrue(due.isEmpty());
    }

    @Test
    @DisplayName("a later earn pushes the check back; an earlier one never pulls it forward")
    void laterEarnPushesBack() {
        DeferredCapstoneGrants.Due due = new DeferredCapstoneGrants.Due();
        due.schedule(A, 10);
        due.schedule(A, 15);
        due.schedule(A, 12);
        assertTrue(due.drainDue(14).isEmpty());
        assertEquals(List.of(A), due.drainDue(15));
    }

    @Test
    @DisplayName("players are checked independently")
    void perPlayer() {
        DeferredCapstoneGrants.Due due = new DeferredCapstoneGrants.Due();
        due.schedule(A, 10);
        due.schedule(B, 20);
        assertEquals(List.of(A), due.drainDue(10));
        assertEquals(List.of(B), due.drainDue(20));
    }
}
