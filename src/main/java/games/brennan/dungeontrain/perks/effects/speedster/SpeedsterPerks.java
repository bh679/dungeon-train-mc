package games.brennan.dungeontrain.perks.effects.speedster;

import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.world.entity.player.Player;

/**
 * The Speedster class's tier 2/3 perks: ids and the pure maths behind each effect. Every function takes the
 * perk's rank (active points, 0 when the perk is not active) and returns a multiplier or impulse; rank 0 is
 * always vanilla. The hooks live in {@link SpeedsterEvents}, {@code mixin/PlayerSprintHungerMixin} and
 * {@code wind/WindPush}.
 *
 * <p>Tuning constants are collected here so balancing is a one-file change.</p>
 */
public final class SpeedsterPerks {

    public static final String HASTE = "haste";
    public static final String SPRINT_HUNGER = "sprint-hunger";
    public static final String SPRINT_SPEED = "sprint-speed";
    public static final String SPRINT_JUMP = "sprint-jump";
    public static final String WIND_RESISTANCE = "wind-resistance";

    /** Haste: extra mining speed per rank (vanilla Haste I is +20%). */
    public static final double HASTE_PER_RANK = 0.20;
    /** Sprint Hunger: exhaustion saved at rank 1, and per further rank. */
    public static final double SPRINT_HUNGER_FIRST = 0.10;
    public static final double SPRINT_HUNGER_PER_EXTRA_RANK = 0.05;
    /** Sprint Speed: extra sprint speed per rank, on top of vanilla's +30% sprint modifier. */
    public static final double SPRINT_SPEED_PER_RANK = 0.05;
    /** Sprint Jump: extra distance per rank, in blocks. */
    public static final double SPRINT_JUMP_BLOCKS_PER_RANK = 0.1;
    /**
     * Blocks a sprint-jump carries per block/tick of horizontal impulse. Measured in a dev client on flat stone:
     * a rank-5 impulse of 0.073 b/t (the 6.85 drag-sum estimate) carried 0.359 blocks further, so the real
     * figure is 4.9; a bare sprint-jump carried 3.84 blocks.
     */
    public static final double BLOCKS_PER_IMPULSE = 4.9;
    /** Wind Resistance: push removed per rank; rank 10 is immune. */
    public static final double WIND_RESISTANCE_PER_RANK = 0.10;

    private SpeedsterPerks() {}

    // ---- pure maths ----------------------------------------------------------------------------------------

    /** Mining-speed multiplier: 1 at rank 0, +{@link #HASTE_PER_RANK} per rank. */
    public static double hasteMultiplier(int rank) {
        return 1.0 + HASTE_PER_RANK * clampRank(rank);
    }

    /** Multiplier on the hunger sprinting costs: 1 at rank 0, 0.9 at rank 1, 0.85 at rank 2, never below 0. */
    public static double sprintHungerFactor(int rank) {
        int r = clampRank(rank);
        if (r == 0) return 1.0;
        double saved = SPRINT_HUNGER_FIRST + SPRINT_HUNGER_PER_EXTRA_RANK * (r - 1);
        return Math.max(0.0, 1.0 - saved);
    }

    /** Extra sprint speed as an ADD_MULTIPLIED_TOTAL amount: 0 at rank 0, 0.05 per rank. */
    public static double sprintSpeedBonus(int rank) {
        return SPRINT_SPEED_PER_RANK * clampRank(rank);
    }

    /** Extra horizontal impulse (blocks/tick) a sprint-jump gets so it lands 0.1 block further per rank. */
    public static double sprintJumpImpulse(int rank) {
        return SPRINT_JUMP_BLOCKS_PER_RANK * clampRank(rank) / BLOCKS_PER_IMPULSE;
    }

    /** Multiplier on the wind's push: 1 at rank 0, 0.9 at rank 1, 0 from rank 10 on. */
    public static double windFactor(int rank) {
        return Math.max(0.0, 1.0 - WIND_RESISTANCE_PER_RANK * clampRank(rank));
    }

    static int clampRank(int rank) {
        return Math.max(0, rank);
    }

    // ---- player overloads ----------------------------------------------------------------------------------

    public static double hasteMultiplier(Player p) {
        return hasteMultiplier(Perks.rank(p, HASTE));
    }

    public static double sprintHungerFactor(Player p) {
        return sprintHungerFactor(Perks.rank(p, SPRINT_HUNGER));
    }

    public static double sprintSpeedBonus(Player p) {
        return sprintSpeedBonus(Perks.rank(p, SPRINT_SPEED));
    }

    public static double sprintJumpImpulse(Player p) {
        return sprintJumpImpulse(Perks.rank(p, SPRINT_JUMP));
    }

    public static double windFactor(Player p) {
        return windFactor(Perks.rank(p, WIND_RESISTANCE));
    }

    /** The exhaustion {@code p} should be charged for a movement cost: scaled only while sprinting. */
    public static float scaleSprintExhaustion(Player p, float exhaustion) {
        if (!p.isSprinting() || exhaustion <= 0.0f) return exhaustion;
        return (float) (exhaustion * sprintHungerFactor(p));
    }
}
