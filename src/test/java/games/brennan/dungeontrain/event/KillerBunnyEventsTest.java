package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic unit tests for
 * {@link KillerBunnyEvents#shouldTurnKiller(float, double, boolean, boolean)} and
 * the shipped {@code killerBunnyChance} default. The variant swap itself needs a
 * live {@code ServerLevel} and is verified in-game (Gate 2).
 */
final class KillerBunnyEventsTest {

    private static final double CHANCE = 1.0 / 250.0;

    @Test
    @DisplayName("roll under the chance → killer bunny")
    void rollUnderChance_turnsKiller() {
        assertTrue(KillerBunnyEvents.shouldTurnKiller(0.001f, CHANCE, false, false));
    }

    @Test
    @DisplayName("roll at or above the chance → ordinary rabbit")
    void rollAtOrAboveChance_staysRabbit() {
        assertFalse(KillerBunnyEvents.shouldTurnKiller((float) CHANCE, CHANCE, false, false));
        assertFalse(KillerBunnyEvents.shouldTurnKiller(0.5f, CHANCE, false, false));
    }

    @Test
    @DisplayName("chance 0 disables it; chance 1 always fires")
    void chanceBounds() {
        assertFalse(KillerBunnyEvents.shouldTurnKiller(0.0f, 0.0, false, false));
        assertTrue(KillerBunnyEvents.shouldTurnKiller(0.9999f, 1.0, false, false));
    }

    @Test
    @DisplayName("Peaceful never spawns a killer bunny")
    void peaceful_never() {
        assertFalse(KillerBunnyEvents.shouldTurnKiller(0.0f, 1.0, true, false));
    }

    @Test
    @DisplayName("onboarding no-hostiles stretch never spawns a killer bunny")
    void onboardingNoHostiles_never() {
        assertFalse(KillerBunnyEvents.shouldTurnKiller(0.0f, 1.0, false, true));
    }

    @Test
    @DisplayName("name roll under the chance → Monty Python name; at or above → left alone")
    void nameRoll() {
        assertTrue(KillerBunnyEvents.shouldName(0.05f, 0.10));
        assertFalse(KillerBunnyEvents.shouldName(0.10f, 0.10));
        assertFalse(KillerBunnyEvents.shouldName(0.5f, 0.10));
    }

    @Test
    @DisplayName("name chance 0 never names; 1 always names")
    void nameChanceBounds() {
        assertFalse(KillerBunnyEvents.shouldName(0.0f, 0.0));
        assertTrue(KillerBunnyEvents.shouldName(0.9999f, 1.0));
    }

    @Test
    @DisplayName("shipped name chance is one in ten — above AIN's 5% passive roll")
    void defaultNameChance() {
        assertEquals(0.10, DungeonTrainConfig.DEFAULT_KILLER_BUNNY_NAME_CHANCE, 1e-12);
        assertTrue(DungeonTrainConfig.DEFAULT_KILLER_BUNNY_NAME_CHANCE > 0.05);
    }

    @Test
    @DisplayName("shipped default is one rabbit in 250")
    void defaultChance() {
        assertEquals(1.0 / 250.0, DungeonTrainConfig.DEFAULT_KILLER_BUNNY_CHANCE, 1e-12);
    }
}
