package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.CreativeShiftHotbarState;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shift-click an item in the creative menu twice and the stack lands in your hotbar.
 *
 * <p>Vanilla's {@code QUICK_MOVE} on a creative item-list slot maxes the stack onto your
 * cursor; getting it into the hotbar then takes a second, unrelated action. The first
 * shift-click is left exactly as vanilla has it — every repeat shift-click on the same item is
 * cancelled here and placed into a hotbar slot instead (see {@link CreativeShiftHotbarState}
 * for the slot rule). Clicking a different item, or clicking any other way, starts over.</p>
 *
 * <p>Targets {@link CreativeModeInventoryScreen} rather than {@code AbstractContainerScreen}
 * because {@code slotClicked} <em>is</em> overridden on the creative screen in 1.21.1 — Mixin
 * only sees methods declared on the target class, so the override is what we can inject into,
 * and it is also the only version that knows about the item-list container.</p>
 *
 * <p>The placement mirrors vanilla's own {@code ClickType.SWAP} branch a few lines further
 * down that same method (write the stack into {@link Inventory} and broadcast), with an
 * explicit {@code handleCreativeModeItemAdd} so the server is told as well.</p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeShiftToHotbarMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {

    /**
     * Container-slot index of hotbar slot 0 in the player's inventory menu — vanilla uses this
     * same {@code 36 + i} mapping elsewhere in {@code slotClicked}.
     */
    private static final int DUNGEONTRAIN$HOTBAR_CONTAINER_OFFSET = 36;

    private CreativeShiftToHotbarMixin() {
        super(null, null, null);
    }

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$shiftClickToHotbar(Slot slot, int slotId, int mouseButton, ClickType clickType, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;

        if (clickType != ClickType.QUICK_MOVE || mouseButton != 0) {
            CreativeShiftHotbarState.reset();
            return;
        }
        if (!ClientDisplayConfig.isCreativeShiftClickToHotbar()) return;

        // The Inventory tab's slots are the real inventory, not the item list — leave it alone.
        CreativeModeTab tab = CreativeModeInventoryScreenAccessor.dungeontrain$getSelectedTab();
        if (tab == null || tab.getType() == CreativeModeTab.Type.INVENTORY) {
            CreativeShiftHotbarState.reset();
            return;
        }
        if (slot == null || !((CreativeModeInventoryScreenAccessor) this).dungeontrain$isCreativeSlot(slot)) {
            CreativeShiftHotbarState.reset();
            return;
        }

        ItemStack hovered = slot.getItem();
        if (hovered.isEmpty()) {
            CreativeShiftHotbarState.reset();
            return;
        }

        CreativeModeInventoryScreen screen = (CreativeModeInventoryScreen) (Object) this;
        if (!CreativeShiftHotbarState.isRepeat(screen, hovered)) {
            // First shift-click on this item: let vanilla max the stack onto the cursor.
            CreativeShiftHotbarState.remember(screen, hovered);
            return;
        }

        Inventory inventory = minecraft.player.getInventory();
        int target = CreativeShiftHotbarState.claimHotbarSlot(inventory);
        ItemStack give = hovered.copyWithCount(hovered.getMaxStackSize());

        inventory.setItem(target, give);
        minecraft.gameMode.handleCreativeModeItemAdd(give, DUNGEONTRAIN$HOTBAR_CONTAINER_OFFSET + target);
        // The stack on the cursor is what just went to the hotbar, so it should not linger.
        this.menu.setCarried(ItemStack.EMPTY);
        minecraft.player.inventoryMenu.broadcastChanges();

        ci.cancel();
    }
}
