package games.brennan.dungeontrain.client.live;

/**
 * What a live-headpiece wearer is looking at, as the helmet's aerial tip shows it to everyone
 * nearby: red for a hostile mob, green for any other living thing, blue for a block, unlit for
 * nothing at all.
 *
 * <p>Each constant's {@link #propertyValue()} drives the {@code dungeontrain:antenna_target}
 * item-model predicate; the overrides in {@code models/item/live_headpiece.json} pick the tip
 * swatch at thresholds between neighbouring values ({@code LiveHeadpieceModelTest} pins that).
 * The classification is plain booleans so it can be unit-tested without a level.</p>
 */
public enum AntennaTarget {
    OFF(0f),
    HOSTILE(1f / 3f),
    FRIENDLY(2f / 3f),
    BLOCK(1f);

    private final float propertyValue;

    AntennaTarget(float propertyValue) {
        this.propertyValue = propertyValue;
    }

    /** The model predicate value that selects this tip colour. */
    public float propertyValue() {
        return propertyValue;
    }

    /**
     * A living thing in the way beats the block behind it.
     *
     * @param hitEntity a living entity sits on the look ray before any block
     * @param hostile   that entity is a hostile mob (vanilla {@code Enemy})
     * @param hitBlock  the look ray reaches a block within range
     */
    public static AntennaTarget classify(boolean hitEntity, boolean hostile, boolean hitBlock) {
        if (hitEntity) {
            return hostile ? HOSTILE : FRIENDLY;
        }
        return hitBlock ? BLOCK : OFF;
    }
}
