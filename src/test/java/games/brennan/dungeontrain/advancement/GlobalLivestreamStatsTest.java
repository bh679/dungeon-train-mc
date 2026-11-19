package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifetime streaming total behind "For The Fans". The cached methods need FML's game dir, so these drive
 * the path-taking read/save layer and the pure relay credit directly.
 */
final class GlobalLivestreamStatsTest {

    @Test
    @DisplayName("save then read round-trips the total")
    void roundTrip(@TempDir Path dir) {
        Path file = dir.resolve("p-livestream.json");
        GlobalLivestreamStats.save(file, 144_000L);
        assertEquals(144_000L, GlobalLivestreamStats.read(file));
    }

    @Test
    @DisplayName("an older total written late never lowers what is saved")
    void neverShrinks(@TempDir Path dir) {
        Path file = dir.resolve("p-livestream.json");
        GlobalLivestreamStats.save(file, 500L);
        GlobalLivestreamStats.save(file, 100L);
        assertEquals(500L, GlobalLivestreamStats.read(file));
    }

    @Test
    @DisplayName("no file reads as zero")
    void missingIsZero(@TempDir Path dir) {
        assertEquals(0L, GlobalLivestreamStats.read(dir.resolve("absent.json")));
    }

    @Test
    @DisplayName("an unreadable file is moved aside, never written over")
    void unreadableKept(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p-livestream.json");
        Files.writeString(file, "{not json");
        assertNull(GlobalLivestreamStats.read(file));

        GlobalLivestreamStats.save(file, 40L);

        assertEquals(40L, GlobalLivestreamStats.read(file));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".unreadable-")));
        }
    }

    @Test
    @DisplayName("relay seconds become ticks and raise a lower total")
    void relayRaises() {
        // AshSeven: 1h 28m left in one world, 17,055 s on the relay.
        assertEquals(341_100L, GlobalLivestreamStats.credited(105_240L, 17_055L));
    }

    @Test
    @DisplayName("the relay's figure replaces rather than adds, and never lowers")
    void relayNeverLowers() {
        assertEquals(200_000L, GlobalLivestreamStats.credited(200_000L, 7_200L));
        assertEquals(200_000L, GlobalLivestreamStats.credited(200_000L, 10_000L));
    }

    @Test
    @DisplayName("a nonsense relay figure counts for nothing")
    void relayNonsenseIgnored() {
        assertEquals(50L, GlobalLivestreamStats.credited(50L, 0L));
        assertEquals(50L, GlobalLivestreamStats.credited(50L, -9L));
        assertEquals(50L, GlobalLivestreamStats.credited(50L, Long.MAX_VALUE));
    }
}
