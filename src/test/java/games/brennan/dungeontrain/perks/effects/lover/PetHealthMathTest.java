package games.brennan.dungeontrain.perks.effects.lover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PetHealthMathTest {

    @Test
    void oneHeartPerRank() {
        assertEquals(0.0, PetHealthMath.bonusHealth(0));
        assertEquals(2.0, PetHealthMath.bonusHealth(1));
        assertEquals(6.0, PetHealthMath.bonusHealth(3));
    }

    @Test
    void negativeRankGivesNothing() {
        assertEquals(0.0, PetHealthMath.bonusHealth(-4));
    }
}
