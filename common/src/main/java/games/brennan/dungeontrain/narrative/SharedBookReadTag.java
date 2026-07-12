package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.OptionalInt;

/**
 * Stamps and reads the relay pool {@code id} on a DISCOVERED community book (one drawn from
 * {@link SharedBookPool#rollShared} into chest loot), so a read of it can be attributed back to the
 * specific submission on the data-explorer's Books page.
 *
 * <p>Distinct from {@link SharedBookTag}: that marker lives on the CONTRIBUTION book (the one a player
 * signs → the mod burns → uploads) and carries no identity. A discovered book is a plain written book
 * the player keeps and reads; this tag adds only an opaque numeric id under a unique key so the
 * read-telemetry client ({@link games.brennan.dungeontrain.client.BookReadClientEvents}) can identify
 * it. It never affects loot, burning, or story progression.</p>
 */
public final class SharedBookReadTag {

    /** Relay pool id of the community submission this discovered book was built from. */
    public static final String NBT_ID = "dt_shared_book_id";

    private SharedBookReadTag() {}

    /** Stamp the pool id onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stampId(ItemStack stack, int id) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(NBT_ID, id));
    }

    /** The stamped pool id, or empty when {@code stack} is not a discovered community book. */
    public static OptionalInt readId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return OptionalInt.empty();
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_ID, Tag.TAG_INT) ? OptionalInt.of(tag.getInt(NBT_ID)) : OptionalInt.empty();
    }
}
