package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.tier1.CraftLevelStats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Craft Level Stats on shift-click crafting (crafting table and the inventory's 2x2 grid). Vanilla
 * moves the result into the inventory before firing {@code ItemCraftedEvent}, which then carries
 * only the emptied leftover, so the perk stamps the result here, just before it is moved.
 */
@Mixin({CraftingMenu.class, InventoryMenu.class})
public abstract class CraftingResultQuickMoveMixin {

    @Inject(method = "quickMoveStack", at = @At("HEAD"))
    private void dungeontrain$stampShiftCraft(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (index != 0 || !(player instanceof ServerPlayer sp)) return;
        Slot slot = ((AbstractContainerMenu) (Object) this).getSlot(0);
        if (slot instanceof ResultSlot && slot.hasItem()) CraftLevelStats.onQuickCraft(sp, slot.getItem());
    }
}
