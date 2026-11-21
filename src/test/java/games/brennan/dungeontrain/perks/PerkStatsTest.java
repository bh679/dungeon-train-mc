package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.perks.PerkStats.Num;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkStatsTest {

    private static double at(String perk, String stat, int points) {
        PerkStats.Stat s = PerkStats.of(perk).stream().filter(x -> x.id().equals(stat)).findFirst().orElseThrow();
        return ((Num) s.valueAt(points)).value();
    }

    @Test
    @DisplayName("every perk with stats is built and in the tree")
    void statsBelongToBuiltTreePerks() throws Exception {
        try (var tin = PerkTreeLoader.class.getResourceAsStream(PerkTreeLoader.RESOURCE)) {
            PerkTree tree = PerkTreeLoader.parse(new InputStreamReader(tin, StandardCharsets.UTF_8));
            for (String id : PerkStats.perkIds()) {
                if (id.startsWith("test-")) continue;
                assertTrue(tree.perk(id).isPresent(), "stats for perk not in the tree: " + id);
                assertTrue(BuiltPerks.isBuilt(id), "stats for a perk that is not built: " + id);
            }
        }
    }

    @Test
    @DisplayName("Live Eye Safe Duration starts both windows at the relay's 10 minutes")
    void liveEyeWindowsHaveBase() {
        assertEquals(10.0, at("live-eye-safe-duration", "protected", 0), 1e-9);
        assertEquals(10.0, at("live-eye-safe-duration", "community_vote", 0), 1e-9);
        assertEquals(12.5, at("live-eye-safe-duration", "protected", 1), 1e-9);
        assertEquals(12.5, at("live-eye-safe-duration", "community_vote", 1), 1e-9);
    }

    @Test
    @DisplayName("chips read the effect code's own numbers")
    void chipsMatchEffects() {
        assertEquals(1.0, at("vote-weight", "vote_weight", 0), 1e-9);
        assertEquals(3.0, at("vote-weight", "vote_weight", 2), 1e-9);
        assertEquals(100.0, at("cooking-time", "cook_time", 0), 1e-9);
        assertEquals(80.0, at("cooking-time", "cook_time", 1), 1e-9);
        assertEquals(1.0, at("pet-health", "pet_health", 1), 1e-9);
        assertEquals(1.0, at("restock-villager", "restocks", 1), 1e-9);
        assertEquals(1.0, at("good-gifter", "gift_strength", 0), 1e-9);
    }

    @Test
    @DisplayName("heart stats are in hit points and drawn as icons")
    void heartsAreIcons() {
        Num n = (Num) PerkStats.of("pet-health").get(0).valueAt(1);
        assertEquals(Unit.HEARTS, n.unit());
        assertTrue(n.unit().isIcon());
        assertFalse(Unit.PERCENT.isIcon());
    }

    @Test
    @DisplayName("registering a stat id again replaces it, and bad ids are refused")
    void registerReplacesAndValidates() {
        PerkStats.register("test-perk", "a", r -> PerkStats.num(r, Unit.PLAIN));
        PerkStats.register("test-perk", "a", r -> PerkStats.num(r * 2, Unit.PLAIN));
        List<PerkStats.Stat> s = PerkStats.of("test-perk");
        assertEquals(1, s.size());
        assertEquals(6.0, ((Num) s.get(0).valueAt(3)).value(), 1e-9);
        assertEquals(0.0, ((Num) s.get(0).valueAt(-4)).value(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> PerkStats.register("test-perk", "Bad Id", r -> PerkStats.num(0, Unit.PLAIN)));
    }
}
