package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.OptionalInt;

/**
 * Stamps and reads the carriage index (the HUD "Carriage:" {@code pIdx}) a player was standing in
 * when they signed a book &amp; quill into a written book, via
 * {@link games.brennan.dungeontrain.mixin.ServerGamePacketListenerImplSignBookMixin}. Absent when the
 * player wasn't tracked near/on a train at sign time.
 */
public final class SignedCarriageTag {

    /** Carriage index the book was signed in. */
    public static final String NBT_KEY = "dt_signed_carriage";

    private SignedCarriageTag() {}

    /** Stamp the carriage index onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack, int carriageIndex) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(NBT_KEY, carriageIndex));
    }

    /** The stamped carriage index, or empty when {@code stack} carries no sign-time carriage data. */
    public static OptionalInt readIndex(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return OptionalInt.empty();
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_INT) ? OptionalInt.of(tag.getInt(NBT_KEY)) : OptionalInt.empty();
    }
}
