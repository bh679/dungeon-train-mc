package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Remembers the last item shift-clicked in the creative menu so that shift-clicking the
 * <em>same</em> item again can send a full stack straight to the hotbar.
 *
 * <p>The first shift-click on an item is left to vanilla (it maxes the stack onto your
 * cursor); every repeat click on that same item claims a hotbar slot here. The first claim
 * prefers the slot you already have selected, falling back to the leftmost empty one; each
 * further claim simply steps to the next slot along, wrapping 8 -&gt; 0.</p>
 *
 * <p>Client-only mutable state, driven from
 * {@code games.brennan.dungeontrain.mixin.client.CreativeShiftToHotbarMixin}. It is scoped to
 * one screen instance — reopening the creative menu starts over — and holds a defensive copy
 * of the remembered stack so nothing here can alias a live inventory stack.</p>
 */
public final class CreativeShiftHotbarState {

    /** Hotbar slots 0-8 of {@link Inventory}. */
    private static final int HOTBAR_SIZE = 9;

    /** The screen the remembered click belongs to; state is dropped when a different one asks. */
    private static Screen owner;
    private static ItemStack lastItem = ItemStack.EMPTY;
    private static int lastHotbarSlot = -1;

    private CreativeShiftHotbarState() {}

    /** Forget the remembered item — any click that is not a repeat shift-click clears it. */
    public static void reset() {
        owner = null;
        lastItem = ItemStack.EMPTY;
        lastHotbarSlot = -1;
    }

    /** Is this click a repeat shift-click on the same item, in the same open screen? */
    public static boolean isRepeat(Screen screen, ItemStack stack) {
        return owner == screen
            && !lastItem.isEmpty()
            && ItemStack.isSameItemSameComponents(lastItem, stack);
    }

    /** Start (or restart) tracking: this item was just shift-clicked for the first time. */
    public static void remember(Screen screen, ItemStack stack) {
        owner = screen;
        lastItem = stack.copy();
        lastHotbarSlot = -1;
    }

    /**
     * Pick the hotbar slot this stack should land in and record it, so the next repeat click
     * moves one slot along.
     *
     * @return a hotbar index in {@code [0, 9)}
     */
    public static int claimHotbarSlot(Inventory inventory) {
        int slot = lastHotbarSlot < 0
            ? firstSlot(inventory)
            : (lastHotbarSlot + 1) % HOTBAR_SIZE;
        lastHotbarSlot = slot;
        return slot;
    }

    /**
     * The selected slot when it is free, else the leftmost empty slot, else the selected slot
     * anyway — overwriting one hotbar entry is harmless in creative, and refusing to place
     * anything at all would read as the feature being broken.
     */
    private static int firstSlot(Inventory inventory) {
        int selected = Mth.clamp(inventory.selected, 0, HOTBAR_SIZE - 1);
        if (inventory.getItem(selected).isEmpty()) return selected;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (inventory.getItem(i).isEmpty()) return i;
        }
        return selected;
    }
}
