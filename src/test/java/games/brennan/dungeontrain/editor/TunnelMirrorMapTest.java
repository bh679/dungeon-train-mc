package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the tunnel mirror mapping used by
 * {@link TunnelEditor#mirrorAuthoredHalf}: the author edits the low master
 * quarter ({@code x ≤ MIRROR_LAST_MASTER_X, z ≤ MIRROR_CENTER_Z}) and the rest
 * is rebuilt by reflecting across the enabled axes. The in-world copy is
 * editor-coupled (covered by the Gate-2 in-game test); this locks down the
 * index arithmetic and source classification — the error-prone bits —
 * mirroring the {@code TunnelPortalProbeTest} approach for {@code probeXOffsets}.
 */
final class TunnelMirrorMapTest {

    // ─── planes / dimensions ────────────────────────────────────────────

    @Test
    @DisplayName("Mirror planes: X between 4/5 (even length, no centre), Z centre column 6 (odd width)")
    void mirrorPlanes() {
        assertEquals(10, TunnelPlacer.LENGTH, "tunnel is 10 long on X");
        assertEquals(13, TunnelPlacer.WIDTH, "tunnel is 13 wide on Z");
        assertEquals(4, TunnelEditor.MIRROR_LAST_MASTER_X, "last master X column is 4 (master 0..4)");
        assertEquals(6, TunnelEditor.MIRROR_CENTER_Z, "Z mirror centre column is 6");
    }

    // ─── reflection arithmetic ──────────────────────────────────────────

    @Test
    @DisplayName("X reflects 0↔9 … 4↔5; the map is its own inverse")
    void mirrorTargetX() {
        assertEquals(9, TunnelEditor.mirrorTargetX(0));
        assertEquals(8, TunnelEditor.mirrorTargetX(1));
        assertEquals(7, TunnelEditor.mirrorTargetX(2));
        assertEquals(6, TunnelEditor.mirrorTargetX(3));
        assertEquals(5, TunnelEditor.mirrorTargetX(4));
        for (int x = 0; x < TunnelPlacer.LENGTH; x++) {
            assertEquals(x, TunnelEditor.mirrorTargetX(TunnelEditor.mirrorTargetX(x)), "X mirror is an involution");
        }
    }

    @Test
    @DisplayName("Z reflects 0↔12 … 5↔7, 6 fixed; the map is its own inverse")
    void mirrorTargetZ() {
        assertEquals(12, TunnelEditor.mirrorTargetZ(0));
        assertEquals(7, TunnelEditor.mirrorTargetZ(5));
        assertEquals(6, TunnelEditor.mirrorTargetZ(6), "centre column maps to itself");
        for (int z = 0; z < TunnelPlacer.WIDTH; z++) {
            assertEquals(z, TunnelEditor.mirrorTargetZ(TunnelEditor.mirrorTargetZ(z)), "Z mirror is an involution");
        }
    }

    // ─── source classification ──────────────────────────────────────────

    @Test
    @DisplayName("Axis off → identity: every column maps to itself")
    void sourceIdentityWhenAxisOff() {
        for (int dx = 0; dx < TunnelPlacer.LENGTH; dx++) {
            assertEquals(dx, TunnelEditor.sourceX(dx, false));
        }
        for (int dz = 0; dz < TunnelPlacer.WIDTH; dz++) {
            assertEquals(dz, TunnelEditor.sourceZ(dz, false));
        }
    }

    @Test
    @DisplayName("X-mirror on: master 0..4 fixed; far 5..9 reflect into the master half")
    void sourceXMirrorOn() {
        for (int dx = 0; dx <= TunnelEditor.MIRROR_LAST_MASTER_X; dx++) {
            assertEquals(dx, TunnelEditor.sourceX(dx, true), "master X column " + dx + " maps to itself");
        }
        for (int dx = TunnelEditor.MIRROR_LAST_MASTER_X + 1; dx < TunnelPlacer.LENGTH; dx++) {
            int sx = TunnelEditor.sourceX(dx, true);
            assertEquals(TunnelPlacer.LENGTH - 1 - dx, sx, "far X column " + dx + " reflects to " + (TunnelPlacer.LENGTH - 1 - dx));
            assertTrue(sx <= TunnelEditor.MIRROR_LAST_MASTER_X, "…which is inside the master half");
        }
    }

    @Test
    @DisplayName("Z-mirror on: master 0..6 fixed; far 7..12 reflect into the master half")
    void sourceZMirrorOn() {
        for (int dz = 0; dz <= TunnelEditor.MIRROR_CENTER_Z; dz++) {
            assertEquals(dz, TunnelEditor.sourceZ(dz, true), "master Z column " + dz + " maps to itself");
        }
        for (int dz = TunnelEditor.MIRROR_CENTER_Z + 1; dz < TunnelPlacer.WIDTH; dz++) {
            int sz = TunnelEditor.sourceZ(dz, true);
            assertEquals(TunnelPlacer.WIDTH - 1 - dz, sz, "far Z column " + dz + " reflects to " + (TunnelPlacer.WIDTH - 1 - dz));
            assertTrue(sz <= TunnelEditor.MIRROR_CENTER_Z, "…which is inside the master half");
        }
    }

    @Test
    @DisplayName("Both axes on: every cell sources from the low master quarter — no gaps")
    void masterQuarterCoversEverything() {
        for (int dx = 0; dx < TunnelPlacer.LENGTH; dx++) {
            for (int dz = 0; dz < TunnelPlacer.WIDTH; dz++) {
                int sx = TunnelEditor.sourceX(dx, true);
                int sz = TunnelEditor.sourceZ(dz, true);
                assertTrue(sx <= TunnelEditor.MIRROR_LAST_MASTER_X && sz <= TunnelEditor.MIRROR_CENTER_Z,
                    "(" + dx + "," + dz + ") must source from the master quarter, got (" + sx + "," + sz + ")");
            }
        }
    }

    // ─── chest (sidecar marker [7,1,1]) across axis combos ───────────────

    @Test
    @DisplayName("Z-only: chest column [7,1] is master (preserved); its Z-mirror [7,11] sources from it (→ AIR)")
    void chestUnderZOnly() {
        int cx = 7, cz = 1;
        // X off, Z on → chest cell is master on both axes, so it is left untouched.
        assertEquals(cx, TunnelEditor.sourceX(cx, false), "x=7 fixed (X mirror off)");
        assertEquals(cz, TunnelEditor.sourceZ(cz, true), "z=1 is in the master Z half");
        // Its Z reflection [7,11] is a far cell whose Z source is the chest column.
        int tz = TunnelEditor.mirrorTargetZ(cz);
        assertEquals(11, tz);
        assertEquals(cz, TunnelEditor.sourceZ(tz, true), "[7,11] sources Z from the chest column → suppressed to AIR");
    }

    @Test
    @DisplayName("X-mirror on: chest column [7,1] is a far-X cell — preserved by the target-marker skip, never duplicated")
    void chestUnderXMirror() {
        int cx = 7, cz = 1;
        // X on → x=7 is far; the cell is NOT master, so the impl keeps it only
        // via the target-marker skip (it is a sidecar position).
        assertEquals(2, TunnelEditor.sourceX(cx, true), "chest x=7 reflects from master x=2");
        assertNotEquals(cx, TunnelEditor.sourceX(cx, true), "chest cell is not master under X-mirror");
        // The master source x=2 is plain airspace (not the chest), so nothing re-creates a chest elsewhere.
        assertEquals(cz, TunnelEditor.sourceZ(cz, true), "z stays in the master half");
    }
}
