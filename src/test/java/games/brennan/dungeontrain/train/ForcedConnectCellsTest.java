package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.VariantConnect.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("a forced cell reads back its mode, on that carriage only")
    void modeIsPerCarriage() {
        assertFalse(ForcedConnectCells.hasAny());
        ForcedConnectCells.put(CARRIAGE, 42L, Mode.OFF);
        ForcedConnectCells.put(CARRIAGE, 43L, Mode.ON);

        assertTrue(ForcedConnectCells.hasAny());
        assertEquals(Mode.OFF, ForcedConnectCells.get(CARRIAGE, 42L));
        assertEquals(Mode.ON, ForcedConnectCells.get(CARRIAGE, 43L));
        assertNull(ForcedConnectCells.get(CARRIAGE, 44L), "another cell");
        assertNull(ForcedConnectCells.get(OTHER, 42L), "same cell, other carriage");
    }

    @Test
    @DisplayName("a later mode overwrites an earlier one at the same cell")
    void overwrite() {
        ForcedConnectCells.put(CARRIAGE, 42L, Mode.OFF);
        ForcedConnectCells.put(CARRIAGE, 42L, Mode.ON);
        assertEquals(Mode.ON, ForcedConnectCells.get(CARRIAGE, 42L));
    }

    @Test
    @DisplayName("removing the last cell drops the carriage entry, so hasAny goes cheap again")
    void removeDropsEmptyEntries() {
        ForcedConnectCells.put(CARRIAGE, 42L, Mode.OFF);
        ForcedConnectCells.remove(CARRIAGE, 42L);
        assertNull(ForcedConnectCells.get(CARRIAGE, 42L));
        assertFalse(ForcedConnectCells.hasAny());
        ForcedConnectCells.remove(OTHER, 1L); // unknown carriage: a no-op, not a throw
    }

    @Test
    @DisplayName("deleting a sub-level forgets only its cells; clear forgets everything")
    void subLevelAndClear() {
        ForcedConnectCells.put(CARRIAGE, 42L, Mode.OFF);
        ForcedConnectCells.put(OTHER, 42L, Mode.ON);

        ForcedConnectCells.removeSubLevel(CARRIAGE);
        assertNull(ForcedConnectCells.get(CARRIAGE, 42L));
        assertEquals(Mode.ON, ForcedConnectCells.get(OTHER, 42L));

        ForcedConnectCells.clear();
        assertFalse(ForcedConnectCells.hasAny());
    }
}
