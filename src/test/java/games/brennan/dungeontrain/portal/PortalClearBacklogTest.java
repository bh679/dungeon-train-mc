package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure bookkeeping tests for {@link PortalClearBacklog}. No NeoForge bootstrap. */
final class PortalClearBacklogTest {

    private static final String MASK = "mask";

    private static BoundingBox span(int chunkX, int minY, int maxY) {
        return new BoundingBox(chunkX << 4, minY, 0, (chunkX << 4) + 15, maxY, 15);
    }

    @Test
    @DisplayName("Stacked sections of one column merge into one clear")
    void stackedSectionsMerge() {
        PortalClearBacklog<String> backlog = new PortalClearBacklog<>();
        backlog.add(span(0, 0, 15), MASK);
        backlog.add(span(0, 16, 31), MASK);
        backlog.add(span(0, 32, 40), MASK);

        assertEquals(1, backlog.size());
        List<PortalClearBacklog.Entry<String>> taken = backlog.takeReady(key -> true, 10, 10);
        assertEquals(span(0, 0, 40), taken.get(0).box());
    }

    @Test
    @DisplayName("The same span queued twice is one clear")
    void duplicateIsOneClear() {
        PortalClearBacklog<String> backlog = new PortalClearBacklog<>();
        backlog.add(span(0, 0, 15), MASK);
        backlog.add(span(0, 0, 15), MASK);
        assertEquals(1, backlog.size());
    }

    @Test
    @DisplayName("A different mask, or a gap in Y, is never merged away")
    void differentMaskOrGapStaysSeparate() {
        PortalClearBacklog<String> backlog = new PortalClearBacklog<>();
        backlog.add(span(0, 0, 15), MASK);
        backlog.add(span(0, 16, 31), new String("other"));
        backlog.add(span(0, 64, 79), new String("other"));
        assertEquals(3, backlog.size());
    }

    @Test
    @DisplayName("A chunk that is not ready keeps its clear until it is")
    void notReadyWaits() {
        PortalClearBacklog<String> backlog = new PortalClearBacklog<>();
        backlog.add(span(0, 0, 15), MASK);

        assertTrue(backlog.takeReady(key -> false, 10, 10).isEmpty());
        assertEquals(1, backlog.size());
        assertEquals(1, backlog.takeReady(key -> true, 10, 10).size());
        assertTrue(backlog.isEmpty());
    }

    @Test
    @DisplayName("The per-tick caps hold, and a chunk that never loads cannot starve the rest")
    void capsAndNoStarvation() {
        PortalClearBacklog<String> backlog = new PortalClearBacklog<>();
        for (int cx = 0; cx < 5; cx++) backlog.add(span(cx, 0, 15), MASK);

        assertEquals(2, backlog.takeReady(key -> true, 10, 2).size());
        assertEquals(3, backlog.size());

        long ready = PortalClearBacklog.chunkKey(4, 0);
        assertTrue(backlog.takeReady(key -> key == ready, 2, 10).isEmpty());
        assertEquals(1, backlog.takeReady(key -> key == ready, 2, 10).size());
    }

    @Test
    @DisplayName("Settling takes only ready chunks the box touches")
    void touchingTakesOnlyReadyOverlap() {
        PortalClearBacklog<String> backlog = new PortalClearBacklog<>();
        backlog.add(span(0, 0, 15), MASK);
        backlog.add(span(1, 0, 15), MASK);
        backlog.add(span(5, 0, 15), MASK);
        long notReady = PortalClearBacklog.chunkKey(1, 0);

        List<PortalClearBacklog.Entry<String>> taken = backlog.takeReadyTouching(
            new BoundingBox(0, 0, 0, 31, 15, 15), key -> key != notReady);

        assertEquals(List.of(span(0, 0, 15)), taken.stream().map(PortalClearBacklog.Entry::box).toList());
        assertEquals(2, backlog.size());
    }

    @Test
    @DisplayName("Negative chunk coordinates keep distinct keys")
    void negativeCoordinatesDistinct() {
        assertTrue(PortalClearBacklog.chunkKey(-1, 0) != PortalClearBacklog.chunkKey(0, -1));
        assertEquals(new net.minecraft.world.level.ChunkPos(-3, 7).toLong(), PortalClearBacklog.chunkKey(-3, 7));
    }
}
