package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto the written book the mod drops when a player signs a book titled
 * "Love Note" (see {@link DeathNoteSigning}) — the mirror of {@link DeathNoteBookTag}. Like it, the
 * marker makes the stack burnable ({@link BurnableBookTag#isBurnable}); unlike it, it selects the
 * <b>love</b> burn variant in {@code StartingBookEvents} (pink dust + a warm chime instead of
 * soul-fire), and the pink book look (a {@code CUSTOM_MODEL_DATA} override).
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with key {@code dt_love_note} so it never
 * collides with {@code dt_death_note} / {@code dt_shared_book} / {@code dt_starting_book*} /
 * {@code dt_random_book*} / {@code dt_narrative_*}. Carries no identity — the target/author live in
 * the note record (world save + relay), not on the item.</p>
 */
public final class LoveNoteBookTag {

    /** Boolean marker — present + true means "this is a signed Love Note; burn it (love variant)". */
    public static final String NBT_KEY = "dt_love_note";

    private LoveNoteBookTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /** True when {@code stack} carries the Love Note marker. Safe on any stack. */
    public static boolean isLoveNote(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }
}
