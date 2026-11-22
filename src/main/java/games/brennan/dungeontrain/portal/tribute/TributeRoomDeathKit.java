package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.compat.vista.TvBlocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * What a visitor has to build with: a copy of whatever they dropped when they died.
 *
 * <p>The real drops still fall where they fell — a friend can pick them up — and the copy is confined
 * to the room: whatever is still in hand when the visit ends is cleared
 * ({@link TributeRoomVisitService}), so only what was built into the room, or stored in it, survives. With
 * keepInventory on there is no copy at all: the player arrives with their real inventory and the room's
 * chests are a stash across runs.</p>
 *
 * <p>The drops have lost where they were carried, so the inventory's layout is snapshotted at death
 * ({@link #rememberLayout}, before the drops) and each kit stack is paired back with the slot it came
 * from: armour worn, the offhand held, the hotbar as it was.</p>
 */
public final class TributeRoomDeathKit {

    /** A kit stack and the {@link Inventory} slot it goes back into, or {@link #NO_SLOT}. */
    public record Entry(ItemStack stack, int slot) {}

    public static final int NO_SLOT = -1;

    private static final Map<UUID, List<ItemStack>> KITS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ItemStack>> LAYOUTS = new ConcurrentHashMap<>();

    private TributeRoomDeathKit() {}

    /** Remember a copy of {@code inventory}, slot by slot (empty slots kept as empty). Called at death, before the drops. */
    public static void rememberLayout(UUID player, Inventory inventory) {
        List<ItemStack> bySlot = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) bySlot.add(inventory.getItem(i).copy());
        LAYOUTS.put(player, bySlot);
    }

    /** Remember a copy of {@code drops} as {@code player}'s kit. Called from the drops event, before the visit. */
    public static void remember(UUID player, Collection<ItemEntity> drops) {
        List<ItemStack> copies = new ArrayList<>(drops.size());
        for (ItemEntity drop : drops) copies.add(drop.getItem().copy());
        KITS.put(player, copies);
    }

    /** Take {@code player}'s kit, filtered by {@link #kitFor} and paired with its slots; empty when nothing was remembered. */
    public static List<Entry> take(UUID player, boolean keepInventory) {
        List<ItemStack> drops = KITS.remove(player);
        List<ItemStack> layout = LAYOUTS.remove(player);
        if (drops == null) return List.of();
        List<ItemStack> kit = kitFor(drops, keepInventory);
        int[] slots = assignSlots(kit, layout == null ? List.of() : layout,
            ItemStack::matches, ItemStack::isSameItemSameComponents, ItemStack::isEmpty);
        List<Entry> entries = new ArrayList<>(kit.size());
        for (int i = 0; i < kit.size(); i++) entries.add(new Entry(kit.get(i), slots[i]));
        return List.copyOf(entries);
    }

    public static void forget(UUID player) {
        KITS.remove(player);
        LAYOUTS.remove(player);
    }

    public static void clear() {
        KITS.clear();
        LAYOUTS.clear();
    }

    /**
     * The stacks handed in: nothing with keepInventory (the real inventory is still theirs), and never a
     * TV or a cassette, which are not a player's to hold.
     */
    public static List<ItemStack> kitFor(List<ItemStack> drops, boolean keepInventory) {
        if (keepInventory) return List.of();
        List<ItemStack> kit = new ArrayList<>(drops.size());
        for (ItemStack stack : drops) {
            if (stack.isEmpty() || excluded(stack)) continue;
            kit.add(stack.copy());
        }
        return List.copyOf(kit);
    }

    /**
     * For each kit item, the layout slot it came from, or {@link #NO_SLOT}. Exact matches are paired
     * first, then {@code loose} ones (a count another handler changed); a slot is never used twice, and
     * {@code empty} layout slots are never matched.
     */
    static <T> int[] assignSlots(List<T> kit, List<T> layout, BiPredicate<T, T> exact, BiPredicate<T, T> loose,
                                 Predicate<T> empty) {
        int[] slots = new int[kit.size()];
        Arrays.fill(slots, NO_SLOT);
        boolean[] used = new boolean[layout.size()];
        for (int i = 0; i < layout.size(); i++) used[i] = empty.test(layout.get(i));
        pair(kit, layout, exact, slots, used);
        pair(kit, layout, loose, slots, used);
        return slots;
    }

    private static <T> void pair(List<T> kit, List<T> layout, BiPredicate<T, T> same, int[] slots, boolean[] used) {
        for (int k = 0; k < kit.size(); k++) {
            if (slots[k] != NO_SLOT) continue;
            for (int s = 0; s < layout.size(); s++) {
                if (used[s] || !same.test(kit.get(k), layout.get(s))) continue;
                slots[k] = s;
                used[s] = true;
                break;
            }
        }
    }

    static boolean excluded(ItemStack stack) {
        if (TvBlocks.isLiveCassette(stack) || TvBlocks.isTributeCassette(stack)) return true;
        return stack.getItem() instanceof BlockItem block && TvBlocks.isTelevision(block.getBlock().defaultBlockState());
    }
}
