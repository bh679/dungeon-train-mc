package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for {@link SableStorageSync}. */
final class SableStorageSyncTest {

    @BeforeEach
    void reset() {
        SableStorageSync.beginSave();
        SableStorageSync.endLevelSave();
    }

    @Test
    @DisplayName("Sable's open options lose DSYNC and keep the rest in order")
    void stripsDsyncFromSableOptions() {
        OpenOption[] sable = {
            StandardOpenOption.CREATE, StandardOpenOption.READ,
            StandardOpenOption.WRITE, StandardOpenOption.DSYNC
        };

        OpenOption[] result = SableStorageSync.withoutDsync(sable);

        assertArrayEquals(new OpenOption[] {
            StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE
        }, result);
    }

    @Test
    @DisplayName("the input array is never mutated")
    void doesNotMutateInput() {
        OpenOption[] sable = {StandardOpenOption.DSYNC, StandardOpenOption.WRITE};

        SableStorageSync.withoutDsync(sable);

        assertArrayEquals(new OpenOption[] {StandardOpenOption.DSYNC, StandardOpenOption.WRITE}, sable);
    }

    @Test
    @DisplayName("options without DSYNC are returned as-is")
    void noDsyncReturnsSameArray() {
        OpenOption[] plain = {StandardOpenOption.CREATE, StandardOpenOption.WRITE};

        assertSame(plain, SableStorageSync.withoutDsync(plain));
    }

    @Test
    @DisplayName("SYNC is not DSYNC and is left alone; null passes through")
    void leavesOtherSyncOptionsAndNull() {
        OpenOption[] withSync = {StandardOpenOption.WRITE, StandardOpenOption.SYNC};

        assertSame(withSync, SableStorageSync.withoutDsync(withSync));
        assertNull(SableStorageSync.withoutDsync(null));
    }

    @Test
    @DisplayName("force/skip counters reset at the start of each save")
    void countersResetPerSave() {
        SableStorageSync.recordForced();
        SableStorageSync.recordForced();
        SableStorageSync.recordSkipped();
        assertEquals(2, SableStorageSync.forcedSinceBegin());
        assertEquals(1, SableStorageSync.skippedSinceBegin());

        SableStorageSync.beginSave();

        assertEquals(0, SableStorageSync.forcedSinceBegin());
        assertEquals(0, SableStorageSync.skippedSinceBegin());
    }

    @Test
    @DisplayName("outside any level save every dirty file is forced")
    void forcesOutsideLevelSave() {
        assertTrue(SableStorageSync.shouldForce());
    }

    @Test
    @DisplayName("an autosave (flush=false) defers the sync; a flush save forces it")
    void autosaveDefersFlushSaveForces() {
        SableStorageSync.beginLevelSave(false);
        assertFalse(SableStorageSync.shouldForce());

        SableStorageSync.endLevelSave();
        assertTrue(SableStorageSync.shouldForce());

        SableStorageSync.beginLevelSave(true);
        assertTrue(SableStorageSync.shouldForce());
    }

    @Test
    @DisplayName("the end of a level save restores forcing even after a non-flush save")
    void endLevelSaveRestoresDefault() {
        SableStorageSync.beginLevelSave(false);
        SableStorageSync.endLevelSave();

        assertTrue(SableStorageSync.shouldForce());
    }

    @Test
    @DisplayName("deferred count and phase timers reset at the start of each save")
    void deferredAndTimersResetPerSave() {
        SableStorageSync.recordDeferred();
        SableStorageSync.addSerialiseNanos(3_000_000L);
        SableStorageSync.addWriteNanos(2_500_000L);
        SableStorageSync.addSyncNanos(1_000_000L);
        assertEquals(1, SableStorageSync.deferredSinceBegin());
        assertEquals(3, SableStorageSync.serialiseMsSinceBegin());
        assertEquals(2, SableStorageSync.writeMsSinceBegin());
        assertEquals(1, SableStorageSync.syncMsSinceBegin());

        SableStorageSync.beginSave();

        assertEquals(0, SableStorageSync.deferredSinceBegin());
        assertEquals(0, SableStorageSync.serialiseMsSinceBegin());
        assertEquals(0, SableStorageSync.writeMsSinceBegin());
        assertEquals(0, SableStorageSync.syncMsSinceBegin());
    }

    @Test
    @DisplayName("the escape hatch is off unless the JVM property is set")
    void escapeHatchOffByDefault() {
        assertFalse(SableStorageSync.forcesOnAutosave());
    }

    @Test
    @DisplayName("the phase split lists every phase, then what is left of the total as other")
    void formatsPhasesWithRemainder() {
        long[] ms = {1, 2, 3, 4, 5, 6, 7, 8};
        assertEquals("phases: changes 1, deps 2, move 3, save 4, holding 5, flush 6, prune 7, tickets 8, other 964 ms",
            SableStorageSync.formatPhases(1_000, ms));
    }

    @Test
    @DisplayName("other is never negative when the rounded phases add up past the total")
    void otherIsClamped() {
        long[] ms = {10, 0, 0, 0, 0, 0, 0, 0};
        assertTrue(SableStorageSync.formatPhases(5, ms).endsWith("other 0 ms"));
    }

    @Test
    @DisplayName("a new save starts every phase at zero")
    void beginSaveResetsPhases() {
        SableStorageSync.addPhaseNanos(SableStorageSync.SavePhase.FLUSH, 3_000_000L);
        SableStorageSync.beginSave();
        assertArrayEquals(new long[SableStorageSync.SavePhase.values().length], SableStorageSync.phaseMsSinceBegin());
    }

    @Test
    @DisplayName("a phase's nanoseconds add up across calls in one save")
    void phasesAccumulate() {
        SableStorageSync.beginSave();
        SableStorageSync.addPhaseNanos(SableStorageSync.SavePhase.MOVE, 1_500_000L);
        SableStorageSync.addPhaseNanos(SableStorageSync.SavePhase.MOVE, 1_500_000L);
        assertEquals(3, SableStorageSync.phaseMsSinceBegin()[SableStorageSync.SavePhase.MOVE.ordinal()]);
    }
}
