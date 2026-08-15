package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.DimensionalCarriageSizes;
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
 * Dimensional carriage plots are the only ones whose width varies per variant, so they are the only row that
 * cannot use a uniform stride. Widening one used to grow it straight into its neighbour's plot.
 */
class TrackSidePlotsPackingTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @AfterEach
    void tearDown() {
        DimensionalCarriageSizes.clear();
        TrackVariantRegistry.clear();
    }

    /** Far edge of {@code name}'s plot along Z. */
    private static int endZ(String name) {
        return TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, name, DIMS).getZ()
            + TrackSidePlots.footprint(TrackKind.DIMENSIONAL_CARRIAGE, name, DIMS).getZ();
    }

    private static void assertNoOverlap() {
        List<String> names = TrackVariantRegistry.namesFor(TrackKind.DIMENSIONAL_CARRIAGE);
        for (int i = 1; i < names.size(); i++) {
            int previousEnd = endZ(names.get(i - 1));
            int start = TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, names.get(i), DIMS).getZ();
            assertTrue(start >= previousEnd,
                names.get(i) + " starts at " + start + " but " + names.get(i - 1)
                    + " runs to " + previousEnd + " — plots overlap");
        }
    }

    @Test
    @DisplayName("Widening inside the reserved slot moves nothing — the row is not re-laid out")
    void wideningInsideTheSlotMovesNothing() {
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "second");
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "third");
        assertNoOverlap();

        int secondAt13 = TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ();

        // The base slot is 13 + GAP = 18, so anything up to 15 wide still leaves the required
        // 3 blocks of clearance and costs nothing.
        for (int width = 14; width <= 15; width++) {
            DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, width));
            assertEquals(secondAt13,
                TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ(),
                "width " + width + " still fits its slot and must not move the row");
            assertNoOverlap();
        }
    }

    @Test
    @DisplayName("Outgrowing the slot moves the rest of the row by exactly one SLOT_STEP")
    void outgrowingTheSlotMovesByOneStep() {
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "second");
        int before = TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ();

        // 16 wide leaves under 3 blocks of clearance in an 18-block slot, so it buys another 10.
        DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, 16));
        int after = TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ();
        assertEquals(before + TrackSidePlots.SLOT_STEP, after);
        assertNoOverlap();

        // …and the next nine blocks of growth are then free.
        for (int width = 17; width <= 25; width++) {
            DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, width));
            assertEquals(after, TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ(),
                "width " + width + " should still fit the widened slot");
            assertNoOverlap();
        }

        // 26 needs a second step.
        DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, 26));
        assertEquals(before + 2 * TrackSidePlots.SLOT_STEP,
            TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ());
        assertNoOverlap();
    }

    @Test
    @DisplayName("Narrowing gives the slot back, so the row does not creep wider forever")
    void narrowingReleasesTheSlot() {
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "second");
        int base = TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ();

        DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, 25));
        assertTrue(TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ() > base);

        DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, 13));
        assertEquals(base, TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ());
        assertNoOverlap();
    }

    @Test
    @DisplayName("A row of default-sized rooms lays out exactly as it did before sizes were authorable")
    void defaultSizedRow_isUnchanged() {
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "second");
        int stride = TrackSidePlots.footprint(TrackKind.DIMENSIONAL_CARRIAGE, DIMS).getZ() + EditorLayout.GAP;
        assertEquals(TrackSidePlots.Z_BASELINE + stride,
            TrackSidePlots.plotOrigin(TrackKind.DIMENSIONAL_CARRIAGE, "second", DIMS).getZ());
    }

    @Test
    @DisplayName("No overlap at any mix of widths, including the maximum")
    void noOverlapAcrossAMixOfWidths() {
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "second");
        TrackVariantRegistry.register(TrackKind.DIMENSIONAL_CARRIAGE, "third");

        DimensionalCarriageSizes.pending("default", new Vec3i(11, 7, 48));
        DimensionalCarriageSizes.pending("second", new Vec3i(11, 7, 13));
        DimensionalCarriageSizes.pending("third", new Vec3i(11, 7, 30));
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
