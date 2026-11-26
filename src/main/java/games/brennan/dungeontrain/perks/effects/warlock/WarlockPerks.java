package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

/**
 * The Warlock's perks: ids and the pure maths behind them (designed with Brennan, 2026-11-26; see
 * {@code docs/design/perks-unbuilt-designs.md}). Rank is the perk's active points this life; every
 * method returns the "off" value at rank 0. Health is in hit points, two to a heart.
 */
public final class WarlockPerks {

    /** Tier 1: thrown snowballs, eggs, wind charges, slimeballs and fire charges hurt and hinder. */
    public static final String THROWABLES = "throwables";
    /** Tier 2: call a player or a PlayerMob met this life to you. */
    public static final String TELEPORT_PLAYER = "teleport-player";
    /** Tier 3: look at a mob and attack with an empty hand to strike it with lightning, paid in health. */
    public static final String SUMMON_LIGHTNING = "summon-lightning";
    /** Tier 3: sneak and right-click a hostile mob with an empty hand to turn it to your side for a while. */
    public static final String CONVERT_MOB = "convert-mob";

    static final double THROW_EFFECT_SECONDS = 2.0;
    static final int TELEPORT_COOLDOWN_SECONDS = 300;
    static final double COOLDOWN_FALLOFF = 0.9;
    static final double CONVERT_SECONDS = 15.0;
    static final double CONVERT_COOLDOWN_SECONDS = 120.0;
    /** How far Summon Lightning reaches, in blocks. */
    public static final double LIGHTNING_RANGE = 32.0;
    /** How far a converted mob looks for hostiles to fight, in blocks. */
    public static final double CONVERT_FIGHT_RANGE = 16.0;

    /**
     * Summon Lightning's ladder as Brennan set it, points 1 to 25: bolt damage and health cost, in hit
     * points. From 26 on, the cost drops ½♥ at double the last gap (49, 97, 193 ...) and the damage
     * climbs 1♥ a point, holding on each point the cost drops, as the table does.
     */
    private static final int[] LIGHTNING_DAMAGE = {
        10, 11, 11, 12, 12, 13, 14, 14, 15, 16, 18, 20, 20, 21, 23, 24, 26, 28, 30, 32, 34, 36, 38, 40, 40};
    private static final int[] LIGHTNING_COST = {
        8, 8, 7, 7, 6, 6, 6, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3};
    /** The table's last cost drop (point 25) and the gap before it (13 to 25). */
    private static final int LAST_TABLE_DROP = 25, LAST_TABLE_GAP = 12;

    private WarlockPerks() {}

    /** Marks the built Warlock perks and registers their menu stats. */
    public static void declare() {
        BuiltPerks.markBuilt(THROWABLES, TELEPORT_PLAYER, SUMMON_LIGHTNING, CONVERT_MOB);
        PerkStats.registerStepped(THROWABLES, "damage", r -> PerkStats.num(throwDamage(r), Unit.HEARTS));
        PerkStats.register(THROWABLES, "duration", r -> PerkStats.num(throwEffectTicks(r) / 20.0, Unit.SECONDS));
        PerkStats.register(TELEPORT_PLAYER, "cooldown", r -> PerkStats.num(teleportCooldownTicks(r) / 20.0, Unit.SECONDS));
        PerkStats.register(SUMMON_LIGHTNING, "damage", r -> PerkStats.num(lightningDamage(r), Unit.HEARTS));
        PerkStats.registerStepped(SUMMON_LIGHTNING, "cost", r -> PerkStats.num(lightningCost(r), Unit.HEARTS));
        PerkStats.registerStepped(SUMMON_LIGHTNING, "cost_chance", r -> lightningCostPercent(r) >= 100.0 ? null
            : PerkStats.num(lightningCostPercent(r), Unit.PERCENT));
        PerkStats.register(CONVERT_MOB, "duration", r -> PerkStats.num(convertTicks(r) / 20.0, Unit.SECONDS));
        PerkStats.register(CONVERT_MOB, "cooldown", r -> PerkStats.num(convertCooldownTicks(r) / 20.0, Unit.SECONDS));
    }

    // ---- Throwables -----------------------------------------------------------------------------

    /** How many triangular numbers (1, 3, 6, 10, 15 ...) are at or below {@code rank}. */
    static int triangularSteps(int rank) {
        int k = 0;
        while ((long) (k + 1) * (k + 2) / 2 <= rank) k++;
        return k;
    }

