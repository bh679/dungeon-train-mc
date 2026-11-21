package games.brennan.dungeontrain.perks.effects.lover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PetHealthMathTest {

    @Test
    void halfHeartPerRankNoCap() {
        assertEquals(0.0, PetHealthMath.bonusHealth(0));
        assertEquals(1.0, PetHealthMath.bonusHealth(1));
        assertEquals(3.0, PetHealthMath.bonusHealth(3));
        assertEquals(40.0, PetHealthMath.bonusHealth(40));
    }

    @Test
    void negativeRankGivesNothing() {
        assertEquals(0.0, PetHealthMath.bonusHealth(-4));
    }
}
