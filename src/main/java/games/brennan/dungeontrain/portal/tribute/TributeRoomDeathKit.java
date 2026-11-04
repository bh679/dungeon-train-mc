package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.compat.vista.TvBlocks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a visitor has to build with: a copy of whatever they dropped when they died.
 *
 * <p>The real drops still fall where they fell — a friend can pick them up — and the copy is confined
 * to the room: whatever is still in hand when the visit ends is cleared
 * ({@link TributeRoomVisitService}), so only what was built into the room, or stored in it, survives. With
 * keepInventory on there is no copy at all: the player arrives with their real inventory and the room's
 * chests are a stash across runs.</p>
 */
public final class TributeRoomDeathKit {

    private static final Map<UUID, List<ItemStack>> KITS = new ConcurrentHashMap<>();

    private TributeRoomDeathKit() {}

    /** Remember a copy of {@code drops} as {@code player}'s kit. Called from the drops event, before the visit. */
    public static void remember(UUID player, Collection<ItemEntity> drops) {
        List<ItemStack> copies = new ArrayList<>(drops.size());
        for (ItemEntity drop : drops) copies.add(drop.getItem().copy());
        KITS.put(player, copies);
    }

    /** Take {@code player}'s kit, filtered by {@link #kitFor}; empty when nothing was remembered. */
    public static List<ItemStack> take(UUID player, boolean keepInventory) {
        List<ItemStack> drops = KITS.remove(player);
        return drops == null ? List.of() : kitFor(drops, keepInventory);
    }

    public static void forget(UUID player) {
        KITS.remove(player);
    }

    public static void clear() {
        KITS.clear();
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

    static boolean excluded(ItemStack stack) {
        if (TvBlocks.isLiveCassette(stack) || TvBlocks.isHomeCassette(stack)) return true;
        return stack.getItem() instanceof BlockItem block && TvBlocks.isTelevision(block.getBlock().defaultBlockState());
    }
}
