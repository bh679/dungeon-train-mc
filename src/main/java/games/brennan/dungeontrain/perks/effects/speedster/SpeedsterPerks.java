package games.brennan.dungeontrain.perks.effects.speedster;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
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
    /** Sprint Hunger: sprint exhaustion is multiplied by this once per rank (never free). */
    public static final double SPRINT_HUNGER_KEEP_PER_RANK = 0.90;
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
    /** Wind Resistance: wind push is multiplied by this once per rank (never immune). */
    public static final double WIND_KEEP_PER_RANK = 0.90;

    private SpeedsterPerks() {}

    /** Marks the built Speedster perks and registers their menu stats. */
    public static void declare() {
        BuiltPerks.markBuilt(HASTE, SPRINT_HUNGER, SPRINT_SPEED, SPRINT_JUMP, WIND_RESISTANCE);
        PerkStats.register(HASTE, "mining", r -> PerkStats.num(hasteMultiplier(r), Unit.MULTIPLIER));
        PerkStats.register(SPRINT_HUNGER, "sprint_hunger", r -> PerkStats.num(-(1.0 - sprintHungerFactor(r)) * 100, Unit.PERCENT));
        PerkStats.register(SPRINT_SPEED, "sprint_speed", r -> PerkStats.plus(sprintSpeedBonus(r) * 100, Unit.PERCENT));
        PerkStats.register(SPRINT_JUMP, "jump_distance", r -> PerkStats.plus(sprintJumpBlocks(r), Unit.BLOCKS));
        PerkStats.register(WIND_RESISTANCE, "wind_push", r -> PerkStats.num(-(1.0 - windFactor(r)) * 100, Unit.PERCENT));
    }

    // ---- pure maths ----------------------------------------------------------------------------------------

    /** Mining-speed multiplier: 1 at rank 0, +{@link #HASTE_PER_RANK} per rank. */
    public static double hasteMultiplier(int rank) {
        return 1.0 + HASTE_PER_RANK * clampRank(rank);
    }

    /** Multiplier on the hunger sprinting costs: 0.9^rank (0.9, 0.81, … 0.35 at rank 10), never free. */
    public static double sprintHungerFactor(int rank) {
        return Math.pow(SPRINT_HUNGER_KEEP_PER_RANK, clampRank(rank));
    }

    /** Extra sprint speed as an ADD_MULTIPLIED_TOTAL amount: 0 at rank 0, 0.05 per rank. */
    public static double sprintSpeedBonus(int rank) {
        return SPRINT_SPEED_PER_RANK * clampRank(rank);
    }

    /** Extra blocks a sprint jump carries at {@code rank}. */
    public static double sprintJumpBlocks(int rank) {
        return SPRINT_JUMP_BLOCKS_PER_RANK * clampRank(rank);
    }

    /** Extra horizontal impulse (blocks/tick) a sprint-jump gets so it lands 0.1 block further per rank. */
    public static double sprintJumpImpulse(int rank) {
        return sprintJumpBlocks(rank) / BLOCKS_PER_IMPULSE;
    }

    /** Multiplier on the wind's push: 0.9^rank (0.9, 0.81, … 0.35 at rank 10), never immune. */
    public static double windFactor(int rank) {
        return Math.pow(WIND_KEEP_PER_RANK, clampRank(rank));
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
