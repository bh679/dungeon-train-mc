package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Per-stack "has this exact book been opened" marker, stamped via
 * {@link DataComponents#CUSTOM_DATA}. Distinct from {@link RandomBookTag#NBT_HELD},
 * which only means "reached a hand slot" — this marks the moment the player actually
 * right-clicks to open the book (see {@code NarrativeBookEvents.onRightClickRandomBookItem}
 * / {@code onRightClickStartingBookItem}).
 *
 * <p>Drives the "burned without reading" milestone
 * ({@code games.brennan.dungeontrain.event.StartingBookEvents#onEntityJoinLevel}) — a
 * starting/random book that burns while still lacking this marker counts toward it.</p>
 */
public final class BookReadMarkerTag {

    private static final String NBT_OPENED = "dt_book_opened";

    private BookReadMarkerTag() {}

    /** Idempotently flag {@code stack} as opened. No-op on empty/null stacks. */
    public static void markOpened(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_OPENED, true));
    }

    /** True when {@code stack} has been opened at least once. Safe on any stack. */
    public static boolean isOpened(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_OPENED, Tag.TAG_BYTE) && tag.getBoolean(NBT_OPENED);
    }
}
