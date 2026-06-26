package games.brennan.dungeontrain.train;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-stage carriage-preview pick ({@link CarriagePartAssignment#pickPerPlacementForStage}) that
 * backs the editor's "select a stage → preview every carriage for it" feature, plus a guard that the
 * shared refactor left the gate/spawn path ({@link CarriagePartAssignment#pickPerPlacement}) intact.
 *
 * <p>Pure list/string/weight logic — no Minecraft bootstrap (same style as {@code GateContextTest}).
 * The stage path filters on the entry's {@code stageId} link only: no gate evaluation, and crucially
 * <b>no</b> ungated fallback — an empty result is the "air out this slot" signal.</p>
 */
final class CarriagePartStagePreviewTest {

    private static WeightedName floor(String name, String stageId) {
        return new WeightedName(name, 1, SideMode.BOTH, EndMode.BOTH, TemplateGate.DEFAULT, stageId);
    }

    private static CarriagePartAssignment withFloor(WeightedName... entries) {
        return CarriagePartAssignment.EMPTY.with(CarriagePartKind.FLOOR, List.of(entries));
    }

    @Test
    @DisplayName("stage filter keeps only entries linked to the selected stage")
    void keepsOnlyLinkedStage() {
        CarriagePartAssignment a = withFloor(
            floor("stonefloor", "nether"),
            floor("woodfloor", "end"));

        assertEquals(List.of("stonefloor"),
            a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "nether"));
        assertEquals(List.of("woodfloor"),
            a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "end"));
    }

    @Test
    @DisplayName("nothing linked to the stage → empty list (the 'air out the slot' signal)")
    void emptyWhenNothingLinked() {
        CarriagePartAssignment a = withFloor(
            floor("stonefloor", "nether"),
            floor("woodfloor", null));   // unlinked (Custom)

        assertTrue(a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "end").isEmpty(),
            "no entry links to 'end' — must return empty, NOT fall back to the full pool");
    }

    @Test
    @DisplayName("a [none] slot links to no stage → always empty (flatbed stays air)")
    void noneEntryNeverMatches() {
        CarriagePartAssignment a = withFloor(floor(CarriagePartKind.NONE, null));
        assertTrue(a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "nether").isEmpty());
    }

    @Test
    @DisplayName("stage id match is case-insensitive")
    void caseInsensitive() {
        CarriagePartAssignment a = withFloor(floor("stonefloor", "Nether"));
        assertEquals(List.of("stonefloor"),
            a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "NETHER"));
    }

    @Test
    @DisplayName("stage filter ignores the inline gate — a linked entry is shown regardless of its gate band")
    void ignoresInlineGate() {
        // A part linked to 'nether' but with an inline gate that would exclude level 0 / Overworld.
        WeightedName gated = new WeightedName("lavafloor", 1, SideMode.BOTH, EndMode.BOTH,
            new TemplateGate(50, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER)), "nether");
        CarriagePartAssignment a = withFloor(gated);
        assertEquals(List.of("lavafloor"),
            a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "nether"),
            "the per-stage preview is keyed on the stage LINK, not gate overlap");
    }

    @Test
    @DisplayName("WALLS resolve two placements; both are the stage's wall (SideMode BOTH mirrors)")
    void wallsTwoPlacements() {
        WeightedName wall = new WeightedName("brickwall", 1, SideMode.BOTH, EndMode.BOTH,
            TemplateGate.DEFAULT, "nether");
        WeightedName otherStage = new WeightedName("icewall", 1, SideMode.BOTH, EndMode.BOTH,
            TemplateGate.DEFAULT, "end");
        CarriagePartAssignment a = CarriagePartAssignment.EMPTY
            .with(CarriagePartKind.WALLS, List.of(wall, otherStage));

        List<String> picks = a.pickPerPlacementForStage(CarriagePartKind.WALLS, 1L, 0, false, false, "nether");
        assertEquals(2, picks.size(), "WALLS has two placements");
        assertEquals(List.of("brickwall", "brickwall"), picks, "SideMode BOTH mirrors the one nether wall");
    }

    @Test
    @DisplayName("determinism: same (seed,index,stage) → same pick")
    void deterministic() {
        CarriagePartAssignment a = withFloor(
            floor("a", "nether"), floor("b", "nether"), floor("c", "nether"));
        List<String> first = a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 7L, 3, false, false, "nether");
        List<String> again = a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 7L, 3, false, false, "nether");
        assertEquals(first, again);
        assertTrue(List.of("a", "b", "c").contains(first.get(0)));
    }

    @Test
    @DisplayName("refactor guard: the gateless spawn/preview path still resolves a single entry and stays deterministic")
    void gatelessPathUnaffected() {
        CarriagePartAssignment single = withFloor(floor("onlyfloor", null));
        // gateCtx == null → no gating, no stage filter: today's behaviour.
        assertEquals(List.of("onlyfloor"),
            single.pickPerPlacement(CarriagePartKind.FLOOR, 1L, 0, false, false, (games.brennan.dungeontrain.template.GateContext) null));

        CarriagePartAssignment many = withFloor(floor("x", null), floor("y", null), floor("z", null));
        List<String> p1 = many.pickPerPlacement(CarriagePartKind.FLOOR, 42L, 5, false, false, (games.brennan.dungeontrain.template.GateContext) null);
        List<String> p2 = many.pickPerPlacement(CarriagePartKind.FLOOR, 42L, 5, false, false, (games.brennan.dungeontrain.template.GateContext) null);
        assertEquals(p1, p2, "deterministic on (seed,index)");
        assertTrue(List.of("x", "y", "z").contains(p1.get(0)));
        // Different carriage indices vary the draw across this 3-way pool (sanity that the mixer still keys on index).
        boolean anyDiffers = false;
        for (int i = 0; i < 8 && !anyDiffers; i++) {
            anyDiffers = !many.pickPerPlacement(CarriagePartKind.FLOOR, 42L, i, false, false, (games.brennan.dungeontrain.template.GateContext) null)
                .equals(p1);
        }
        assertTrue(anyDiffers, "varying the carriage index should change at least one pick across a 3-way pool");
    }

    @Test
    @DisplayName("blank stage id selects nothing")
    void blankStageSelectsNothing() {
        CarriagePartAssignment a = withFloor(floor("stonefloor", "nether"));
        assertTrue(a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "").isEmpty());
        assertNotEquals(List.of(),
            a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, "nether"));
    }
}
