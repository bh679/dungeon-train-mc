package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.event.StartingBookEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Trash for a player's own fresh print: it burns as it would on closing, and it is taken back from
 * the community pool ({@link SharedPhotos#withdrawOwnUpload}) so no one else ever finds it. Offered
 * only on a print that was queued for sharing — any other print burns unseen on {@code X} anyway.
 */
public final class OwnPhotoTrash {

    private static final int TRASHED_LINES = 3;

    private OwnPhotoTrash() {}

    /** The player chose Trash for their own print in hand. */
    public static void trash(ServerPlayer player) {
        Optional<InteractionHand> hand = OwnPhotoTribute.heldOwnPrintHand(player);
        if (hand.isEmpty()) return;
        ItemStack held = player.getItemInHand(hand.get());
        if (!SharedPhotos.withdrawOwnUpload(player, held)) return;
        // Out of the hand first, so the view-closed packet that follows finds nothing left to burn.
        player.setItemInHand(hand.get(), ItemStack.EMPTY);
        StartingBookEvents.dropAndBurn(player, held);
        int n = 1 + player.getRandom().nextInt(TRASHED_LINES);
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.own_photo_trash." + n).withStyle(ChatFormatting.GRAY));
    }
}
