package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifetime random-book record behind "Faulthurst's Favourite": it only ever grows. The cached
 * add/seen methods need FML's game dir, so these drive the path-taking read/save/merge layer directly.
 */
final class GlobalRandomBookProgressTest {

    private static final String MUSINGS = "musings_of_faulthurst";
    private static final String ASIDES = "faulthursts_asides_i";

    @Test
    @DisplayName("save then read round-trips every variant")
    void roundTrip(@TempDir Path dir) {
        Path file = dir.resolve("p-random-books.json");
        GlobalRandomBookProgress.save(file, Map.of(MUSINGS, Set.of(0, 3, 26), ASIDES, Set.of(1)));
        assertEquals(Map.of(MUSINGS, Set.of(0, 3, 26), ASIDES, Set.of(1)), GlobalRandomBookProgress.read(file));
    }

    @Test
    @DisplayName("an older snapshot written late never drops a variant already saved")
    void neverShrinks(@TempDir Path dir) {
        Path file = dir.resolve("p-random-books.json");
        GlobalRandomBookProgress.save(file, Map.of(MUSINGS, Set.of(0, 1, 2)));
        GlobalRandomBookProgress.save(file, Map.of(MUSINGS, Set.of(0), ASIDES, Set.of(2)));
        assertEquals(Map.of(MUSINGS, Set.of(0, 1, 2), ASIDES, Set.of(2)), GlobalRandomBookProgress.read(file));
    }

    @Test
    @DisplayName("no file reads as empty")
    void missingIsEmpty(@TempDir Path dir) {
        assertEquals(Map.of(), GlobalRandomBookProgress.read(dir.resolve("absent.json")));
    }

    @Test
    @DisplayName("an unreadable file is kept aside, not written over")
    void unreadableKeptAside(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p-random-books.json");
        Files.writeString(file, "{ not json");
        assertNull(GlobalRandomBookProgress.read(file));

        GlobalRandomBookProgress.save(file, Map.of(ASIDES, Set.of(0)));

        assertEquals(Map.of(ASIDES, Set.of(0)), GlobalRandomBookProgress.read(file));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".unreadable-")));
        }
    }

    @Test
    @DisplayName("merge keeps both sides and leaves its inputs alone")
    void mergeIsUnion() {
        Map<String, Set<Integer>> a = Map.of(MUSINGS, Set.of(1));
        Map<String, Set<Integer>> b = Map.of(MUSINGS, Set.of(2), ASIDES, Set.of(0));
        assertEquals(Map.of(MUSINGS, Set.of(1, 2), ASIDES, Set.of(0)), GlobalRandomBookProgress.merged(a, b));
        assertEquals(Map.of(MUSINGS, Set.of(1)), a);
    }

    @Test
    @DisplayName("containsAll is true only when nothing new would be added")
    void containsAll() {
        Map<String, Set<Integer>> have = Map.of(MUSINGS, Set.of(0, 1));
        assertTrue(GlobalRandomBookProgress.containsAll(have, Map.of(MUSINGS, Set.of(1))));
        assertFalse(GlobalRandomBookProgress.containsAll(have, Map.of(MUSINGS, Set.of(2))));
        assertFalse(GlobalRandomBookProgress.containsAll(have, Map.of(ASIDES, Set.of(0))));
    }
}
