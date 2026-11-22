package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.trader.ExtraCostContainer;
import games.brennan.dungeontrain.trader.ExtraCostMenu;
import games.brennan.dungeontrain.trader.ExtraCostSlot;
import games.brennan.dungeontrain.trader.ExtraTradeCosts;
import games.brennan.ediblebackpacks.menu.BackpackLayout;
import games.brennan.ediblebackpacks.menu.BackpackSlot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Extra trade input slots: a backing container holding one payment per extra cost
 * ({@link ExtraTradeCosts}) and twelve on-screen {@link ExtraCostSlot}s showing it as a scrolling
 * grid. The slots go after the player inventory (index 39+, next to Edible Backpacks' panel slots,
 * in whichever order the two mixins run), so vanilla's slot ranges are untouched. The scroll row syncs through vanilla's {@code clickMenuButton}.
 */
@Mixin(MerchantMenu.class)
public abstract class MerchantMenuExtraCostsMixin extends AbstractContainerMenu implements ExtraCostMenu {

    @Unique private static final int INVENTORY_START = 3;
    @Unique private static final int INVENTORY_END = 39;

    @Shadow @Final private Merchant trader;
    @Shadow @Final private MerchantContainer tradeContainer;

    @Shadow public abstract MerchantOffers getOffers();

    @Unique private final SimpleContainer dungeontrain$backing = new SimpleContainer(ExtraTradeCosts.MAX_EXTRA);
    @Unique private int dungeontrain$scrollRow;

