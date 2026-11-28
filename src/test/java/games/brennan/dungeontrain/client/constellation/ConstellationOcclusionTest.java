package games.brennan.dungeontrain.client.constellation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstellationOcclusionTest {

    @Test
    void walksEveryCellAlongAnAxis() {
        List<int[]> cells = ConstellationOcclusion.cells(new Vec3(0.5, 0.5, 0.5), new Vec3(0.5, 4.5, 0.5));
        assertEquals(5, cells.size());
        assertArrayEquals(new int[] {0, 4, 0}, cells.get(4));
    }

    @Test
    void diagonalStepsOneFaceAtATime() {
        List<int[]> cells = ConstellationOcclusion.cells(new Vec3(0.5, 0.5, 0.5), new Vec3(3.5, 3.2, 0.5));
        for (int i = 1; i < cells.size(); i++) {
            int[] a = cells.get(i - 1), b = cells.get(i);
            int moved = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
            assertEquals(1, moved, "each step crosses exactly one face");
        }
        assertArrayEquals(new int[] {3, 3, 0}, cells.get(cells.size() - 1));
    }

    @Test
    void negativeDirectionsWork() {
        List<int[]> cells = ConstellationOcclusion.cells(new Vec3(0.5, 0.5, 0.5), new Vec3(-2.5, 0.5, -1.5));
        assertArrayEquals(new int[] {-3, 0, -2}, cells.get(cells.size() - 1));
    }
}
