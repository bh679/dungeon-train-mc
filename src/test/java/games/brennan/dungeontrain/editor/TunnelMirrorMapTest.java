package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tunnel-specific regression coverage for the mirror mapping now provided by
 * {@link EditorMirror}: the author edits the low master quarter
 * ({@code x ≤ lastMaster(LENGTH), z ≤ lastMaster(WIDTH)}) and the rest is
 * rebuilt by reflecting across the enabled axes. The in-world copy is
 * editor-coupled (covered by the Gate-2 in-game test); this locks down the
 * index arithmetic and source classification at the real tunnel dimensions.
 * The generalised maths (parity, vertical flip, image enumeration) live in
 * {@link EditorMirrorTest}.
 */
final class TunnelMirrorMapTest {

    private static final int LEN = TunnelPlacer.LENGTH;   // 10 (even)
    private static final int WID = TunnelPlacer.WIDTH;    // 13 (odd)

    // ─── planes / dimensions ────────────────────────────────────────────

    @Test
    @DisplayName("Mirror planes: X between 4/5 (even length, no centre), Z centre column 6 (odd width)")
    void mirrorPlanes() {
        assertEquals(10, LEN, "tunnel is 10 long on X");
        assertEquals(13, WID, "tunnel is 13 wide on Z");
        assertEquals(4, EditorMirror.lastMaster(LEN), "last master X column is 4 (master 0..4)");
        assertEquals(6, EditorMirror.lastMaster(WID), "Z mirror centre column is 6");
    }

    // ─── reflection arithmetic ──────────────────────────────────────────

    @Test
    @DisplayName("X reflects 0↔9 … 4↔5; the map is its own inverse")
    void mirrorTargetX() {
        assertEquals(9, EditorMirror.target(0, LEN));
        assertEquals(8, EditorMirror.target(1, LEN));
        assertEquals(5, EditorMirror.target(4, LEN));
        for (int x = 0; x < LEN; x++) {
            assertEquals(x, EditorMirror.target(EditorMirror.target(x, LEN), LEN), "X mirror is an involution");
        }
    }

    @Test
    @DisplayName("Z reflects 0↔12 … 5↔7, 6 fixed; the map is its own inverse")
    void mirrorTargetZ() {
        assertEquals(12, EditorMirror.target(0, WID));
        assertEquals(7, EditorMirror.target(5, WID));
        assertEquals(6, EditorMirror.target(6, WID), "centre column maps to itself");
        for (int z = 0; z < WID; z++) {
            assertEquals(z, EditorMirror.target(EditorMirror.target(z, WID), WID), "Z mirror is an involution");
        }
    }

    // ─── source classification ──────────────────────────────────────────

    @Test
    @DisplayName("Axis off → identity: every column maps to itself")
    void sourceIdentityWhenAxisOff() {
        for (int dx = 0; dx < LEN; dx++) {
            assertEquals(dx, EditorMirror.source(dx, LEN, false));
        }
        for (int dz = 0; dz < WID; dz++) {
            assertEquals(dz, EditorMirror.source(dz, WID, false));
        }
    }

    @Test
    @DisplayName("X-mirror on: master 0..4 fixed; far 5..9 reflect into the master half")
    void sourceXMirrorOn() {
        int lastMaster = EditorMirror.lastMaster(LEN);
        for (int dx = 0; dx <= lastMaster; dx++) {
            assertEquals(dx, EditorMirror.source(dx, LEN, true), "master X column " + dx + " maps to itself");
        }
        for (int dx = lastMaster + 1; dx < LEN; dx++) {
            int sx = EditorMirror.source(dx, LEN, true);
            assertEquals(LEN - 1 - dx, sx, "far X column " + dx + " reflects to " + (LEN - 1 - dx));
            assertTrue(sx <= lastMaster, "…which is inside the master half");
        }
    }

    @Test
    @DisplayName("Z-mirror on: master 0..6 fixed; far 7..12 reflect into the master half")
    void sourceZMirrorOn() {
        int lastMaster = EditorMirror.lastMaster(WID);
        for (int dz = 0; dz <= lastMaster; dz++) {
            assertEquals(dz, EditorMirror.source(dz, WID, true), "master Z column " + dz + " maps to itself");
        }
        for (int dz = lastMaster + 1; dz < WID; dz++) {
            int sz = EditorMirror.source(dz, WID, true);
            assertEquals(WID - 1 - dz, sz, "far Z column " + dz + " reflects to " + (WID - 1 - dz));
            assertTrue(sz <= lastMaster, "…which is inside the master half");
        }
    }

    @Test
    @DisplayName("Both axes on: every cell sources from the low master quarter — no gaps")
    void masterQuarterCoversEverything() {
        int lastX = EditorMirror.lastMaster(LEN);
        int lastZ = EditorMirror.lastMaster(WID);
        for (int dx = 0; dx < LEN; dx++) {
            for (int dz = 0; dz < WID; dz++) {
                int sx = EditorMirror.source(dx, LEN, true);
                int sz = EditorMirror.source(dz, WID, true);
                assertTrue(sx <= lastX && sz <= lastZ,
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
        assertEquals(cx, EditorMirror.source(cx, LEN, false), "x=7 fixed (X mirror off)");
        assertEquals(cz, EditorMirror.source(cz, WID, true), "z=1 is in the master Z half");
        // Its Z reflection [7,11] is a far cell whose Z source is the chest column.
        int tz = EditorMirror.target(cz, WID);
        assertEquals(11, tz);
        assertEquals(cz, EditorMirror.source(tz, WID, true), "[7,11] sources Z from the chest column → suppressed to AIR");
    }

    @Test
    @DisplayName("X-mirror on: chest column [7,1] is a far-X cell — preserved by the target-marker skip, never duplicated")
    void chestUnderXMirror() {
        int cx = 7, cz = 1;
        // X on → x=7 is far; the cell is NOT master, so the impl keeps it only
        // via the target-marker skip (it is a sidecar position).
        assertEquals(2, EditorMirror.source(cx, LEN, true), "chest x=7 reflects from master x=2");
        assertNotEquals(cx, EditorMirror.source(cx, LEN, true), "chest cell is not master under X-mirror");
        // The master source x=2 is plain airspace (not the chest), so nothing re-creates a chest elsewhere.
        assertEquals(cz, EditorMirror.source(cz, WID, true), "z stays in the master half");
    }
}
