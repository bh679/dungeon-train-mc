package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic unit tests for
 * {@link CarriageContentsPlacer#shouldPopulateDefaultEquipment(boolean, boolean)} —
 * the predicate gating the vanilla {@code finalizeSpawn(SPAWN_EGG)} default-equipment
 * pass on variant-spawned carriage mobs.
 *
 * <p>No Forge / NeoForge bootstrap: the method under test takes plain booleans,
 * mirroring {@link games.brennan.dungeontrain.difficulty.DifficultyProgressionTest}.
 * The equipment population itself needs a live {@code ServerLevel} + registries and
 * is verified in-game (Gate 2), per this module's integration-harness convention.</p>
 */
final class VariantMobEquipmentTest {

    @Test
    @DisplayName("plain mob (no authored NBT, non-slime) → populate vanilla defaults")
    void plainMob_populates() {
        assertTrue(CarriageContentsPlacer.shouldPopulateDefaultEquipment(false, false));
    }

    @Test
    @DisplayName("authored NBT → respect the baked entity, skip finalizeSpawn")
    void authoredNbt_skips() {
        assertFalse(CarriageContentsPlacer.shouldPopulateDefaultEquipment(true, false));
    }

    @Test
    @DisplayName("slime → DT rolls its own size, skip finalizeSpawn")
    void slime_skips() {
        assertFalse(CarriageContentsPlacer.shouldPopulateDefaultEquipment(false, true));
    }

    @Test
    @DisplayName("authored-NBT slime → skip (either guard alone suppresses)")
    void authoredSlime_skips() {
        assertFalse(CarriageContentsPlacer.shouldPopulateDefaultEquipment(true, true));
    }
}
