package games.brennan.dungeontrain.narrative;

import net.minecraft.world.item.ItemStack;

/**
 * Shared predicate for "this book should burn when it leaves a player's
 * inventory". Used by both the client close-detection
 * ({@link games.brennan.dungeontrain.client.StartingBookClientEvents}) and the
 * server burn lifecycle
 * ({@link games.brennan.dungeontrain.event.StartingBookEvents}) so the two
 * sides stay in lock-step about which book types catch fire.
 *
 * <p>Burnable book types:</p>
 * <ul>
 *   <li>{@link StartingBookTag} — lightning-spawned welcome books (one per
 *       login / respawn). Burn on close + any drop.</li>
 *   <li>{@link RandomBookTag} — books that spawn in train chests via
 *       {@link RandomBookFactory}. Burn on close + any drop, matching the
 *       starting-book lifecycle.</li>
 * </ul>
 *
 * <p>Explicitly NOT burnable:</p>
 * <ul>
 *   <li>{@link NarrativeBookTag} — multi-letter "story" books. Must remain
 *       re-readable from lecterns and the player's inventory; burning them
 *       would destroy the narrative arc.</li>
 *   <li>Vanilla written books, books from foreign mods, and any
 *       {@link ItemStack#EMPTY} / null stack.</li>
 * </ul>
 *
 * <p>This is a thin predicate, not a tag — it doesn't stamp anything onto
 * stacks; it just unifies the read-side check across the two existing tag
 * systems.</p>
 */
public final class BurnableBookTag {

    private BurnableBookTag() {}

    /**
     * True when {@code stack} should burn when it leaves a player's inventory.
     * Safe to call on any ItemStack — returns {@code false} for empty / null
     * stacks and for any stack without one of the burnable identity tags.
     */
    public static boolean isBurnable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return StartingBookTag.isStartingBook(stack)
            || RandomBookTag.read(stack).isPresent();
    }
}
