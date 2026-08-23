package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * Flavour chat line shown when a writer picks up one of their own books that the train has not
 * released — see {@link UnapprovedBookGreeter}.
 *
 * <p>Three separate sets, one per {@link BookModerationState}, because they are three different
 * pieces of news and only one of them is bad. {@link BookModerationState#READING} is "nothing has
 * happened yet"; {@link BookModerationState#UNDECIDED} is "it has been read and there is still no
 * answer"; {@link BookModerationState#DISLIKED} is a no — softened by the fact that the train kept
 * the writer's copy anyway, which is the whole point of the feature.</p>
 *
 * <p>Delivered via {@link net.minecraft.server.level.ServerPlayer#sendSystemMessage}, styled
 * {@link ChatFormatting#GRAY} to sit with the game's other muted lines ({@link SharedBookMessage},
 * {@link UnsignedBookMessage}, {@link FamiliarBookMessage}). Grey rather than the book's own tint on
 * purpose: the colour belongs to the object, and a coloured chat line among grey ones reads as an
 * alert about something going wrong.</p>
 *
 * <p><b>Localization.</b> Each line is a {@link Component#translatable} key
 * {@code chat.dungeontrain.unapproved_book.<set>.1..LINE_COUNT}; the server picks one and the client
 * renders it in its own language.</p>
 */
public final class UnapprovedBookMessage {

    private UnapprovedBookMessage() {}

    private static final String KEY = "chat.dungeontrain.unapproved_book.";

    /** Lines per set, keyed {@code ....<set>.1..LINE_COUNT} in the lang files. */
    static final int LINE_COUNT = 10;

    /**
     * A random line for {@code state}, or {@code null} for {@link BookModerationState#APPROVED} —
     * a released book is an ordinary community book and says nothing about itself.
     */
    public static Component random(BookModerationState state, RandomSource rng) {
        if (state == null || !state.isWithheld()) return null;
        int n = rng.nextInt(LINE_COUNT) + 1;
        return Component.translatable(KEY + state.messageKey() + "." + n).withStyle(ChatFormatting.GRAY);
    }
}
