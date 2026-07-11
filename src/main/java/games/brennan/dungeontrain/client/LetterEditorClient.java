package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

import java.util.List;

/**
 * Client-side entry point for the lectern-letter sign flow. The server (via
 * {@code OpenLetterEditorPacket}) tells the client to open the vanilla book edit/sign screen when a
 * book &amp; quill is right-clicked onto a lectern and the feature is active; this opens it and
 * remembers which lectern it was opened from so
 * {@link LetterLecternClientEvents} can, on a close-without-sign, ask the server to leave the draft
 * on that lectern.
 *
 * <p>The screen is built from the pages carried in the packet (not the held stack) because the
 * client's block-interaction prediction has not rolled back yet when the packet arrives — see
 * {@code OpenLetterEditorPacket}. The real book &amp; quill stays in the server-side hand, so vanilla
 * signing (which targets the hand slot) still works.</p>
 */
public final class LetterEditorClient {

    /** Lectern the currently-open letter editor was opened from, or {@code null} if none is open. */
    private static BlockPos pendingLecternPos;
    /** Set by {@link games.brennan.dungeontrain.mixin.client.BookEditScreenSignMixin} when the player signs. */
    private static boolean signed;

    private LetterEditorClient() {}

    /** Open the book edit/sign screen for a lectern letter (called from the server-sent open packet). */
    public static void open(int handOrdinal, BlockPos pos, List<String> pages) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        InteractionHand[] hands = InteractionHand.values();
        InteractionHand hand = handOrdinal >= 0 && handOrdinal < hands.length ? hands[handOrdinal] : InteractionHand.MAIN_HAND;

        // Build a display-only writable book from the carried pages; the real book lives in the
        // server-side hand slot the sign packet targets.
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        List<Filterable<String>> filterable = pages.stream().map(Filterable::passThrough).toList();
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(filterable));

        pendingLecternPos = pos;
        signed = false;
        Minecraft.getInstance().setScreen(new BookEditScreen(player, book, hand));
    }

    /** Called by the sign mixin when the player clicks Sign &amp; Close (a "publish" save). */
    public static void markSigned() {
        signed = true;
    }

    /**
     * Called when a {@link BookEditScreen} is closing. Returns the lectern to leave the unsigned draft
     * on (so the caller sends the draft packet), or {@code null} when there is no open letter editor or
     * the player signed. Always clears the pending state.
     */
    public static BlockPos onEditScreenClosing() {
        BlockPos pos = pendingLecternPos;
        boolean wasSigned = signed;
        pendingLecternPos = null;
        signed = false;
        return (pos != null && !wasSigned) ? pos : null;
    }
}
