package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto a written book the moment vanilla actually signs it (see
 * {@code ServerGamePacketListenerImplSignBookMixin}'s {@code @At("RETURN")} injector) — the
 * fallback path taken when the community-books CONTRIBUTION gate fails (feature off, or the
 * signer hasn't granted network consent) and vanilla keeps the book in the player's hand
 * instead of it being uploaded + burned as a {@link SharedBookTag} contribution.
 *
 * <p>Without this tag, a book signed on that fallback path never burns — it sits in the
 * player's inventory forever like any other vanilla written book. This marker brings it into
 * {@link BurnableBookTag#isBurnable}'s scope so it burns after being read (or dropped), the
 * same as starting/random books, since writing the book is itself explicit player intent — no
 * "held" gate is needed (compare {@link RandomBookTag}, which requires one because its books
 * arrive as passive chest loot the player may never have touched).</p>
 *
 * <p>Deliberately NOT a {@link StartingBookTag} / {@link RandomBookTag} / {@link NarrativeBookTag}
 * / {@link SharedBookTag}, so the read-counters in {@code NarrativeBookEvents} and
 * {@code RunStatsEvents} never confuse this with those. Stored under
 * {@link DataComponents#CUSTOM_DATA} with a unique key prefix ({@code dt_player_written_book}).</p>
 */
public final class PlayerWrittenBookTag {

    /** Boolean marker — present + true means "vanilla actually signed this; burn it after reading". */
    public static final String NBT_KEY = "dt_player_written_book";

    private PlayerWrittenBookTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /** True when {@code stack} carries the player-written-book marker. Safe on any stack. */
    public static boolean isPlayerWritten(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }
}
