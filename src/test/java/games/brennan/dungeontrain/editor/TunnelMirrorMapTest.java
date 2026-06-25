package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the tunnel half-model mirror mapping used by
 * {@link TunnelEditor#mirrorAuthoredHalf} — the author edits only the
 * chest-side master half (local Z {@code 0..centre}) and the far half is
 * rebuilt by reflecting across the centre column on save. The in-world copy
 * itself is editor-coupled and is exercised in the Gate 2 in-game test; this
 * locks down the index arithmetic (the error-prone bit), mirroring the
 * {@code TunnelPortalProbeTest} approach for {@code probeXOffsets}.
 */
final class TunnelMirrorMapTest {

    @Test
    @DisplayName("Centre plane is the middle column of the 13-wide tunnel (Z=6)")
    void centreIsTheMiddleColumn() {
        assertEquals(13, TunnelPlacer.WIDTH, "tunnel is 13 wide on Z");
        assertEquals(6, TunnelEditor.MIRROR_CENTER_Z, "mirrors about local Z=6");
    }

    @Test
    @DisplayName("Each master column reflects to its far-half counterpart (sz → 12 − sz)")
    void masterColumnsReflectAcrossCentre() {
        assertEquals(12, TunnelEditor.mirrorTargetZ(0));
        assertEquals(11, TunnelEditor.mirrorTargetZ(1));
        assertEquals(10, TunnelEditor.mirrorTargetZ(2));
        assertEquals(9, TunnelEditor.mirrorTargetZ(3));
        assertEquals(8, TunnelEditor.mirrorTargetZ(4));
        assertEquals(7, TunnelEditor.mirrorTargetZ(5));
    }

    @Test
    @DisplayName("Reflection is its own inverse; targets land strictly in the far half")
    void reflectionIsAnInvolution() {
        for (int sz = 0; sz < TunnelEditor.MIRROR_CENTER_Z; sz++) {
            int tz = TunnelEditor.mirrorTargetZ(sz);
            assertTrue(tz > TunnelEditor.MIRROR_CENTER_Z && tz < TunnelPlacer.WIDTH,
                "target column " + tz + " lands in the far half");
            assertEquals(sz, TunnelEditor.mirrorTargetZ(tz),
                "mirror is its own inverse about the centre");
        }
    }

    @Test
    @DisplayName("Master half rebuilds every far-half column exactly once — no gaps, no overlap")
    void masterHalfFullyCoversTheFarHalf() {
        Set<Integer> produced = new HashSet<>();
        for (int sz = 0; sz < TunnelEditor.MIRROR_CENTER_Z; sz++) {
            produced.add(TunnelEditor.mirrorTargetZ(sz));
        }
        for (int tz = TunnelEditor.MIRROR_CENTER_Z + 1; tz < TunnelPlacer.WIDTH; tz++) {
            assertTrue(produced.contains(tz), "far-half column " + tz + " is rebuilt");
        }
        assertEquals(TunnelPlacer.WIDTH - 1 - TunnelEditor.MIRROR_CENTER_Z, produced.size(),
            "no duplicate / missing far-half columns");
    }

    @Test
    @DisplayName("Section chest [7,1,1] sits on the master half; its mirror target [7,1,11] is far-half (suppressed)")
    void chestStaysSingle() {
        int chestZ = 1; // section chest sidecar marker is local [7,1,1]
        assertTrue(chestZ < TunnelEditor.MIRROR_CENTER_Z, "chest lives on the authored master half");
        assertEquals(11, TunnelEditor.mirrorTargetZ(chestZ), "chest reflects to local Z=11");
        assertFalse(11 <= TunnelEditor.MIRROR_CENTER_Z,
            "…which is on the far half, where mirrorAuthoredHalf writes AIR instead of a duplicate chest");
    }
}
