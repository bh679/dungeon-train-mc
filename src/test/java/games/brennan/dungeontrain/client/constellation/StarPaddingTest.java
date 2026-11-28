package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarPaddingTest {

    @Test
    void paddingReachesTwiceTheStar() {
        List<float[]> out = new ArrayList<>();
        StarPadding.around(10f, 20f, 3f, out);
        double far = out.stream().mapToDouble(p -> Math.hypot(p[0] - 10f, p[1] - 20f)).max().orElse(0);
        assertEquals(6.0, far, 1e-4);
    }

    @Test
    void discIsFilledToItsEdgeAndNoFurther() {
        List<float[]> out = new ArrayList<>();
        StarPadding.disc(0f, 0f, 40f, 4f, out);
        double far = out.stream().mapToDouble(p -> Math.hypot(p[0], p[1])).max().orElse(0);
        assertEquals(40.0, far, 1e-3);
        // no point of the disc is further than a step from a sample
        for (float x = -38; x <= 38; x += 7) {
            for (float y = -38; y <= 38; y += 7) {
                if (Math.hypot(x, y) > 40) continue;
                final float fx = x, fy = y;
                double nearest = out.stream().mapToDouble(p -> Math.hypot(p[0] - fx, p[1] - fy)).min().orElse(99);
                assertTrue(nearest <= 5.0, "gap at " + x + "," + y + ": " + nearest);
            }
        }
    }
}
