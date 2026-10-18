package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Every line the game says about Priority Books — the thank-you a donation carries: one credit makes
 * one of the player's own books turn up far more often in other players' trains.
 *
 * <p>Donation vocabulary throughout (a thank-you for giving, never something bought), and the copy
 * never promises "first in the queue": the relay's boost is bounded, and the free books still come up.
 * Count-dependent lines go through {@link PluralRules} against the reader's own language, which callers
 * pass in (the recipient's synced language on the server, the selected one on the client).</p>
 */
public final class PriorityBookMessage {

    private static final String CHAT = "chat.dungeontrain.priority_book.";
    private static final String GUI = "gui.dungeontrain.priority_book.";

    private PriorityBookMessage() {}

    /** Spent on the book just signed or chosen, with {@code left} credits remaining. */
    public static Component spent(String locale, long left) {
        return PluralRules.clause(locale, CHAT + "spent", left).withStyle(ChatFormatting.GOLD);
    }

    /** Spent, but the book is still waiting on review — the boost starts once it is approved. */
    public static Component held() {
        return Component.translatable(CHAT + "held").withStyle(ChatFormatting.GOLD);
    }

    /** The player asked for one and none was used (nothing left, or the relay could not apply it). */
    public static Component unused() {
        return Component.translatable(CHAT + "unused").withStyle(ChatFormatting.GRAY);
    }

    /** "N Priority Books waiting." on the Contribute page's "you" line. */
    public static Component waiting(String locale, long n) {
        return PluralRules.clause(locale, GUI + "waiting", n);
    }

    /** The sign page's checkbox label, "Use a Priority Book (N left)". */
    public static Component signToggle(String locale, long n) {
        return PluralRules.clause(locale, GUI + "sign", n);
    }

    /** What the sign toggle does, on hover. */
    public static Component signTooltip() {
        return Component.translatable(GUI + "sign_tooltip");
    }

    /** The note on the sign page, under vanilla's warning, while the star is selected. */
    public static Component signNote() {
        return Component.translatable(GUI + "sign_note");
    }

    /** The vote page control on the author's own approved book: "Make this a Priority Book (N left)". */
    public static Component make(String locale, long n) {
        return PluralRules.clause(locale, GUI + "make", n);
    }

    /** Second tap of that control — spending is final, so it asks once. */
    public static Component makeConfirm() {
        return Component.translatable(GUI + "make_confirm");
    }

    /** The passive mark on a book that already is one. */
    public static Component isPriority() {
        return Component.translatable(GUI + "is_priority");
    }
}
