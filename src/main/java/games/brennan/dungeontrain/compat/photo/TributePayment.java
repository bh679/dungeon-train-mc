package games.brennan.dungeontrain.compat.photo;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Paying a Tribute in emeralds. A player short of loose emeralds can still pay if their emerald
 * blocks cover the rest: just enough blocks are broken down, the cost is taken, and the change
 * comes back as emeralds — provided there is somewhere to put it.
 *
 * <p>Shared by the client (is the button affordable?) and the server (take the payment), so the
 * two can never disagree about what counts as enough.</p>
 */
public final class TributePayment {

    private static final int EMERALDS_PER_BLOCK = 9;

    private TributePayment() {}

    /** Everything the player could pay with, in emeralds. */
    public static int worth(Inventory inventory) {
        return inventory.countItem(Items.EMERALD) + EMERALDS_PER_BLOCK * inventory.countItem(Items.EMERALD_BLOCK);
    }

    /** Emerald blocks that must be broken down to cover {@code cost} given {@code emeralds} loose ones. */
    static int blocksNeeded(int emeralds, int cost) {
        return emeralds >= cost ? 0 : (cost - emeralds + EMERALDS_PER_BLOCK - 1) / EMERALDS_PER_BLOCK;
    }

    /** Emeralds handed back after breaking {@link #blocksNeeded} blocks. */
    static int change(int emeralds, int cost) {
        return emeralds >= cost ? 0 : blocksNeeded(emeralds, cost) * EMERALDS_PER_BLOCK - (cost - emeralds);
    }

    public static boolean canPay(Inventory inventory, int cost) {
        int emeralds = inventory.countItem(Items.EMERALD);
        if (emeralds >= cost) return true;
        int blocks = inventory.countItem(Items.EMERALD_BLOCK);
        int needed = blocksNeeded(emeralds, cost);
        if (blocks < needed) return false;
        if (change(emeralds, cost) == 0) return true;
        // The change needs a slot: one opens up when the loose emeralds are all spent or the last
        // block goes; otherwise the player must already have an empty one.
        return emeralds > 0 || blocks == needed || inventory.getFreeSlot() != -1;
    }

    /** Take {@code cost} from the player. Returns false, taking nothing, if they cannot pay. */
    public static boolean pay(Player player, int cost) {
        Inventory inventory = player.getInventory();
        if (!canPay(inventory, cost)) return false;
        int emeralds = inventory.countItem(Items.EMERALD);
        if (emeralds >= cost) {
            take(player, Items.EMERALD, cost);
            return true;
        }
        int change = change(emeralds, cost);
        if (emeralds > 0) take(player, Items.EMERALD, emeralds);
        take(player, Items.EMERALD_BLOCK, blocksNeeded(emeralds, cost));
        if (change > 0) inventory.placeItemBackInInventory(new ItemStack(Items.EMERALD, change));
        return true;
    }

    private static void take(Player player, net.minecraft.world.item.Item item, int count) {
        player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item), count, player.inventoryMenu.getCraftSlots());
    }
}
