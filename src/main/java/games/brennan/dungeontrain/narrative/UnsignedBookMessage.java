package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * Flavour chat line shown when a player shelves a book &amp; quill they have written in but never signed
 * (see {@link UnsignedBookShelfNotice}).
 *
 * <p>Signing is what makes a book real to the train: {@code mixin.ServerGamePacketListenerImplSignBookMixin}
 * turns a signed book into a community contribution, a Death/Love Note, or a lectern letter, and stamps the
 * carriage it was written in. A book left unsigned reaches none of that — so shelving one is the moment to
 * say, quietly, that nothing happened.</p>
 *
 * <p>Delivered via {@link net.minecraft.server.level.ServerPlayer#sendSystemMessage} into the chat box, styled
 * {@link ChatFormatting#GRAY} to match the game's other muted lines — the drifting-carriage entry lines
 * ({@link games.brennan.dungeontrain.train.SharedCarriageMessage}) and the book-burn send-off
 * ({@link SharedBookMessage}).</p>
 *
 * <p><b>Localization.</b> Each line is a {@link Component#translatable} key
 * {@code chat.dungeontrain.unsigned_book.1..LINE_COUNT}; the server picks one at random and the client renders
 * it in its own language.</p>
 */
public final class UnsignedBookMessage {

    private UnsignedBookMessage() {}

    /** Number of lines, keyed {@code chat.dungeontrain.unsigned_book.1..LINE_COUNT} in the lang files. */
    static final int LINE_COUNT = 5;

    /** A random "you left it unsigned" line as a gray chat {@link Component}. */
    public static Component random(RandomSource rng) {
        int n = rng.nextInt(LINE_COUNT) + 1;
        return Component.translatable("chat.dungeontrain.unsigned_book." + n).withStyle(ChatFormatting.GRAY);
    }
}
