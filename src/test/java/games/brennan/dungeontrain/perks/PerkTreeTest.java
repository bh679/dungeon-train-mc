package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkTreeTest {

    private static final String FIXTURE = """
        {"v":1,
         "classes":[{"id":"c7","name":"Tank","color":"#e8956a"},{"id":"c8","name":"Speedster","color":"#7fb4f0"}],
         "perks":[
          {"id":"root","cls":null,"name":"The Engine","desc":"","x":400,"y":400},
          {"id":"health","cls":"c7","name":"Health","desc":"More max health.","tier":1,"x":460,"y":486},
          {"id":"armour","cls":"c7","name":"Armour","desc":"","tier":2,"x":479,"y":599},
          {"id":"speed","cls":"c8","name":"Speed","desc":"","tier":1,"x":336,"y":483},
          {"id":"haste","cls":"c8","name":"Haste","desc":"","tier":2,"x":313,"y":566},
          {"id":"totem","cls":"c7","name":"Totem","desc":"","tier":3,"x":541,"y":639}],
         "links":[
          {"a":"root","b":"health","kind":"req","oneWay":false},
          {"a":"root","b":"speed","kind":"req","oneWay":false},
          {"a":"health","b":"armour","kind":"req","oneWay":false},
          {"a":"speed","b":"haste","kind":"req","oneWay":false},
          {"a":"armour","b":"haste","kind":"cross","oneWay":false},
          {"a":"haste","b":"totem","kind":"cross","oneWay":true}]}
        """;

    private static PerkTree tree() {
        return PerkTreeLoader.parse(new StringReader(FIXTURE));
    }

    @Test
    @DisplayName("Engine-linked perks are reachable from nothing")
    void engineLinked() {
        PerkTree t = tree();
        assertTrue(t.reachable("health", Set.of()::contains));
        assertTrue(t.reachable("speed", Set.of()::contains));
        assertFalse(t.reachable("armour", Set.of()::contains));
        assertFalse(t.reachable("root", Set.of()::contains));
    }

    @Test
    @DisplayName("a requirement neighbour unlocked opens the next star")
    void requirement() {
        PerkTree t = tree();
        assertTrue(t.reachable("armour", Set.of("health")::contains));
        assertFalse(t.reachable("armour", Set.of("speed")::contains));
    }

    @Test
    @DisplayName("dashed lines (chords) open nothing, whichever way they point")
    void chords() {
        PerkTree t = tree();
        assertFalse(t.reachable("haste", Set.of("armour")::contains));
        assertFalse(t.reachable("armour", Set.of("haste")::contains));
        assertFalse(t.reachable("totem", Set.of("haste")::contains));
        assertTrue(t.reachable("haste", Set.of("speed")::contains), "a solid line still opens it");
    }

    @Test
    @DisplayName("a Coming soon perk is crossed on the way to the perks behind it")
    void passThrough() {
        PerkTree t = tree();
        assertFalse(t.reachable("armour", Set.of()::contains));
        assertTrue(t.reachable("armour", Set.of()::contains, Set.of("health")::contains), "through Coming soon health");
        assertFalse(t.reachable("armour", Set.of()::contains, Set.of("haste")::contains), "a chord is never crossed");
        assertFalse(t.reachable("totem", Set.of()::contains, Set.of("health", "armour")::contains),
            "only solid lines carry the path");
    }

    private static final Predicate<String> NONE = Set.<String>of()::contains;

    @Test
    @DisplayName("unlock path: solid lines from the Engine, through perks holding points, all the way")
    void unlockPathMustReachEngine() {
        PerkTree t = tree();
        assertTrue(t.pathOpen("health", NONE, NONE, false), "beside the Engine");
        assertTrue(t.pathOpen("armour", Set.of("health")::contains, NONE, false));
        assertFalse(t.pathOpen("armour", NONE, NONE, false));
        assertTrue(t.pathOpen("armour", Set.of("armour", "health")::contains, NONE, false), "repeat unlock");
        assertFalse(t.pathOpen("armour", Set.of("armour", "haste")::contains, NONE, false),
            "a held neighbour cut off from the Engine opens nothing");
        assertFalse(t.pathOpen("armour", Set.of("speed", "haste")::contains, NONE, false), "dashes never carry an unlock");
        assertTrue(t.pathOpen("armour", NONE, Set.of("health")::contains, false), "Coming soon counts as holding points");
        assertFalse(t.pathOpen("root", NONE, NONE, false));
        assertFalse(t.pathOpen("nope", NONE, NONE, false));
    }

    @Test
    @DisplayName("active path: solid or dashed lines from the Engine through perks with active points")
    void activePathTakesDashes() {
        PerkTree t = tree();
        assertTrue(t.pathOpen("armour", Set.of("speed", "haste")::contains, NONE, true), "speed, haste, then a dash");
        assertTrue(t.pathOpen("totem", Set.of("speed", "haste")::contains, NONE, true), "a one-way dash counts too");
        assertFalse(t.pathOpen("totem", Set.of("haste")::contains, NONE, true), "haste is cut off without speed");
        assertTrue(t.pathOpen("totem", Set.of("haste")::contains, Set.of("speed")::contains, true), "through Coming soon");
        assertFalse(t.pathOpen("armour", NONE, NONE, true));
        assertTrue(t.pathOpen("speed", NONE, NONE, true));
    }

    @Test
    @DisplayName("Drift Gift Repaid is reachable through locked Drift Chance in the bundled tree")
    void bundledLockedBuilderEntry() {
        PerkTree t = PerkTreeLoader.get();
        assertFalse(PerkRegistry.isBuilt("drift-chance"), "Drift Chance is locked");
        assertFalse(t.reachable("drift-gift-repaid", Set.of()::contains));
        assertTrue(t.reachableNow("drift-gift-repaid", Set.of()::contains));
    }

    @Test
    @DisplayName("with every built perk holding points, every built perk in the bundled tree can take more")
    void bundledTreeHasNoUnfillablePerk() {
        PerkTree t = PerkTreeLoader.get();
        PerkRegistry.bootstrap();
        Predicate<String> all = id -> true;
        for (PerkTree.Perk p : t.perks()) {
            if (p.isRoot() || !PerkRegistry.isBuilt(p.id())) continue;
            assertTrue(t.reachableNow(p.id(), all), p.id() + " has no solid path");
            assertTrue(t.activeReachable(p.id(), all), p.id() + " has no path");
        }
    }

    @Test
    @DisplayName("a perk removed from the tree (Gift Live Eye) is gone, and a saved unlock of it costs nothing")
    void removedPerkIsHarmless() {
        PerkTree t = PerkTreeLoader.get();
        assertTrue(t.perk("gift-live-eye").isEmpty());
        assertEquals(t.unlockCostOf(java.util.Map.of("vote-weight", 1)),
            t.unlockCostOf(java.util.Map.of("vote-weight", 1, "gift-live-eye", 2)));
        assertFalse(t.reachableNow("gift-live-eye", Set.of()::contains));
    }

    @Test
    @DisplayName("costs follow the tier and sum over the unlocked set")
    void costs() {
        PerkTree t = tree();
        assertEquals(1, t.perk("health").orElseThrow().unlockCost());
        assertEquals(2, t.perk("armour").orElseThrow().unlockCost());
        assertEquals(3, t.perk("totem").orElseThrow().unlockCost());
        assertEquals(6, t.unlockCostOf(Set.of("health", "armour", "totem", "not-a-perk")));
    }

    @Test
    @DisplayName("the class title hangs off the Engine-linked perk")
    void firstPerk() {
        assertEquals("health", tree().firstPerkOf("c7").orElseThrow().id());
    }

    @Test
    @DisplayName("a floating star, a bad id or a moved root is refused")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> PerkTreeLoader.parse(new StringReader(FIXTURE.replace("\"x\":400,\"y\":400", "\"x\":10,\"y\":400"))));
        assertThrows(IllegalArgumentException.class, () -> PerkTreeLoader.parse(new StringReader(FIXTURE.replace("\"id\":\"haste\"", "\"id\":\"Haste\""))));
        String floating = FIXTURE.replace("{\"a\":\"root\",\"b\":\"speed\",\"kind\":\"req\",\"oneWay\":false},", "")
            .replace("{\"a\":\"speed\",\"b\":\"haste\",\"kind\":\"req\",\"oneWay\":false},", "");
        assertThrows(IllegalArgumentException.class, () -> PerkTreeLoader.parse(new StringReader(floating)));
    }

    @Test
    @DisplayName("the bundled tree parses, sits on the Engine, and knows every registered perk")
    void bundled() {
        PerkTree t = PerkTreeLoader.get();
        assertTrue(t.perks().size() > 10, "bundled tree loaded");
        assertEquals(PerkTree.CENTRE, t.root().x());
        PerkRegistry.bootstrap();
        for (String id : PerkRegistry.ids()) {
            if (id.startsWith("perk-registry-test")) continue; // PerkRegistryTest's own fixtures
            assertTrue(t.perk(id).isPresent(), "registered perk missing from tree.json: " + id);
        }
        for (PerkTree.Perk p : t.perks()) {
            if (!p.isRoot()) assertTrue(p.cls() != null && t.perkClass(p.cls()).isPresent(), p.id() + " has a class");
        }
    }
}
