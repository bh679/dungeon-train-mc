package games.brennan.dungeontrain.train;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.train.CarriagePartAssignment.EndMode;
import games.brennan.dungeontrain.train.CarriagePartAssignment.SideMode;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that the door {@link EndMode} filter in
 * {@link CarriagePartAssignment#pickPerPlacement} restricts each placement to
 * entries whose end-mode matches the placement's flatbed-neighbour status,
 * falls back to the unfiltered pool when filtering empties a placement, and
 * composes correctly with {@link SideMode}.
 */
final class CarriagePartAssignmentPickPerPlacementTest {

    /** Build a doors-only assignment with the given list. Floor/walls/roof are inert NONE entries. */
    private static CarriagePartAssignment doorsOnly(List<WeightedName> doors) {
        return new CarriagePartAssignment(
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            doors
        );
    }

    @Test
    @DisplayName("End-tagged doors only render at flatbed-facing placements; mid-tagged only at non-flatbed-facing")
    void endModeFiltersPerPlacement() {
        List<WeightedName> doors = List.of(
            new WeightedName("end_door", 1, SideMode.BOTH, EndMode.END),
            new WeightedName("mid_door", 1, SideMode.BOTH, EndMode.MID),
            new WeightedName("any_door", 1, SideMode.BOTH, EndMode.BOTH)
        );
        CarriagePartAssignment a = doorsOnly(doors);

        // Slot 0 of a multi-carriage group: back faces a flatbed pad,
        // front faces the next enclosed carriage.
        // We use many seeds to assert the filter constrains the *whole*
        // possible output set, not just one draw.
        for (long seed = 0; seed < 200; seed++) {
            List<String> picks = a.pickPerPlacement(CarriagePartKind.DOORS, seed, 0, true, false);
            assertEquals(2, picks.size(), "doors always produce two placements");
            String back = picks.get(0);
            String front = picks.get(1);
            // Back placement faces a flatbed → only end_door or any_door allowed.
            assertTrue(back.equals("end_door") || back.equals("any_door"),
                "back pick should not include mid_door (seed=" + seed + ", got " + back + ")");
            // Front placement faces another carriage → only mid_door or any_door allowed.
            assertTrue(front.equals("mid_door") || front.equals("any_door"),
                "front pick should not include end_door (seed=" + seed + ", got " + front + ")");
        }
    }

    @Test
    @DisplayName("All-MID list with a flatbed-facing placement falls back to the unfiltered pool (no empty stamps)")
    void emptyFilteredPoolFallsBack() {
        List<WeightedName> doors = List.of(
            new WeightedName("mid_only", 1, SideMode.BOTH, EndMode.MID)
        );
        CarriagePartAssignment a = doorsOnly(doors);

        // Both ends face flatbeds (e.g. groupSize == 1) — MID-only entry
        // would filter to empty without the fallback.
        List<String> picks = a.pickPerPlacement(CarriagePartKind.DOORS, 42L, 0, true, true);
        assertEquals(List.of("mid_only", "mid_only"), picks,
            "fallback should yield the unfiltered pool's only entry on both sides");
    }

    @Test
    @DisplayName("Same (seed, carriageIndex, flags) always produces identical picks")
    void deterministic() {
        List<WeightedName> doors = List.of(
            new WeightedName("end_a", 1, SideMode.BOTH, EndMode.END),
            new WeightedName("end_b", 1, SideMode.BOTH, EndMode.END),
            new WeightedName("mid_a", 1, SideMode.BOTH, EndMode.MID)
        );
        CarriagePartAssignment a = doorsOnly(doors);
        List<String> first  = a.pickPerPlacement(CarriagePartKind.DOORS, 12345L, 7, true, false);
        List<String> second = a.pickPerPlacement(CarriagePartKind.DOORS, 12345L, 7, true, false);
        assertEquals(first, second, "identical inputs must yield identical picks");

        // Differing flags should normally yield different picks (sanity check that the flags actually do something).
        List<String> different = a.pickPerPlacement(CarriagePartKind.DOORS, 12345L, 7, false, true);
        // We can't assert *guaranteed* difference (a tiny pool could collide),
        // but with this list one of the two placements must change because
        // the candidate pools are disjoint between the two flag combos:
        // (true, false) → back=end_a/end_b, front=mid_a;
        // (false, true) → back=mid_a, front=end_a/end_b.
        assertNotEquals(first, different, "different flatbed-flag combos should yield different placements");
    }

    @Test
    @DisplayName("End-mode filter composes with SideMode=ONE for separate-side picks")
    void endModeWithSideModeOne() {
        // end_only is the only entry that can land on a flatbed-facing
        // side, and its SideMode=ONE forces the OTHER side to pick a
        // different name. The "other" side faces a non-flatbed neighbour
        // and so excludes end_only via the end-mode filter — so it must
        // land on any_door.
        List<WeightedName> doors = List.of(
            new WeightedName("end_only", 1, SideMode.ONE, EndMode.END),
            new WeightedName("any_door", 1, SideMode.BOTH, EndMode.BOTH)
        );
        CarriagePartAssignment a = doorsOnly(doors);

        // Search across enough seeds that we get a draw where end_only
        // is picked first; assert the second pick on the non-flatbed
        // side is any_door (the only remaining option after the
        // end-mode filter excludes end_only).
        boolean sawEndFirst = false;
        for (long seed = 0; seed < 200 && !sawEndFirst; seed++) {
            List<String> picks = a.pickPerPlacement(CarriagePartKind.DOORS, seed, 0, true, false);
            if (picks.get(0).equals("end_only")) {
                sawEndFirst = true;
                assertEquals("any_door", picks.get(1),
                    "front placement (no flatbed) excludes end_only via end-mode filter, so SideMode=ONE must pick any_door");
            }
        }
        assertTrue(sawEndFirst,
            "expected at least one seed in [0, 200) to pick end_only on the back placement");
    }

    @Test
    @DisplayName("JSON round-trip preserves endMode")
    void jsonRoundTrip() {
        CarriagePartAssignment original = doorsOnly(List.of(
            new WeightedName("standard", 5, SideMode.BOTH, EndMode.BOTH),
            new WeightedName("fancy",    3, SideMode.ONE,  EndMode.END),
            new WeightedName("plain",    7, SideMode.BOTH, EndMode.MID)
        ));
        JsonObject json = original.toJson();
        CarriagePartAssignment loaded = CarriagePartAssignment.fromJson(json);
        List<WeightedName> doors = loaded.entries(CarriagePartKind.DOORS);
        assertEquals(3, doors.size());
        assertEquals(EndMode.BOTH, doors.get(0).endMode());
        assertEquals(EndMode.END,  doors.get(1).endMode());
        assertEquals(EndMode.MID,  doors.get(2).endMode());
        // SideMode round-trip still intact too.
        assertEquals(SideMode.BOTH, doors.get(0).sideMode());
        assertEquals(SideMode.ONE,  doors.get(1).sideMode());
        assertEquals(SideMode.BOTH, doors.get(2).sideMode());
    }

    @Test
    @DisplayName("Legacy JSON (no endMode field) defaults to BOTH")
    void legacyJsonDefaultsToBoth() {
        // Build JSON without the endMode field — exactly what older
        // .parts.json files on disk look like.
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty(CarriagePartAssignment.SCHEMA_KEY, CarriagePartAssignment.SCHEMA_VERSION);
        com.google.gson.JsonArray doors = new com.google.gson.JsonArray();
        com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        entry.addProperty("name", "vintage");
        entry.addProperty("weight", 2);
        doors.add(entry);
        obj.add("doors", doors);

        CarriagePartAssignment loaded = CarriagePartAssignment.fromJson(obj);
        WeightedName e = loaded.entries(CarriagePartKind.DOORS).get(0);
        assertEquals("vintage", e.name());
        assertEquals(2, e.weight());
        assertSame(EndMode.BOTH, e.endMode(), "missing endMode field must default to BOTH");
    }

    @Test
    @DisplayName("Walls ignore endMode — flags don't change WALLS picks")
    void wallsIgnoreEndMode() {
        // Walls have two placements but should NOT apply the end-mode
        // filter — that's a doors-only concept per the design.
        List<WeightedName> walls = List.of(
            new WeightedName("solid",   1, SideMode.BOTH, EndMode.END),
            new WeightedName("windows", 1, SideMode.BOTH, EndMode.MID)
        );
        CarriagePartAssignment a = new CarriagePartAssignment(
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            walls,
            List.of(WeightedName.of(CarriagePartKind.NONE)),
            List.of(WeightedName.of(CarriagePartKind.NONE))
        );
        // Same seed + same kind should yield identical picks regardless
        // of flag combination, because walls aren't end-mode filtered.
        List<String> picksA = a.pickPerPlacement(CarriagePartKind.WALLS, 99L, 0, true, false);
        List<String> picksB = a.pickPerPlacement(CarriagePartKind.WALLS, 99L, 0, false, true);
        assertEquals(picksA, picksB, "walls picks should be flag-independent");
    }
}
