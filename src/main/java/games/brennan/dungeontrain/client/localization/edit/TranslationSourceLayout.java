package games.brennan.dungeontrain.client.localization.edit;

/**
 * How tall the English block on {@link TranslationEditScreen} is, and how far it scrolls.
 *
 * <p>Split out of the screen because it is the only part of that layout worth testing and the
 * only part that can be: everything else there needs a {@code Font} and a live client. The rule
 * it encodes is that the source is allowed to grow to fit its text, but never past half the
 * window and never so far that the box the translator types in loses its two rows.</p>
 */
final class TranslationSourceLayout {

    /** A pane shorter than one line is not a pane; it is a clipped word. */
    private static final int MIN_LINES = 1;

    private TranslationSourceLayout() {
    }

    /**
     * The height the block wants: the heading, the wrapped English, and a reviewer's reply when
     * there is one. Reply lines cost an extra gap and a byline, which is why they are counted
     * separately rather than added to {@code sourceLines}.
     */
    static int contentHeight(int lineHeight, int gap, int sourceLines, int replyLines) {
        int height = lineHeight + gap + lineHeight * Math.max(0, sourceLines);
        if (replyLines > 0) {
            height += gap + lineHeight * (replyLines + 1);
        }
        return height;
    }

    /**
     * The height the block actually gets: what it wants, capped at half the window, and capped
     * again at whatever room is left over the editor's minimum. The second cap only bites on
     * very short windows, where half the screen is more than the screen can spare.
     */
    static int viewportHeight(int contentHeight, int screenHeight, int available, int lineHeight) {
        int cap = Math.min(screenHeight / 2, available);
        return Math.max(lineHeight * MIN_LINES, Math.min(contentHeight, cap));
    }

    /** How far the block scrolls — zero whenever it is showing everything it has. */
    static int maxScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - viewportHeight);
    }
}
