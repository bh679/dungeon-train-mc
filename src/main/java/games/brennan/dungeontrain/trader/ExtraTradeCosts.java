package games.brennan.dungeontrain.trader;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Villager trades that cost more than vanilla's two slots allow. The costs past A and B ride on
 * the offer's result stack as {@code minecraft:custom_data} → {@value #KEY}, so they save with the
 * villager and reach the client in the vanilla offers packet without a new packet or registry.
 * The trade screen shows them as a grid of extra input slots ({@code MerchantMenuExtraCostsMixin}),
 * and the item a player actually receives has the marker stripped.
 *
 * <p>Grid layout: all of an offer's costs (A, B, then the extras) in reading order, {@value #COLUMNS}
 * to a row, {@value #VISIBLE_ROWS} rows on screen, scrolling a row at a time past that.</p>
 */
public final class ExtraTradeCosts {

    /** Custom-data key holding the extra costs: a list of {@code {id, count}}. */
    public static final String KEY = "dungeontrain_extra_costs";
    /** Most costs one trade may ask for, A and B included. */
    public static final int MAX_COSTS = 32;
    /** Most extra costs, past A and B. */
    public static final int MAX_EXTRA = MAX_COSTS - 2;
    public static final int COLUMNS = 4;
    public static final int VISIBLE_ROWS = 3;
    /** Extra-cost slots on screen: a full grid, since past row 0 the A and B places hold extras too. */
    public static final int VISIBLE_SLOTS = COLUMNS * VISIBLE_ROWS;
    /** {@code clickMenuButton} id for scroll row 0; row {@code r} is this plus {@code r}. */
    public static final int SCROLL_BUTTON_BASE = 7000;

    /** Grid item positions in the merchant screen, matching the inventory's 18-pixel pitch. */
    public static final int GRID_X = 108;
    public static final int GRID_Y = 17;
    public static final int PITCH = 18;

    private ExtraTradeCosts() {}

    /** The extra costs on {@code result}, empty when it has none. Malformed entries are skipped. */
    public static List<ItemCost> read(ItemStack result) {
        CustomData data = result.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) return List.of();
        ListTag list = data.copyTag().getList(KEY, Tag.TAG_COMPOUND);
        List<ItemCost> out = new ArrayList<>();
        for (int i = 0; i < list.size() && out.size() < MAX_EXTRA; i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("id"));
            int count = entry.getInt("count");
            if (id == null || count <= 0) continue;
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> out.add(new ItemCost(item, Math.min(count, 99))));
        }
        return List.copyOf(out);
    }

    public static List<ItemCost> of(MerchantOffer offer) {
        return offer == null ? List.of() : read(offer.getResult());
    }

    /** A copy of {@code result} carrying {@code costs} as its extra costs. */
    public static ItemStack withCosts(ItemStack result, List<ItemCost> costs) {
        ItemStack copy = result.copy();
        if (costs.isEmpty()) return strip(copy);
        ListTag list = new ListTag();
        for (ItemCost cost : costs.subList(0, Math.min(costs.size(), MAX_EXTRA))) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", BuiltInRegistries.ITEM.getKey(cost.item().value()).toString());
            entry.putInt("count", cost.count());
            list.add(entry);
        }
        CustomData.update(DataComponents.CUSTOM_DATA, copy, tag -> tag.put(KEY, list));
        return copy;
    }

    /** {@code stack} without the marker; the same stack when it has none. */
    public static ItemStack strip(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) return stack;
        ItemStack copy = stack.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, copy, tag -> tag.remove(KEY));
        return copy;
    }

    /** Every cost of the offer in grid order: A, B (when present), then the extras. */
    public static List<ItemCost> allCosts(MerchantOffer offer) {
        List<ItemCost> out = new ArrayList<>();
        out.add(offer.getItemCostA());
        offer.getItemCostB().ifPresent(out::add);
        out.addAll(of(offer));
        return List.copyOf(out);
    }

    /** True when every extra cost is met by the backing container slot of the same index. */
    public static boolean isPaid(List<ItemCost> extras, Container backing) {
        for (int i = 0; i < extras.size(); i++) {
            ItemStack paid = backing.getItem(i);
            if (!extras.get(i).test(paid) || paid.getCount() < extras.get(i).count()) return false;
        }
        return true;
    }

    /** Takes each extra cost out of {@code backing}; call only after {@link #isPaid}. */
    public static void take(List<ItemCost> extras, Container backing) {
        for (int i = 0; i < extras.size(); i++) {
            backing.removeItem(i, extras.get(i).count());
        }
    }

    /**
     * Backing-container index shown by on-screen extra slot {@code visible} at {@code scrollRow},
     * or -1 when that place is A or B (grid places 0 and 1 on row 0). Pure, for tests.
     */
    public static int backingIndex(int scrollRow, int visible) {
        int place = scrollRow * COLUMNS + visible;
        return place < 2 ? -1 : place - 2;
    }

    /** Rows the grid can scroll past the first, for {@code totalCosts} costs. Pure, for tests. */
    public static int maxScrollRow(int totalCosts) {
        int rows = (totalCosts + COLUMNS - 1) / COLUMNS;
        return Math.max(0, rows - VISIBLE_ROWS);
    }
}
