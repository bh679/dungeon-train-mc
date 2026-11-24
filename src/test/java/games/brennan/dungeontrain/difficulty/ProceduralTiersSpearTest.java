package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceduralTiersSpearTest {

    private static final String[] TIERS = {"wooden", "stone", "iron", "diamond", "netherite", "golden"};

    private static Map<String, Integer> weapons(int level) {
        return ProceduralTiers.tierFor(level).weapon().mainhand().stream()
                .collect(Collectors.toMap(w -> w.item().toString(), DifficultyTier.WeightedItem::weight));
    }

    @Test
    void everySwordInTheWeaponPoolHasASpearOfTheSameWeight() {
        for (int level : new int[]{1, 5, 12, 25, 50, 80}) {
            Map<String, Integer> pool = weapons(level);
            for (String tier : TIERS) {
                Integer sword = pool.get("minecraft:" + tier + "_sword");
                Integer spear = pool.get("minecraft:" + tier + "_spear");
                assertEquals(sword, spear, tier + " at level " + level);
            }
        }
    }

    @Test
    void spearsAreInTheEarlyPool() {
        assertTrue(weapons(1).keySet().stream().anyMatch(id -> id.endsWith("_spear")));
    }
}
