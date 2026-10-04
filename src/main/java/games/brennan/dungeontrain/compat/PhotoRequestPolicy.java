package games.brennan.dungeontrain.compat;

/**
 * The world-free rule for whether a PlayerMob photographs the player who just gifted it a camera:
 * the gift must be a camera, the mob must not be busy fighting or fleeing, and it must feel at
 * least neutral toward the giver (PlayerMob feelings run 0–10, 5 = neutral). Static and primitive
 * so it unit-tests like PlayerMob's own policy classes.
 */
public final class PhotoRequestPolicy {

    /** PlayerMob's neutral feeling — {@code FeelingLedger.DEFAULT}; anything below is dislike. */
    public static final float NEUTRAL_FEELING = 5.0F;

    private PhotoRequestPolicy() {}

    public static boolean shouldPhotograph(boolean giftIsCamera, boolean inCombat, float feelingTowardGiver) {
        return giftIsCamera && !inCombat && feelingTowardGiver >= NEUTRAL_FEELING;
    }
}
