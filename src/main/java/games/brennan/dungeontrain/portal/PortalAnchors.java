package games.brennan.dungeontrain.portal;

import java.util.ArrayList;
import java.util.List;

/**
 * The anchor grid the auto-spawner places hallway portals on: a portal belongs at every
 * {@code X = k × spacing} along the track.
 *
 * <p>Deterministic on purpose. Because the grid is a pure function of {@code spacing}, every player
 * and every session agrees on where portals belong without persisting any placement decision —
 * {@link PortalRegistry} only has to record what has actually been stamped, and the spawner fills
 * whatever gaps it finds. Two players riding the same stretch cannot disagree, and a reload cannot
 * shift the grid.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap — same convention as
 * {@link PortalGeometry}.</p>
 */
public final class PortalAnchors {

    private PortalAnchors() {}

    /** Smallest usable spacing. Below this the corridors would overlap their own pocket rooms. */
    public static final int MIN_SPACING = 96;

    /** Spacing that means "auto-spawning is off". */
    public static final int SPACING_OFF = 0;

    /**
     * The anchor X values in {@code [fromX, toX]}, ascending. Empty when spacing is off or the
     * window contains no multiple of it.
     */
    public static List<Integer> anchorsIn(int spacing, int fromX, int toX) {
        List<Integer> anchors = new ArrayList<>();
        if (spacing < MIN_SPACING || toX < fromX) return anchors;

        long first = Math.floorDiv((long) fromX, spacing) * (long) spacing;
        if (first < fromX) first += spacing;

        for (long x = first; x <= toX; x += spacing) {
            anchors.add((int) x);
        }
        return anchors;
    }

    /**
     * The first anchor strictly ahead of {@code x}, or {@link Integer#MIN_VALUE} when spacing is
     * off. Used to tell a player where the next portal will be.
     */
    public static int nextAnchorAfter(int spacing, int x) {
        if (spacing < MIN_SPACING) return Integer.MIN_VALUE;
        return (int) ((Math.floorDiv((long) x, spacing) + 1L) * (long) spacing);
    }
}
