package games.brennan.dungeontrain.trader;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.List;

/**
 * One on-screen place in the extra-cost grid. It shows whichever backing slot the grid's scroll
 * row puts here ({@link ExtraTradeCosts#backingIndex}), so twelve menu slots cover up to
 * {@link ExtraTradeCosts#MAX_EXTRA} costs. It is active, and takes items, only where the selected
 * trade has a cost, and only the item that cost asks for.
 */
public final class ExtraCostSlot extends Slot {

    private final ExtraCostMenu menu;
    private final int visible;

    public ExtraCostSlot(ExtraCostMenu menu, int visible) {
        super(menu.dungeontrain$backing(), 0,
            ExtraTradeCosts.GRID_X + (visible % ExtraTradeCosts.COLUMNS) * ExtraTradeCosts.PITCH,
            ExtraTradeCosts.GRID_Y + (visible / ExtraTradeCosts.COLUMNS) * ExtraTradeCosts.PITCH);
        this.menu = menu;
        this.visible = visible;
    }

    /** The backing slot under this place, whatever the trade; -1 for the A and B places. */
    private int rawIndex() {
        int index = ExtraTradeCosts.backingIndex(menu.dungeontrain$scrollRow(), visible);
        return index < ExtraTradeCosts.MAX_EXTRA ? index : -1;
    }

    /** The extra cost this place pays for the selected trade, or null when it pays none. */
    private ItemCost cost() {
        MerchantOffer offer = menu.dungeontrain$selectedOffer();
        int index = rawIndex();
        if (offer == null || index < 0) return null;
        List<ItemCost> extras = ExtraTradeCosts.of(offer);
        return index < extras.size() ? extras.get(index) : null;
    }

    @Override
    public boolean isActive() {
        return cost() != null;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        ItemCost cost = cost();
        return cost != null && cost.test(stack);
    }

    @Override
    public ItemStack getItem() {
        int index = rawIndex();
        return index < 0 ? ItemStack.EMPTY : container.getItem(index);
    }

    @Override
    public void set(ItemStack stack) {
        int index = rawIndex();
        if (index < 0) return;
        container.setItem(index, stack);
        setChanged();
    }

    @Override
    public ItemStack remove(int amount) {
        int index = rawIndex();
        return index < 0 ? ItemStack.EMPTY : container.removeItem(index, amount);
    }

    @Override
    public int getContainerSlot() {
        return Math.max(0, rawIndex());
    }
}
