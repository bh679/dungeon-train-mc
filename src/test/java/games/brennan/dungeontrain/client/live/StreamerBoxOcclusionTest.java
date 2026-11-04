package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.StreamerBoxOcclusion.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StreamerBoxOcclusionTest {

    private static final Rect BOX = new Rect(100, 0, 200, 60);

    private static int area(List<Rect> rects) {
        int a = 0;
        for (Rect r : rects) a += (r.x1() - r.x0()) * (r.y1() - r.y0());
        return a;
    }

    @Test
    @DisplayName("nothing over the box: drawn whole")
    void nothingOver() {
        assertEquals(List.of(BOX), StreamerBoxOcclusion.visible(BOX, List.of(new Rect(0, 100, 50, 150))));
    }

    @Test
    @DisplayName("a panel covering the box hides it")
    void covered() {
        assertTrue(StreamerBoxOcclusion.visible(BOX, List.of(new Rect(50, -10, 250, 100))).isEmpty());
    }

    @Test
    @DisplayName("a panel over part of the box: the rest shows, none of the panel, no overlap between parts")
    void partlyCovered() {
        Rect effects = new Rect(150, 20, 260, 40);
        List<Rect> parts = StreamerBoxOcclusion.visible(BOX, List.of(effects));
        assertEquals(100 * 60 - 50 * 20, area(parts));
        for (Rect p : parts) assertFalse(p.overlaps(effects));
        for (int i = 0; i < parts.size(); i++)
            for (int j = i + 1; j < parts.size(); j++) assertFalse(parts.get(i).overlaps(parts.get(j)));
    }

    @Test
    @DisplayName("two overlapping occluders are not cut twice")
    void twoOccluders() {
        List<Rect> parts = StreamerBoxOcclusion.visible(BOX, List.of(new Rect(90, 10, 130, 30), new Rect(120, 20, 140, 50)));
        // inside the box: 30x20 + 20x30 - 10x10 overlap = 1100 covered
        assertEquals(100 * 60 - 1100, area(parts));
    }

    @Test
    @DisplayName("a backpack's padded slots merge into one panel; a separate panel stays separate")
    void clusters() {
        List<Rect> slots = List.of(new Rect(0, 0, 32, 32), new Rect(18, 0, 50, 32), new Rect(0, 18, 32, 50),
            new Rect(300, 0, 332, 32));
        List<Rect> panels = StreamerBoxOcclusion.clusters(slots);
        assertEquals(2, panels.size());
        assertTrue(panels.contains(new Rect(0, 0, 50, 50)));
        assertTrue(panels.contains(new Rect(300, 0, 332, 32)));
    }

    @Test
    @DisplayName("backpack hover: inside a backpack panel only")
    void overBackpack() {
        var layers = new StreamerBoxOcclusion.Layers(List.of(), List.of(new Rect(0, 0, 10, 10)));
        assertTrue(layers.overBackpack(5, 5));
        assertFalse(layers.overBackpack(15, 5));
    }
}
