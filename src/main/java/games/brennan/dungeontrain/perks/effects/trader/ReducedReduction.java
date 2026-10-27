package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
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

    /** The share of a sale the villager remembers approaches this but never reaches it: at most 4x slower. */
    static final double MIN_WEIGHT = 0.25;

    /** Rank at which the villager tires half as fast (weight 0.5). Balancing knob. */
    static final int HALF_RATE_RANK = 5;

    /** Decay per rank so that {@link #saleWeight} is exactly 0.5 at {@link #HALF_RATE_RANK}. */
    private static final double DECAY =
        Math.log((1.0 - MIN_WEIGHT) / (0.5 - MIN_WEIGHT)) / HALF_RATE_RANK;

    private ReducedReduction() {}

    /** Marks Reduced Reduction built; its stat is how much less of each sale's value is lost. */
    public static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "value_loss", r -> PerkStats.num(-(1.0 - saleWeight(r)) * 100.0, Unit.PERCENT));
    }

    /**
     * Share of a sale the villager remembers: 1.0 at rank 0, 0.5 at rank 5, approaching
     * {@link #MIN_WEIGHT} as rank grows with no cap: {@code 0.25 + 0.75 e^(-k rank)}.
     */
    public static double saleWeight(int rank) {
        if (rank <= 0) return 1.0;
        return MIN_WEIGHT + (1.0 - MIN_WEIGHT) * Math.exp(-DECAY * rank);
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
