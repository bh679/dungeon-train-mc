package games.brennan.dungeontrain.perks.effects.lover;

/** Pure numbers for Pet Health: +1 heart (2 health) per active rank. */
public final class PetHealthMath {

    /** One heart per rank. Balancing knob. */
    public static final double HEALTH_PER_RANK = 2.0;

    private PetHealthMath() {}

    /** Max-health bonus for a pet whose owner has {@code rank} active points; never negative. */
    public static double bonusHealth(int rank) {
        return HEALTH_PER_RANK * Math.max(0, rank);
    }
}
