package games.brennan.dungeontrain.perks.effects.lover;

/** Pure numbers for Pet Health: +half a heart (1 health) per active rank, no cap. */
public final class PetHealthMath {

    /** Half a heart per rank. Balancing knob. */
    public static final double HEALTH_PER_RANK = 1.0;

    private PetHealthMath() {}

    /** Max-health bonus for a pet whose owner has {@code rank} active points; never negative. */
    public static double bonusHealth(int rank) {
        return HEALTH_PER_RANK * Math.max(0, rank);
    }
}
