package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LostCitySeating}: the footprint sample grid, the percentile pad height and the fill-only beard. */
final class LostCitySeatingTest {

    @Test
    @DisplayName("the sample grid covers the footprint every 8 blocks, both edges included")
    void grid() {
        BoundingBox box = new BoundingBox(100, 60, 200, 140, 90, 223);   // 41 x 24
        int[] xs = new int[128]; int[] zs = new int[128]; int[] n = {0};
        int[] got = LostCitySeating.sampleFloors(box, (x, z) -> { xs[n[0]] = x; zs[n[0]] = z; n[0]++; return 70; });
        assertEquals(7 * 4, got.length);                       // ceil(41/8)+1 = 7 columns, ceil(24/8)+1 = 4 rows
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n[0]; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]); }
        assertEquals(100, minX); assertEquals(140, maxX); assertEquals(200, minZ); assertEquals(223, maxZ);
        assertEquals(4, LostCitySeating.sampleFloors(new BoundingBox(0, 0, 0, 4, 6, 4), (x, z) -> 70).length);  // a car: corners
    }

    @Test
    @DisplayName("the pad sits at the surface block under which 30% of the samples lie: 70% covered")
    void percentile() {
        // first-free heights 61..80 -> surface 60..79; the 30% point of 20 samples is index 5 -> firstFree 66 -> pad 65
        int[] firstFree = new int[20];
        for (int i = 0; i < 20; i++) firstFree[i] = 80 - i;
        assertEquals(65, LostCitySeating.padHeight(firstFree));
        assertEquals(69, LostCitySeating.padHeight(new int[]{70}));                 // one sample: its own surface
        assertEquals(69, LostCitySeating.padHeight(new int[]{70, 70, 70, 70}));      // flat ground: at grade
        int covered = 0;
        for (int f : firstFree) if (f - 1 >= 65) covered++;
        assertTrue(covered >= 14, "70% of 20 samples at or above the pad, got " + covered);
    }

    @Test
    @DisplayName("the fill-only beard raises terrain under and beside the pad and never touches above it")
    void fill() {
        assertEquals(0.0, LostCitySeating.fillContribution(0, 0, 0));
        assertEquals(0.0, LostCitySeating.fillContribution(0, 3, 0));
        assertEquals(0.0, LostCitySeating.fillContribution(3, 5, 2));
        double under = LostCitySeating.fillContribution(0, -1, 0);
        assertTrue(under > 0.4 && under < 0.6, "one block under ground level: " + under);     // vanilla's ~0.55
        assertTrue(LostCitySeating.fillContribution(0, -4, 0) < under);                     // fades with depth
        assertTrue(LostCitySeating.fillContribution(6, -1, 0) < under);                     // fades with distance
        assertTrue(LostCitySeating.fillContribution(6, -1, 0) > 0.0);                       // but the skirt reaches out
        assertEquals(0.0, LostCitySeating.fillContribution(12, -1, 0));                     // to the kernel's edge
        assertEquals(0.0, LostCitySeating.fillContribution(0, -13, 0));
    }
}
