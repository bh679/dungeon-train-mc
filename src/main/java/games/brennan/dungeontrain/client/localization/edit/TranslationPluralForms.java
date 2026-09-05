package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import games.brennan.dungeontrain.narrative.PluralRules;

/**
 * Rewrites the English key set into the plural forms a given language can actually use.
 *
 * <p>A count-dependent string is not one key but a family — {@code <base>.one},
 * {@code <base>.other} in English, {@code .one}/{@code .few}/{@code .many} in Russian,
 * {@code .other} alone in Japanese. The editor's work list was the raw union of the English keys
 * and the locale's own, so it offered every translator English's family regardless of their
 * grammar. Russian players were shown 27 {@code .other} rows their language can never select,
 * translated them, and had them approved on the relay — where they will sit forever, because
 * writing them into {@code ru_ru.json} would add a key {@code validate-locale.py} rejects and
 * the game can never read. They were also shown {@code .few} and {@code .many} with no source
 * text, since English has no such key to show them.</p>
 *
 * <p>So the English set is projected through {@link PluralRules#categoriesOf} — the same table
 * the game itself selects with, and the same one {@code scripts/localization/plural_forms.py}
 * validates against — before the union with what the locale really ships. The union comes after
 * on purpose: a key the file genuinely carries stays editable whether or not the projection
 * would have asked for it.</p>
 */
public final class TranslationPluralForms {

    private TranslationPluralForms() {
    }

    /** Every suffix that can name a plural form, whether or not any language here selects it. */
    private static final Set<String> SUFFIXES =
            Set.of(PluralRules.ONE, "two", PluralRules.FEW, PluralRules.MANY, PluralRules.OTHER);

    /**
     * The keys of {@code english} as {@code locale} should see them, in English's own order.
     *
     * <p>Order is preserved because it is the order the provenance sidecars and the review CSVs
     * use; a family's forms appear where its {@code .one} did.</p>
     */
    public static Set<String> project(Set<String> english, String locale) {
        Set<String> bases = familyBases(english);
        if (bases.isEmpty()) {
            return new LinkedHashSet<>(english);
        }
        List<String> categories = PluralRules.categoriesOf(locale);
        Set<String> out = new LinkedHashSet<>();
        for (String key : english) {
            String base = baseOf(key);
            if (base != null && bases.contains(base)) {
                for (String category : categories) {
                    out.add(base + "." + category);
                }
            } else {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * Bases that form a plural family — one carrying BOTH {@code .one} and {@code .other}.
     *
     * <p>Requiring both is what stops an ordinary key that merely ends in {@code .one} (there is
     * a {@code gui.dungeontrain…pick.one} that is just a label) from being mistaken for a family
     * and rewritten out of the list.</p>
     */
    private static Set<String> familyBases(Set<String> english) {
        Set<String> bases = new LinkedHashSet<>();
        for (String key : english) {
            if (key.endsWith("." + PluralRules.ONE)) {
                String base = key.substring(0, key.length() - PluralRules.ONE.length() - 1);
                if (english.contains(base + "." + PluralRules.OTHER)) {
                    bases.add(base);
                }
            }
        }
        return bases;
    }

    /** The part before a plural suffix, or null when the key does not end in one. */
    private static String baseOf(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0 || !SUFFIXES.contains(key.substring(dot + 1))) {
            return null;
        }
        return key.substring(0, dot);
    }
}
