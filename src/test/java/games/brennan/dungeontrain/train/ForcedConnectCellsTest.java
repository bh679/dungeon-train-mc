package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.VariantConnect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage for the UUID-keyed core of {@link ForcedConnectCells}. */
class ForcedConnectCellsTest {

    private static final UUID CARRIAGE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @AfterEach
    void reset() {
        ForcedConnectCells.clear();
    }

    @Test
    @DisplayName("a locked cell reads back its arm mask, on that carriage only")
    void modeIsPerCarriage() {
        assertFalse(ForcedConnectCells.hasAny());
        ForcedConnectCells.put(CARRIAGE, 42L, 0);
        ForcedConnectCells.put(CARRIAGE, 43L, VariantConnect.ALL);
        ForcedConnectCells.put(CARRIAGE, 45L, VariantConnect.NORTH | VariantConnect.EAST);

        assertTrue(ForcedConnectCells.hasAny());
        assertEquals(0, ForcedConnectCells.get(CARRIAGE, 42L));
        assertEquals(VariantConnect.ALL, ForcedConnectCells.get(CARRIAGE, 43L));
        assertEquals(VariantConnect.NORTH | VariantConnect.EAST, ForcedConnectCells.get(CARRIAGE, 45L));
        assertEquals(-1, ForcedConnectCells.get(CARRIAGE, 44L), "another cell");
        assertEquals(-1, ForcedConnectCells.get(OTHER, 42L), "same cell, other carriage");
    }

    @Test
    @DisplayName("a later mask overwrites an earlier one at the same cell")
    void overwrite() {
        ForcedConnectCells.put(CARRIAGE, 42L, 0);
        ForcedConnectCells.put(CARRIAGE, 42L, VariantConnect.ALL);
        assertEquals(VariantConnect.ALL, ForcedConnectCells.get(CARRIAGE, 42L));
    }

    @Test
    @DisplayName("removing the last cell drops the carriage entry, so hasAny goes cheap again")
    void removeDropsEmptyEntries() {
        ForcedConnectCells.put(CARRIAGE, 42L, 0);
        ForcedConnectCells.remove(CARRIAGE, 42L);
        assertEquals(-1, ForcedConnectCells.get(CARRIAGE, 42L));
        assertFalse(ForcedConnectCells.hasAny());
        ForcedConnectCells.remove(OTHER, 1L); // unknown carriage: a no-op, not a throw
    }

    @Test
    @DisplayName("deleting a sub-level forgets only its cells; clear forgets everything")
    void subLevelAndClear() {
        ForcedConnectCells.put(CARRIAGE, 42L, 0);
        ForcedConnectCells.put(OTHER, 42L, VariantConnect.ALL);

        ForcedConnectCells.removeSubLevel(CARRIAGE);
        assertEquals(-1, ForcedConnectCells.get(CARRIAGE, 42L));
        assertEquals(VariantConnect.ALL, ForcedConnectCells.get(OTHER, 42L));

        ForcedConnectCells.clear();
        assertFalse(ForcedConnectCells.hasAny());
    }
}
