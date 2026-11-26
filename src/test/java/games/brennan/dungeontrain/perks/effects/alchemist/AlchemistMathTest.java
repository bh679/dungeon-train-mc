package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.perks.PerkOverflow;
import games.brennan.dungeontrain.perks.PerkStats;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every ladder in docs/design/perks-alchemist.md, from the effects' own maths. */
class AlchemistMathTest {

    @Test
    void stackPotionsIsOnePlusPoints() {
        assertEquals(1, PotionStacking.stackSizeFor(0));
        assertEquals(2, PotionStacking.stackSizeFor(1));
        assertEquals(3, PotionStacking.stackSizeFor(2));
        assertEquals(99, PotionStacking.stackSizeFor(98));
        assertEquals(99, PotionStacking.stackSizeFor(500));
    }

    @Test
    void aStackIsNeverStampedBelowItsCount() {
        assertEquals(1, PotionStacking.stampFor(0, 1));
        assertEquals(5, PotionStacking.stampFor(0, 5));
        assertEquals(5, PotionStacking.stampFor(2, 5));
        assertEquals(4, PotionStacking.stampFor(3, 2));
        assertEquals(99, PotionStacking.stampFor(0, 120));
    }

    @Test
    void bottleSlotsEveryFivePoints() {
        assertEquals(0, PocketBrewingMath.bottleSlots(0));
        assertEquals(1, PocketBrewingMath.bottleSlots(1));
        assertEquals(1, PocketBrewingMath.bottleSlots(4));
        assertEquals(2, PocketBrewingMath.bottleSlots(5));
        assertEquals(3, PocketBrewingMath.bottleSlots(10));
        assertEquals(5, PocketBrewingMath.bottleSlots(20));
        assertEquals(7, PocketBrewingMath.bottleSlots(30));
    }

    @Test
    void brewTimeMatchesTheDesignTable() {
        assertEquals(18.0, PocketBrewingMath.brewSeconds(1), 0.05);
        assertEquals(16.2, PocketBrewingMath.brewSeconds(2), 0.05);
        assertEquals(13.1, PocketBrewingMath.brewSeconds(4), 0.05);
        assertEquals(11.8, PocketBrewingMath.brewSeconds(5), 0.05);
        assertEquals(7.0, PocketBrewingMath.brewSeconds(10), 0.05);
        assertEquals(0.8, PocketBrewingMath.brewSeconds(30), 0.05);
        assertEquals(1, PocketBrewingMath.brewTicks(10_000));
    }

    @Test
    void refuelStartsAtFiveMinutes() {
        assertEquals(300.0, PocketBrewingMath.refuelSeconds(1), 0.05);
        assertEquals(270.0, PocketBrewingMath.refuelSeconds(2), 0.05);
        assertEquals(218.7, PocketBrewingMath.refuelSeconds(4), 0.05);
        assertEquals(116.2, PocketBrewingMath.refuelSeconds(10), 0.1);
        assertEquals(40.5, PocketBrewingMath.refuelSeconds(20), 0.1);
        assertEquals(1, PocketBrewingMath.refuelTicks(10_000));
    }

    /** Up to the game's tick floor: from 67 points brew time and the refuel wait are a few ticks long. */
    @Test
    void everyPointChangesAStandlessBrewingChip() {
        for (int r = 1; r < 66; r++) {
            assertTrue(PocketBrewingMath.brewTicks(r) != PocketBrewingMath.brewTicks(r + 1)
                    || PocketBrewingMath.refuelTicks(r) != PocketBrewingMath.refuelTicks(r + 1)
                    || PocketBrewingMath.bottleSlots(r) != PocketBrewingMath.bottleSlots(r + 1),
                "point " + (r + 1) + " changes nothing");
        }
    }

    @Test
    void refuelChipReadsMinutesThenSeconds() {
        assertEquals(PerkStats.num(5.0, PerkStats.Unit.MINUTES), AlchemistPerks.refuelChip(1));
        PerkStats.Num late = (PerkStats.Num) AlchemistPerks.refuelChip(20);
        assertEquals(PerkStats.Unit.SECONDS, late.unit());
        assertEquals(40.5, late.value(), 0.1);
    }

    @Test
    void brewmasterStartsAtAThirdOfALevel() {
        assertEquals(0, BrewmasterMath.percent(0));
        assertEquals(33, BrewmasterMath.percent(1));
        assertEquals(66, BrewmasterMath.percent(2));
        assertEquals(100, BrewmasterMath.percent(3));
        assertEquals(133, BrewmasterMath.percent(4));
        assertEquals(200, BrewmasterMath.percent(6));
        assertEquals(300, BrewmasterMath.percent(9));
        assertEquals(1, PerkOverflow.guaranteed(BrewmasterMath.percent(5)));
        assertEquals(66, PerkOverflow.remainder(BrewmasterMath.percent(5)));
    }

    @Test
    void brewmasterRollsStayInTheirBand() {
        RandomSource random = RandomSource.create(42);
        int ones = 0;
        for (int i = 0; i < 3000; i++) {
            int roll = BrewmasterMath.roll(1, random);
            assertTrue(roll == 0 || roll == 1);
            ones += roll;
            int four = BrewmasterMath.roll(4, random);
            assertTrue(four == 1 || four == 2);
            assertEquals(2, BrewmasterMath.roll(6, random));
        }
        assertTrue(ones > 800 && ones < 1200, "about a third: " + ones);
        assertNotEquals(0, ones);
    }
}
