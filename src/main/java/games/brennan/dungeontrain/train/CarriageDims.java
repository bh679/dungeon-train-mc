package games.brennan.dungeontrain.train;

/**
 * Per-world carriage footprint — length × width × height in blocks.
 *
 * <p>Replaces the previous {@code public static final} constants on
 * {@link CarriageTemplate}. Each world captures its dims at creation time
 * (stored on {@code DungeonTrainWorldData}) and the value is fixed for
 * that world's lifetime. Different worlds can have different dims.</p>
 *
 * <p>Floors chosen so the {@link CarriageTemplate#stateAt} geometry still
 * produces a coherent carriage:
 * <ul>
 *   <li>{@code length >= 4} — the four {@link CarriageTemplate.CarriageType}
 *       variants need at least one carriage each to cycle through</li>
 *   <li>{@code width >= 3} — the door is centred at {@code width / 2};
 *       below 3 the door would sit on the corner wall</li>
 *   <li>{@code height >= 3} — the door gap is hard-coded at
 *       {@code dy == 1 || dy == 2}, which needs {@code height - 1} (roof) ≥ 2</li>
 * </ul>
 *
 * <p>Ceilings are a soft performance guard. The block-placement loops
 * {@link CarriageTemplate#eraseAt}, {@link CarriageTemplate#legacyPlaceAt},
 * and {@link CarriageTemplate#collectFootprint} are {@code O(length × width × height)}
 * and are called for every rolling-window add/remove plus each VS
 * {@code ShipAssembler.assembleToShip} pass. A 32×32×24 carriage is
 * 24 576 block operations per shift — tractable; a 64×64×64 carriage
 * (262 144 ops) would stutter the server tick. The ceilings split the
 * difference.</p>
 */
public record CarriageDims(int length, int width, int height) {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 32;
    public static final int DEFAULT_LENGTH = 9;

    public static final int MIN_WIDTH = 3;
    public static final int MAX_WIDTH = 32;
    public static final int DEFAULT_WIDTH = 9;

    public static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 24;
    public static final int DEFAULT_HEIGHT = 7;

    /** Canonical 9×9×7 shipped footprint — used as fallback for legacy world saves. */
    public static final CarriageDims DEFAULT = new CarriageDims(
            DEFAULT_LENGTH, DEFAULT_WIDTH, DEFAULT_HEIGHT
    );

    /**
     * Compact canonical constructor enforces the floor/ceiling invariants so
     * no downstream code ever sees an out-of-range value. Direct record
     * construction will throw on violation.
     */
    public CarriageDims {
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "length " + length + " out of range [" + MIN_LENGTH + ", " + MAX_LENGTH + "]");
        }
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            throw new IllegalArgumentException(
                    "width " + width + " out of range [" + MIN_WIDTH + ", " + MAX_WIDTH + "]");
        }
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
            throw new IllegalArgumentException(
                    "height " + height + " out of range [" + MIN_HEIGHT + ", " + MAX_HEIGHT + "]");
        }
    }

    /**
     * Forgiving factory — clamps each component into its valid range rather
     * than throwing. Use from UI input-validation and NBT-load paths where
     * we'd rather self-heal bad values than crash the world.
     */
    public static CarriageDims clamp(int length, int width, int height) {
        return new CarriageDims(
                Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length)),
                Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)),
                Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height))
        );
    }
}
