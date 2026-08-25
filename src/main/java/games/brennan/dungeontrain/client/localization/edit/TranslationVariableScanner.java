package games.brennan.dungeontrain.client.localization.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the format placeholders in a translation string and numbers them the way Java will.
 *
 * <p>Deliberately free of Minecraft types so it can be unit tested — the examples it attaches come
 * in through a lookup function, which the screen wires to {@link TranslationVariableExamples} and a
 * test wires to a map.</p>
 *
 * <h2>Numbering</h2>
 * The same rule {@link java.util.Formatter} uses, because that is what actually renders these
 * strings: a bare {@code %s} takes the next ordinary index, an explicit {@code %2$s} names its own
 * and does <em>not</em> advance the counter. {@code %%} is a literal percent sign and is not a
 * variable at all — underlining it would send a translator hunting for a value that never arrives.
 */
public final class TranslationVariableScanner {

    /**
     * A percent, then either a second percent (a literal) or an optional {@code N$} index and a
     * conversion letter. Deliberately accepts any letter rather than just {@code s}/{@code d}: the
     * lang files only use {@code %s} today, and a new conversion should still be underlined.
     */
    private static final Pattern TOKEN = Pattern.compile("%(?:%|(?:(\\d+)\\$)?([a-zA-Z]))");

    /** How a slot's curated entry reaches the scanner; see {@link TranslationVariableExamples}. */
    @FunctionalInterface
    public interface Lookup {
        /** The entry for {@code key}'s {@code slot}, or null when nothing is curated for it. */
        TranslationVariableExamples.Entry find(String key, int slot);
    }

    private TranslationVariableScanner() {}

    /** Scan against the shipped data file — what the edit screen calls. */
    public static List<TranslationVariable> scan(String key, String text) {
        return scan(key, text, TranslationVariableExamples::lookup);
    }

    /**
     * Every placeholder in {@code text}, in the order it appears, decorated from {@code lookup}.
     *
     * @return an empty list for null/blank text — a string with no variables is the common case
     */
    public static List<TranslationVariable> scan(String key, String text, Lookup lookup) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<TranslationVariable> out = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        int nextOrdinary = 1;
        while (matcher.find()) {
            String token = matcher.group();
            if ("%%".equals(token)) {
                continue; // a literal percent, not a variable
            }
            int slot;
            if (matcher.group(1) != null) {
                slot = parseSlot(matcher.group(1));
                if (slot < 1) {
                    continue; // %0$s is not a valid argument index; leave it as plain text
                }
            } else {
                slot = nextOrdinary++;
            }
            TranslationVariableExamples.Entry entry = lookup == null ? null : lookup.find(key, slot);
            out.add(new TranslationVariable(slot, matcher.start(), matcher.end(), token,
                entry == null ? "" : entry.label(),
                entry == null ? List.of() : entry.examples()));
        }
        return List.copyOf(out);
    }

    /** The highest slot a string uses — what the coverage guard checks the data file against. */
    public static int slotCount(String text) {
        int highest = 0;
        for (TranslationVariable variable : scan("", text, null)) {
            highest = Math.max(highest, variable.slot());
        }
        return highest;
    }

    private static int parseSlot(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1; // an index too long to be an int is not one the game could ever pass
        }
    }
}
