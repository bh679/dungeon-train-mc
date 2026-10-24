package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

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

    /** The passive mark on a book that already is one. */
    public static Component isPriority() {
        return Component.translatable(GUI + "is_priority");
    }

    // ---- the confirmation before boosting your own book (client/BookBoostConfirm) ----

    /** "Boosting your own book costs 10 boosts, boosting someone else's costs 1." */
    public static Component ownConfirmBody() {
        return Component.translatable(GUI + "own_confirm.body");
    }

    public static Component ownConfirmDoIt() {
        return Component.translatable(GUI + "own_confirm.do_it");
    }

    public static Component ownConfirmCancel() {
        return Component.translatable(GUI + "own_confirm.cancel");
    }

    public static Component ownConfirmDontShow() {
        return Component.translatable(GUI + "own_confirm.dont_show");
    }

    // ---- boosts: a tenth of a Priority Book, given to somebody else's book ----

    /** How many booster names the author's line spells out before "and N more". */
    static final int NAMED_BOOSTERS = 3;

    /** The reader's boost landed, with {@code left} boosts remaining. */
    public static Component boosted(String locale, long left) {
        return PluralRules.clause(locale, CHAT + "boosted", left).withStyle(ChatFormatting.GOLD);
    }

    /** The boost landed, but the relay did not say how many are left. */
    public static Component boostedNoCount() {
        return Component.translatable(CHAT + "boosted_plain").withStyle(ChatFormatting.GOLD);
    }

    /**
     * To the author: which donors boosted their book ("Donor Alex", "Donors Alex, Sam"). Names the
     * newest {@link #NAMED_BOOSTERS} and counts the rest; {@code total} is the number of boosts, so a reader who boosted twice counts twice.
     */
    public static Component boostedBy(String locale, List<String> names, int total) {
        List<String> shown = names == null ? List.of() : names.subList(0, Math.min(NAMED_BOOSTERS, names.size()));
        if (shown.isEmpty()) {
            return PluralRules.clause(locale, CHAT + "boosted_by_anon", total).withStyle(ChatFormatting.GOLD);
        }
        Component who = Component.literal(String.join(", ", shown));
        int others = names.size() - shown.size();
        MutableComponent line = others > 0
            ? Component.translatable(CHAT + "boosted_by_more", who, others)
            : Component.translatable(CHAT + "boosted_by." + PluralRules.category(locale, shown.size()), who);
        return line.withStyle(ChatFormatting.GOLD);
    }

    /** The wallet star's hover beside the book: what a boost does, and that your own costs ten. */
    public static Component boostTooltip() {
        return Component.translatable(GUI + "boost_tooltip");
    }

    /** The word under the star, beside (Y)es and (N)o. */
    public static Component boostLabel() {
        return Component.translatable(GUI + "boost_label");
    }

}
