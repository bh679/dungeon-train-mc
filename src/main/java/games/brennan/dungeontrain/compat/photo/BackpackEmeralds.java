package games.brennan.dungeontrain.compat.photo;

import games.brennan.ediblebackpacks.registry.ModAttachments;
import games.brennan.ediblebackpacks.storage.BackpackData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The Edible Backpacks slots a Tribute can draw on. Only unlocked slots count; a closed panel still
 * counts, since the emeralds in it are still the player's. The slots ride the player's
 * {@code InventoryMenu}, so the client sees the same contents the server pays from.
 */
final class BackpackEmeralds {

    private BackpackEmeralds() {}

    /** How many of {@code item} sit in the player's unlocked backpack slots. */
    static int count(Player player, Item item) {
        BackpackData backpack = player.getData(ModAttachments.BACKPACK);
        ItemStackHandler items = backpack.items();
        int usable = Math.min(backpack.unlocked(), items.getSlots());
        int total = 0;
        for (int slot = 0; slot < usable; slot++) {
            ItemStack stack = items.getStackInSlot(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Take up to {@code count} of {@code item} from the unlocked backpack slots; returns how many were taken. */
    static int take(Player player, Item item, int count) {
        BackpackData backpack = player.getData(ModAttachments.BACKPACK);
        ItemStackHandler items = backpack.items();
        int usable = Math.min(backpack.unlocked(), items.getSlots());
        int taken = 0;
        for (int slot = 0; slot < usable && taken < count; slot++) {
            if (!items.getStackInSlot(slot).is(item)) continue;
            taken += items.extractItem(slot, count - taken, false).getCount();
        }
        return taken;
    }
}
