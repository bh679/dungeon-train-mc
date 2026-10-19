package games.brennan.dungeontrain.client;

import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The plain text the book screen's Copy button puts on the clipboard: title, "by author", then every
 * page, each separated by a blank line. Formatting codes are stripped and trailing empty pages dropped
 * (the vote page {@code BookVoteClientEvents} appends to community books is one).
 */
public final class BookCopyText {

    private BookCopyText() {}

    /**
     * @param title  the book's title, or null/blank when unknown (lectern without a stack, say)
     * @param byLine the already-translated "by author" line, or null/blank when unknown
     * @param pages  each page's plain text, in order
     */
    public static String format(String title, String byLine, List<String> pages) {
        List<String> blocks = new ArrayList<>();
        StringBuilder head = new StringBuilder();
        if (title != null && !title.isBlank()) head.append(clean(title));
        if (byLine != null && !byLine.isBlank()) {
            if (head.length() > 0) head.append('\n');
            head.append(clean(byLine));
        }
        if (head.length() > 0) blocks.add(head.toString());

        int last = pages.size() - 1;
        while (last >= 0 && clean(pages.get(last)).isBlank()) last--;
        for (int i = 0; i <= last; i++) blocks.add(clean(pages.get(i)));
        return String.join("\n\n", blocks);
    }

    private static String clean(String s) {
        if (s == null) return "";
        String stripped = ChatFormatting.stripFormatting(s);
        return (stripped == null ? "" : stripped).strip();
    }
}
