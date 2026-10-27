package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.List;
import java.util.UUID;

/**
 * Trader tier 3 perk "Restock Villager": a villager's sold-out trades come back, {@link #charges}
 * times per villager per life.
 *
 * <p>Charges used are kept on the player's persistent data under {@link #STORE_KEY}, keyed by
 * villager UUID. NeoForge copies only its {@code PlayerPersisted} sub-tag across the death clone,
 * so this top-level key resets with every life yet survives logout.</p>
 */
public final class RestockVillager {

    public static final String ID = "restock-villager";

    /** Restocks per villager per life, per rank. Balancing knob. */
    static final int CHARGES_PER_RANK = 1;

    static final String STORE_KEY = "dungeontrain:perk_restocks";

    private RestockVillager() {}

    /** Marks Restock Villager built; its stat is the restocks per villager each life. */
    public static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "restocks", r -> PerkStats.num(charges(r), Unit.PLAIN));
    }

    /** Restocks a player of this rank gets on each villager this life. */
    public static int charges(int rank) {
        return Math.max(0, rank) * CHARGES_PER_RANK;
    }

    /** Charges left on one villager; never negative, even after a rank drop. */
    public static int remaining(int rank, int used) {
        return Math.max(0, charges(rank) - Math.max(0, used));
    }

    /** Whether any offer is sold out, so a restock would change something. */
    public static boolean needsRestock(List<MerchantOffer> offers) {
        return offers.stream().anyMatch(MerchantOffer::isOutOfStock);
    }

    /** Restocks used on {@code villager} this life. */
    public static int used(Player player, UUID villager) {
        return player.getPersistentData().getCompound(STORE_KEY).getInt(villager.toString());
    }

    /** Records one more restock on {@code villager}; returns the new count. */
    public static int recordUse(Player player, UUID villager) {
        CompoundTag root = player.getPersistentData();
        CompoundTag store = root.getCompound(STORE_KEY).copy();
        int next = store.getInt(villager.toString()) + 1;
        store.putInt(villager.toString(), next);
        root.put(STORE_KEY, store);
        return next;
    }
}
