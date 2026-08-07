package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
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
    @DisplayName("Widening one room pushes every later plot along, never into it")
    void wideningPushesLaterPlotsAlong() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "third");
        assertNoOverlap();

        int secondBefore = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();

        // Default goes from its 13-wide floor to 19.
        PortalRoomSizes.pending("default", new Vec3i(11, 7, 19));
        assertNoOverlap();

        int secondAfter = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();
        assertEquals(secondBefore + 6, secondAfter,
            "the later plot should move by exactly the 6 blocks default grew");
    }

    @Test
    @DisplayName("Narrowing pulls them back — the row packs, it does not just grow")
    void narrowingPullsLaterPlotsBack() {
        TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, "second");
        PortalRoomSizes.pending("default", new Vec3i(11, 7, 25));
        int wide = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();

        PortalRoomSizes.pending("default", new Vec3i(11, 7, 13));
        int narrow = TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, "second", DIMS).getZ();

        assertTrue(narrow < wide, "narrowing default should pull 'second' back, got " + narrow + " vs " + wide);
        assertNoOverlap();
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
}
