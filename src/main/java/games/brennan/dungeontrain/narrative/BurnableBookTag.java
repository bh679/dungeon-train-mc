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
 *       login / respawn). Burn on close + any drop. Unconditional — the
 *       lightning strike that delivers them implies player intent to
 *       receive.</li>
 *   <li>{@link RandomBookTag} — books that spawn in train chests via
 *       {@link RandomBookFactory}. Burn on close + any drop, but ONLY
 *       after a player has held the stack at least once (the
 *       {@link RandomBookTag#NBT_HELD} marker is set by
 *       {@code NarrativeBookEvents.onEquipmentChange}). A random book
 *       falling out of a broken pot / chest / hopper does not burn until
 *       someone has actually picked it up.</li>
 * </ul>
 *
 * <p>Explicitly NOT burnable:</p>
 * <ul>
 *   <li>{@link NarrativeBookTag} — multi-letter "story" books. Must remain
 *       re-readable from lecterns and the player's inventory; burning them
 *       would destroy the narrative arc.</li>
 *   <li>Random-book stacks that have never been held by a player (no
 *       {@link RandomBookTag#NBT_HELD} marker).</li>
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
     *
     * <p>Random books additionally require the
     * {@link RandomBookTag#isHeld} marker — they don't burn until a player
     * has held them at least once.</p>
     */
    public static boolean isBurnable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (StartingBookTag.isStartingBook(stack)) return true;
        if (RandomBookTag.read(stack).isPresent() && RandomBookTag.isHeld(stack)) return true;
        return false;
    }
}
