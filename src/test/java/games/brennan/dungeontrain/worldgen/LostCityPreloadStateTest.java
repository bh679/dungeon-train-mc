package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.LostCityPreloadState.Scope;
import games.brennan.dungeontrain.worldgen.LostCityPreloadState.Ticket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LostCityPreloadStateTest {

    @Test
    @DisplayName("a reset during a pre-load: the first task ending does not unblock eviction under the second")
    void resetWhileRunning() {
        LostCityPreloadState state = new LostCityPreloadState();
        Ticket first = state.tryBegin(Scope.FULL);
        assertNotNull(first);
        state.reset();                                   // /reload: re-armed while the first task still loads
        Ticket second = state.tryBegin(Scope.FULL);
        assertNotNull(second, "the reset re-arms the pre-load");
        assertFalse(state.current(first), "the first task is stale and stops early");
        assertTrue(state.current(second));

        state.end(first);
        assertTrue(state.running(), "the second task is still loading");
        assertFalse(state.evict(() -> { throw new AssertionError("evicted under a running pre-load"); }));
        assertEquals(Scope.FULL, state.scope());

        state.end(second);
        assertFalse(state.running());
        boolean[] ran = {false};
        assertTrue(state.evict(() -> ran[0] = true));
        assertTrue(ran[0]);
        assertEquals(Scope.NONE, state.scope());
    }

    @Test
    @DisplayName("a scope is claimed once; a wider one can follow, a narrower or equal one cannot")
    void scopes() {
        LostCityPreloadState state = new LostCityPreloadState();
        Ticket foretaste = state.tryBegin(Scope.FORETASTE);
        assertNotNull(foretaste);
        assertNull(state.tryBegin(Scope.FORETASTE));
        Ticket full = state.tryBegin(Scope.FULL);
        assertNotNull(full, "the run widens a foretaste pre-load");
        assertNull(state.tryBegin(Scope.FORETASTE));
        assertNull(state.tryBegin(Scope.FULL));
        assertNull(state.tryBegin(Scope.NONE));

        state.end(foretaste);
        assertTrue(state.running());
        state.end(full);
        assertFalse(state.running());
    }

    @Test
    @DisplayName("eviction re-arms the pre-load, and a failed removal does not")
    void evictRearms() {
        LostCityPreloadState state = new LostCityPreloadState();
        state.end(state.tryBegin(Scope.FULL));
        assertThrows(IllegalStateException.class, () -> state.evict(() -> { throw new IllegalStateException(); }));
        assertEquals(Scope.FULL, state.scope());
        assertNull(state.tryBegin(Scope.FULL));

        assertTrue(state.evict(() -> {}));
        assertNotNull(state.tryBegin(Scope.FULL));
    }

    @Test
    @DisplayName("an extra end never drives the count below zero")
    void endIsBounded() {
        LostCityPreloadState state = new LostCityPreloadState();
        Ticket t = state.tryBegin(Scope.FULL);
        state.end(t);
        state.end(t);
        assertFalse(state.running());
        state.reset();
        Ticket next = state.tryBegin(Scope.FULL);
        assertTrue(state.running());
        state.end(next);
        assertFalse(state.running());
    }
}
