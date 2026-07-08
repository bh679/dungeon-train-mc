package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.List;

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
 */
public final class SharedBookMessage {

    private SharedBookMessage() {}

    /** The pool of one-line send-offs. First is the operator-authored line; the rest are variations. */
    private static final List<String> LINES = List.of(
        "The train has your book now. For someone else's eyes…",
        "Your words ride the rails now — bound for a stranger's hands.",
        "The book burns away. Somewhere down the line, someone will find it.",
        "Ash and ink. Your story travels on without you.",
        "The train swallows your book. It will surface in another traveler's chest.",
        "Gone to smoke — carried ahead to a reader you'll never meet.",
        "Your book is the train's now, to leave in a carriage far from here.",
        "The pages curl and vanish. They'll open again for someone else.",
        "Sealed, signed, surrendered. The rails will carry it to a stranger.",
        "Into the fire, and out into the world. May another find your words."
    );

    /** A random send-off line as a gray chat {@link Component}, matching the offline-chat style. */
    public static Component random(RandomSource rng) {
        String line = LINES.get(rng.nextInt(LINES.size()));
        return Component.literal(line).withStyle(ChatFormatting.GRAY);
    }
}
