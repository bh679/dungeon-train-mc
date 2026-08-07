package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry tests for {@link PortalClear#forEachSpan} — the section-by-section walk an erase
 * takes so its chunk and section lookups hoist out of the inner loop. No NeoForge bootstrap.
 *
 * <p>The walk has to be exactly equivalent to the naive triple loop it replaced: every cell of the
 * box visited once, nothing outside it, and no span straddling a boundary (which would make the
 * hoisted section wrong for part of its own cells).</p>
 */
final class PortalClearSpanTest {

    /** A basement-depth level: build floor at -48, ceiling below 320. */
    private static final int LEVEL_MIN_Y = -48;
    private static final int LEVEL_MAX_Y = 319;

    private record Span(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    private static List<Span> spansOf(BoundingBox box) {
        return spansOf(box, LEVEL_MIN_Y, LEVEL_MAX_Y);
    }

    private static List<Span> spansOf(BoundingBox box, int levelMinY, int levelMaxY) {
        List<Span> spans = new ArrayList<>();
        PortalClear.forEachSpan(box, levelMinY, levelMaxY,
            (minX, minY, minZ, maxX, maxY, maxZ) ->
                spans.add(new Span(minX, minY, minZ, maxX, maxY, maxZ)));
        return spans;
    }

    private static long volumeOf(BoundingBox box) {
        return (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    }

    /** Every cell the spans visit, asserting on the way that none is visited twice. */
    private static Set<Long> cellsOf(List<Span> spans) {
        Set<Long> cells = new HashSet<>();
        for (Span s : spans) {
            for (int x = s.minX(); x <= s.maxX(); x++) {
                for (int y = s.minY(); y <= s.maxY(); y++) {
                    for (int z = s.minZ(); z <= s.maxZ(); z++) {
                        assertTrue(cells.add(key(x, y, z)),
                            "cell " + x + "," + y + "," + z + " visited twice");
                    }
                }
            }
        }
        return cells;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 4096) << 40) | ((long) (y + 4096) << 20) | (z + 4096);
    }

    @Test
    @DisplayName("a room-sized box in the basement is covered exactly once")
    void coversARoomExactlyOnce() {
        // Straddles a chunk boundary in X and Z and a section boundary in Y, like a real twin does.
        BoundingBox box = new BoundingBox(-10, -47, 6, 24, -30, 20);
        Set<Long> cells = cellsOf(spansOf(box));

        assertEquals(volumeOf(box), cells.size());
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    assertTrue(cells.contains(key(x, y, z)),
                        "cell " + x + "," + y + "," + z + " was never visited");
                }
            }
        }
    }

    @Test
    @DisplayName("no span crosses a chunk or section boundary")
    void spansStayInsideOneSection() {
        for (Span s : spansOf(new BoundingBox(-10, -47, 6, 24, 40, 20))) {
            assertEquals(s.minX() >> 4, s.maxX() >> 4, "span crosses a chunk boundary in X");
            assertEquals(s.minZ() >> 4, s.maxZ() >> 4, "span crosses a chunk boundary in Z");
            assertEquals(s.minY() >> 4, s.maxY() >> 4, "span crosses a section boundary");
        }
    }

    @Test
    @DisplayName("a box inside one section is a single span")
    void singleSectionBoxIsOneSpan() {
        BoundingBox box = new BoundingBox(3, -40, 3, 9, -36, 9);
        List<Span> spans = spansOf(box);
        assertEquals(1, spans.size());
        assertEquals(new Span(3, -40, 3, 9, -36, 9), spans.get(0));
    }

    @Test
    @DisplayName("the walk is clamped to the level, never below the build floor")
    void clampsToTheLevel() {
        // A structure's footprint reaches one row under its floor; the lowest lane sits on the build
        // floor itself, so the box can ask for a row that cannot exist.
        BoundingBox box = new BoundingBox(0, LEVEL_MIN_Y - 4, 0, 4, LEVEL_MIN_Y + 2, 4);
        List<Span> spans = spansOf(box);

        assertTrue(spans.stream().allMatch(s -> s.minY() >= LEVEL_MIN_Y),
            "no span may start below the build floor");
        assertEquals((long) box.getXSpan() * 3 * box.getZSpan(), cellsOf(spans).size(),
            "only the rows inside the level are visited");
    }

    @Test
    @DisplayName("a box entirely outside the level is skipped")
    void whollyOutsideTheLevelIsSkipped() {
        assertEquals(List.of(), spansOf(
            new BoundingBox(0, LEVEL_MIN_Y - 20, 0, 4, LEVEL_MIN_Y - 10, 4)));
        assertEquals(List.of(), spansOf(
            new BoundingBox(0, LEVEL_MAX_Y + 1, 0, 4, LEVEL_MAX_Y + 8, 4)));
    }

    @Test
    @DisplayName("negative coordinates land in the right chunk and section")
    void negativeCoordinates() {
        BoundingBox box = new BoundingBox(-33, -47, -33, -31, -33, -31);
        for (Span s : spansOf(box)) {
            assertEquals(s.minX() >> 4, s.maxX() >> 4);
            assertEquals(s.minZ() >> 4, s.maxZ() >> 4);
            assertEquals(s.minY() >> 4, s.maxY() >> 4);
        }
        assertEquals(volumeOf(box), cellsOf(spansOf(box)).size());
    }

    @Test
    @DisplayName("a masked corridor cell is spared, the room around it is not")
    void maskSparesTheCorridor() {
        // The mask is what keeps an erased room copy from taking its twin corridor with it.
        PortalCorridorMask mask = new PortalCorridorMask(
            List.of(new BoundingBox(0, -47, 0, 3, -41, 3)));
        assertTrue(mask.covers(2, -44, 2));
        assertTrue(mask.covers(0, -47, 0));
        assertTrue(mask.covers(3, -41, 3));
        assertTrue(!mask.covers(4, -44, 2));
        assertTrue(!mask.covers(2, -40, 2));
        assertTrue(!PortalCorridorMask.NONE.covers(2, -44, 2));
    }
}
