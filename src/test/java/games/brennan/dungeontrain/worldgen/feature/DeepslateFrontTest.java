package games.brennan.dungeontrain.worldgen.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stone→deepslate front: far half never, near half always, a ±WAVE ragged band in between. */
final class DeepslateFrontTest {

    @Test
    @DisplayName("outside the wobble band the answer is fixed; inside it varies with position")
    void frontShape() {
        long seed = 3L;
        int half = DeepslateFront.half(900);        // 450
        assertTrue(half == 450);
        int inside = 0, total = 0;
        for (int gap = 0; gap < 900; gap += 3) {
            boolean anyTrue = false, anyFalse = false;
            for (int x = 0; x < 400; x += 8) {
                for (int y = 70; y < 200; y += 10) {
                    boolean d = DeepslateFront.isDeepslate(seed, gap, half, x, y, 40);
                    anyTrue |= d; anyFalse |= !d;
                }
            }
            if (gap >= half + DeepslateFront.WAVE) { assertFalse(anyTrue, "deepslate far out at gap " + gap); assertFalse(DeepslateFront.mayApply(gap, half)); }
            else if (gap < half - DeepslateFront.WAVE) { assertFalse(anyFalse, "stone near the core at gap " + gap); assertTrue(DeepslateFront.mayApply(gap, half)); }
            else { total++; if (anyTrue && anyFalse) inside++; }
        }
        assertTrue(inside > total / 2, "the boundary band should be mixed: " + inside + "/" + total);
    }

    @Test
    @DisplayName("the front is coherent: neighbouring cells rarely disagree")
    void coherent() {
        long seed = 9L;
        int half = 450, flips = 0, n = 0;
        for (int x = 0; x < 512; x++) {
            for (int y = 80; y < 160; y += 4) {
                n++;
                if (DeepslateFront.isDeepslate(seed, half, half, x, y, 0) != DeepslateFront.isDeepslate(seed, half, half, x + 1, y, 0)) flips++;
            }
        }
        assertTrue(flips < n / 10, "front flips too often: " + flips + "/" + n);
    }
}
