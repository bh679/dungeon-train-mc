package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.train.CarriagePartAssignment.EndMode;
import games.brennan.dungeontrain.train.CarriagePartAssignment.SideMode;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the per-entry spawn gate added to {@link CarriagePartAssignment}: out-of-band /
 * out-of-dimension entries drop from the candidate pool before the weighted draw, an emptied pool
 * falls back to the ungated list (a slot is never unfillable), all-default gates leave picks
 * byte-identical (full backward compatibility), and the gate round-trips through JSON while a
 * default gate stays compact.
 */
final class CarriagePartAssignmentGateTest {

    private static WeightedName gated(String name, int weight, TemplateGate gate) {
        return new WeightedName(name, weight, SideMode.BOTH, EndMode.BOTH, gate);
    }

    private static TemplateGate netherOnly() {
        return new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER));
    }

    /** Build a floor-only assignment with the given list; other kinds are inert NONE entries. */
    private static CarriagePartAssignment floorOnly(List<WeightedName> floor) {
        return new CarriagePartAssignment(
            floor,
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(WeightedName.of(CarriagePartKind.NONE))
        );
    }

    @Test
    @DisplayName("Out-of-dimension entry is dropped before the weighted draw")
    void dimensionGateExcludes() {
        CarriagePartAssignment a = floorOnly(List.of(
            gated("ow_floor", 1, TemplateGate.DEFAULT),
            gated("nether_floor", 1, netherOnly())
        ));
        GateContext ow = new GateContext(0, TrainPhase.OVERWORLD);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals("ow_floor", a.pick(CarriagePartKind.FLOOR, seed, 0, ow),
                "nether_floor must never be picked in the Overworld dimension (seed=" + seed + ")");
        }
        // Once the Nether dimension is active both are eligible — reachable across seeds.
        boolean sawNether = false;
        GateContext nether = new GateContext(0, TrainPhase.NETHER);
        for (long seed = 0; seed < 200 && !sawNether; seed++) {
            if (a.pick(CarriagePartKind.FLOOR, seed, 0, nether).equals("nether_floor")) sawNether = true;
        }
        assertTrue(sawNether, "nether_floor should be reachable once its dimension is active");
    }

    @Test
    @DisplayName("Diff-Level band gates the entry")
    void levelGateExcludes() {
        CarriagePartAssignment a = floorOnly(List.of(
            gated("early", 1, TemplateGate.ofLevels(0, 9)),
            gated("late",  1, TemplateGate.ofLevels(10, TemplateGate.ALL))
        ));
        GateContext lvl5  = new GateContext(5, TrainPhase.OVERWORLD);
        GateContext lvl20 = new GateContext(20, TrainPhase.OVERWORLD);
        for (long seed = 0; seed < 100; seed++) {
            assertEquals("early", a.pick(CarriagePartKind.FLOOR, seed, 0, lvl5),
                "only the early-band entry is eligible at Diff-Level 5");
            assertEquals("late", a.pick(CarriagePartKind.FLOOR, seed, 0, lvl20),
                "only the late-band entry is eligible at Diff-Level 20");
        }
    }

    @Test
    @DisplayName("Gate that empties the pool falls back to the ungated list (slot never unfillable)")
    void emptyGateFallsBack() {
        CarriagePartAssignment a = floorOnly(List.of(gated("nether_only", 1, netherOnly())));
        // Overworld context excludes the only entry — the fallback must still return it.
        assertEquals("nether_only",
            a.pick(CarriagePartKind.FLOOR, 7L, 0, new GateContext(0, TrainPhase.OVERWORLD)));
    }

    @Test
    @DisplayName("All-default gates: a non-null context produces identical picks to no gating")
    void defaultGateMatchesUngated() {
        CarriagePartAssignment a = floorOnly(List.of(
            gated("a", 3, TemplateGate.DEFAULT),
            gated("b", 1, TemplateGate.DEFAULT),
            gated("c", 2, TemplateGate.DEFAULT)
        ));
        GateContext ctx = new GateContext(42, TrainPhase.END);
        for (long seed = 0; seed < 300; seed++) {
            assertEquals(
                a.pick(CarriagePartKind.FLOOR, seed, 0, (GateContext) null),
                a.pick(CarriagePartKind.FLOOR, seed, 0, ctx),
                "all-default gates must not change the pick (seed=" + seed + ")");
        }
    }

    @Test
    @DisplayName("NONE sentinel keeps the default gate and survives any context (FLATBED walls stay open)")
    void noneSurvivesGate() {
        CarriagePartAssignment a = new CarriagePartAssignment(
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(
                WeightedName.of(CarriagePartKind.NONE),
                gated("nether_wall", 1, netherOnly())
            ),
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(WeightedName.of(CarriagePartKind.NONE))
        );
        GateContext ow = new GateContext(0, TrainPhase.OVERWORLD);
        for (long seed = 0; seed < 100; seed++) {
            for (String p : a.pickPerPlacement(CarriagePartKind.WALLS, seed, 0, false, false, ow)) {
                assertEquals(CarriagePartKind.NONE, p,
                    "only the default-gated NONE entry is eligible in the Overworld (seed=" + seed + ")");
            }
        }
    }

    @Test
    @DisplayName("Gate mutators rewrite only the matched entry; absent name is a no-op")
    void gateMutators() {
        CarriagePartAssignment a = floorOnly(List.of(
            gated("x", 1, TemplateGate.DEFAULT),
            gated("y", 1, TemplateGate.DEFAULT)
        ));

        CarriagePartAssignment min = a.withMinLevel(CarriagePartKind.FLOOR, "x", 3);
        assertEquals(3, entry(min, "x").gate().minLevel());
        assertEquals(0, entry(min, "y").gate().minLevel(), "other entry untouched");

        // maxLevel steps the ALL sentinel: ALL -> 0 on the first +1.
        CarriagePartAssignment max = a.withMaxLevel(CarriagePartKind.FLOOR, "x", +1);
        assertEquals(0, entry(max, "x").gate().maxLevel());

        CarriagePartAssignment ph = a.togglePhase(CarriagePartKind.FLOOR, "x", TrainPhase.OVERWORLD);
        assertFalse(entry(ph, "x").gate().phases().contains(TrainPhase.OVERWORLD));
        assertTrue(entry(ph, "y").gate().isDefault(), "other entry untouched");

        assertSame(a, a.withMinLevel(CarriagePartKind.FLOOR, "missing", 5), "no match → this unchanged");
    }

    @Test
    @DisplayName("JSON round-trips the gate; a default gate stays compact (back-compat)")
    void jsonRoundTrip() {
        TemplateGate g = new TemplateGate(3, 20, EnumSet.of(TrainPhase.NETHER, TrainPhase.END));
        CarriagePartAssignment a = floorOnly(List.of(
            gated("plain", 5, TemplateGate.DEFAULT),
            gated("fancy", 2, g)
        ));
        JsonObject json = a.toJson();
        assertEquals(CarriagePartAssignment.SCHEMA_VERSION, json.get(CarriagePartAssignment.SCHEMA_KEY).getAsInt());

        CarriagePartAssignment loaded = CarriagePartAssignment.fromJson(json);
        assertTrue(entry(loaded, "plain").gate().isDefault());
        assertEquals(3, entry(loaded, "fancy").gate().minLevel());
        assertEquals(20, entry(loaded, "fancy").gate().maxLevel());
        assertEquals(EnumSet.of(TrainPhase.NETHER, TrainPhase.END), entry(loaded, "fancy").gate().phases());

        // The default-gate entry emits no gate keys — it round-trips exactly like a v2 file.
        JsonObject plainObj = findEntryObject(json, "floor", "plain");
        assertFalse(plainObj.has("minLevel"));
        assertFalse(plainObj.has("maxLevel"));
        assertFalse(plainObj.has("phases"));
    }

    @Test
    @DisplayName("Legacy v2 JSON (no gate fields) loads with the default gate")
    void legacyJsonDefaultsToDefaultGate() {
        JsonObject obj = new JsonObject();
        obj.addProperty(CarriagePartAssignment.SCHEMA_KEY, 2);
        JsonArray floor = new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("name", "vintage");
        e.addProperty("weight", 4);
        floor.add(e);
        obj.add("floor", floor);

        WeightedName loaded = CarriagePartAssignment.fromJson(obj).entries(CarriagePartKind.FLOOR).get(0);
        assertEquals("vintage", loaded.name());
        assertEquals(4, loaded.weight());
        assertTrue(loaded.gate().isDefault(), "missing gate fields must default to TemplateGate.DEFAULT");
    }

    private static WeightedName entry(CarriagePartAssignment a, String name) {
        for (WeightedName e : a.entries(CarriagePartKind.FLOOR)) {
            if (e.name().equals(name)) return e;
        }
        throw new AssertionError("no floor entry named " + name);
    }

    private static JsonObject findEntryObject(JsonObject root, String slot, String name) {
        for (JsonElement el : root.getAsJsonArray(slot)) {
            JsonObject o = el.getAsJsonObject();
            if (o.get("name").getAsString().equals(name)) return o;
        }
        throw new AssertionError("no json entry named " + name);
    }
}
