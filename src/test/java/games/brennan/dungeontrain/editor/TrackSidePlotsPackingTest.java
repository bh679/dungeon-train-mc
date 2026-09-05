package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portal room plots are the only ones whose width varies per variant, so they are the only row that
 * cannot use a uniform stride. Widening one used to grow it straight into its neighbour's plot.
 */
class TrackSidePlotsPackingTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @AfterEach
    void tearDown() {
        PortalRoomSizes.clear();
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
    }

    /** Far edge of {@code name}'s plot along Z. */
    private static int endZ(String name) {
        return TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, name, DIMS).getZ()
            + TrackSidePlots.footprint(TrackKind.PORTAL_ROOM, name, DIMS).getZ();
    }

    private static void assertNoOverlap() {
        List<String> names = TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM);
        for (int i = 1; i < names.size(); i++) {
            int previousEnd = endZ(names.get(i - 1));
            int start = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, names.get(i), DIMS).getZ();
            assertTrue(start >= previousEnd,
                names.get(i) + " starts at " + start + " but " + names.get(i - 1)
                    + " runs to " + previousEnd + " — plots overlap");
        }
    }

    @Test
    @DisplayName("Widening inside the reserved slot moves nothing — the row is not re-laid out")
    void wideningInsideTheSlotMovesNothing() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "third");
        assertNoOverlap();

        int secondAt13 = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();

        // The base slot is 13 + GAP = 18, so anything up to 15 wide still leaves the required
        // 3 blocks of clearance and costs nothing.
        for (int width = 14; width <= 15; width++) {
            PortalRoomSizes.pending("default", new Vec3i(11, 7, width));
            assertEquals(secondAt13,
                TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ(),
                "width " + width + " still fits its slot and must not move the row");
            assertNoOverlap();
        }
    }

    @Test
    @DisplayName("Outgrowing the slot moves the rest of the row by exactly one SLOT_STEP")
    void outgrowingTheSlotMovesByOneStep() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        int before = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();

        // 16 wide leaves under 3 blocks of clearance in an 18-block slot, so it buys another 10.
        PortalRoomSizes.pending("default", new Vec3i(11, 7, 16));
        int after = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();
        assertEquals(before + TrackSidePlots.SLOT_STEP, after);
        assertNoOverlap();

        // …and the next nine blocks of growth are then free.
        for (int width = 17; width <= 25; width++) {
            PortalRoomSizes.pending("default", new Vec3i(11, 7, width));
            assertEquals(after, TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ(),
                "width " + width + " should still fit the widened slot");
            assertNoOverlap();
        }

        // 26 needs a second step.
        PortalRoomSizes.pending("default", new Vec3i(11, 7, 26));
        assertEquals(before + 2 * TrackSidePlots.SLOT_STEP,
            TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ());
        assertNoOverlap();
    }

    @Test
    @DisplayName("Narrowing gives the slot back, so the row does not creep wider forever")
    void narrowingReleasesTheSlot() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        int base = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();

        PortalRoomSizes.pending("default", new Vec3i(11, 7, 25));
        assertTrue(TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ() > base);

        PortalRoomSizes.pending("default", new Vec3i(11, 7, 13));
        assertEquals(base, TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ());
        assertNoOverlap();
    }

    @Test
    @DisplayName("A row of default-sized rooms lays out exactly as it did before sizes were authorable")
    void defaultSizedRow_isUnchanged() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        int stride = TrackSidePlots.footprint(TrackKind.PORTAL_ROOM, DIMS).getZ() + EditorLayout.GAP;
        assertEquals(TrackSidePlots.Z_BASELINE + stride,
            TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ());
    }

    @Test
    @DisplayName("No overlap at any mix of widths, including the maximum")
    void noOverlapAcrossAMixOfWidths() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "third");

        PortalRoomSizes.pending("default", new Vec3i(11, 7, 48));
        PortalRoomSizes.pending("second", new Vec3i(11, 7, 13));
        PortalRoomSizes.pending("third", new Vec3i(11, 7, 30));
        assertNoOverlap();
    }

    @Test
    @DisplayName("Fixed-footprint kinds keep their uniform stride")
    void otherKindsAreUnchanged() {
        TrackVariantRegistry.register(TrackKind.TUNNEL_SECTION, "second");
        List<String> names = TrackVariantRegistry.namesFor(TrackKind.TUNNEL_SECTION);

        BlockPos first = TrackSidePlots.plotOrigin(TrackKind.TUNNEL_SECTION, names.get(0), DIMS);
        BlockPos second = TrackSidePlots.plotOrigin(TrackKind.TUNNEL_SECTION, names.get(1), DIMS);
        int stride = TrackSidePlots.footprint(TrackKind.TUNNEL_SECTION, DIMS).getZ() + EditorLayout.GAP;
        assertEquals(first.getZ() + stride, second.getZ());
    }

    /**
     * A member sits on its parent's Z line, so the row has to reserve the deepest member's depth,
     * not the parent's. House is eleven deep; one of its rooms was forty-eight.
     */
    @Test
    @DisplayName("A sub-variant deeper than its parent pushes the next row past it")
    void deepMemberWidensTheParentsSlot() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "house");
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "miniword");
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "next");
        PortalRoomSizes.observe("house", new Vec3i(11, 7, 11));
        PortalRoomSizes.observe("miniword", new Vec3i(48, 30, 48));
        TrackVariantGroupStore.injectForTesting(TrackKind.PORTAL_ROOM, "house",
            TrackVariantGroup.EMPTY.withMember(new TrackVariantGroup.Member("miniword", 1)));

        BlockPos house = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "house", DIMS);
        BlockPos member = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "miniword", DIMS);
        BlockPos next = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "next", DIMS);

        assertEquals(house.getZ(), member.getZ(), "a member shares its parent's Z line");
        assertTrue(member.getX() > house.getX(), "and sits +X of it");
        int memberEnd = member.getZ() + TrackSidePlots.footprint(TrackKind.PORTAL_ROOM, "miniword", DIMS).getZ();
        assertTrue(next.getZ() >= memberEnd + TrackSidePlots.SLOT_MIN_CLEARANCE,
            "next row starts at " + next.getZ() + " but the member runs to " + memberEnd);
    }

    /**
     * The House group as shipped, at its real sizes: eleven members stacked +X of an eleven-long
     * parent, from a nine-long room to a forty-eight-long one. Every pair of neighbours has to be
     * clear of each other along X, cage included.
     */
    @Test
    @DisplayName("The real House group lays its sub-variants out without any two touching")
    void houseMembersDoNotTouch() {
        String[][] rooms = {
            {"house", "11", "7", "11"}, {"evilhouse", "11", "7", "13"}, {"medievalhouse", "30", "11", "23"},
            {"end", "11", "7", "12"}, {"cvspharmacy", "24", "11", "13"}, {"abandonedroom", "20", "11", "26"},
            {"sanemaze", "41", "7", "14"}, {"upsidedown", "21", "11", "20"}, {"randomized", "9", "7", "9"},
            {"miniword", "48", "30", "48"}, {"deserter", "15", "7", "15"}, {"terrarium", "43", "80", "9"}};
        TrackVariantGroup group = TrackVariantGroup.EMPTY;
        for (String[] r : rooms) {
            TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, r[0]);
            PortalRoomSizes.observe(r[0], new Vec3i(Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3])));
            if (!r[0].equals("house")) group = group.withMember(new TrackVariantGroup.Member(r[0], 1));
        }
        TrackVariantGroupStore.injectForTesting(TrackKind.PORTAL_ROOM, "house", group);

        int previousEndX = Integer.MIN_VALUE;
        String previous = null;
        for (String[] r : rooms) {
            BlockPos o = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, r[0], DIMS);
            Vec3i fp = TrackSidePlots.footprint(TrackKind.PORTAL_ROOM, r[0], DIMS);
            assertEquals(Integer.parseInt(r[1]), fp.getX(), r[0] + " lays out at its own length");
            // One block of cage on each side, so two plots need at least two blocks between boxes.
            assertTrue(o.getX() >= previousEndX + 2,
                r[0] + " starts at x=" + o.getX() + " but " + previous + " runs to x=" + previousEndX);
            previousEndX = o.getX() + fp.getX();
            previous = r[0];
        }
    }
}
