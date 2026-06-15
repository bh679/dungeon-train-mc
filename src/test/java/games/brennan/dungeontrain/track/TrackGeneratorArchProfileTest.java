package games.brennan.dungeontrain.track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Unit tests for {@link TrackGenerator#archProfile(int)} — the pure arch-taper
 * profile selector. Each value is the per-column block count stepping outward
 * from a pillar's footprint edge. No Minecraft runtime required, matching the
 * neighbouring {@code computeSpacing}/{@code computeThickness} helper convention.
 */
final class TrackGeneratorArchProfileTest {

    @Test
    @DisplayName("Pillars shorter than 5 get no arch")
    void shortPillarsGetNoArch() {
        assertArrayEquals(new int[0], TrackGenerator.archProfile(0));
        assertArrayEquals(new int[0], TrackGenerator.archProfile(1));
        assertArrayEquals(new int[0], TrackGenerator.archProfile(4));
    }

    @Test
    @DisplayName("Pillars 5..9 tall get the standard 3,2,1,1 taper")
    void midPillarsGetStandardProfile() {
        int[] expected = {3, 2, 1, 1};
        assertArrayEquals(expected, TrackGenerator.archProfile(5));
        assertArrayEquals(expected, TrackGenerator.archProfile(7));
        assertArrayEquals(expected, TrackGenerator.archProfile(9));
    }

    @Test
    @DisplayName("Pillars 10+ tall get the extended 5,3,2,1,1,1 taper")
    void tallPillarsGetExtendedProfile() {
        int[] expected = {5, 3, 2, 1, 1, 1};
        assertArrayEquals(expected, TrackGenerator.archProfile(10));
        assertArrayEquals(expected, TrackGenerator.archProfile(20));
        assertArrayEquals(expected, TrackGenerator.archProfile(64));
    }

    @Test
    @DisplayName("Profile boundaries are contiguous — no gap or overlap at 4/5 and 9/10")
    void boundariesAreContiguous() {
        assertArrayEquals(new int[0], TrackGenerator.archProfile(4));
        assertArrayEquals(new int[] {3, 2, 1, 1}, TrackGenerator.archProfile(5));
        assertArrayEquals(new int[] {3, 2, 1, 1}, TrackGenerator.archProfile(9));
        assertArrayEquals(new int[] {5, 3, 2, 1, 1, 1}, TrackGenerator.archProfile(10));
    }
}
