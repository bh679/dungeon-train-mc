package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceduralTiersSpearTest {

    private static final String[] TIERS = {"wooden", "stone", "copper", "iron", "diamond", "netherite", "golden"};

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
    void copperSitsBetweenStoneAndIron() {
        Map<String, Integer> early = weapons(15);
        assertTrue(early.containsKey("minecraft:stone_sword") && early.containsKey("minecraft:copper_sword"));
        assertTrue(!early.containsKey("minecraft:iron_sword"), "iron now starts after copper");
        Map<String, Integer> mid = weapons(30);
        assertTrue(mid.containsKey("minecraft:copper_sword") && mid.containsKey("minecraft:iron_sword"));
        assertTrue(!weapons(40).containsKey("minecraft:copper_sword"), "copper is gone by level 38");
    }

    @Test
    void spearsAreInTheEarlyPool() {
        assertTrue(weapons(1).keySet().stream().anyMatch(id -> id.endsWith("_spear")));
    }
}
