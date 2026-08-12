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
 * The side rule and the step plan behind a portal-room resize.
 *
 * <p>What these guard is the property the whole feature rests on: grow and shrink are exact inverses.
 * A slab filed by a shrink is looked up by size alone, so the moment a step from {@code s} to
 * {@code s + 1} stops landing on the same face as the step back, a cropped row comes back on the
 * wrong side of the room — or not at all.</p>
 */
class PortalRoomResizeTest {

    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;   // 9 × 7 × 7

    private static Vec3i sizeAt(CarriageDims dims, PortalRoomResize.Axis axis, int value) {
        return PortalRoomResize.with(PortalRoomLayout.builtInSize(dims), axis, value);
    }

    @Test
    @DisplayName("Length and width alternate faces on consecutive steps")
    void growSide_alternates() {
        for (PortalRoomResize.Axis axis : List.of(
                PortalRoomResize.Axis.LENGTH, PortalRoomResize.Axis.WIDTH)) {
            int base = PortalRoomResize.minOf(DEFAULT_DIMS, axis);
            for (int s = base; s < base + 8; s++) {
                assertNotEquals(
                    PortalRoomResize.growSide(DEFAULT_DIMS, axis, s),
                    PortalRoomResize.growSide(DEFAULT_DIMS, axis, s + 1),
                    axis + " should alternate between " + s + " and " + (s + 1));
            }
        }
    }

    @Test
    @DisplayName("Height only ever moves its ceiling — the floor is the corridor's")
    void growSide_heightNeverAlternates() {
        int base = PortalRoomResize.minOf(DEFAULT_DIMS, PortalRoomResize.Axis.HEIGHT);
        for (int s = base; s <= PortalRoomLayout.MAX_HEIGHT; s++) {
            assertEquals(PortalRoomResize.Side.MAX,
                PortalRoomResize.growSide(DEFAULT_DIMS, PortalRoomResize.Axis.HEIGHT, s),
                "height " + s + " must grow at the ceiling");
        }
    }

