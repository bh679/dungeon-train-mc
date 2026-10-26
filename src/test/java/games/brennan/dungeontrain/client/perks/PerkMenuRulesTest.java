package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkRankCost;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkTreeLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The web page's test-mode rules as the in-game menu runs them: class visibility, layout, the Engine clock. */
class PerkMenuRulesTest {

    /** Two classes either side of the Engine: Tank (health, armour) and Speedster (speed, haste), plus a chord. */
    private static final String FIXTURE = """
        {"v":1,
         "classes":[{"id":"tank","name":"Tank","color":"#e8956a"},{"id":"speed","name":"Speedster","color":"#7fb4f0"},
                    {"id":"lover","name":"Lover","color":"#f08aa8"}],
         "perks":[
          {"id":"root","cls":null,"name":"The Engine","desc":"","x":400,"y":400},
          {"id":"health","cls":"tank","name":"Health","desc":"","tier":1,"x":500,"y":400},
          {"id":"armour","cls":"tank","name":"Armour","desc":"","tier":2,"x":600,"y":420},
          {"id":"speed","cls":"speed","name":"Speed","desc":"","tier":1,"x":300,"y":400},
          {"id":"haste","cls":"speed","name":"Haste","desc":"","tier":2,"x":200,"y":380},
          {"id":"friend","cls":"lover","name":"Friend","desc":"","tier":1,"x":400,"y":300}],
         "links":[
          {"a":"root","b":"health","kind":"req","oneWay":false},
          {"a":"health","b":"armour","kind":"req","oneWay":false},
          {"a":"root","b":"speed","kind":"req","oneWay":false},
          {"a":"speed","b":"haste","kind":"req","oneWay":false},
          {"a":"root","b":"friend","kind":"req","oneWay":false},
          {"a":"armour","b":"haste","kind":"cross","oneWay":false}]}
        """;

    private static PerkTree tree() {
        return PerkTreeLoader.parse(new StringReader(FIXTURE));
    }

    private static ClassVisibility vis(Set<String> off, String preview, Map<String, Integer> unlocks, Map<String, Integer> active, int points) {
        return new ClassVisibility(tree(), off, preview, unlocks, active, points);
    }

    private static final Set<String> ALL_OFF = Set.of("tank", "speed", "lover");

    @Test
    @DisplayName("a fresh start with no Advancement Points draws only the Engine")
    void freshStartIsDark() {
        ClassVisibility v = vis(ALL_OFF, null, Map.of(), Map.of(), 0);
        PerkTree t = tree();
        t.perks().stream().filter(p -> !p.isRoot()).forEach(p -> assertTrue(v.hidden(p), p.id()));
        assertFalse(v.hidden(t.root()));
    }

    @Test
    @DisplayName("holding Advancement Points shows the perks a line joins to the Engine, nothing further")
    void pointsRevealTheNextRing() {
        ClassVisibility v = vis(ALL_OFF, null, Map.of(), Map.of(), 1);
        PerkTree t = tree();
        assertFalse(v.hidden(t.perk("health").orElseThrow()));
        assertFalse(v.hidden(t.perk("speed").orElseThrow()));
        assertTrue(v.hidden(t.perk("armour").orElseThrow()));
        assertFalse(v.titleShown("tank"), "a hidden class is not named until a perk in it is unlocked");
    }

    @Test
    @DisplayName("an unlocked perk stays drawn, opens its neighbours by any line, and names its class")
    void unlockedPerkOpensNeighbours() {
        ClassVisibility v = vis(ALL_OFF, null, Map.of("health", 1), Map.of(), 1);
        PerkTree t = tree();
        assertFalse(v.hidden(t.perk("armour").orElseThrow()), "solid neighbour");
        assertTrue(v.titleShown("tank"));
        assertFalse(v.titleLit("tank", null), "muted until a point is in the class");
        ClassVisibility withPoint = vis(ALL_OFF, null, Map.of("health", 1, "armour", 1), Map.of("health", 1), 1);
        assertTrue(withPoint.titleLit("tank", null));
        assertFalse(withPoint.hidden(t.perk("haste").orElseThrow()), "a dashed line shows it too");
    }

    @Test
    @DisplayName("previewing a hidden class draws all of it")
    void previewShowsAll() {
        ClassVisibility v = vis(ALL_OFF, "speed", Map.of(), Map.of(), 0);
        assertFalse(v.hidden(tree().perk("haste").orElseThrow()));
        assertTrue(v.ghost(tree().perk("haste").orElseThrow()));
    }

    @Test
    @DisplayName("classes sort anticlockwise from Lover round the Engine")
    void circleOrder() {
        assertEquals(java.util.List.of("lover", "speed", "tank"),
            ClassVisibility.circleOrder(tree()).stream().map(PerkTree.PerkClass::id).toList());
    }

    @Test
    @DisplayName("every class shown: the tree as authored, nothing moves")
    void allShownIsAuthored() {
        ClassVisibility v = vis(Set.of(), null, Map.of(), Map.of(), 0);
        assertEquals("", SkyLayout.keyOf(tree(), v));
        assertTrue(new SkyLayout().positions(tree(), v, 0).isEmpty());
    }

    @Test
    @DisplayName("one class shown: it keeps its radii and spreads no wider than a third of the circle")
    void oneClassLayout() {
        ClassVisibility v = vis(Set.of("speed", "lover"), null, Map.of(), Map.of(), 0);
        Map<String, double[]> pos = SkyLayout.target(tree(), v);
        PerkTree t = tree();
        for (String id : new String[] {"health", "armour"}) {
            PerkTree.Perk p = t.perk(id).orElseThrow();
            double[] xy = pos.get(id);
            assertEquals(p.distanceToCentre(), Math.hypot(xy[0] - 400, xy[1] - 400), 1e-6, id + " keeps its radius");
        }
        double a = Math.atan2(pos.get("health")[1] - 400, pos.get("health")[0] - 400);
        double b = Math.atan2(pos.get("armour")[1] - 400, pos.get("armour")[0] - 400);
        assertTrue(Math.abs(SkyLayout.wrap(a - b)) <= SkyLayout.MAX_SPREAD * Math.PI * 2 + 1e-9);
    }

    @Test
    @DisplayName("a new layout swings stars over the move time, then lands exactly")
    void layoutAnimates() {
        SkyLayout layout = new SkyLayout();
        PerkTree t = tree();
        layout.positions(t, vis(Set.of(), null, Map.of(), Map.of(), 0), 0);
        ClassVisibility oneShown = vis(Set.of("speed", "lover"), null, Map.of(), Map.of(), 0);
        layout.positions(t, oneShown, 1000);
        assertTrue(layout.moving());
        Map<String, double[]> end = layout.positions(t, oneShown, 1000 + SkyLayout.MOVE_MS);
        assertFalse(layout.moving());
        assertEquals(SkyLayout.target(t, oneShown).get("health")[0], end.get("health")[0], 1e-9);
    }

    @Test
    @DisplayName("the Engine clock: points spent and progress through the price run")
    void engineClock() {
        assertEquals(0, EngineClock.totalFor(0));
        assertEquals(10, EngineClock.totalFor(10), "ten ranks at 1");
        assertEquals(12, EngineClock.totalFor(11), "then 2 each");
        EngineClock.Progress p = EngineClock.progress(3);
        assertEquals(1, p.price());
        assertEquals(10, p.size());
        assertEquals(3, p.done());
        assertEquals(2, EngineClock.progress(10).price());
        assertEquals(0, EngineClock.progress(10).done(), "a new run starts empty");
        assertEquals(PerkRankCost.nextCost(55), EngineClock.progress(55).price());
    }
}
