package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OffThreadStructureChunks}: only an off-server-thread lookup through a real
 * {@code ServerLevel}'s structure manager is kept from sync-loading (the Distant Horizons
 * {@code Cat.finalizeSpawn} stalls of the 2026-10-28 sweep), and the skip note is one line a minute.
 */
class OffThreadStructureChunksTest {

    @Test
    @DisplayName("ServerLevel manager, off the server thread → bypass the sync load")
    void offThreadServerLevel_bypassed() {
        assertTrue(OffThreadStructureChunks.shouldBypass(true, false));
    }

    @Test
    @DisplayName("ServerLevel manager on the server thread → original path")
    void serverThread_original() {
        assertFalse(OffThreadStructureChunks.shouldBypass(true, true));
    }

    @Test
    @DisplayName("region-wrapped manager (WorldGenRegion level) → original path on any thread")
    void regionManager_original() {
        assertFalse(OffThreadStructureChunks.shouldBypass(false, false));
        assertFalse(OffThreadStructureChunks.shouldBypass(false, true));
    }

    @Test
    @DisplayName("skip note logs the first skip, then at most once per interval")
    void skipNote_rateLimited() {
        final OffThreadStructureChunks.SkipNote note = new OffThreadStructureChunks.SkipNote();
        final long t0 = 1_000_000L;
        assertTrue(note.record(t0));
        assertFalse(note.record(t0 + 1));
        assertFalse(note.record(t0 + OffThreadStructureChunks.NOTE_INTERVAL_MS - 1));
        assertTrue(note.record(t0 + OffThreadStructureChunks.NOTE_INTERVAL_MS));
        assertFalse(note.record(t0 + OffThreadStructureChunks.NOTE_INTERVAL_MS + 1));
    }
}
