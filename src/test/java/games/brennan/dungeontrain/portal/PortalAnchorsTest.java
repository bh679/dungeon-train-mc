package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for {@link PortalAnchors} — the deterministic grid the auto-spawner places
 * portals on. No NeoForge bootstrap.
 */
final class PortalAnchorsTest {

    private static final int SPACING = 128;

    @Test
    @DisplayName("a window returns each multiple of the spacing inside it, ascending")
    void anchorsInWindow() {
        assertEquals(List.of(128, 256, 384), PortalAnchors.anchorsIn(SPACING, 100, 400));
        assertEquals(List.of(0), PortalAnchors.anchorsIn(SPACING, -50, 50));
        assertEquals(List.of(), PortalAnchors.anchorsIn(SPACING, 129, 255));
    }

    @Test
    @DisplayName("window bounds are inclusive at both ends")
    void inclusiveBounds() {
        assertEquals(List.of(128, 256), PortalAnchors.anchorsIn(SPACING, 128, 256));
    }

    @Test
    @DisplayName("the grid extends behind the origin, so westward travel is covered too")
    void negativeX() {
        assertEquals(List.of(-256, -128), PortalAnchors.anchorsIn(SPACING, -300, -100));
        assertEquals(-128, PortalAnchors.nextAnchorAfter(SPACING, -200));
    }

    @Test
    @DisplayName("spacing below the minimum, or off, yields nothing")
    void spacingOff() {
        assertTrue(PortalAnchors.anchorsIn(PortalAnchors.SPACING_OFF, 0, 10_000).isEmpty());
        assertTrue(PortalAnchors.anchorsIn(PortalAnchors.MIN_SPACING - 1, 0, 10_000).isEmpty());
        assertEquals(Integer.MIN_VALUE, PortalAnchors.nextAnchorAfter(PortalAnchors.SPACING_OFF, 0));
    }

    @Test
    @DisplayName("nextAnchorAfter is strictly ahead, even standing exactly on an anchor")
    void nextIsStrictlyAhead() {
        assertEquals(128, PortalAnchors.nextAnchorAfter(SPACING, 0));
        assertEquals(256, PortalAnchors.nextAnchorAfter(SPACING, 128));
        assertEquals(256, PortalAnchors.nextAnchorAfter(SPACING, 200));
    }

    /**
     * The property the spawner depends on: sliding the scan window forward tick after tick, as a
     * player travels, must never offer the same anchor twice once it has been built — otherwise a
     * portal would be re-stamped over a corridor a player might be standing in.
     */
    @Test
    @DisplayName("a scan window sliding along the track offers each anchor exactly once")
    void slidingWindowNeverRepeats() {
        Set<Integer> built = new HashSet<>();
        List<Integer> offered = new ArrayList<>();

        for (int px = 0; px <= 2000; px += 16) {           // a player advancing along +X
            for (int anchor : PortalAnchors.anchorsIn(SPACING, px - 64, px + 160)) {
                if (built.contains(anchor)) continue;       // what the registry check does
                built.add(anchor);
                offered.add(anchor);
            }
        }

        assertEquals(offered.size(), new HashSet<>(offered).size(), "an anchor was offered twice");
        assertEquals(List.of(0, 128, 256, 384, 512, 640, 768, 896, 1024, 1152, 1280, 1408, 1536,
            1664, 1792, 1920, 2048), offered);
    }
}
