package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The side rule and the step plan behind a dimensional-carriage resize.
 *
 * <p>What these guard is the property the whole feature rests on: grow and shrink are exact inverses.
 * A slab filed by a shrink is looked up by size alone, so the moment a step from {@code s} to
 * {@code s + 1} stops landing on the same face as the step back, a cropped row comes back on the
 * wrong side of the room — or not at all.</p>
 */
class DimensionalCarriageResizeTest {

    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;   // 9 × 7 × 7

    private static Vec3i sizeAt(CarriageDims dims, DimensionalCarriageResize.Axis axis, int value) {
        return DimensionalCarriageResize.with(DimensionalCarriageLayout.builtInSize(dims), axis, value);
    }

    @Test
    @DisplayName("Length and width alternate faces on consecutive steps")
    void growSide_alternates() {
        for (DimensionalCarriageResize.Axis axis : List.of(
                DimensionalCarriageResize.Axis.LENGTH, DimensionalCarriageResize.Axis.WIDTH)) {
            int base = DimensionalCarriageResize.minOf(DEFAULT_DIMS, axis);
            for (int s = base; s < base + 8; s++) {
                assertNotEquals(
                    DimensionalCarriageResize.growSide(DEFAULT_DIMS, axis, s),
                    DimensionalCarriageResize.growSide(DEFAULT_DIMS, axis, s + 1),
                    axis + " should alternate between " + s + " and " + (s + 1));
            }
        }
    }

    @Test
    @DisplayName("Height only ever moves its ceiling — the floor is the corridor's")
    void growSide_heightNeverAlternates() {
        int base = DimensionalCarriageResize.minOf(DEFAULT_DIMS, DimensionalCarriageResize.Axis.HEIGHT);
        for (int s = base; s <= DimensionalCarriageLayout.MAX_HEIGHT; s++) {
            assertEquals(DimensionalCarriageResize.Side.MAX,
                DimensionalCarriageResize.growSide(DEFAULT_DIMS, DimensionalCarriageResize.Axis.HEIGHT, s),
                "height " + s + " must grow at the ceiling");
        }
    }

