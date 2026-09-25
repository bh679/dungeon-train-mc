package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for {@link SableStorageSync}. */
final class SableStorageSyncTest {

    @BeforeEach
    void reset() {
        SableStorageSync.beginSave();
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
}
