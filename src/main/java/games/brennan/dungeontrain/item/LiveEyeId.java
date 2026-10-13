package games.brennan.dungeontrain.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/**
 * Per-stack id of a Live Eye, given the first time it starts a stream and kept for the item's life.
 *
 * <p>The relay's #live-eye Discord post matches reconnects by it: a relog while still wearing the
 * Live Eye claims the channel again with the same id (no new post), while a newly worn one — a removed
 * Live Eye always burns — carries a new id and gets a post of its own. Same shape as
 * {@link LiveHeadpiecePendingTag}.</p>
 */
public final class LiveEyeId {

    public static final String NBT_EYE_ID = "dt_live_eye_id";

    private LiveEyeId() {}

    /** The id on {@code stack}, or empty when it has none. Safe on any stack. */
    public static String get(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return "";
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_EYE_ID, Tag.TAG_STRING) ? tag.getString(NBT_EYE_ID) : "";
    }

    /** The id on {@code stack}, stamping a fresh one first when it has none. Empty for empty/null stacks. */
    public static String ensure(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        String have = get(stack);
        if (!have.isEmpty()) return have;
        String id = UUID.randomUUID().toString();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(NBT_EYE_ID, id));
        return id;
    }
}
