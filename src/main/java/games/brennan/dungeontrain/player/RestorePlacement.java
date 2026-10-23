package games.brennan.dungeontrain.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * The Minecraft-free part of an admin inventory restore: where each restored stack goes, and which
 * of the player's current stacks have to move out of the way. Nothing a player holds is ever
 * dropped by this step — an occupied slot's stack is listed as displaced, and the caller packs
 * those into chest items.
 *
 * <p>Snapshot slots follow the player NBT layout ({@code Inventory} list): 0-35 hotbar + main,
 * 100-103 armour (feet, legs, chest, head), -106 offhand. {@link #toInventoryIndex} maps those onto
 * {@code Inventory#getItem} indices: 0-35, 36-39, 40.</p>
 */
public final class RestorePlacement {

    /** Slots a single chest item carries through placement ({@code ChestBlockEntity} size). */
    public static final int CHEST_SLOTS = 27;

    private static final int ARMOR_SNAPSHOT_BASE = 100;
    private static final int ARMOR_INVENTORY_BASE = 36;
    private static final int OFFHAND_SNAPSHOT_SLOT = -106;
    private static final int OFFHAND_INVENTORY_INDEX = 40;

    private RestorePlacement() {}

    /** A restored stack bound for one snapshot slot. */
    public record Target<T>(int snapshotSlot, T stack) {}

    /**
     * The outcome: {@code placements} maps inventory index to restored stack, {@code displaced}
     * lists the player's current stacks that must leave those slots, {@code skipped} counts targets
     * with an unknown slot or a slot already claimed earlier in the snapshot.
     */
    public record Plan<T>(Map<Integer, T> placements, List<T> displaced, int skipped) {}

    /** Inventory index for a snapshot slot, or -1 when the slot isn't one a player has. */
    public static int toInventoryIndex(int snapshotSlot) {
        if (snapshotSlot >= 0 && snapshotSlot <= 35) return snapshotSlot;
        if (snapshotSlot >= ARMOR_SNAPSHOT_BASE && snapshotSlot <= ARMOR_SNAPSHOT_BASE + 3) {
            return ARMOR_INVENTORY_BASE + (snapshotSlot - ARMOR_SNAPSHOT_BASE);
        }
        if (snapshotSlot == OFFHAND_SNAPSHOT_SLOT) return OFFHAND_INVENTORY_INDEX;
        return -1;
    }

    /**
     * Plan the restore. {@code current} reads the player's stack at an inventory index and
     * {@code isEmpty} says whether a stack is nothing; neither is called for an unknown slot.
     */
    public static <T> Plan<T> plan(List<Target<T>> targets, IntFunction<T> current, Predicate<T> isEmpty) {
        Map<Integer, T> placements = new LinkedHashMap<>();
        List<T> displaced = new ArrayList<>();
        int skipped = 0;
        for (Target<T> target : targets) {
            int index = toInventoryIndex(target.snapshotSlot());
            if (index < 0 || placements.containsKey(index)) {
                skipped++;
                continue;
            }
            T existing = current.apply(index);
            if (existing != null && !isEmpty.test(existing)) {
                displaced.add(existing);
            }
            placements.put(index, target.stack());
        }
        return new Plan<>(Collections.unmodifiableMap(placements), List.copyOf(displaced), skipped);
    }

    /** Split {@code stacks} into chest-sized groups, in order. Empty input gives no groups. */
    public static <T> List<List<T>> packIntoChests(List<T> stacks) {
        List<List<T>> chests = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i += CHEST_SLOTS) {
            chests.add(List.copyOf(stacks.subList(i, Math.min(stacks.size(), i + CHEST_SLOTS))));
        }
        return chests;
    }
}
