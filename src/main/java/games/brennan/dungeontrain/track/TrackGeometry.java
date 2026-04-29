package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.train.CarriageDims;

/**
 * World-space track layout captured once at train-spawn time and attached to
 * the train's {@link games.brennan.dungeontrain.train.TrainTransformProvider}.
 *
 * <p>Coordinates are fixed for the train's lifetime — the train moves along
 * +X but {@code bedY} / {@code railY} / Z-range never change (velocity is
 * hardcoded to +X with zero Y/Z component in {@code TrainCommand}).</p>
 *
 * <p>Layout:
 * <pre>
 *   y = railY   — vanilla rails at Z = trackZMin+1 and Z = trackZMax-1
 *   y = bedY    — 5-block-wide stone-brick bed (trackZMin..trackZMax)
 *   y &lt; bedY — pillars every 8 blocks on X, where ground sits below
 * </pre>
 * Carriage voxels live at {@code y = origin.y ..  origin.y + HEIGHT - 1}, so
 * {@code bedY = origin.y - 2} and {@code railY = origin.y - 1} leave a
 * 1-block visual gap between rails and the carriage floor — the train
 * appears to sit on top of the rails.</p>
 */
public record TrackGeometry(int bedY, int railY, int trackZMin, int trackZMax) {

    public int trackCenterZ() {
        return (trackZMin + trackZMax) / 2;
    }

    /**
     * Derive the standard track layout for a train spawned at world origin
     * (X=0, Z=0). Mirrors the inline derivation in {@code TrainAssembler}:
     * bed two below the carriage floor, rails one above the bed, Z spans
     * the full carriage width starting at Z=0.
     *
     * <p>Used by the worldgen {@code TrackBedFeature} and the deferred
     * pillar pass — neither has access to a live train, but both need the
     * same coordinates. Keep this in sync with {@code TrainAssembler.spawnTrain}'s
     * {@code new TrackGeometry(...)} so the worldgen and live paths agree
     * on which blocks belong to the corridor.</p>
     */
    public static TrackGeometry from(CarriageDims dims, int trainY) {
        return new TrackGeometry(
            trainY - 2,
            trainY - 1,
            0,
            dims.width() - 1
        );
    }
}
