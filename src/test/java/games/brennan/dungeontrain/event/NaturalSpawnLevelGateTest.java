package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for {@link NaturalSpawnLevelGate}. */
final class NaturalSpawnLevelGateTest {

    @Test
    @DisplayName("a mob in no room never unlocks, at any level")
    void absentNeverUnlocks() {
        assertTrue(NaturalSpawnLevelGate.isLocked(OptionalInt.empty(), 0));
        assertTrue(NaturalSpawnLevelGate.isLocked(OptionalInt.empty(), 10_000));
    }

    @Test
    @DisplayName("a mob unlocks exactly at its first room level")
    void unlocksAtFirstRoomLevel() {
        assertTrue(NaturalSpawnLevelGate.isLocked(OptionalInt.of(12), 0));
        assertTrue(NaturalSpawnLevelGate.isLocked(OptionalInt.of(12), 11));
        assertFalse(NaturalSpawnLevelGate.isLocked(OptionalInt.of(12), 12));
        assertFalse(NaturalSpawnLevelGate.isLocked(OptionalInt.of(12), 13));
    }

    @Test
    @DisplayName("a level-0 room mob is open from the start")
    void levelZeroIsOpen() {
        assertFalse(NaturalSpawnLevelGate.isLocked(OptionalInt.of(0), 0));
    }

    @Test
    @DisplayName("only gated mods are touched")
    void onlyGatedNamespaces() {
        assertTrue(NaturalSpawnLevelGate.isGated("luminous_monsters:swamp_zombie"));
        assertFalse(NaturalSpawnLevelGate.isGated("minecraft:zombie"));
        assertFalse(NaturalSpawnLevelGate.isGated("deeperdarker:stalker"));
    }
}
