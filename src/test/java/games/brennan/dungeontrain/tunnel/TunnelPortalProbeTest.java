package games.brennan.dungeontrain.tunnel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Unit tests for {@link TunnelGenerator#probeXOffsets} — the X-offset shape
 * used by {@code airAboveOk} when validating portal placement at a tunnel
 * end. The {@code airAboveOk} caller itself is worldgen-coupled and is
 * exercised in the Gate 2 in-game test; this test just locks down the
 * offset arithmetic (the most error-prone bit — easy to flip a sign).
 */
final class TunnelPortalProbeTest {

    @Test
    @DisplayName("Entrance probe (mirrorX=false) — 3 X probes at -1, +1, +3 (every 2nd block)")
    void entranceOffsets() {
        assertArrayEquals(
            new int[] { -1, 1, 3 },
            TunnelGenerator.probeXOffsets(false),
            "Entrance: 3 probes every 2 blocks, starting one column outside the -X open end and extending toward connecting side at +X"
        );
    }

    @Test
    @DisplayName("Exit probe (mirrorX=true) — 3 X probes at +1, -1, -3 (mirrored)")
    void exitOffsets() {
        assertArrayEquals(
            new int[] { 1, -1, -3 },
            TunnelGenerator.probeXOffsets(true),
            "Exit: 3 probes every 2 blocks, mirrored from entrance — covers the +X open end and 4 blocks toward connecting side at -X"
        );
    }
}
