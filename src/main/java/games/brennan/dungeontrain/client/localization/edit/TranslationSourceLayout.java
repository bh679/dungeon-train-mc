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

    /**
     * The tallest the English may be dragged: until all of it is showing, or until the edit box is
     * down to its two rows, whichever stops you first.
     *
     * <p>This is allowed to exceed the half-window default from {@link #viewportHeight}, and that
     * is the point. Half the window is what the screen picks when nobody has said otherwise — not
     * a ceiling on what a translator reading a long book variant is permitted to ask for.</p>
     */
    static int maxDragHeight(int contentHeight, int available, int lineHeight) {
        return Math.max(lineHeight * MIN_LINES, Math.min(contentHeight, available));
    }

    /**
     * A height the translator dragged to, clamped into what the window can actually give. The
     * floor is one line so the heading — which is where "this is unreviewed machine translation"
     * is written — cannot be dragged out of existence.
     */
    static int draggedHeight(int preferred, int contentHeight, int available, int lineHeight) {
        int max = maxDragHeight(contentHeight, available, lineHeight);
        return Math.max(lineHeight * MIN_LINES, Math.min(preferred, max));
    }

    /** Is there any room to drag at all? A short string that already fits offers none. */
    static boolean isResizable(int contentHeight, int available, int lineHeight) {
        return maxDragHeight(contentHeight, available, lineHeight) > lineHeight * MIN_LINES;
    }

    /** How far the block scrolls — zero whenever it is showing everything it has. */
    static int maxScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - viewportHeight);
    }
}
