package games.brennan.dungeontrain.train;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-stage carriage-preview pick ({@link CarriagePartAssignment#pickPerPlacementForStage}) behind
 * the editor's "select a stage → preview every carriage for it" feature, plus a guard that the shared
 * refactor left the gate/spawn path ({@link CarriagePartAssignment#pickPerPlacement}) intact.
 *
 * <p>Two-tier resolution per slot: (1) entries explicitly linked to the stage via {@code stageId};
 * (2) if none, entries whose effective gate <i>overlaps</i> the stage's own gate (same diff-level band
 * + dimension); air only when neither tier matches. Pure list/string/weight/gate logic — no Minecraft
 * bootstrap (same style as {@code GateContextTest}); unlinked entries keep StageStore out of the path.</p>
 */
final class CarriagePartStagePreviewTest {

    private static final TemplateGate NETHER = new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER));
    private static final TemplateGate OVERWORLD = new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.OVERWORLD));

    /** Default-gate floor entry with an optional explicit stage link. */
    private static WeightedName floor(String name, String stageId) {
        return new WeightedName(name, 1, SideMode.BOTH, EndMode.BOTH, TemplateGate.DEFAULT, stageId);
    }

    /** Unlinked floor entry carrying an inline gate — the candidate kind for the gate-overlap tier. */
    private static WeightedName floorGated(String name, int weight, TemplateGate gate) {
        return new WeightedName(name, weight, SideMode.BOTH, EndMode.BOTH, gate, null);
    }

    private static CarriagePartAssignment withFloor(WeightedName... entries) {
        return CarriagePartAssignment.EMPTY.with(CarriagePartKind.FLOOR, List.of(entries));
    }

    /** Resolve the per-stage FLOOR pick (seed 1, carriage 0, no flatbed neighbours). */
    private static List<String> floorPick(CarriagePartAssignment a, String stageId, TemplateGate stageGate) {
        return a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 1L, 0, false, false, stageId, stageGate);
    }

    // ---------- tier 1: explicit stage link ----------

    @Test
    @DisplayName("tier 1: keeps only entries explicitly linked to the selected stage")
    void keepsOnlyLinkedStage() {
        CarriagePartAssignment a = withFloor(floor("stonefloor", "nether"), floor("woodfloor", "end"));
        assertEquals(List.of("stonefloor"), floorPick(a, "nether", NETHER));
        assertEquals(List.of("woodfloor"), floorPick(a, "end", null));
    }

    @Test
    @DisplayName("tier 1 match is case-insensitive and ignores the entry's inline gate")
    void linkMatchCaseInsensitiveGateIgnored() {
        WeightedName linkedButGated = new WeightedName("lavafloor", 1, SideMode.BOTH, EndMode.BOTH,
            new TemplateGate(50, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER)), "Nether");
        CarriagePartAssignment a = withFloor(linkedButGated);
        // Linked to 'nether' → shown for 'NETHER' regardless of the restrictive inline gate or stage gate.
        assertEquals(List.of("lavafloor"), floorPick(a, "NETHER", new TemplateGate(0, 0, EnumSet.of(TrainPhase.NETHER))));
    }

    @Test
    @DisplayName("explicit link wins over a gate-overlap candidate (tier 2 not consulted)")
    void explicitLinkBeatsOverlap() {
        CarriagePartAssignment a = withFloor(
            floor("linked", "nether"),               // tier 1 (explicit link)
            floorGated("overlap", 1, NETHER));        // would also qualify for tier 2
        assertEquals(List.of("linked"), floorPick(a, "nether", NETHER));
    }

    // ---------- tier 2: gate-overlap fallback ----------

    @Test
    @DisplayName("tier 2: no explicit link → use a part whose effective gate overlaps the stage gate")
    void gateOverlapFallback() {
        CarriagePartAssignment a = withFloor(floorGated("netherfloor", 1, NETHER));   // unlinked, NETHER-gated
        assertEquals(List.of("netherfloor"), floorPick(a, "nether", NETHER),
            "nothing linked to 'nether', but this part's gate overlaps the stage → show it, not air");
    }

    @Test
    @DisplayName("tier 2: a different-dimension part does NOT overlap → air")
    void overlapDimensionMismatch() {
        CarriagePartAssignment a = withFloor(floorGated("owfloor", 1, OVERWORLD));
        assertTrue(floorPick(a, "nether", NETHER).isEmpty(), "an Overworld gate doesn't overlap a NETHER stage");
    }

    @Test
    @DisplayName("tier 2: level bands must intersect (disjoint → air, overlapping → shown)")
    void overlapLevelBand() {
        TemplateGate stageGate = new TemplateGate(0, 10, EnumSet.of(TrainPhase.NETHER));

        CarriagePartAssignment disjoint = withFloor(
            floorGated("highfloor", 1, new TemplateGate(50, 60, EnumSet.of(TrainPhase.NETHER))));
        assertTrue(floorPick(disjoint, "nether", stageGate).isEmpty(), "level 50..60 doesn't intersect the stage's 0..10");

        CarriagePartAssignment overlapping = withFloor(
            floorGated("midfloor", 1, new TemplateGate(5, 15, EnumSet.of(TrainPhase.NETHER))));
        assertEquals(List.of("midfloor"), floorPick(overlapping, "nether", stageGate), "level 5..15 intersects 0..10");
    }

    @Test
    @DisplayName("tier 2 skips the [none] sentinel so an overlapping real part always wins over air")
    void overlapSkipsNone() {
        // none carries the highest weight, but is skipped in the overlap tier → the NETHER floor always shows.
        CarriagePartAssignment a = withFloor(
            new WeightedName(CarriagePartKind.NONE, 10, SideMode.BOTH, EndMode.BOTH, TemplateGate.DEFAULT, null),
            floorGated("netherfloor", 1, NETHER));
        for (long seed = 0; seed < 6; seed++) {
            assertEquals(List.of("netherfloor"),
                a.pickPerPlacementForStage(CarriagePartKind.FLOOR, seed, 0, false, false, "nether", NETHER),
                "[none] is skipped in the overlap fallback, so the overlapping part is the only candidate");
        }
    }

    @Test
    @DisplayName("neither tier matches → empty list (air the slot)")
    void airWhenNeitherTier() {
        CarriagePartAssignment a = withFloor(floorGated("owfloor", 1, OVERWORLD));   // unlinked + non-overlapping
        assertTrue(floorPick(a, "nether", NETHER).isEmpty());
    }

    @Test
    @DisplayName("a [none] slot with no overlapping part stays air (flatbed walls=[none])")
    void noneOnlyStaysAir() {
        CarriagePartAssignment a = withFloor(floor(CarriagePartKind.NONE, null));
        assertTrue(floorPick(a, "nether", NETHER).isEmpty());
    }

    @Test
    @DisplayName("null stage gate → no tier-2 fallback (empty when nothing linked)")
    void nullStageGateNoFallback() {
        CarriagePartAssignment a = withFloor(floorGated("netherfloor", 1, NETHER));
        assertTrue(floorPick(a, "nether", null).isEmpty(),
            "with no stage gate to overlap against, an unlinked part can't qualify");
    }

    // ---------- shared core / refactor guard ----------

    @Test
    @DisplayName("WALLS resolve two placements; SideMode BOTH mirrors the chosen part")
    void wallsTwoPlacements() {
        WeightedName wall = new WeightedName("brickwall", 1, SideMode.BOTH, EndMode.BOTH, TemplateGate.DEFAULT, "nether");
        CarriagePartAssignment a = CarriagePartAssignment.EMPTY.with(CarriagePartKind.WALLS, List.of(wall));
        List<String> picks = a.pickPerPlacementForStage(CarriagePartKind.WALLS, 1L, 0, false, false, "nether", NETHER);
        assertEquals(List.of("brickwall", "brickwall"), picks);
    }

    @Test
    @DisplayName("determinism: same (seed,index,stage) → same pick")
    void deterministic() {
        CarriagePartAssignment a = withFloor(floor("a", "nether"), floor("b", "nether"), floor("c", "nether"));
        List<String> first = a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 7L, 3, false, false, "nether", NETHER);
        assertEquals(first, a.pickPerPlacementForStage(CarriagePartKind.FLOOR, 7L, 3, false, false, "nether", NETHER));
        assertTrue(List.of("a", "b", "c").contains(first.get(0)));
    }

    @Test
    @DisplayName("refactor guard: the gateless spawn/preview path is unchanged and deterministic")
    void gatelessPathUnaffected() {
        CarriagePartAssignment single = withFloor(floor("onlyfloor", null));
        assertEquals(List.of("onlyfloor"),
            single.pickPerPlacement(CarriagePartKind.FLOOR, 1L, 0, false, false, (GateContext) null));

        CarriagePartAssignment many = withFloor(floor("x", null), floor("y", null), floor("z", null));
        List<String> p1 = many.pickPerPlacement(CarriagePartKind.FLOOR, 42L, 5, false, false, (GateContext) null);
        assertEquals(p1, many.pickPerPlacement(CarriagePartKind.FLOOR, 42L, 5, false, false, (GateContext) null));
        assertTrue(List.of("x", "y", "z").contains(p1.get(0)));
    }
}
