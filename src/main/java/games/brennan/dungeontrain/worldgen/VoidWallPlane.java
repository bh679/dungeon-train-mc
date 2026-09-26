package games.brennan.dungeontrain.worldgen;

/**
 * The cull half of the see-through wall for one frame: a vertical plane across the track at world X
 * {@code wallX}, past which nothing is drawn. The {@code PortalSealPlane} idea turned on its side: that
 * one hides what lies beyond a horizontal plane, this one what lies beyond a vertical one.
 *
 * <h2>Reaching past it is enough</h2>
 * <p>Culling is by whole boxes — a 16-block chunk section, or a Distant Horizons LOD section that can
 * be hundreds of blocks wide — so a box that <em>straddles</em> the wall is hidden too, not kept.
 * Keeping it would let whatever sits in its far part (an End island just past the void) show through.
 * The near part of a straddling box is the void itself, so nothing is lost there. Only a box that also
 * reaches back to the camera ({@code keepBeforeX}) is kept — the camera's own surroundings are never
 * cut, and nor is anything with an unbounded box.</p>
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
 * @param wallX        hide anything reaching past this
 * @param keepBeforeX  never hide a box reaching back to here — the camera's X
 * @param corridorMinY bottom of the track corridor kept for bodies (the track bed)
 * @param corridorMaxY top of the track corridor kept for bodies (over a carriage)
 * @param corridorMinZ near edge of the track corridor
 * @param corridorMaxZ far edge of the track corridor
 */
public record VoidWallPlane(boolean active, double wallX, double keepBeforeX,
                            double corridorMinY, double corridorMaxY,
                            double corridorMinZ, double corridorMaxZ) {

    /** Draw everything — no wall ahead, or the feature switched off. */
    public static final VoidWallPlane NONE =
            new VoidWallPlane(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0, 0, 0);

    /** Height over the carriage floor the body corridor reaches — a carriage and whatever rides on top. */
    private static final int BODY_HEADROOM = 24;

    /** Whether terrain spanning {@code [minX, maxX]} reaches past the wall and not back to the camera. */
    public boolean hidesTerrain(double minX, double maxX) {
        return active && maxX > wallX && minX > keepBeforeX;
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
     * The plane at {@code wallX} for a camera at {@code cameraX}, with a body corridor {@code width}
     * blocks from Z 0 and from the track bed ({@code trainY - 2}, {@code TrackGeometry.from}'s layout)
     * up over a carriage — or {@link #NONE} when {@code wallX} is infinite.
     */
    public static VoidWallPlane at(double wallX, double cameraX, int trainY, int width) {
        if (Double.isInfinite(wallX) || Double.isNaN(wallX)) return NONE;
        return new VoidWallPlane(true, wallX, cameraX, trainY - 2, trainY + BODY_HEADROOM, 0, width);
    }
}