    protected MerchantMenuExtraCostsMixin(MenuType<?> type, int containerId) {
        super(type, containerId);
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/trading/Merchant;)V",
            at = @At("TAIL"))
    private void dungeontrain$addExtraCostSlots(int containerId, Inventory inventory, Merchant merchant, CallbackInfo ci) {
        ((ExtraCostContainer) tradeContainer).dungeontrain$setBacking(dungeontrain$backing);
        dungeontrain$backing.addListener(container -> tradeContainer.updateSellItem());
        for (int visible = 0; visible < ExtraTradeCosts.VISIBLE_SLOTS; visible++) {
            addSlot(new ExtraCostSlot(this, visible));
        }
    }

    @Override
    public SimpleContainer dungeontrain$backing() {
        return dungeontrain$backing;
    }

    @Override
    public int dungeontrain$scrollRow() {
        return dungeontrain$scrollRow;
    }

    @Override
    public void dungeontrain$setScrollRow(int row) {
        MerchantOffer offer = dungeontrain$selectedOffer();
        int max = offer == null ? 0 : ExtraTradeCosts.maxScrollRow(ExtraTradeCosts.allCosts(offer).size());
        dungeontrain$scrollRow = Math.max(0, Math.min(max, row));
    }

    @Override
    public MerchantOffer dungeontrain$selectedOffer() {
        int index = ((ExtraCostContainer) tradeContainer).dungeontrain$selectionHint();
        MerchantOffers offers = getOffers();
        return index >= 0 && index < offers.size() ? offers.get(index) : null;
    }

    /** Scroll-row buttons from the client ({@link ExtraTradeCosts#SCROLL_BUTTON_BASE} + row). */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        int row = id - ExtraTradeCosts.SCROLL_BUTTON_BASE;
        if (row < 0 || row >= ExtraTradeCosts.MAX_COSTS) return super.clickMenuButton(player, id);
        dungeontrain$setScrollRow(row);
        return true;
    }

    /** Shift-click: out of the grid back to the inventory; from the inventory into the next unpaid extra cost. */
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$quickMoveExtraCosts(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        // Found by type, not by index: Edible Backpacks appends its 108 panel slots to this
        // menu too, and which mixin's TAIL runs first decides whose slots come first.
        if (index >= 0 && index < slots.size() && slots.get(index) instanceof ExtraCostSlot) {
            Slot slot = slots.get(index);
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem();
                moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true);
                if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
                else slot.setChanged();
            }
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        if (index < INVENTORY_START || index >= INVENTORY_END) return;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !dungeontrain$fillExtraCosts(slot.getItem())) return;
        if (slot.getItem().isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        cir.setReturnValue(ItemStack.EMPTY);
    }

    /** Selecting a trade: return what sat in the grid, then fill each extra cost from the inventory. */
    @Inject(method = "tryMoveItems", at = @At("RETURN"))
    private void dungeontrain$autoFillExtraCosts(int selected, CallbackInfo ci) {
        dungeontrain$scrollRow = 0;
        for (int i = 0; i < dungeontrain$backing.getContainerSize(); i++) {
            ItemStack held = dungeontrain$backing.getItem(i);
            if (held.isEmpty()) continue;
            moveItemStackTo(held, INVENTORY_START, INVENTORY_END, true);
            dungeontrain$backing.setItem(i, held);
        }
        MerchantOffers offers = getOffers();
        if (selected < 0 || selected >= offers.size()) return;
        List<ItemCost> extras = ExtraTradeCosts.of(offers.get(selected));
        for (int i = 0; i < extras.size(); i++) {
            ItemCost cost = extras.get(i);
            for (int s = INVENTORY_START; s < INVENTORY_END; s++) {
                ItemStack stack = slots.get(s).getItem();
                if (stack.isEmpty() || !cost.test(stack)) continue;
                if (dungeontrain$moveInto(i, stack) && dungeontrain$backing.getItem(i).getCount() >= stack.getMaxStackSize()) break;
            }
            dungeontrain$fillFromBackpack(i, cost);
        }
    }

    /**
     * After the inventory, the backpack pays: the slots the player could take from by hand
     * ({@code BackpackSlot#mayPickup} is false for locked slots, a closed backpack and DT's own
     * access lock). Edible Backpacks tops up vanilla's two payment slots the same way.
     */
    @Unique
    private void dungeontrain$fillFromBackpack(int i, ItemCost cost) {
        for (Slot slot : slots) {
            if (!(slot instanceof BackpackSlot backpack) || !backpack.mayPickup(backpack.owner())) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !cost.test(stack)) continue;
            if (!dungeontrain$moveInto(i, stack)) continue;
            slot.setChanged();
            if (dungeontrain$backing.getItem(i).getCount() >= stack.getMaxStackSize()) break;
        }
    }

    /** Closing the screen returns the grid's items, or drops them for a dead or disconnected player. */
    @Inject(method = "removed", at = @At("TAIL"))
    private void dungeontrain$returnExtraCosts(Player player, CallbackInfo ci) {
        if (trader.isClientSide()) return;
        boolean gone = !player.isAlive() || player instanceof ServerPlayer sp && sp.hasDisconnected();
        for (int i = 0; i < dungeontrain$backing.getContainerSize(); i++) {
            ItemStack held = dungeontrain$backing.removeItemNoUpdate(i);
            if (held.isEmpty()) continue;
            if (gone) {
                player.drop(held, false);
                continue;
            }
            // Inventory first, then the backpack: grid items can come out of the backpack, so a
            // full inventory would otherwise drop them at the player's feet.
            moveItemStackTo(held, INVENTORY_START, INVENTORY_END, false);
            int backpack = dungeontrain$backpackStart();
            if (!held.isEmpty() && backpack >= 0) moveItemStackTo(held, backpack, backpack + BackpackLayout.MAX_SLOTS, false);
            if (!held.isEmpty()) player.getInventory().placeItemBackInInventory(held);
        }
    }

    /** Index of the first Edible Backpacks panel slot in this menu, or -1. */
    @Unique
    private int dungeontrain$backpackStart() {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) instanceof BackpackSlot) return i;
        }
        return -1;
    }

    /** Moves {@code stack} into the first extra cost it pays and isn't full yet; true when anything moved. */
    @Unique
    private boolean dungeontrain$fillExtraCosts(ItemStack stack) {
        List<ItemCost> extras = ExtraTradeCosts.of(dungeontrain$selectedOffer());
        for (int i = 0; i < extras.size(); i++) {
            if (extras.get(i).test(stack) && dungeontrain$moveInto(i, stack)) return true;
        }
        return false;
    }

    /** Moves as much of {@code stack} as fits into backing slot {@code i}; true when anything moved. */
    @Unique
    private boolean dungeontrain$moveInto(int i, ItemStack stack) {
        ItemStack target = dungeontrain$backing.getItem(i);
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, stack)) return false;
        int room = stack.getMaxStackSize() - target.getCount();
        int moved = Math.min(room, stack.getCount());
        if (moved <= 0) return false;
        dungeontrain$backing.setItem(i, stack.copyWithCount(target.getCount() + moved));
        stack.shrink(moved);
        return true;
    }
}
