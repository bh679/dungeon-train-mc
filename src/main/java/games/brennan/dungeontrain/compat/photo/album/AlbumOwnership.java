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
 * <p>An owned {@code exposure:album} is a player's one album: the stack is only a window onto it,
 * its pages are refilled from {@link AlbumWorldCache} every time it opens. A found album (another
 * player's, read-only) is an {@code exposure:signed_album} carrying {@link #NBT_FOUND} instead.</p>
 */
public final class AlbumOwnership {

    static final String NBT_OWNER = "dt_album_owner";
    static final String NBT_OWNER_NAME = "dt_album_owner_name";
    /** On a found album: the owner whose album it shows, so the view can be counted and it burns on close. */
    static final String NBT_FOUND = "dt_found_album_owner";

    public record Owner(UUID uuid, String name) {}

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
        return Optional.of(new Owner(tag.getUUID(NBT_OWNER), tag.getString(NBT_OWNER_NAME)));
    }

    public static boolean isOwnedBy(ItemStack stack, UUID player) {
        return owner(stack).map(o -> o.uuid().equals(player)).orElse(false);
    }

    /** Make {@code stack} {@code owner}'s album: their name on it, now and for good — it cannot be signed. */
    public static void stamp(ItemStack stack, UUID owner, String name) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID(NBT_OWNER, owner);
            tag.putString(NBT_OWNER_NAME, name);
        });
        stack.set(DataComponents.CUSTOM_NAME, albumName(name));
    }

    /** "Alex's Album", unitalicised like a real item name. */
    public static Component albumName(String ownerName) {
        return Component.translatable("item.dungeontrain.player_album.name", ownerName)
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.WHITE));
    }

    public static void markFound(ItemStack stack, UUID owner) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(NBT_FOUND, owner));
    }

    /** The owner of the album a found (read-only) album shows, if {@code stack} is one. */
    public static Optional<UUID> foundOwner(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CompoundTag tag = tag(stack);
        return tag.hasUUID(NBT_FOUND) ? Optional.of(tag.getUUID(NBT_FOUND)) : Optional.empty();
    }
}
