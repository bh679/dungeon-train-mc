package games.brennan.dungeontrain.client.localization.edit;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whether a translator's text will render — asked while they are still typing it.
 *
 * <p>A lang string's placeholders are not decoration. The caller passes arguments positionally, so
 * a translation that loses a {@code %s} renders with a value missing, one that invents a
 * {@code %2$s} reads an argument nobody passed, and a bare {@code %} throws outright: it is
 * {@code Component.translatable} that resolves these, and that hands the result to Minecraft's
 * format parser.</p>
 *
 * <p>All three used to be found only at the far end of the pipe. On 2026-08-30 an approved Russian
 * translation of {@code clear_backups.confirm.both} carried one {@code %s} where the English has
 * two; it passed through the editor, the relay and human approval, and then failed the weekly
 * import — after the translator's effort had been spent. This asks the same question at the
 * keystroke, so the answer arrives while the text is still in front of them.</p>
 *
 * <p>Deliberately free of Minecraft types, like {@link TranslationVariableScanner} which it builds
 * on: it returns a lang KEY and the tokens to name, and the screen turns that into a component.
 * The rule is the one {@code scripts/localization/lang_format.py} enforces on the import side, so
 * both ends of the pipe agree about what a valid translation is.</p>
 */
public final class TranslationFormatCheck {

    /** A lang key naming what is wrong, plus the tokens to name in it (may be empty). */
    public record Problem(String messageKey, String tokens) {}

    private static final String PREFIX = "gui.dungeontrain.translate.edit.blocked.";
    public static final String BARE_PERCENT = PREFIX + "bare_percent";
    public static final String MISSING_VARS = PREFIX + "missing_vars";
    public static final String EXTRA_VARS = PREFIX + "extra_vars";

    /**
     * Everything the format parser accepts — the scanner's own pattern. Whatever percent survives
     * removing all of these is one the parser will choke on.
     */
    private static final Pattern TOKEN = Pattern.compile("%(?:%|(?:\\d+\\$)?[a-zA-Z])");

    private TranslationFormatCheck() {}

    /**
     * What is wrong with {@code translated} as a rendering of {@code source}, or null if nothing.
     *
     * <p>Blank text is always fine: clearing the box is how a translator reverts to the shipped
     * string, so it means "no override" rather than "an empty translation".</p>
     *
     * <p>Order matters. A bare {@code %} is reported ahead of a slot mismatch because it is the
     * one that crashes rather than merely reads wrong, and because a translator who has typed
     * {@code 50%} is usually not thinking about argument slots at all.</p>
     */
    public static Problem check(String source, String translated) {
        if (translated == null || translated.isBlank()) {
            return null;
        }
        if (TOKEN.matcher(translated).replaceAll("").indexOf('%') >= 0) {
            return new Problem(BARE_PERCENT, "");
        }
        Set<Integer> wanted = slots(source);
        Set<Integer> got = slots(translated);
        String missing = render(difference(wanted, got));
        if (!missing.isEmpty()) {
            return new Problem(MISSING_VARS, missing);
        }
        String extra = render(difference(got, wanted));
        if (!extra.isEmpty()) {
            return new Problem(EXTRA_VARS, extra);
        }
        return null;
    }

    /**
     * The argument slots {@code text} fills, as Java will number them.
     *
     * <p>Slots rather than raw tokens on purpose: {@code "%s %s"} and {@code "%2$s %1$s"} both fill
     * slots 1 and 2, and a translator reordering arguments to suit their grammar is doing exactly
     * what the positional forms exist for. What must not change is WHICH arguments are used.</p>
     */
    private static Set<Integer> slots(String text) {
        Set<Integer> out = new TreeSet<>();
        for (TranslationVariable variable : TranslationVariableScanner.scan("", text, null)) {
            out.add(variable.slot());
        }
        return out;
    }

    private static List<Integer> difference(Set<Integer> from, Set<Integer> without) {
        return from.stream().filter(slot -> !without.contains(slot)).toList();
    }

    /** Slots named the way a translator sees them in the source: {@code %1$s, %2$s}. */
    private static String render(List<Integer> slots) {
        return slots.stream().map(slot -> "%" + slot + "$s").collect(Collectors.joining(", "));
    }

    /** Convenience for callers holding a raw edit-box value rather than a stored one. */
    public static Problem checkTyped(String source, String typed) {
        return check(source, TranslationEdits.normalizeValue(typed));
    }
}
