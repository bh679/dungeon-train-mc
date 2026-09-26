package games.brennan.dungeontrain.worldgen;

/**
 * The cull half of the see-through wall for one frame: a vertical plane across the track at world X
 * {@code wallX}, past which nothing is drawn — except the track corridor, so the rails still run on
 * into the distance. The {@code PortalSealPlane} idea turned on its side: that one hides whatever lies
 * wholly beyond a horizontal plane, this one whatever lies wholly beyond a vertical one.
 *
 * <p>No Minecraft types, so it unit-tests without a bootstrap. The client half, which knows where the
 * camera is and where the wall stands, is {@code ClientVoidWall}.</p>
 *
 * @param active          whether anything is hidden at all — {@code false} short-circuits every test
 * @param wallX           hide anything whose X span starts at or past this
 * @param corridorMinY    bottom of the kept track corridor (the track bed)
 * @param corridorMaxY    top of the kept track corridor (above the rails)
 * @param corridorMinZ    near edge of the kept track corridor
 * @param corridorMaxZ    far edge of the kept track corridor
 */
public record VoidWallPlane(boolean active, double wallX,
                            double corridorMinY, double corridorMaxY,
                            double corridorMinZ, double corridorMaxZ) {

    /** Draw everything — no wall ahead, or the feature switched off. */
    public static final VoidWallPlane NONE = new VoidWallPlane(false, Double.POSITIVE_INFINITY, 0, 0, 0, 0);

    /**
     * Whether a box lies wholly past the wall and clear of the track corridor.
     *
     * <p>Wholly, not partly: a box straddling the wall holds blocks on the near side the camera is
     * entitled to see. A box that touches the corridor is kept whole — 16-block granular for a chunk
     * section, so the sections the track runs through survive past the wall with whatever else is in
     * them; the corridor itself is kept clear by the train, so that is mostly the track.</p>
     */
    public boolean hides(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!active || minX < wallX) return false;
        boolean touchesCorridor = maxY > corridorMinY && minY < corridorMaxY
                && maxZ > corridorMinZ && minZ < corridorMaxZ;
        return !touchesCorridor;
    }

    /**
     * The plane at {@code wallX} keeping a corridor of {@code width} blocks from Z 0 and from the track
     * bed ({@code trainY - 2}) up to the carriage floor ({@code trainY}) — {@code TrackGeometry.from}'s
     * layout — or {@link #NONE} when {@code wallX} is infinite.
     */
    public static VoidWallPlane at(double wallX, int trainY, int width) {
        if (Double.isInfinite(wallX) || Double.isNaN(wallX)) return NONE;
        return new VoidWallPlane(true, wallX, trainY - 2, trainY + 1, 0, width);
    }
}
