package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.perks.effects.alchemist.AlchemistPerks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stepped stat's preview names its next change: "Bottle slots 1 → 2 at 5 points". */
class PerkStatsSteppedTest {

    @Test
    void bottleSlotsLookAheadToTheirNextStep() {
        PerkStats.Stat slots = PerkStats.of(AlchemistPerks.STANDLESS_BREWING).stream()
            .filter(s -> s.id().equals("bottle_slots")).findFirst().orElseThrow();
        assertTrue(slots.stepped());
        assertEquals(5, slots.nextChangeAfter(1));
        assertEquals(5, slots.nextChangeAfter(4));
        assertEquals(10, slots.nextChangeAfter(5));
    }

    @Test
    void ordinaryStatsAreNotStepped() {
        PerkStats.of(AlchemistPerks.STANDLESS_BREWING).stream()
            .filter(s -> !s.id().equals("bottle_slots"))
            .forEach(s -> assertFalse(s.stepped(), s.id()));
    }

    @Test
    void noChangeWithinReachIsMinusOne() {
        PerkStats.registerStepped("test-stepped", "flat", r -> PerkStats.num(1, PerkStats.Unit.PLAIN));
        PerkStats.Stat flat = PerkStats.of("test-stepped").get(0);
        assertEquals(-1, flat.nextChangeAfter(3));
    }
}
