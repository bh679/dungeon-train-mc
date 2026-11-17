package games.brennan.dungeontrain.compat;

import games.brennan.ediblebackpacks.registry.ModAttachments;
import games.brennan.ediblebackpacks.storage.BackpackData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * What sits in the player's unlocked Edible Backpacks slots. The slots ride the player's
 * {@code InventoryMenu}, so the client sees the same contents the server holds. Edible Backpacks is
 * jarJar'd into every DT jar, so it is always loaded.
 */
public final class BackpackContents {

    private BackpackContents() {}

    /** Copies of every non-empty stack in the unlocked backpack slots, in slot order. */
    public static List<ItemStack> stacks(Player player) {
        BackpackData backpack = player.getData(ModAttachments.BACKPACK);
        ItemStackHandler items = backpack.items();
        int usable = Math.min(backpack.unlocked(), items.getSlots());
        List<ItemStack> out = new ArrayList<>();
        for (int slot = 0; slot < usable; slot++) {
            ItemStack stack = items.getStackInSlot(slot);
            if (!stack.isEmpty()) out.add(stack.copy());
        }
        return List.copyOf(out);
    }
}
