package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.perks.PerkStats;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HoarderPerksTest {

    private static PerkStats.Stat stat(String perk, String id) {
        return PerkStats.of(perk).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Luck: 5% a point; 20 points make Luck I certain, then Luck II is the chance")
    void luckOverflow() {
        assertEquals(5, HoarderLuck.percent(1));
        assertEquals(100, HoarderLuck.percent(20));
        RandomSource random = RandomSource.create(3);
        for (int i = 0; i < 100; i++) {
            assertEquals(1, HoarderLuck.rollLevels(20, random));
            int l = HoarderLuck.rollLevels(21, random);
            assertEquals(true, l == 1 || l == 2);
        }
        assertEquals(2.0f, HoarderLuck.effectiveLuck(2.0f, 1), 1e-6);
        assertEquals(1.0f, HoarderLuck.effectiveLuck(0.0f, 1), 1e-6);
    }

    @Test
    @DisplayName("Looting: 10% a point; 10 points make +1 certain, then +2 is the chance")
    void lootingOverflow() {
        assertEquals(10, HoarderLooting.percent(1));
        RandomSource random = RandomSource.create(5);
        for (int i = 0; i < 100; i++) assertEquals(1, HoarderLooting.rollLevels(10, random));
    }

    @Test
    @DisplayName("overflow chips: chance below 100%, then 'always' plus the next level's chance")
    void overflowChips() {
        PerkStats.Stat chance = stat("luck", "luck_chance"), always = stat("luck", "luck_always");
        assertEquals(5.0, ((PerkStats.Num) chance.valueAt(1)).value(), 1e-9);
        assertNull(always.valueAt(1));
        assertNull(chance.valueAt(20));
        assertNotNull(always.valueAt(20));
        assertEquals(5.0, ((PerkStats.Num) chance.valueAt(21)).value(), 1e-9);
        assertEquals(1, always.labelArgsAt(21).length);
        PerkStats.Stat lootChance = stat("looting", "looting_chance");
        assertEquals(1, lootChance.labelArgsAt(1)[0]);
        assertEquals(2, lootChance.labelArgsAt(11)[0]);
        assertEquals(10.0, ((PerkStats.Num) lootChance.valueAt(11)).value(), 1e-9);
    }
}
