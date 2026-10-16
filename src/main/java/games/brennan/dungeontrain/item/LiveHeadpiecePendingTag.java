package games.brennan.dungeontrain.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Per-stack "rolled from loot, not yet checked against its finder" marker on a live headpiece.
 *
 * <p>{@code ContainerContentsRoller} bakes chest contents per carriage from the world seed, with no
 * player in sight, so it cannot know who will open the chest. It stamps this marker on a headpiece
 * it rolls; {@code event/LiveHeadpieceLootGate} resolves it the first time the stack reaches a
 * player's hand or inventory: a Kid with Livestreaming off gets the book the slot would otherwise
 * have held, everyone else keeps the headpiece and the marker is cleared. Same shape as
 * {@code narrative/PlayerBookPendingTag}.</p>
 */
public final class LiveHeadpiecePendingTag {

    public static final String NBT_PENDING = "dt_live_headpiece_pending";
    /**
     * A loot Live Eye that resolved to a real one but no player has taken yet. It is re-checked each
     * time a player sees it, so it turns into a TV for anyone who already holds a Live Eye. Cleared
     * once it reaches an inventory; eyes a player stores themselves never carry it.
     */
    public static final String NBT_UNCLAIMED = "dt_live_eye_unclaimed";

    private LiveHeadpiecePendingTag() {}

    /** Idempotently flag {@code stack} as awaiting its finder check. No-op on empty/null stacks. */
    public static void markPending(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_PENDING, true));
    }

    /** True when {@code stack} still carries the marker. Safe on any stack. */
    public static boolean isPending(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_PENDING, Tag.TAG_BYTE) && tag.getBoolean(NBT_PENDING);
    }

    /** Remove the marker once the finder check has run. No-op on empty/null stacks. */
    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(NBT_PENDING));
    }

    /** Flag a freshly resolved loot Live Eye as not yet taken by anyone. */
    public static void markUnclaimed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_UNCLAIMED, true));
    }

    /** True when {@code stack} is a loot Live Eye no player has taken yet. Safe on any stack. */
    public static boolean isUnclaimed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_UNCLAIMED, Tag.TAG_BYTE) && tag.getBoolean(NBT_UNCLAIMED);
    }

    /** A player took it: drop the marker. No-op on empty/null stacks. */
    public static void clearUnclaimed(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(NBT_UNCLAIMED));
    }
}
