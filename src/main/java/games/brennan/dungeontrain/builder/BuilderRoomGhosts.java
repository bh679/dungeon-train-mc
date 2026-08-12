package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Train Builder draws around an open portal room to show what its mode does at the walls.
 *
 * <p>The mode is the coarsest thing about a room and the least visible: a Bedrock room and a
 * Repeating room are stamped identically in the builder and behave nothing alike in a run. Drawing
 * the boundary the mode implies is the only way that difference is on screen at all.</p>
 *
 * <p><b>Drawn, never stamped.</b> Nothing here touches the world, so a ghost cannot be edited, saved
 * into the template, or confuse the dirty check — which is why this is a render-time geometry table
 * rather than a second call to the room stamp. Same reasoning as {@code BuilderGhostSlots} on the
 * carriage side.</p>
 *
 * <p>Pure, so the arithmetic is testable without a client.</p>
 */
public final class BuilderRoomGhosts {

    /** How much of a tile is drawn — the answer to a cost problem, not a stylistic choice. */
    public enum Detail {
        /**
         * Every exposed face of the room, copied. What actually sells "this room repeats", and
         * affordable only for the ring you can reach out and touch.
         */
        FULL,
        /**
         * The room's box, six quads. A full window is 120 tiles and a default 11×7×13 room has on the
         * order of a thousand exposed faces, so copying faces everywhere is ~100k quads against a
         * renderer ceiling of 16k. Past the first ring the silhouette is all that reads at distance
         * anyway.
         */
        SHELL
    }

    /**
     * One copy of the room, on the tile grid.
     *
     * @param tileX Chebyshev grid step on X — multiply by the room's length for the world offset
     * @param tileZ grid step on Z
     * @param ring  Chebyshev distance from the room itself; drives both detail and fade
     */
    public record Tile(int tileX, int tileZ, int ring, Detail detail) {}

    /**
     * What to draw for one open room.
     *
     * @param shell      a one-block skin hugging the room — Bedrock Lock's boundary, and no tiling
     * @param planesOnly the tiles carry the floor and ceiling rows only, not the walls between them:
     *                   {@link PortalRoomMode#ENDLESS_OPEN}, whose whole claim is that there are no
     *                   walls
     */
    public record Ghosts(List<Tile> tiles, boolean shell, boolean planesOnly, int maxRing) {

        public boolean isEmpty() {
            return tiles.isEmpty() && !shell;
        }
    }

    private static final Ghosts NOTHING = new Ghosts(List.of(), false, false, 0);

    /** Rings drawn face-for-face. One: the touching neighbours, and nothing further. */
    private static final int FULL_DETAIL_RING = 1;

    private BuilderRoomGhosts() {}

    /**
     * The ghosts for a room of {@code size} in {@code mode}.
     *
     * <p>The window is {@link PortalRoomTiling}'s, not a number chosen here — {@code MAX_RADIUS}
     * narrowed by {@link PortalRoomTiling#budgetTiles}, which is what the game will actually build
     * around this room. A ghost that tiled further than the real thing would be a confident lie
     * about how far the space goes.</p>
     */
    public static Ghosts of(PortalRoomMode mode, Vec3i size) {
        if (mode == null || size == null
                || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return NOTHING;
        }
        // Bedrockless is the mode whose boundary is *nothing at all* for VOID_CLEARANCE blocks.
        // Drawing anything for it would contradict the one thing it says about itself.
        if (!mode.tiles()) {
            return mode.clearsSurroundings()
                    ? NOTHING
                    : new Ghosts(List.of(), /*shell*/ true, false, 0);   // BEDROCK_LOCK's skin
        }

        int radius = radiusFor(size);
        List<Tile> tiles = new ArrayList<>();
        for (int tx = -radius; tx <= radius; tx++) {
            for (int tz = -radius; tz <= radius; tz++) {
                int ring = Math.max(Math.abs(tx), Math.abs(tz));
                if (ring == 0) {
                    continue;   // the room itself is real blocks, not a ghost of one
                }
                tiles.add(new Tile(tx, tz, ring,
                        ring <= FULL_DETAIL_RING ? Detail.FULL : Detail.SHELL));
            }
        }
        return new Ghosts(List.copyOf(tiles), false, !mode.tilesWholeRoom(), radius);
    }

    /**
     * How many rings this room may tile, from the same budget the world uses.
     *
     * <p>{@code budgetTiles} answers in tiles over the whole square window, so it comes back as a
     * radius: a budget of 9 tiles is a 3×3 window, which is radius 1.</p>
     */
    public static int radiusFor(Vec3i size) {
        int blocksPerTile = size.getX() * size.getY() * size.getZ();
        int budget = PortalRoomTiling.budgetTiles(blocksPerTile);
        int side = (int) Math.floor(Math.sqrt(budget));
        int radius = (side - 1) / 2;
        return Math.max(0, Math.min(PortalRoomTiling.MAX_RADIUS, radius));
    }

    /**
     * How opaque ring {@code ring} is drawn, as a fraction of the base alpha.
     *
     * <p>Fades to nothing at the outer ring rather than stopping at full strength, so the window
     * ends the way the room's own fog would end it ({@link PortalRoomLayout#VOID_FOG_MIN}) instead of
     * announcing a hard edge the real space does not have.</p>
     */
    public static float fadeFor(int ring, int maxRing) {
        if (maxRing <= 0 || ring <= 0) {
            return 1.0f;
        }
        float t = (float) (ring - 1) / Math.max(1, maxRing);
        return Math.max(0.0f, 1.0f - t);
    }
}
