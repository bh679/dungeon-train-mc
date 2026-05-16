package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Marker tag stamped onto every welcome book at spawn time so client + server
 * can recognise it. Used by:
 * <ul>
 *   <li><b>Client</b> — {@code StartingBookClientEvents.onScreenClosing} checks
 *       this on the player's held stack to decide whether a closing
 *       {@code BookViewScreen} was showing a starting book; if so, the client
 *       fires {@code StartingBookClosedPacket}.</li>
 *   <li><b>Server</b> — {@code StartingBookEvents.findAndRemoveStartingBook}
 *       scans the player's inventory for stamped stacks when the close packet
 *       arrives, so the right book gets dropped + burned.</li>
 * </ul>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} alongside any other
 * mod NBT — uses a unique key prefix ({@code dt_starting_book}) so it doesn't
 * collide with {@link NarrativeBookTag} ({@code dt_narrative_*}) or
 * {@link RandomBookTag} ({@code dt_random_book*}).</p>
 *
 * <p>The tag is intentionally NOT a {@link RandomBookTag} — random books get
 * auto-swapped to unseen content on the second hold (see
 * {@link NarrativeBookEvents#onEquipmentChange}); starting books must keep
 * their original content so the close-and-burn flow makes narrative sense.</p>
 */
public final class StartingBookTag {

    /** Boolean marker — present + true means "this is a starting book". */
    public static final String NBT_KEY = "dt_starting_book";

    private StartingBookTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /**
     * True when {@code stack} carries the starting-book marker. Used by both
     * the client (close-detection) and server (inventory scan) sides — safe
     * to call on any ItemStack including empty / non-book stacks.
     */
    public static boolean isStartingBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }
}
