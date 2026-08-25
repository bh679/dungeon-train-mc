package games.brennan.dungeontrain.client.localization.edit;

import java.util.List;

/**
 * One format placeholder found in a translation string, with what the game will actually put there.
 *
 * <p>A translator reading {@code "%1$s held it; the longest reading ran %2$s."} has no way to know
 * whether {@code %1$s} is a player, a mob or a duration — and getting that wrong is how a
 * placeholder ends up in a grammatical slot the real value cannot fill. This record is what the
 * editor hovers: the token as written, the slot it fills, and the curated label and examples.</p>
 *
 * @param slot     the 1-based argument position — explicit in {@code %2$s}, implicit (and counted)
 *                 for a bare {@code %s}
 * @param start    index of the first character of the token in the source string
 * @param end      index one past the last character of the token
 * @param token    the token exactly as it appears ({@code %s}, {@code %2$s})
 * @param label    what the value is, in English ("a player name"), or {@code ""} when nothing is
 *                 curated for this slot — the screen shows a localized generic label instead
 * @param examples a few real values, or empty when none are recorded for this slot
 */
public record TranslationVariable(int slot, int start, int end, String token, String label,
                                  List<String> examples) {

    public TranslationVariable {
        label = label == null ? "" : label;
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    /** Whether a curated label exists; a slot with none falls back to the generic wording. */
    public boolean hasLabel() {
        return !label.isBlank();
    }

    /** Whether any curated example values exist — the tooltip says so outright when they do not. */
    public boolean hasExamples() {
        return !examples.isEmpty();
    }
}
