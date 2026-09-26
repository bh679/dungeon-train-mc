package games.brennan.dungeontrain.worldgen;

/**
 * The cull half of the see-through walls for one frame: vertical planes across the track — one ahead at
 * world X {@code wallX}, past which nothing is drawn, and one behind at {@code backX}, before which
 * nothing is drawn. The {@code PortalSealPlane} idea turned on its side: that one hides what lies beyond
 * a horizontal plane, these what lies beyond vertical ones.
 *
 * <h2>Reaching past it is enough</h2>
 * <p>Culling is by whole boxes — a 16-block chunk section, or a Distant Horizons LOD section that can
 * be hundreds of blocks wide — so a box that <em>straddles</em> a wall is hidden too, not kept.
 * Keeping it would let whatever sits in its far part (an End island just past the void) show through.
 * The near part of a straddling box is the void itself, so nothing is lost there. Only a box that also
 * reaches the camera ({@code keepX}) is kept — the camera's own surroundings are never cut, and nor is
 * anything with an unbounded box; the sky pass hides, to the pixel, whatever such a box carries.</p>
 *
 * <h2>Terrain and bodies</h2>
 * <p>{@link #hidesTerrain} is for chunk sections: nothing past the wall survives, the track's sections
 * included (the track past the wall is drawn separately — {@code VoidWallTrackRenderer}). {@link
 * #hidesBody} is for the train's Sable sub-levels and the entities riding it: anything on the track
 * corridor is kept, so the train carries on through the wall with the track.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a bootstrap. The client half, which knows where the
 * camera is and where the wall stands, is {@code ClientVoidWall}.</p>
 *
 * @param active       whether anything is hidden at all — {@code false} short-circuits every test
 * @param wallX        hide anything reaching past this ({@link Double#POSITIVE_INFINITY}: no wall ahead)
 * @param backX        hide anything reaching back before this ({@link Double#NEGATIVE_INFINITY}: none)
 * @param keepX        never hide a box reaching to here — the camera's X
 * @param corridorMinY bottom of the track corridor kept for bodies (the track bed)
 * @param corridorMaxY top of the track corridor kept for bodies (over a carriage)
 * @param corridorMinZ near edge of the track corridor
 * @param corridorMaxZ far edge of the track corridor
 */
public record VoidWallPlane(boolean active, double wallX, double backX, double keepX,
                            double corridorMinY, double corridorMaxY,
                            double corridorMinZ, double corridorMaxZ) {

    /** Draw everything — no wall ahead, or the feature switched off. */
    public static final VoidWallPlane NONE =
            new VoidWallPlane(false, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0, 0, 0, 0);

    /** Height over the carriage floor the body corridor reaches — a carriage and whatever rides on top. */
    private static final int BODY_HEADROOM = 24;

    /** Whether terrain spanning {@code [minX, maxX]} reaches past either wall without reaching the camera. */
    public boolean hidesTerrain(double minX, double maxX) {
        if (!active) return false;
        return (maxX > wallX && minX > keepX) || (minX < backX && maxX < keepX);
    }

    /**
     * Whether a body (a train sub-level, an entity) is hidden: as {@link #hidesTerrain}, except that one
     * on the track corridor is kept — the train and its riders carry on through the wall.
     */
    public boolean hidesBody(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!hidesTerrain(minX, maxX)) return false;
        boolean onTrack = maxY > corridorMinY && minY < corridorMaxY
                && maxZ > corridorMinZ && minZ < corridorMaxZ;
        return !onTrack;
    }

    /**
     * The planes at {@code wallX} ahead and {@code backX} behind for a camera at {@code cameraX}, with a
     * body corridor {@code width} blocks from Z 0 and from the track bed ({@code trainY - 2},
     * {@code TrackGeometry.from}'s layout) up over a carriage — or {@link #NONE} when neither is set.
     */
    public static VoidWallPlane at(double wallX, double backX, double cameraX, int trainY, int width) {
        boolean ahead = !Double.isInfinite(wallX) && !Double.isNaN(wallX);
        boolean behind = !Double.isInfinite(backX) && !Double.isNaN(backX);
        if (!ahead && !behind) return NONE;
        return new VoidWallPlane(true,
                ahead ? wallX : Double.POSITIVE_INFINITY, behind ? backX : Double.NEGATIVE_INFINITY,
                cameraX, trainY - 2, trainY + BODY_HEADROOM, 0, width);
    }
}