    /** Throw damage in hit points: ½♥ at 1 point, ½♥ more at 3, 6, 10, 15, 21 ... */
    public static float throwDamage(int rank) {
        return rank <= 0 ? 0f : triangularSteps(rank);
    }

    /** How long a throw's slow or frostbite lasts, in ticks: 2·√points seconds to one decimal. */
    public static int throwEffectTicks(int rank) {
        if (rank <= 0) return 0;
        return (int) Math.round(oneDecimal(THROW_EFFECT_SECONDS * Math.sqrt(rank)) * 20.0);
    }

    // ---- Teleport Player ------------------------------------------------------------------------

    /** Cooldown after a call, in ticks: 300 s, 10% less a point, whole seconds, never under 1 s. */
    public static int teleportCooldownTicks(int rank) {
        if (rank <= 0) return 0;
        long seconds = Math.round(TELEPORT_COOLDOWN_SECONDS * Math.pow(COOLDOWN_FALLOFF, rank - 1));
        return (int) Math.max(1, seconds) * 20;
    }

    /**
     * The chance, in percent, that a PlayerMob answers a call: always when it likes you (its reaction to
     * you is a greeting), otherwise 10% per point of its friendliness trait (0 to 10).
     */
    public static int playerMobAcceptPercent(boolean likesYou, int friendliness) {
        if (likesYou) return 100;
        return Math.max(0, Math.min(10, friendliness)) * 10;
    }

    // ---- Summon Lightning -----------------------------------------------------------------------

    /** The points past the table where the cost drops: 49, 97, 193 ... (the gap doubles each time). */
    static int costDropsPast(int rank) {
        int drops = 0;
        long gap = LAST_TABLE_GAP * 2L, at = LAST_TABLE_DROP + gap;
        while (at <= rank) {
            drops++;
            gap *= 2;
            at += gap;
        }
        return drops;
    }

    /** Bolt damage in hit points: Brennan's table to 25, then 1♥ a point, holding where the cost drops. */
    public static float lightningDamage(int rank) {
        if (rank <= 0) return 0f;
        if (rank <= LIGHTNING_DAMAGE.length) return LIGHTNING_DAMAGE[rank - 1];
        int past = rank - LIGHTNING_DAMAGE.length;
        return LIGHTNING_DAMAGE[LIGHTNING_DAMAGE.length - 1] + 2f * (past - costDropsPast(rank));
    }

    /** Health a cast costs, in hit points; never under ½♥, which past that turns into a chance. */
    public static float lightningCost(int rank) {
        if (rank <= 0) return 0f;
        if (rank <= LIGHTNING_COST.length) return LIGHTNING_COST[rank - 1];
        return Math.max(1, LIGHTNING_COST[LIGHTNING_COST.length - 1] - costDropsPast(rank));
    }

    /**
     * The chance, in percent, that a cast takes its cost: 100% until the cost reaches ½♥, then each
     * further drop halves it (50%, 25% ...).
     */
    public static double lightningCostPercent(int rank) {
        if (rank <= 0) return 0.0;
        if (rank <= LIGHTNING_COST.length) return 100.0;
        int pastHalf = costDropsPast(rank) - (LIGHTNING_COST[LIGHTNING_COST.length - 1] - 1);
        return pastHalf <= 0 ? 100.0 : 100.0 / Math.pow(2, pastHalf);
    }

    // ---- Convert Mob ----------------------------------------------------------------------------

    /** How long a converted mob fights for you, in ticks: 15·√points seconds to one decimal. */
    public static int convertTicks(int rank) {
        if (rank <= 0) return 0;
        return (int) Math.round(oneDecimal(CONVERT_SECONDS * Math.sqrt(rank)) * 20.0);
    }

    /** Cooldown after converting, in ticks: 120 s, 10% less a point, to one decimal, never under 0.1 s. */
    public static int convertCooldownTicks(int rank) {
        if (rank <= 0) return 0;
        double seconds = Math.max(0.1, oneDecimal(CONVERT_COOLDOWN_SECONDS * Math.pow(COOLDOWN_FALLOFF, rank - 1)));
        return (int) Math.max(2, Math.round(seconds * 20.0));
    }

    static double oneDecimal(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
