package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Trader tier 3 perk "Reroll Trades": a villager's trades are rolled afresh at its level,
 * {@link #charges} times per villager per life. The roll is the one train villagers get at spawn
 * ({@link games.brennan.dungeontrain.difficulty.VillagerOfferRoll}), with Mending books always
 * replaced so rerolling can't farm Mending.
 *
 * <p>Charges used are kept on the player's persistent data under {@link #STORE_KEY}, keyed by
 * villager UUID; like Restock Villager's, the key resets with every life yet survives logout.</p>
 */
public final class RerollTrades {

    public static final String ID = "reroll-trades";

    /** Rerolls per villager per life, per rank. Balancing knob. */
    static final int CHARGES_PER_RANK = 1;

    static final String STORE_KEY = "dungeontrain:perk_rerolls";

    private RerollTrades() {}

    /** Marks Reroll Trades built; its stat is the rerolls per villager each life. */
    public static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "rerolls", r -> PerkStats.num(charges(r), Unit.PLAIN));
    }

    /** Rerolls a player of this rank gets on each villager this life. */
    public static int charges(int rank) {
        return Math.max(0, rank) * CHARGES_PER_RANK;
    }

    /** Charges left on one villager; never negative, even after a rank drop. */
    public static int remaining(int rank, int used) {
        return Math.max(0, charges(rank) - Math.max(0, used));
    }

    /** Rerolls used on {@code villager} this life. */
    public static int used(Player player, UUID villager) {
        return player.getPersistentData().getCompound(STORE_KEY).getInt(villager.toString());
    }

    /** Records one more reroll on {@code villager}; returns the new count. */
    public static int recordUse(Player player, UUID villager) {
        CompoundTag root = player.getPersistentData();
        CompoundTag store = root.getCompound(STORE_KEY).copy();
        int next = store.getInt(villager.toString()) + 1;
        store.putInt(villager.toString(), next);
        root.put(STORE_KEY, store);
        return next;
    }
}
