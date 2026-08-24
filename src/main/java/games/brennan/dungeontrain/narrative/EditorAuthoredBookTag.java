package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto a written book an author signed while standing inside an editor plot
 * (see {@code ServerGamePacketListenerImplSignBookMixin}'s {@code @At("RETURN")} injector). It marks
 * hand-authored <b>content</b> — a lore book stocked into a carriage's loot chest and saved into the
 * template — rather than a player contribution, and takes the place of
 * {@link PlayerWrittenBookTag} on that path.
 *
 * <p>The distinction is the burn timing. {@link PlayerWrittenBookTag} is unconditional: a book
 * signed out in the world burns the first time it is read or dropped, which would destroy an
 * author's copy while they are still building with it. This tag is gated on {@link #NBT_HELD}
 * instead, exactly like {@link RandomBookTag} and {@link SharedBookFoundTag}, and the held marker is
 * deliberately NOT stamped while the holder is standing in an editor plot (see
 * {@code NarrativeBookEvents.onEquipmentChange}). The result:</p>
 * <ul>
 *   <li><b>In the plot</b> — the author writes, signs, proof-reads, drops and re-shelves the book
 *       freely; it never ignites, and it saves into the carriage / contents template verbatim as
 *       ordinary block-entity NBT.</li>
 *   <li><b>In the live train</b> — the first player to take it out of the chest arms it, and it
 *       burns after being read (or dropped) like any other found book.</li>
 * </ul>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with the key prefix
 * {@code dt_editor_authored_book}, distinct from every other book tag so the read-counters in
 * {@code NarrativeBookEvents} and {@code RunStatsEvents} never confuse it with generated
 * mod content or a community book.</p>
 */
public final class EditorAuthoredBookTag {

    /** Boolean marker — present + true means "an author hand-wrote this inside an editor plot". */
    public static final String NBT_KEY = "dt_editor_authored_book";

    /**
     * "Has been held" marker, mirroring {@link SharedBookFoundTag#NBT_HELD}. Set the first time the
     * book lands in a player's mainhand or offhand slot <em>outside</em> every editor plot. The burn
     * flow gates on this, so the authoring session — and a chest spilling the book before anyone
     * picks it up — never ignites it. Sticky: never cleared once set.
     */
    public static final String NBT_HELD = "dt_editor_authored_book_held";

    private EditorAuthoredBookTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /** True when {@code stack} carries the editor-authored marker. Safe on any stack. */
    public static boolean isAuthored(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }

    /**
     * Idempotently flag {@code stack} as "has been held by a player out in the world". Subsequent
     * reads / drops will burn. No-op if the stack already carries the marker.
     */
    public static void markHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_HELD, true));
    }

    /**
     * True when {@code stack} has been held outside an editor plot at least once (see
     * {@link #markHeld}). Safe to call on any stack.
     */
    public static boolean isHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_HELD, Tag.TAG_BYTE) && tag.getBoolean(NBT_HELD);
    }
}
