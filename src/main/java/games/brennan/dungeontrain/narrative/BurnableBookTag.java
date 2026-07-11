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
 *   <li>{@link SharedBookTag} — the written book dropped when a player SIGNS a
 *       book &amp; quill for the community-books feature. The mod cancels vanilla
 *       signing and drops this marked stack so it burns away (its text having
 *       been uploaded to the relay). Unconditional — signing is explicit
 *       intent.</li>
 *   <li>{@link LetterBookTag} — the written book the mod spawns at a lectern when a player signs a
 *       book &amp; quill opened from that lectern (the player-written "lectern letters" feature). Its
 *       text has been uploaded to the relay's per-life narrative series. Unconditional — signing is
 *       explicit intent.</li>
 *   <li>{@link DeathNoteBookTag} — the written book dropped when a player signs a book titled
 *       "Death Note" (the curse mechanic). Burns with the SOUL variant. Unconditional.</li>
 *   <li>{@link RandomBookTag} — books that spawn in train chests via
 *       {@link RandomBookFactory}. Burn on close + any drop, but ONLY
 *       after a player has held the stack at least once (the
 *       {@link RandomBookTag#NBT_HELD} marker is set by
 *       {@code NarrativeBookEvents.onEquipmentChange}). A random book
 *       falling out of a broken pot / chest / hopper does not burn until
 *       someone has actually picked it up.</li>
 *   <li>{@link PlayerWrittenBookTag} — a book a player wrote &amp; signed
 *       themselves that vanilla actually kept (the community-sharing
 *       contribution gate failed or is disabled — see
 *       {@code ServerGamePacketListenerImplSignBookMixin}). Unconditional,
 *       like {@link StartingBookTag} — writing the book is itself explicit
 *       intent, no "held" gate needed.</li>
 *   <li>{@link SharedBookFoundTag} — a community book discovered as chest
 *       loot, written by a real (other) player and credited to them. Burns
 *       ONLY after a player has held the stack at least once (the
 *       {@link SharedBookFoundTag#NBT_HELD} marker, set the same way as
 *       {@link RandomBookTag#NBT_HELD}), so a chest/pot spilling one
 *       doesn't ignite it before anyone picks it up.</li>
 * </ul>
 *
 * <p>Explicitly NOT burnable:</p>
 * <ul>
 *   <li>{@link NarrativeBookTag} — multi-letter "story" books. Must remain
 *       re-readable from lecterns and the player's inventory; burning them
 *       would destroy the narrative arc.</li>
 *   <li>Random-book / discovered-shared-book stacks that have never been
 *       held by a player (no {@code NBT_HELD} marker).</li>
 *   <li>Vanilla written books from foreign mods, and any
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
        if (SharedBookTag.isSharedBook(stack)) return true;
        if (LetterBookTag.isLetter(stack)) return true;
        if (DeathNoteBookTag.isDeathNote(stack)) return true;
        if (RandomBookTag.read(stack).isPresent() && RandomBookTag.isHeld(stack)) return true;
        if (PlayerWrittenBookTag.isPlayerWritten(stack)) return true;
        if (SharedBookFoundTag.isFound(stack) && SharedBookFoundTag.isHeld(stack)) return true;
        return false;
    }
}
