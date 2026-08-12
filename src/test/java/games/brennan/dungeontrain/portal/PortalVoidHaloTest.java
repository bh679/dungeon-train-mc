package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slabs a Bedrockless room clears — halo minus footprint, carved geometrically rather than
 * masked.
 *
 * <p>The covering is the whole safety argument. A slab that reached into the footprint would clear
 * the corridors, their doors and their plugs, which are the way back to the train and the one part of
 * a twin that has to stay block-identical to the carriage it mirrors; a gap between slabs would leave
 * a wall of rock standing in the middle of the emptiness. So these tests count every cell of the halo
 * exactly rather than checking bounds, which is the form the two failures would actually take.</p>
 */
class PortalVoidHaloTest {

    /** A footprint sitting off-centre in its halo, so no symmetry can hide an off-by-one. */
    private static final BoundingBox HALO = new BoundingBox(-20, -62, -14, 30, -54, 26);
    private static final BoundingBox FOOTPRINT = new BoundingBox(-3, -62, 2, 11, -54, 9);

    private static boolean contains(BoundingBox box, int x, int y, int z) {
        return x >= box.minX() && x <= box.maxX()
            && y >= box.minY() && y <= box.maxY()
            && z >= box.minZ() && z <= box.maxZ();
    }

    private static void assertPartitions(BoundingBox halo, BoundingBox footprint, String name) {
        List<BoundingBox> slabs = PortalCarriageBuilder.voidSlabs(halo, footprint);
        for (int x = halo.minX(); x <= halo.maxX(); x++) {
            for (int y = halo.minY(); y <= halo.maxY(); y++) {
                for (int z = halo.minZ(); z <= halo.maxZ(); z++) {
                    int hits = 0;
                    for (BoundingBox slab : slabs) {
                        if (contains(slab, x, y, z)) hits++;
                    }
                    // Inside the structure: never touched. Outside it: cleared, and cleared once —
                    // overlapping slabs would walk the same cells twice for nothing.
                    int wanted = contains(footprint, x, y, z) ? 0 : 1;
                    assertEquals(wanted, hits, name + " cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("The slabs cover the halo outside the footprint exactly once, and never the footprint")
    void slabsPartitionTheHalo() {
        assertPartitions(HALO, FOOTPRINT, "equal bands:");
    }

    /**
     * The real halo is one row shallower than the footprint — it spares the row under the structure's
     * floor, which is the world's own bedrock in a Compatible Terrain world. The carve is horizontal,
     * so that asymmetry must change nothing about the covering.
     */
    @Test
    @DisplayName("A halo shallower than its footprint still partitions cleanly")
    void shallowerHaloStillPartitions() {
        BoundingBox deeper = new BoundingBox(
            FOOTPRINT.minX(), HALO.minY() - 1, FOOTPRINT.minZ(),
            FOOTPRINT.maxX(), HALO.maxY(), FOOTPRINT.maxZ());
        assertPartitions(HALO, deeper, "shallow halo:");

        for (BoundingBox slab : PortalCarriageBuilder.voidSlabs(HALO, deeper)) {
            assertTrue(slab.minY() >= HALO.minY(),
                "a slab reached below the halo, into the row the sweep must spare: " + slab);
        }
    }

    @Test
    @DisplayName("No slab reaches outside the halo — the clearance is a bound, not a suggestion")
    void slabsStayInsideTheHalo() {
        for (BoundingBox slab : PortalCarriageBuilder.voidSlabs(HALO, FOOTPRINT)) {
            assertTrue(slab.minX() >= HALO.minX() && slab.maxX() <= HALO.maxX(), "x");
            assertTrue(slab.minY() >= HALO.minY() && slab.maxY() <= HALO.maxY(), "y");
            assertTrue(slab.minZ() >= HALO.minZ() && slab.maxZ() <= HALO.maxZ(), "z");
        }
    }

    /**
     * The degenerate case is real, not hypothetical: {@code voidHaloOf} unions the clearance with the
     * footprint, so a world whose corridors and plugs reach further than the clearance does yields a
     * halo flush with the structure on that axis. Emitting an inverted slab there would clear a box
     * with min &gt; max.
     */
    @Test
    @DisplayName("A footprint flush with the halo produces no slab on that side")
    void flushSidesProduceNoSlab() {
        BoundingBox flush = new BoundingBox(
            HALO.minX(), HALO.minY(), FOOTPRINT.minZ(),
            HALO.maxX(), HALO.maxY(), HALO.maxZ());
        List<BoundingBox> slabs = PortalCarriageBuilder.voidSlabs(HALO, flush);

        // Only the -Z side is left, and it is the full width of the footprint.
        assertEquals(1, slabs.size());
        BoundingBox only = slabs.get(0);
        assertEquals(HALO.minZ(), only.minZ());
        assertEquals(flush.minZ() - 1, only.maxZ());

        assertTrue(PortalCarriageBuilder.voidSlabs(HALO, HALO).isEmpty(),
            "a halo the size of its own footprint has nothing to clear");
    }

    @Test
    @DisplayName("Every slab is non-empty — an inverted box would sweep in the wrong direction")
    void slabsAreWellFormed() {
        for (BoundingBox slab : PortalCarriageBuilder.voidSlabs(HALO, FOOTPRINT)) {
            assertFalse(slab.minX() > slab.maxX() || slab.minY() > slab.maxY()
                || slab.minZ() > slab.maxZ(), slab.toString());
        }
    }
}
