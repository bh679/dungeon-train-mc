package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.tradeeverything.trade.DemandLedger;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Trader tier 2 perk "Reduced Reduction": items lose their worth slower in Trade Everything's
 * Trade Anything slot.
 *
 * <p>TE remembers, per villager and item, how many emeralds of trade value it has bought and
 * pays less the more it holds ({@code DemandCurve}). This perk scales what the villager
 * <em>remembers</em> of each sale by {@link #saleWeight}: a rank-2 player's sales count half,
 * so the villager tires twice as slowly. Recovery is untouched because the item value, which
 * sets the step size, is left alone. Applied from
 * {@code mixin/tradeeverything/VillagerDemandsReducedReductionMixin}.</p>
 */
public final class ReducedReduction {

    public static final String ID = "reduced-reduction";

    /** Per rank, the villager tires this much slower again: rank 1 = 1.5x slower, rank 2 = 2x. */
    static final double SLOWDOWN_PER_RANK = 0.5;

    /** Ranks past this add nothing (rank 6 = 4x slower). Balancing knob. */
    static final int MAX_EFFECTIVE_RANK = 6;

    private ReducedReduction() {}

    /** Share of a sale the villager remembers: 1.0 at rank 0, {@code 1 / (1 + 0.5 rank)} above. */
    public static double saleWeight(int rank) {
        int effective = Math.clamp(rank, 0, MAX_EFFECTIVE_RANK);
        return 1.0 / (1.0 + SLOWDOWN_PER_RANK * effective);
    }

    /** The tally after a sale that moved it from {@code prev} to {@code next}, with only {@code weight} of the gain kept. */
    public static double scaledSold(double prev, double next, double weight) {
        return prev + Math.max(0.0, next - prev) * weight;
    }

    /**
     * {@code after} with the trading player's perk applied to {@code itemId}'s entry, or {@code after}
     * itself when there is nothing to scale (no player, rank 0, no entry).
     */
    public static DemandLedger apply(AbstractVillager villager, String itemId, double prevSold, DemandLedger after) {
        Player player = villager.getTradingPlayer();
        double weight = saleWeight(Perks.rank(player, ID));
        return scaledLedger(itemId, prevSold, after, weight);
    }

    /** Pure part of {@link #apply}: a new ledger with {@code itemId}'s gain over {@code prevSold} scaled by {@code weight}. */
    static DemandLedger scaledLedger(String itemId, double prevSold, DemandLedger after, double weight) {
        if (weight >= 1.0) return after;
        DemandLedger.Entry entry = after.entries().get(itemId);
        if (entry == null) return after;
        double sold = scaledSold(prevSold, entry.soldEmeralds(), weight);
        Map<String, DemandLedger.Entry> next = new HashMap<>(after.entries());
        next.put(itemId, new DemandLedger.Entry(sold, entry.tick(), entry.valueEmeralds()));
        return DemandLedger.of(next);
    }
}