    @Test
    @DisplayName("A grow and the shrink that undoes it move the same face, at every size")
    void growAndShrink_areInverses() {
        for (PortalRoomResize.Axis axis : PortalRoomResize.Axis.values()) {
            int base = PortalRoomResize.minOf(DEFAULT_DIMS, axis);
            for (int s = base; s < base + 12; s++) {
                PortalRoomResize.Step grow =
                    PortalRoomResize.plan(DEFAULT_DIMS, axis, sizeAt(DEFAULT_DIMS, axis, s), s + 1).get(0);
                PortalRoomResize.Step shrink =
                    PortalRoomResize.plan(DEFAULT_DIMS, axis, sizeAt(DEFAULT_DIMS, axis, s + 1), s).get(0);

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
        for (PortalRoomResize.Axis axis : PortalRoomResize.Axis.values()) {
            int base = PortalRoomResize.minOf(DEFAULT_DIMS, axis);
            Vec3i start = sizeAt(DEFAULT_DIMS, axis, base + 2);

            List<PortalRoomResize.Step> out = PortalRoomResize.plan(DEFAULT_DIMS, axis, start, base + 6);
            Vec3i grown = out.get(out.size() - 1).sizeAfter();
            List<PortalRoomResize.Step> back = PortalRoomResize.plan(DEFAULT_DIMS, axis, grown, base + 2);

            List<PortalRoomResize.Step> both = new ArrayList<>(out);
            both.addAll(back);
            assertEquals(Vec3i.ZERO, PortalRoomResize.totalShift(both),
                axis + ": a there-and-back resize must not drift the room");
            assertEquals(start, back.get(back.size() - 1).sizeAfter(), axis + ": back to the start size");
        }
    }

    @Test
    @DisplayName("A MIN-face grow shifts the contents up one, a MAX-face grow leaves them alone")
    void shift_followsTheFaceThatMoved() {
        PortalRoomResize.Axis axis = PortalRoomResize.Axis.WIDTH;
        int base = PortalRoomResize.minOf(DEFAULT_DIMS, axis);

        for (PortalRoomResize.Step step
                : PortalRoomResize.plan(DEFAULT_DIMS, axis, sizeAt(DEFAULT_DIMS, axis, base), base + 4)) {
            Vec3i expected = step.side() == PortalRoomResize.Side.MIN
                ? PortalRoomResize.along(axis, 1)
                : Vec3i.ZERO;
            assertEquals(expected, step.shift(), "grow at " + step.side());
            assertEquals(step.side() == PortalRoomResize.Side.MIN ? 0 : step.memoryKey(),
                step.slabIndex(), "the row added sits on the face that moved");
        }
    }

    @Test
    @DisplayName("A typed size splits across the two faces exactly as tapping the stepper would")
    void typedJump_matchesRepeatedSteps() {
        PortalRoomResize.Axis axis = PortalRoomResize.Axis.LENGTH;
        Vec3i start = sizeAt(DEFAULT_DIMS, axis, 11);

        List<PortalRoomResize.Step> jump = PortalRoomResize.plan(DEFAULT_DIMS, axis, start, 21);

        List<PortalRoomResize.Step> tapped = new ArrayList<>();
        Vec3i size = start;
        for (int i = 0; i < 10; i++) {
            List<PortalRoomResize.Step> one =
                PortalRoomResize.plan(DEFAULT_DIMS, axis, size, PortalRoomResize.of(size, axis) + 1);
            tapped.addAll(one);
            size = one.get(0).sizeAfter();
        }

        assertEquals(tapped, jump, "one jump of ten must equal ten jumps of one");
        assertTrue(jump.stream().anyMatch(s -> s.side() == PortalRoomResize.Side.MIN)
                && jump.stream().anyMatch(s -> s.side() == PortalRoomResize.Side.MAX),
            "a ten-block jump should have grown both ends");
    }

    @Test
    @DisplayName("A whole-box plan covers every axis that changed, and nothing that did not")
    void wholeBoxPlan_touchesOnlyChangedAxes() {
        Vec3i from = PortalRoomLayout.builtInSize(DEFAULT_DIMS);          // 11 × 7 × 13
        Vec3i to = PortalRoomLayout.clampSize(DEFAULT_DIMS, new Vec3i(15, 7, 13));

        List<PortalRoomResize.Step> steps = PortalRoomResize.plan(DEFAULT_DIMS, from, to);

        assertEquals(4, steps.size(), "four blocks of length, nothing else");
        assertTrue(steps.stream().allMatch(s -> s.axis() == PortalRoomResize.Axis.LENGTH));
        assertEquals(to, steps.get(steps.size() - 1).sizeAfter());
    }

    @Test
    @DisplayName("Nothing to do is an empty plan, not a no-op restamp")
    void noChange_isAnEmptyPlan() {
        Vec3i size = PortalRoomLayout.builtInSize(DEFAULT_DIMS);
        assertTrue(PortalRoomResize.plan(DEFAULT_DIMS, size, size).isEmpty());
        assertTrue(PortalRoomResize.plan(DEFAULT_DIMS, PortalRoomResize.Axis.LENGTH, size,
            size.getX()).isEmpty());
    }

    @Test
    @DisplayName("The alternation counts from this world's floor, not from zero")
    void alternation_isRelativeToTheWorldsMinimum() {
        CarriageDims wide = CarriageDims.clamp(9, 21, 7);
        PortalRoomResize.Axis axis = PortalRoomResize.Axis.WIDTH;

        // Whatever the floor is, the first step up from it is the same face in every world —
        // otherwise a room authored in one world would grow the other way in another.
        assertEquals(PortalRoomResize.Side.MAX,
            PortalRoomResize.growSide(DEFAULT_DIMS, axis, PortalRoomResize.minOf(DEFAULT_DIMS, axis)));
        assertEquals(PortalRoomResize.Side.MAX,
            PortalRoomResize.growSide(wide, axis, PortalRoomResize.minOf(wide, axis)));
        assertNotEquals(PortalRoomResize.minOf(DEFAULT_DIMS, axis), PortalRoomResize.minOf(wide, axis),
            "the two worlds should genuinely have different floors, or this proves nothing");
    }
}
