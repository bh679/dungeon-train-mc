package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PhysicsTicketNoSyncLoad#shouldSkipBlockingLoad}: Sable's physics ticket manager may skip
 * its blocking chunk load only for an unloaded world chunk (the 17 Oct 2026 26 s resume freeze).
 */
class PhysicsTicketNoSyncLoadTest {

    @Test
    @DisplayName("unloaded world chunk → skip the blocking load (its ticket loads it async)")
    void unloadedWorldChunk_skipped() {
        assertTrue(PhysicsTicketNoSyncLoad.shouldSkipBlockingLoad(false, false));
    }

    @Test
    @DisplayName("loaded world chunk → original path (resolves instantly)")
    void loadedWorldChunk_original() {
        assertFalse(PhysicsTicketNoSyncLoad.shouldSkipBlockingLoad(false, true));
    }

    @Test
    @DisplayName("plot chunk → original path, loaded or not (Sable-managed)")
    void plotChunk_original() {
        assertFalse(PhysicsTicketNoSyncLoad.shouldSkipBlockingLoad(true, false));
        assertFalse(PhysicsTicketNoSyncLoad.shouldSkipBlockingLoad(true, true));
    }
}
