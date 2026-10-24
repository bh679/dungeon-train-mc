package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.command.PerkDebug;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerkRegistryTest {

    @Test
    @DisplayName("the nine tier-1 perks are registered under their kebab-case ids")
    void tierOneIdsRegistered() {
        PerkRegistry.bootstrap();
        Set<String> ids = PerkRegistry.ids();
        for (String id : new String[] {"health", "speed", "damage", "stackable-book-quill", "friendliness",
                "see-trade-values", "bigger-backpacks", "craft-level-stats", "drift-chance"}) {
            assertTrue(ids.contains(id), id);
            PerkDef def = PerkRegistry.get(id).orElseThrow();
            assertEquals(1, def.tier());
            assertEquals(PerkDef.DEFAULT_MAX_RANK, def.maxRank());
        }
        assertEquals(PerkClass.TANK, PerkRegistry.get("health").orElseThrow().perkClass());
        assertEquals(PerkClass.BUILDER, PerkRegistry.get("drift-chance").orElseThrow().perkClass());
    }

    @Test
    @DisplayName("ids must be kebab-case, tiers 1..3, cost 1/5/10")
    void defValidation() {
        assertThrows(IllegalArgumentException.class, () -> new PerkDef("Health", PerkClass.TANK, 1));
        assertThrows(IllegalArgumentException.class, () -> new PerkDef("see_trade", PerkClass.TRADER, 1));
        assertThrows(IllegalArgumentException.class, () -> new PerkDef("x", PerkClass.TANK, 0));
        assertThrows(IllegalArgumentException.class, () -> new PerkDef("x", PerkClass.TANK, 4));
        assertEquals(1, PerkDef.unlockCostFor(1));
        assertEquals(5, PerkDef.unlockCostFor(2));
        assertEquals(10, PerkDef.unlockCostFor(3));
        assertEquals(10, new PerkDef("x", PerkClass.TANK, 3).unlockCost());
        PerkDef d = new PerkDef("x", PerkClass.TANK, 1, 5);
        assertEquals(5, d.clampRank(99));
        assertEquals(0, d.clampRank(-1));
    }

    @Test
    @DisplayName("re-registering the same definition is a no-op; a different one under the same id throws")
    void duplicateIds() {
        PerkDef a = PerkRegistry.register("perk-registry-test-dup", PerkClass.WARLOCK, 2);
        PerkDef again = PerkRegistry.register("perk-registry-test-dup", PerkClass.WARLOCK, 2);
        assertEquals(a, again);
        assertSame(a, PerkRegistry.get(a.id()).orElseThrow());
        assertThrows(IllegalStateException.class,
            () -> PerkRegistry.register("perk-registry-test-dup", PerkClass.WARLOCK, 3));
    }

    @Test
    @DisplayName("debug command is offered only in dev builds")
    void debugCommandGate() {
        // PR CI is a dev build, main is not: assert the gate follows the build, not a constant.
        assertEquals(DungeonTrain.isDevBuild(), PerkDebug.available());
    }
}
