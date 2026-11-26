package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.perks.PerkOverflow;
import net.minecraft.util.RandomSource;

/**
 * Hoarder tier 3 "Looting": a kill sometimes counts as if the weapon had more Looting. Each point is
 * {@value #PERCENT_PER_RANK}% on the overflow curve ({@link PerkOverflow}): up to 10 points the chance of
 * +1 Looting; past that +1 always and the rest the chance of +2, and so on up. The levels go on top of
 * the weapon's own Looting, so the loot tables apply them (more drops, better rare-drop odds). Wiring:
 * {@link HoarderLootingEvents}.
 */
public final class HoarderLooting {

    public static final String PERK_ID = "looting";
    public static final int PERCENT_PER_RANK = 10;

    private HoarderLooting() {}

    /** The overflow percentage at {@code rank}: 10% per point, uncapped. */
    public static int percent(int rank) {
        return rank <= 0 ? 0 : rank * PERCENT_PER_RANK;
    }

    /** Extra Looting levels one kill gets at {@code rank}. */
    public static int rollLevels(int rank, RandomSource random) {
        return PerkOverflow.rollSteps(percent(rank), random);
    }
}
