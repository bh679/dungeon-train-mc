package games.brennan.dungeontrain.client.localization.edit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an example value in the locale being edited, and in English, so the tooltip can show both.
 *
 * <p>Some placeholders are filled with another translated string rather than with data: "The room
 * has taken %s so far" takes {@code …deaths.count.travellers.other}, which zh_cn renders as
 * "3 名旅人". A Chinese translator shown "3 travellers" is being told about a value that will never
 * reach their screen — so a keyed example is resolved here against the locale, with the English
 * kept alongside it as the thing the label describes.</p>
 *
 * <p>Built once per screen open: assembling it reads the four namespaces' lang files twice (the
 * locale and English), which is the same work {@link TranslationCatalog} does and far too much to
 * repeat per tooltip.</p>
 *
 * <h2>Known limitation: vanilla-owned values stay English</h2>
 * Only this repo's namespaces ({@link TranslationCatalog#NAMESPACES}) are read, so a handful of
 * curated examples that stand for VANILLA-translated values are written as English literals in
 * {@code translation_examples.json} and shown as such — game modes ({@code gameMode.*}),
 * difficulties ({@code options.difficulty.*}), {@code options.on}/{@code options.off}, and
 * {@code container.enderchest} — as is {@code @Dev} ({@code discordpresence.chattag.dev}), whose
 * English lives in the sibling mod's own repo and so cannot be checked by the coverage guard.
 *
 * <p>Deliberate, not an oversight: they are short UI words rather than grammatical clauses, so a
 * translator is not going to mis-fit one the way they might mis-fit "3 travellers". Closing it
 * would mean adding {@code minecraft} to the namespaces read here and relaxing the guard for keys
 * whose English this repo does not ship. The data file is strict JSON and cannot carry a comment,
 * which is why the note lives here.</p>
 */
public final class TranslationExampleValues {

    /**
     * One example, both ways round.
     *
     * @param localized what the locale being edited renders it as; equal to {@code english} when the
     *                  locale has no translation for the key, or when the example is a literal
     * @param english   the English rendering — the one the label was written about
     */
    public record Rendered(String localized, String english) {
        /** Whether the two differ, i.e. whether showing both actually tells the translator anything. */
        public boolean differs() {
            return !localized.equals(english);
        }
    }

    /** The locale's strings, merged across namespaces; the editor's own overrides win over these. */
    private final Map<String, String> localized;
    private final Map<String, String> english;

    TranslationExampleValues(Map<String, String> localized, Map<String, String> english) {
        this.localized = localized;
        this.english = english;
    }

    /**
     * Read the lang files for {@code locale}, with this player's unsent edits layered on top — an
     * example of a clause they have just rewritten should show what they wrote.
     */
    public static TranslationExampleValues forLocale(String locale) {
        Map<String, String> localized = new HashMap<>();
        Map<String, String> english = new HashMap<>();
        for (String namespace : TranslationCatalog.NAMESPACES) {
            english.putAll(TranslationCatalog.readLang(namespace, "en_us"));
            if (locale != null && !locale.isBlank()) {
                localized.putAll(TranslationCatalog.readLang(namespace, locale));
            }
        }
        if (locale != null && !locale.isBlank()) {
            localized.putAll(TranslationOverrides.mergedFor(locale).lang());
        }
        return new TranslationExampleValues(localized, english);
    }

    /** Both renderings of one example. A literal is itself in either language. */
    public Rendered render(TranslationVariableExamples.Example example) {
        if (example == null) {
            return new Rendered("", "");
        }
        if (!example.isKeyed()) {
            return new Rendered(example.text(), example.text());
        }
        // The key itself is the last resort: a curated example naming a key nothing ships is a bug
        // the coverage guard catches, but it must not render as a blank line if one slips through.
        String englishForm = format(english.getOrDefault(example.key(), example.key()),
            example.args());
        String localizedForm = localized.containsKey(example.key())
            ? format(localized.get(example.key()), example.args()) : englishForm;
        return new Rendered(localizedForm, englishForm);
    }

    /** Substitute the example's arguments; a string the args don't fit is shown as it is. */
    private static String format(String pattern, List<String> args) {
        if (args.isEmpty()) {
            return pattern;
        }
        try {
            return String.format(pattern, args.toArray());
        } catch (RuntimeException e) {
            return pattern;
        }
    }
}