    @Test
    @DisplayName("A grow and the shrink that undoes it move the same face, at every size")
    void growAndShrink_areInverses() {
        for (DimensionalCarriageResize.Axis axis : DimensionalCarriageResize.Axis.values()) {
            int base = DimensionalCarriageResize.minOf(DEFAULT_DIMS, axis);
            for (int s = base; s < base + 12; s++) {
                DimensionalCarriageResize.Step grow =
                    DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, sizeAt(DEFAULT_DIMS, axis, s), s + 1).get(0);
                DimensionalCarriageResize.Step shrink =
                    DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, sizeAt(DEFAULT_DIMS, axis, s + 1), s).get(0);

                assertEquals(grow.side(), shrink.side(), axis + " at " + s + ": same face");
                assertEquals(grow.slabIndex(), shrink.slabIndex(), axis + " at " + s + ": same row");
                assertEquals(grow.memoryKey(), shrink.memoryKey(), axis + " at " + s + ": same record");
                assertEquals(s, grow.memoryKey(), "filed under the smaller of the two sizes");
            }
        }
    }

    @Test
    @DisplayName("Growing then shrinking back leaves the contents where they started")
    void roundTrip_leavesNoNetShift() {
        for (DimensionalCarriageResize.Axis axis : DimensionalCarriageResize.Axis.values()) {
            int base = DimensionalCarriageResize.minOf(DEFAULT_DIMS, axis);
            Vec3i start = sizeAt(DEFAULT_DIMS, axis, base + 2);

            List<DimensionalCarriageResize.Step> out = DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, start, base + 6);
            Vec3i grown = out.get(out.size() - 1).sizeAfter();
            List<DimensionalCarriageResize.Step> back = DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, grown, base + 2);

            List<DimensionalCarriageResize.Step> both = new ArrayList<>(out);
            both.addAll(back);
            assertEquals(Vec3i.ZERO, DimensionalCarriageResize.totalShift(both),
                axis + ": a there-and-back resize must not drift the room");
            assertEquals(start, back.get(back.size() - 1).sizeAfter(), axis + ": back to the start size");
        }
    }

    @Test
    @DisplayName("A MIN-face grow shifts the contents up one, a MAX-face grow leaves them alone")
    void shift_followsTheFaceThatMoved() {
        DimensionalCarriageResize.Axis axis = DimensionalCarriageResize.Axis.WIDTH;
        int base = DimensionalCarriageResize.minOf(DEFAULT_DIMS, axis);

        for (DimensionalCarriageResize.Step step
                : DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, sizeAt(DEFAULT_DIMS, axis, base), base + 4)) {
            Vec3i expected = step.side() == DimensionalCarriageResize.Side.MIN
                ? DimensionalCarriageResize.along(axis, 1)
                : Vec3i.ZERO;
            assertEquals(expected, step.shift(), "grow at " + step.side());
            assertEquals(step.side() == DimensionalCarriageResize.Side.MIN ? 0 : step.memoryKey(),
                step.slabIndex(), "the row added sits on the face that moved");
        }
    }

    @Test
    @DisplayName("A typed size splits across the two faces exactly as tapping the stepper would")
    void typedJump_matchesRepeatedSteps() {
        DimensionalCarriageResize.Axis axis = DimensionalCarriageResize.Axis.LENGTH;
        Vec3i start = sizeAt(DEFAULT_DIMS, axis, 11);

        List<DimensionalCarriageResize.Step> jump = DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, start, 21);

        List<DimensionalCarriageResize.Step> tapped = new ArrayList<>();
        Vec3i size = start;
        for (int i = 0; i < 10; i++) {
            List<DimensionalCarriageResize.Step> one =
                DimensionalCarriageResize.plan(DEFAULT_DIMS, axis, size, DimensionalCarriageResize.of(size, axis) + 1);
            tapped.addAll(one);
            size = one.get(0).sizeAfter();
        }

        assertEquals(tapped, jump, "one jump of ten must equal ten jumps of one");
        assertTrue(jump.stream().anyMatch(s -> s.side() == DimensionalCarriageResize.Side.MIN)
                && jump.stream().anyMatch(s -> s.side() == DimensionalCarriageResize.Side.MAX),
            "a ten-block jump should have grown both ends");
    }

    @Test
    @DisplayName("A whole-box plan covers every axis that changed, and nothing that did not")
    void wholeBoxPlan_touchesOnlyChangedAxes() {
        Vec3i from = DimensionalCarriageLayout.builtInSize(DEFAULT_DIMS);          // 11 × 7 × 13
        Vec3i to = DimensionalCarriageLayout.clampSize(DEFAULT_DIMS, new Vec3i(15, 7, 13));

        List<DimensionalCarriageResize.Step> steps = DimensionalCarriageResize.plan(DEFAULT_DIMS, from, to);

        assertEquals(4, steps.size(), "four blocks of length, nothing else");
        assertTrue(steps.stream().allMatch(s -> s.axis() == DimensionalCarriageResize.Axis.LENGTH));
        assertEquals(to, steps.get(steps.size() - 1).sizeAfter());
    }

    @Test
    @DisplayName("Nothing to do is an empty plan, not a no-op restamp")
    void noChange_isAnEmptyPlan() {
        Vec3i size = DimensionalCarriageLayout.builtInSize(DEFAULT_DIMS);
        assertTrue(DimensionalCarriageResize.plan(DEFAULT_DIMS, size, size).isEmpty());
        assertTrue(DimensionalCarriageResize.plan(DEFAULT_DIMS, DimensionalCarriageResize.Axis.LENGTH, size,
            size.getX()).isEmpty());
    }

    @Test
    @DisplayName("Reset drops the stepper's override and leaves the size the template reported")
    void revert_restoresTheSavedSize() {
        DimensionalCarriageSizes.clear();
        Vec3i saved = new Vec3i(11, 7, 13);
        DimensionalCarriageSizes.observe("resettable", saved);
        DimensionalCarriageSizes.pending("resettable", new Vec3i(21, 9, 17));
        assertEquals(21, DimensionalCarriageSizes.sizeOf("resettable", DEFAULT_DIMS).getX(),
            "the override should be what the plot stamps at before a reset");

        DimensionalCarriageSizes.revert("resettable");

        assertEquals(saved, DimensionalCarriageSizes.sizeOf("resettable", DEFAULT_DIMS),
            "reset must fall back to the saved size, not keep the abandoned one");
        DimensionalCarriageSizes.clear();
    }

    @Test
    @DisplayName("Reset on a room that was never saved falls back to the built-in size")
    void revert_withNothingSaved_isTheBuiltInSize() {
        DimensionalCarriageSizes.clear();
        DimensionalCarriageSizes.pending("fresh", new Vec3i(31, 9, 19));

        DimensionalCarriageSizes.revert("fresh");

        assertEquals(DimensionalCarriageLayout.builtInSize(DEFAULT_DIMS),
            DimensionalCarriageSizes.sizeOf("fresh", DEFAULT_DIMS));
        DimensionalCarriageSizes.clear();
    }

    @Test
    @DisplayName("A save keeps the new size — revert is the only thing that throws one away")
    void settle_keepsTheSizeThatRevertWouldDrop() {
        DimensionalCarriageSizes.clear();
        DimensionalCarriageSizes.observe("saved", new Vec3i(11, 7, 13));
        DimensionalCarriageSizes.pending("saved", new Vec3i(15, 7, 13));

        DimensionalCarriageSizes.settle("saved", new Vec3i(15, 7, 13));
        DimensionalCarriageSizes.revert("saved");

        assertEquals(new Vec3i(15, 7, 13), DimensionalCarriageSizes.sizeOf("saved", DEFAULT_DIMS),
            "once saved, the size is the template's — a later reset cannot undo it");
        DimensionalCarriageSizes.clear();
    }

    @Test
    @DisplayName("The alternation counts from this world's floor, not from zero")
    void alternation_isRelativeToTheWorldsMinimum() {
        CarriageDims wide = CarriageDims.clamp(9, 21, 7);
        DimensionalCarriageResize.Axis axis = DimensionalCarriageResize.Axis.WIDTH;

        // Whatever the floor is, the first step up from it is the same face in every world —
        // otherwise a room authored in one world would grow the other way in another.
        assertEquals(DimensionalCarriageResize.Side.MAX,
            DimensionalCarriageResize.growSide(DEFAULT_DIMS, axis, DimensionalCarriageResize.minOf(DEFAULT_DIMS, axis)));
        assertEquals(DimensionalCarriageResize.Side.MAX,
            DimensionalCarriageResize.growSide(wide, axis, DimensionalCarriageResize.minOf(wide, axis)));
        assertNotEquals(DimensionalCarriageResize.minOf(DEFAULT_DIMS, axis), DimensionalCarriageResize.minOf(wide, axis),
            "the two worlds should genuinely have different floors, or this proves nothing");
    }
}
