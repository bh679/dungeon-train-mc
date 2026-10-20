package games.brennan.dungeontrain.train;

/**
 * Which entities the kill-ahead pass in {@code TrainTickEvents#killEntitiesAhead} may discard: the
 * ones standing in the runway the train is about to drive through, and nothing beside or under it.
 *
 * <h2>The bug this replaced</h2>
 * The old filter picked candidates by bounding-box overlap with the train's box stretched forward,
 * then spared only those whose <i>centre point</i> sat inside the train's box. The two tests
 * disagree for anything whose feet are just under the carriage floor while its body reaches up past
 * it — a mob that walked off a flatbed onto the ground beside the track, or onto the track bed under
 * the train. Its box overlaps, its feet are outside, so it was discarded, anywhere along the train's
 * full length and not only ahead of it. A mob that was knocked off flew clear of the box and lived,
 * which is why only walked-off mobs vanished.
 *
 * <h2>The rule now</h2>
 * Horizontal position is judged by the entity's centre, so an entity is in the runway only if it
 * stands in the strip ahead of the leading face — inside the train's lateral span, and beyond the
 * face along each axis the train moves on. Height is judged by box overlap, so a mob on the track
 * bed ahead (feet below the floor, body in the train's path) still counts. Pure function over
 * primitives so it can be unit-tested without a game bootstrap.
 */
public final class RunwayZone {

    private RunwayZone() {}

    /**
     * {@code minX..maxZ} are the train's world box (the union of every carriage), and
     * {@code signX..signZ} the sign of the train's velocity on each axis (-1, 0 or 1).
     *
     * @param lookahead    how far ahead of the leading face the runway reaches, in blocks
     * @param x            entity centre X
     * @param y            entity feet Y — only used when the train moves vertically
     * @param z            entity centre Z
     * @param boxMinY      bottom of the entity's bounding box
     * @param boxMaxY      top of the entity's bounding box
     * @return true if the entity stands in the runway ahead of the train
     */
    public static boolean isInRunway(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ,
                                     double lookahead, double signX, double signY, double signZ,
                                     double x, double y, double z, double boxMinY, double boxMaxY) {
        if (signX == 0 && signY == 0 && signZ == 0) return false; // idle train: no runway

        // The runway box: the train's box stretched forward along every axis it moves on.
        double rMinX = signX < 0 ? minX - lookahead : minX;
        double rMaxX = signX > 0 ? maxX + lookahead : maxX;
        double rMinY = signY < 0 ? minY - lookahead : minY;
        double rMaxY = signY > 0 ? maxY + lookahead : maxY;
        double rMinZ = signZ < 0 ? minZ - lookahead : minZ;
        double rMaxZ = signZ > 0 ? maxZ + lookahead : maxZ;

        boolean inRunwayFootprint = within(x, rMinX, rMaxX) && within(z, rMinZ, rMaxZ);
        boolean inRunwayHeight = signY == 0
            ? boxMaxY > rMinY && boxMinY < rMaxY
            : within(y, rMinY, rMaxY);
        if (!inRunwayFootprint || !inRunwayHeight) return false;

        // Already level with the train rather than ahead of it: under it, beside it within its
        // span, or aboard. The train is not about to drive into any of those.
        boolean levelWithTrain = within(x, minX, maxX) && within(z, minZ, maxZ)
            && (signY == 0 || within(y, minY, maxY));
        return !levelWithTrain;
    }

    private static boolean within(double v, double min, double max) {
        return v >= min && v <= max;
    }
}
