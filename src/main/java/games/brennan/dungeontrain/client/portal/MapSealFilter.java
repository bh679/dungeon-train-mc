package games.brennan.dungeontrain.client.portal;

import games.brennan.dungeontrain.client.ClientPortalSeal;
import games.brennan.dungeontrain.portal.PortalSealPlane;

/**
 * The seal cut as a minimap sees it — what {@code JmBlockMDSealMixin} and
 * {@code JmChunkHeightSealMixin} ask before JourneyMap maps a block.
 *
 * <p>JourneyMap scans chunks itself, so none of the renderer's seal hooks reach it, and a player in a
 * basement twin watched the sealed world's lava fill the minimap over the room they were standing in
 * (and, from the train, the twins under the terrain floor showed in cave mode). Hiding the same rows
 * here keeps the map to the world the camera can actually see, from either side of a seal.</p>
 *
 * <p>Reads only {@link ClientPortalSeal#cut()}, a volatile record, so JourneyMap's mapping thread may
 * call it freely. The trim rows are hidden with no reach bound: the renderer bounds them only to spare
 * Sable plots, which JourneyMap never maps.</p>
 */
public final class MapSealFilter {

    private MapSealFilter() {}

    /** Whether the minimap should treat the block at row {@code y} as air this frame. */
    public static boolean hidesForMap(int y) {
        return hides(ClientPortalSeal.cut(), y);
    }

    /** A column's heightmap top, lowered to a sealed roof over it so the surface scan starts inside. */
    public static int clampSurfaceY(int height) {
        return clampSurface(ClientPortalSeal.cut(), height);
    }

    static boolean hides(PortalSealPlane.Cut cut, int y) {
        if (!cut.sealed()) return false;
        return cut.hides(y, y + 1) || cut.hidesBlock(y);
    }

    static int clampSurface(PortalSealPlane.Cut cut, int height) {
        // The roof's own trim starts at roofY, so roofY is the lowest hidden row either way.
        if (!cut.sealed() || cut.roofY() == Integer.MAX_VALUE) return height;
        return Math.min(height, cut.roofY());
    }
}
