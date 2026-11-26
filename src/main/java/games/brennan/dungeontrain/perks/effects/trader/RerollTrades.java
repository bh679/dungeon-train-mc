package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Trader tier 3 perk "Reroll Trades" (Brennan, 2026-10-28): a villager's trades are rolled afresh at
 * its level, up to {@link #charges} times per villager per life. {@link #free} of those cost nothing and
 * are spent first; each paid one costs emeralds from the inventory, 1 for the first paid reroll of that
 * villager and doubling after (2, 4, 8, ...), with no cap. The price is per villager per life, not per
 * point. The roll is the one train villagers get at spawn
 * ({@link games.brennan.dungeontrain.difficulty.VillagerOfferRoll}), with Mending books always replaced
 * so rerolling can't farm Mending.
 *
 * <p>Uses are kept on the player's persistent data under {@link #STORE_KEY}, keyed by villager UUID (all
 * rerolls) and UUID + {@value #PAID_SUFFIX} (the paid ones); like Restock Villager's charges, the store
 * resets with every life yet survives logout.</p>
 */
public final class RerollTrades {

    public static final String ID = "reroll-trades";

    /** Points per free reroll. */
    static final int POINTS_PER_FREE = 10;
    /** A price this high can never be paid anyway; it keeps the doubling inside an int. */
    static final int MAX_PRICE = 1 << 30;

    static final String STORE_KEY = "dungeontrain:perk_rerolls";
    static final String PAID_SUFFIX = "#paid";

    private RerollTrades() {}

    /** Marks Reroll Trades built; its chips are the rerolls per villager and how many are free. */
    public static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "rerolls", r -> PerkStats.num(charges(r), Unit.PLAIN));
        PerkStats.registerStepped(ID, "free_rerolls", r -> PerkStats.num(free(r), Unit.PLAIN));
    }

    /** Rerolls a player of this rank gets on each villager this life. */
    public static int charges(int rank) {
        return Math.max(0, rank);
    }

    /** How many of {@link #charges} are free. */
    public static int free(int rank) {
        return Math.max(0, rank) / POINTS_PER_FREE;
    }

    /** Rerolls left on one villager; never negative, even after a rank drop. */
    public static int remaining(int rank, int used) {
        return Math.max(0, charges(rank) - Math.max(0, used));
    }

    /**
     * Emeralds the next reroll of a villager costs: 0 while free ones remain (free ones are those not
     * paid for), else 2 to the power of the paid rerolls so far.
     */
    public static int nextPrice(int rank, int used, int paid) {
        int freeUsed = Math.max(0, used - paid);
        if (freeUsed < free(rank)) return 0;
        int p = Math.max(0, paid);
        return p >= 30 ? MAX_PRICE : 1 << p;
    }

    /** Rerolls used on {@code villager} this life. */
    public static int used(Player player, UUID villager) {
        return store(player).getInt(villager.toString());
    }

    /** Paid rerolls used on {@code villager} this life. */
    public static int paid(Player player, UUID villager) {
        return store(player).getInt(villager + PAID_SUFFIX);
    }

    /** Records one more reroll on {@code villager}, paid or free. */
    public static void recordUse(Player player, UUID villager, boolean wasPaid) {
        CompoundTag root = player.getPersistentData();
        CompoundTag store = root.getCompound(STORE_KEY).copy();
        String key = villager.toString();
        store.putInt(key, store.getInt(key) + 1);
        if (wasPaid) store.putInt(key + PAID_SUFFIX, store.getInt(key + PAID_SUFFIX) + 1);
        root.put(STORE_KEY, store);
    }

    private static CompoundTag store(Player player) {
        return player.getPersistentData().getCompound(STORE_KEY);
    }
}
