package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.data.InstallKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapAppliedLog}'s sealed v3 format. */
final class NapAppliedLogTest {

    @BeforeEach
    void installKey(@TempDir Path keyDir) {
        InstallKey.useDirectoryForTests(keyDir);
    }

    @AfterEach
    void resetKey() {
        InstallKey.resetForTests();
    }

    @Test
    @DisplayName("a v3 list round-trips its woken ids and pending naps")
    void roundTrip() throws Exception {
        NapAppliedLog.State state = new NapAppliedLog.State(Set.of(UUID.randomUUID(), UUID.randomUUID()),
            Map.of(UUID.randomUUID() + "-survival-hard", UUID.randomUUID()));
        assertEquals(state, NapAppliedLog.decode(NapAppliedLog.encode(state)));
        assertEquals(NapAppliedLog.State.EMPTY, NapAppliedLog.decode(NapAppliedLog.encode(NapAppliedLog.State.EMPTY)));
    }

    @Test
    @DisplayName("a truncated or extended v3 list fails")
    void wrongLength() {
        byte[] good = NapAppliedLog.encode(new NapAppliedLog.State(Set.of(UUID.randomUUID()), Map.of()));
        assertThrows(NapAppliedLog.Corrupt.class, () -> NapAppliedLog.decode(Arrays.copyOf(good, good.length - 1)));
        assertThrows(NapAppliedLog.Corrupt.class, () -> NapAppliedLog.decode(Arrays.copyOf(good, good.length + 1)));
        assertThrows(NapAppliedLog.Corrupt.class, () -> NapAppliedLog.decode(new byte[3]));
    }

    @Test
    @DisplayName("waking moves a nap from pending to woken; register replaces the slot's pending nap")
    void registerAndMarkWoken(@TempDir Path dir) throws Exception {
        NapAppliedLog log = new NapAppliedLog(dir, legacy -> Map.of());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        log.register("p-survival-hard", a);
        assertTrue(log.read().allows("p-survival-hard", a));
        log.register("p-survival-hard", b);
        assertFalse(log.read().allows("p-survival-hard", a));
        log.markWoken("p-survival-hard", b);
        NapAppliedLog.State state = log.read();
        assertTrue(state.pending().isEmpty());
        assertEquals(Set.of(b), state.woken());
    }

    @Test
    @DisplayName("a damaged v3 list is set aside and replaced by an empty one; nothing is adopted")
    void damagedRecoversEmpty(@TempDir Path dir) throws Exception {
        NapAppliedLog log = new NapAppliedLog(dir, () -> 42L, legacy -> Map.of("x-survival-hard", UUID.randomUUID()));
        log.register("p-survival-hard", UUID.randomUUID());
        Path file = dir.resolve(NapAppliedLog.FILE);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);

        assertEquals(NapAppliedLog.State.EMPTY, log.read());
        assertTrue(Files.exists(dir.resolve(NapAppliedLog.DAMAGED_PREFIX + 42)));
        assertEquals(NapAppliedLog.State.EMPTY, NapAppliedLog.decode(Files.readAllBytes(file)));
    }

    @Test
    @DisplayName("a damaged v2 list also recovers empty, rather than migrating")
    void damagedLegacyRecoversEmpty(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        byte[] v2 = NapTestFiles.legacyList((byte) 2, 0L, UUID.randomUUID());
        v2[10] ^= 1;
        Files.write(dir.resolve(NapAppliedLog.FILE), v2);
        NapAppliedLog log = new NapAppliedLog(dir, () -> 7L, legacy -> Map.of("x-survival-hard", UUID.randomUUID()));
        assertEquals(NapAppliedLog.State.EMPTY, log.read());
    }
}
