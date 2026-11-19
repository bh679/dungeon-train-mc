package games.brennan.dungeontrain.client;

import java.util.function.ToIntFunction;

/**
 * Cuts a label to a pixel width, measured with the font it will be drawn in rather than by a count
 * of characters: a device name such as "Brennan's Galaxy S5 Microphone" on a 100 px value button.
 * Cut labels end in an ellipsis; when not even that fits, the bare cut is returned.
 */
public final class LabelCut {

    static final String ELLIPSIS = "…";

    private LabelCut() {}

    /** {@code text} as it is when {@code width} says it fits in {@code avail} pixels, else cut with an ellipsis. */
    public static String fit(String text, ToIntFunction<String> width, int avail) {
        if (width.applyAsInt(text) <= avail) return text;
        int room = avail - width.applyAsInt(ELLIPSIS);
        if (room <= 0) return longestPrefix(text, width, avail);
        return longestPrefix(text, width, room).stripTrailing() + ELLIPSIS;
    }

    private static String longestPrefix(String text, ToIntFunction<String> width, int avail) {
        int end = text.length();
        while (end > 0 && width.applyAsInt(text.substring(0, end)) > avail) end--;
        return text.substring(0, end);
    }
}
