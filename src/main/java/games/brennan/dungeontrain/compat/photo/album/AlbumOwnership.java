package games.brennan.dungeontrain.compat.photo.album;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/**
 * Who an album belongs to, carried on the stack in {@link DataComponents#CUSTOM_DATA}.
 *
 * <p>An owned {@code exposure:album} is a window onto one of its owner's albums ({@link #NBT_INDEX}; a
 * stack without one shows the first): its pages are refilled from {@link AlbumStore} every time it opens. A found album (another
 * player's, read-only) is an {@code exposure:signed_album} carrying {@link #NBT_FOUND} instead.</p>
 */
public final class AlbumOwnership {

    static final String NBT_OWNER = "dt_album_owner";
    static final String NBT_OWNER_NAME = "dt_album_owner_name";
    /** Which of the owner's albums the stack shows: 0 (or missing) is the first; a donor has more. */
    static final String NBT_INDEX = "dt_album_index";
    /** On a found album: the owner whose album it shows, so the view can be counted and it burns on close. */
    static final String NBT_FOUND = "dt_found_album_owner";

    public record Owner(UUID uuid, String name, int index) {
        public Owner(UUID uuid, String name) {
            this(uuid, name, 0);
        }
    }

    private AlbumOwnership() {}

    private static CompoundTag tag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** The owner of a player's album, if {@code stack} is one. */
    public static Optional<Owner> owner(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CompoundTag tag = tag(stack);
        if (!tag.hasUUID(NBT_OWNER)) return Optional.empty();
        return Optional.of(new Owner(tag.getUUID(NBT_OWNER), tag.getString(NBT_OWNER_NAME), cleanIndex(tag.getInt(NBT_INDEX))));
    }

    public static boolean isOwnedBy(ItemStack stack, UUID player) {
        return owner(stack).map(o -> o.uuid().equals(player)).orElse(false);
    }

    private static int cleanIndex(int index) {
        return Math.min(Math.max(index, 0), AlbumSavePayload.MAX_INDEX);
    }

    /** Make {@code stack} {@code owner}'s album at {@code index}: their name on it, now and for good — it cannot be signed. */
    public static void stamp(ItemStack stack, UUID owner, String name, int index) {
        int clean = cleanIndex(index);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID(NBT_OWNER, owner);
            tag.putString(NBT_OWNER_NAME, name);
            if (clean > 0) tag.putInt(NBT_INDEX, clean);
            else tag.remove(NBT_INDEX);
        });
        stack.set(DataComponents.CUSTOM_NAME, albumName(name, clean));
    }

    /** "Alex's Album" for the first, "Alex's Album 2" after it, unitalicised like a real item name. */
    public static Component albumName(String ownerName, int index) {
        Component name = index > 0
                ? Component.translatable("item.dungeontrain.player_album.name_numbered", ownerName, index + 1)
                : Component.translatable("item.dungeontrain.player_album.name", ownerName);
        return name.copy().withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.WHITE));
    }

    public static void markFound(ItemStack stack, UUID owner, int index) {
        int clean = cleanIndex(index);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID(NBT_FOUND, owner);
            if (clean > 0) tag.putInt(NBT_INDEX, clean);
        });
    }

    /** Which of its owner's albums a found album shows (0 for the first). */
    public static int foundIndex(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return cleanIndex(tag(stack).getInt(NBT_INDEX));
    }

    /** The owner of the album a found (read-only) album shows, if {@code stack} is one. */
    public static Optional<UUID> foundOwner(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CompoundTag tag = tag(stack);
        return tag.hasUUID(NBT_FOUND) ? Optional.of(tag.getUUID(NBT_FOUND)) : Optional.empty();
    }
}
