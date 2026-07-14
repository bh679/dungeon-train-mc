package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * Flavour chat line shown to a player the moment their signed book & quill burns away into the shared
 * community pool (the contribution half of shared books — see
 * {@code mixin.ServerGamePacketListenerImplSignBookMixin}).
 *
 * <p>Delivered via {@link net.minecraft.server.level.ServerPlayer#sendSystemMessage} into the chat box,
 * styled {@link ChatFormatting#GRAY} to match the game's other "sent, but no one is here to answer"
 * line — the offline {@code @dev} presence reply in
 * {@link games.brennan.dungeontrain.util.PresenceLine#recentLine} (gray when the person is offline /
 * recently seen). So burning a book reads like speaking into chat with no one on the other end: your
 * words go out to a stranger you'll never meet.</p>
 *
 * <p><b>Localization.</b> Each send-off is a {@link Component#translatable} key
 * {@code chat.dungeontrain.shared_book.1..LINE_COUNT}; the server picks one at random and the client
 * renders it in its own language.</p>
 */
public final class SharedBookMessage {

    private SharedBookMessage() {}

    /** Number of send-off lines, keyed {@code chat.dungeontrain.shared_book.1..LINE_COUNT} in the lang files. */
    private static final int LINE_COUNT = 10;

    /** A random send-off line as a gray chat {@link Component}, matching the offline-chat style. */
    public static Component random(RandomSource rng) {
        int n = rng.nextInt(LINE_COUNT) + 1;
        return Component.translatable("chat.dungeontrain.shared_book." + n).withStyle(ChatFormatting.GRAY);
    }
}
