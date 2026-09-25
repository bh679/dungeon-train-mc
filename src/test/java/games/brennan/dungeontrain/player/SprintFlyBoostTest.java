package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which sprint-fly multiplier a player gets: their chosen one, else the build's default. */
class SprintFlyBoostTest {

    @Test
    void storedValueWinsInEitherBuild() {
        assertEquals(1f, SprintFlyBoost.resolve(1f, false));
        assertEquals(10f, SprintFlyBoost.resolve(10f, true));
    }

    @Test
    void unsetIsFiveTimesInDev() {
        assertEquals(SprintFlyBoost.DEV_DEFAULT, SprintFlyBoost.resolve(null, false));
    }

    @Test
    void unsetIsVanillaInProduction() {
        assertEquals(SprintFlyBoost.VANILLA, SprintFlyBoost.resolve(null, true));
    }
}
