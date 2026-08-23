package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;

/**
 * What the train says about one of the reader's own books that it has not released — shown on the
 * book's vote page (see {@code BookVoteClientEvents}), in that state's colour.
 *
 * <p>One set per {@link BookModerationState}, because they are three different
 * pieces of news and only one of them is bad. {@link BookModerationState#READING} is "nothing has
 * happened yet"; {@link BookModerationState#UNDECIDED} is "it has been read and there is still no
 * answer"; {@link BookModerationState#DISLIKED} is a no — softened by the fact that the train kept
 * the writer's copy anyway, which is the whole point of the feature.</p>
 *
 * <p><b>Deterministic per book, not random.</b> The vote page redraws every frame, so a line picked
 * at random would flicker through all ten of them as fast as the screen refreshes. The index is
 * derived from the book's identity instead — the same way the vote prompt itself is chosen — so one
 * book always says the same thing, and two books rarely say the same thing.</p>
 *
 * <p><b>Localization.</b> Each line is a {@link Component#translatable} key
 * {@code gui.dungeontrain.book_vote.status.<set>.1..LINE_COUNT}; the client renders it in its own
 * language. Unstyled: the vote page draws with raw colours from its own palette, so the colour is
 * applied there rather than carried on the component.</p>
 */
public final class UnapprovedBookMessage {

    private UnapprovedBookMessage() {}

    private static final String KEY = "gui.dungeontrain.book_vote.status.";

    /** Lines per set, keyed {@code ....<set>.1..LINE_COUNT} in the lang files. */
    public static final int LINE_COUNT = 10;

    /**
     * The line for {@code state} on the book identified by {@code seed}, or {@code null} for
     * {@link BookModerationState#PUBLIC} — somebody else's book gets the train's usual question
     * instead, and nothing here to say about it.
     *
     * <p>A released book of the reader's OWN does get a line ({@code released.*}): its page has no
     * thumbs and no question, so without one it would be a page with a button and no words.</p>
     *
     * @param seed anything stable for one book (the vote page passes {@code bookType + ":" + bookId})
     */
    public static Component forBook(BookModerationState state, String seed) {
        if (state == null || !state.isOwn()) return null;
        int n = Math.floorMod((seed == null ? "" : seed).hashCode(), LINE_COUNT) + 1;
        return Component.translatable(KEY + state.messageKey() + "." + n);
    }
}
