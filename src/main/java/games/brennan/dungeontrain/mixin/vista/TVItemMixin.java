package games.brennan.dungeontrain.mixin.vista;

import games.brennan.dungeontrain.compat.vista.TvItems;
import net.mehvahdjukaar.vista.common.tv.TVItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Switching a hand-held TV on or off changes its stack data, which vanilla answers by lowering the
 * hand and raising it again with the "new" item — half a second in which the set seems not to react
 * to the click. The on/off bit is not a different item, so it does not re-equip; a real change (a
 * different slot, a different stack) still does.
 */
@Mixin(value = TVItem.class, remap = false)
public abstract class TVItemMixin {

    @SuppressWarnings("unused") // merged into TVItem; overrides NeoForge's IItemExtension hook
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        if (slotChanged) return true;
        return !ItemStack.matches(TvItems.withOn(oldStack, false), TvItems.withOn(newStack, false));
    }
}
