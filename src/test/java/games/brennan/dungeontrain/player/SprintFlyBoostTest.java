package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which sprint-fly multiplier a player gets: their chosen one, else the default. */
class SprintFlyBoostTest {

    @Test
    void storedValueWins() {
        assertEquals(1f, SprintFlyBoost.resolve(1f));
        assertEquals(10f, SprintFlyBoost.resolve(10f));
    }

    @Test
    void unsetIsThreeTimes() {
        assertEquals(3f, SprintFlyBoost.DEFAULT);
        assertEquals(SprintFlyBoost.DEFAULT, SprintFlyBoost.resolve(null));
    }
}
