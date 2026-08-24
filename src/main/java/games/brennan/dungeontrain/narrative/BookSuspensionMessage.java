package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.time.Duration;

/**
 * What the train says to a writer whose uploads are paused — the player-facing half of duplicate-book
 * detection (relay side: {@code booksuspensions.js}).
 *
 * <p>Three pieces of news, each with the time left folded in:</p>
 * <ul>
 *   <li>{@link #duplicate} — "you have already sent this one"; the relay refused the upload and
 *       started the pause. Arrives when the relay answers, moments after the book burns.</li>
 *   <li>{@link #blocked} — the player signed another book while paused. Nothing was uploaded and
 *       nothing burned: they keep the book (see {@code ServerGamePacketListenerImplSignBookMixin}).</li>
 *   <li>{@link #letterNotShared} — a lectern letter sealed while paused. The letter burns at the
 *       lectern as always, it simply never leaves the machine.</li>
 * </ul>
 *
 * <p><b>Localization.</b> Each line is a {@link Component#translatable} key taking the remaining time
 * as its one argument, itself a localized "30 seconds" / "2 minutes" clause built by
 * {@link PresenceLine#agoComponent} — so the grammatical number is chosen against the RECIPIENT's
 * language ({@link PluralRules}), not the server's. Styled {@link ChatFormatting#RED} for the two
 * refusals and {@link ChatFormatting#GRAY} for the letter, matching {@link SharedBookMessage}'s
 * quieter send-off voice.</p>
 */
public final class BookSuspensionMessage {

    private BookSuspensionMessage() {}

    private static final String KEY = "chat.dungeontrain.book_suspended.";

    /** The remaining window as a localized "N seconds" / "N minutes" clause. Floors at one second. */
    private static Component left(String locale, long remainingSec) {
        return PresenceLine.agoComponent(locale, Duration.ofSeconds(Math.max(1L, remainingSec)));
    }

    /** The relay refused this book as one the player has already uploaded, and paused their uploads. */
    public static Component duplicate(String locale, long remainingSec) {
        return Component.translatable(KEY + "duplicate", left(locale, remainingSec)).withStyle(ChatFormatting.RED);
    }

    /** The player signed a book while paused — it stays in their hands rather than burning for nothing. */
    public static Component blocked(String locale, long remainingSec) {
        return Component.translatable(KEY + "blocked", left(locale, remainingSec)).withStyle(ChatFormatting.RED);
    }

    /** A lectern letter was sealed while paused: it burns as usual, but is not shared. */
    public static Component letterNotShared(String locale, long remainingSec) {
        return Component.translatable(KEY + "letter", left(locale, remainingSec)).withStyle(ChatFormatting.GRAY);
    }
}
