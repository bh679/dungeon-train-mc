package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Per-stack "wanted to be a player-written book, but the shared pool was cold" marker, stamped via
 * {@link DataComponents#CUSTOM_DATA}. Set by the {@code random_playerbook} loot intercept in
 * {@code ContainerContentsRoller.rollItemStack} when discovery is enabled but
 * {@link SharedBookPool} had not warmed yet — the slot bakes a local fallback book carrying this
 * marker so it can be upgraded to a real community book the next time it reaches a player's hand
 * (see {@code NarrativeBookEvents.onEquipmentChange}).
 *
 * <p>Only stamped in the discovery-on / pool-cold case. A {@code random_playerbook} fallback taken
 * because discovery is <b>disabled</b> is a permanent, intended local book and is never marked —
 * there is nothing to upgrade it to. Fresh key prefix ({@code dt_playerbook_pending}) so it can't
 * collide with the {@code dt_random_book*} or {@code dt_shared_book*} keys.</p>
 */
public final class PlayerBookPendingTag {

    /** "Awaiting a player-book upgrade once the shared pool warms" flag. */
    public static final String NBT_PENDING = "dt_playerbook_pending";

    private PlayerBookPendingTag() {}

    /** Idempotently flag {@code stack} as awaiting a player-book upgrade. No-op on empty/null stacks. */
    public static void markPending(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_PENDING, true));
    }

    /** True when {@code stack} is a cold-pool fallback awaiting upgrade. Safe on any stack. */
    public static boolean isPending(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_PENDING, Tag.TAG_BYTE) && tag.getBoolean(NBT_PENDING);
    }

    /** Remove the pending marker (after a successful upgrade). No-op on empty/null stacks. */
    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(NBT_PENDING));
    }
}
