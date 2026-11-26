package games.brennan.dungeontrain.perks.effects.knight;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.network.chat.Component;

/**
 * Knight tier 2 perk "Debuff Aura": when a hostile mob comes near and the aura is ready, it turns on
 * for {@value #ACTIVE_SECONDS} s, weakening (and from 2 points slowing) every hostile in range; then
 * it cools down. Pure maths here, the ticking in {@link DebuffAuraEvents}.
 *
 * <p>No caps: range grows a block a point, the effect levels climb every 5 points and the cooldown
 * shrinks by a tenth a point without reaching zero.</p>
 */
public final class DebuffAura {

    public static final String ID = "debuff-aura";

    static final double BASE_RANGE = 6.0;
    static final double RANGE_PER_RANK = 1.0;
    static final int RANKS_PER_LEVEL = 5;
    /** Slowness joins at this many points. */
    static final int SLOWNESS_FROM_RANK = 2;
    static final int ACTIVE_SECONDS = 20;
    static final double COOLDOWN_BASE_SECONDS = 30.0;
    static final double COOLDOWN_KEEP_PER_RANK = 0.9;

    private DebuffAura() {}

    /** Marks Debuff Aura built and registers its chips. */
    static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "range", r -> PerkStats.num(range(Math.max(1, r)), Unit.BLOCKS));
        PerkStats.register(ID, "weakness", r -> roman(weaknessLevel(Math.max(1, r))));
        PerkStats.register(ID, "slowness", r -> slownessLevel(r) <= 0 ? null : roman(slownessLevel(r)));
        PerkStats.register(ID, "duration", r -> PerkStats.num(ACTIVE_SECONDS, Unit.SECONDS));
        PerkStats.register(ID, "cooldown", r -> PerkStats.num(cooldownSeconds(Math.max(1, r)), Unit.SECONDS));
    }

    private static PerkStats.Value roman(int level) {
        return new PerkStats.Text(Component.translatable("enchantment.level." + level));
    }

    /** Aura radius in blocks; 0 when inactive. */
    public static double range(int rank) {
        if (rank <= 0) return 0.0;
        return BASE_RANGE + RANGE_PER_RANK * rank;
    }

    /** Weakness level (1 = Weakness I); 0 when inactive. */
    public static int weaknessLevel(int rank) {
        if (rank <= 0) return 0;
        return 1 + rank / RANKS_PER_LEVEL;
    }

    /** Slowness level (1 = Slowness I); 0 below {@value #SLOWNESS_FROM_RANK} points. */
    public static int slownessLevel(int rank) {
        if (rank < SLOWNESS_FROM_RANK) return 0;
        return 1 + (rank - SLOWNESS_FROM_RANK) / RANKS_PER_LEVEL;
    }

    /** Seconds between the aura ending and it being ready again; 0 when inactive. */
    public static double cooldownSeconds(int rank) {
        if (rank <= 0) return 0.0;
        return COOLDOWN_BASE_SECONDS * Math.pow(COOLDOWN_KEEP_PER_RANK, rank - 1);
    }

    static int activeTicks() {
        return ACTIVE_SECONDS * 20;
    }

    static int cooldownTicks(int rank) {
        return (int) Math.round(cooldownSeconds(rank) * 20.0);
    }
}
