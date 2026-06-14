package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.NearestPicker.Located;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link NearestPicker#nearestWithin}. Pure-logic coverage with
 * no Minecraft registry types (payloads are {@link String}s) — mirrors the
 * bootstrap-free style of {@link VillagerTrainSpawnLevelTest}.
 */
final class NearestPickerTest {

    @Test
    @DisplayName("nearestWithin returns the closest in-range candidate")
    void nearest_picksClosest() {
        List<Located<String>> candidates = List.of(
            new Located<>(3.0, 0.0, 0.0, "far"),
            new Located<>(1.0, 0.0, 0.0, "near"),
            new Located<>(2.0, 0.0, 0.0, "mid"));
        assertEquals("near",
            NearestPicker.nearestWithin(0.0, 0.0, 0.0, 5.0, candidates));
    }

    @Test
    @DisplayName("nearestWithin keeps a candidate at exactly maxDist, drops one beyond")
    void nearest_excludesBeyondMax() {
        // 'inside' sits at distance 5.0 exactly (kept); 'outside' at ~5.2 (dropped).
        List<Located<String>> candidates = List.of(
            new Located<>(0.0, 0.0, 5.2, "outside"),
            new Located<>(5.0, 0.0, 0.0, "inside"));
        assertEquals("inside",
            NearestPicker.nearestWithin(0.0, 0.0, 0.0, 5.0, candidates));
    }

    @Test
    @DisplayName("nearestWithin returns null when every candidate is out of range")
    void nearest_nullWhenAllOutOfRange() {
        List<Located<String>> candidates = List.of(
            new Located<>(10.0, 0.0, 0.0, "a"),
            new Located<>(0.0, 9.0, 0.0, "b"));
        assertNull(NearestPicker.nearestWithin(0.0, 0.0, 0.0, 5.0, candidates));
    }

    @Test
    @DisplayName("nearestWithin returns null for an empty candidate list")
    void nearest_nullWhenEmpty() {
        List<Located<String>> empty = List.of();
        assertNull(NearestPicker.nearestWithin(0.0, 0.0, 0.0, 5.0, empty));
    }

    @Test
    @DisplayName("nearestWithin breaks exact ties by keeping the first candidate")
    void nearest_tieKeepsFirst() {
        // Both candidates are at distance 2.0 from the origin; the first in
        // iteration order must win (callers rely on this for determinism).
        List<Located<String>> candidates = List.of(
            new Located<>(2.0, 0.0, 0.0, "first"),
            new Located<>(-2.0, 0.0, 0.0, "second"));
        assertEquals("first",
            NearestPicker.nearestWithin(0.0, 0.0, 0.0, 5.0, candidates));
    }

    @Test
    @DisplayName("nearestWithin measures true 3D Euclidean distance")
    void nearest_usesEuclideanDistance() {
        // (3,4,0) -> dist 5.0 (kept, but farther); (2,2,2) -> dist ~3.46 (nearer).
        List<Located<String>> candidates = List.of(
            new Located<>(3.0, 4.0, 0.0, "edge"),
            new Located<>(2.0, 2.0, 2.0, "diagonal"));
        assertEquals("diagonal",
            NearestPicker.nearestWithin(0.0, 0.0, 0.0, 5.0, candidates));
    }
}
