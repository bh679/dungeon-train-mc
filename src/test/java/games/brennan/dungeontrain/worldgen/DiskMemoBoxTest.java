package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DiskMemoBox} — the per-thread bitset behind {@link DiskTestMemo}. */
final class DiskMemoBoxTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    @Test
    @DisplayName("added positions are remembered, others are not")
    void addThenContains() {
        DiskMemoBox box = new DiskMemoBox();
        box.reset(-32, 48, MIN_Y, HEIGHT);
        box.add(-30, 70, 50);
        assertTrue(box.contains(-30, 70, 50));
        assertFalse(box.contains(-30, 71, 50));
        assertFalse(box.contains(-29, 70, 50));
        assertFalse(box.contains(-30, 70, 51));
    }

    @Test
    @DisplayName("margin edges are inside; one block past them is never remembered")
    void boxEdges() {
        DiskMemoBox box = new DiskMemoBox();
        box.reset(0, 0, MIN_Y, HEIGHT);
        int lo = -DiskMemoBox.MARGIN;
        int hi = 16 + DiskMemoBox.MARGIN - 1;
        box.add(lo, MIN_Y, lo);
        box.add(hi, MIN_Y + HEIGHT - 1, hi);
        assertTrue(box.contains(lo, MIN_Y, lo));
        assertTrue(box.contains(hi, MIN_Y + HEIGHT - 1, hi));

        box.add(lo - 1, 0, 0);
        box.add(hi + 1, 0, 0);
        box.add(0, MIN_Y - 1, 0);
        box.add(0, MIN_Y + HEIGHT, 0);
        assertFalse(box.contains(lo - 1, 0, 0));
        assertFalse(box.contains(hi + 1, 0, 0));
        assertFalse(box.contains(0, MIN_Y - 1, 0));
        assertFalse(box.contains(0, MIN_Y + HEIGHT, 0));
    }

    @Test
    @DisplayName("remove forgets only that position")
    void removeOne() {
        DiskMemoBox box = new DiskMemoBox();
        box.reset(16, 16, MIN_Y, HEIGHT);
        box.add(20, 10, 20);
        box.add(21, 10, 20);
        box.remove(20, 10, 20);
        box.remove(500, 10, 20); // outside: ignored
        assertFalse(box.contains(20, 10, 20));
        assertTrue(box.contains(21, 10, 20));
    }

    @Test
    @DisplayName("reset empties the box, including words emptied and refilled since")
    void resetClearsEverything() {
        DiskMemoBox box = new DiskMemoBox();
        box.reset(0, 0, MIN_Y, HEIGHT);
        for (int x = -16; x < 32; x++) {
            for (int y = 60; y < 70; y++) box.add(x, y, 5);
        }
        box.remove(3, 60, 5);
        box.add(3, 60, 5);
        box.reset(0, 0, MIN_Y, HEIGHT);
        for (int x = -16; x < 32; x++) {
            for (int y = 60; y < 70; y++) assertFalse(box.contains(x, y, 5));
        }
    }

    @Test
    @DisplayName("moving to another chunk shifts the box with it")
    void resetMovesBox() {
        DiskMemoBox box = new DiskMemoBox();
        box.reset(0, 0, MIN_Y, HEIGHT);
        box.add(5, 5, 5);
        box.reset(160, -160, MIN_Y, HEIGHT);
        assertFalse(box.contains(5, 5, 5));
        box.add(170, 5, -150);
        assertTrue(box.contains(170, 5, -150));
    }

    @Test
    @DisplayName("a different build height resizes the box")
    void resizesForHeight() {
        DiskMemoBox box = new DiskMemoBox();
        box.reset(0, 0, 0, 256);
        box.add(0, 255, 0);
        box.reset(0, 0, MIN_Y, HEIGHT);
        assertFalse(box.contains(0, 255, 0));
        box.add(0, 319, 0);
        assertTrue(box.contains(0, 319, 0));
    }
}
