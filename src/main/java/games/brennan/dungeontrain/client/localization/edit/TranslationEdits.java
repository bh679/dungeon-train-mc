package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One locale's translation overrides — an immutable snapshot of everything a player (or the
 * relay) has replaced in that language.
 *
 * <p>Two bodies, because they apply through completely different runtime paths:</p>
 * <ul>
 *   <li>{@code lang} — flat lang keys, applied client-side by {@link OverlayLanguage}. Keyed by
 *       the bare key with no namespace: Minecraft merges every {@code assets/*&#47;lang/} file
 *       into ONE flat map, so at runtime {@code gui.dungeontrain.x} is unique regardless of
 *       which mod's lang file declared it. The namespace only matters to export and provenance,
 *       and is recovered there from the catalog.</li>
 *   <li>{@code books} — narrative book fields, keyed {@code <book path>#<field>} (e.g.
 *       {@code random_books/deathnote#variants.0}), applied server-side on the integrated
 *       server.</li>
 * </ul>
 *
 * <p>Every mutator returns a new instance rather than editing in place, so a snapshot handed to
 * the overlay can never change underneath it.</p>
 */
public record TranslationEdits(String locale, Map<String, String> lang, Map<String, String> books) {

    /**
     * Vanilla rewrites {@code %d}/{@code %f} format specifiers to {@code %s} when it loads a lang
     * file ({@code Language.UNSUPPORTED_FORMAT_PATTERN}) because {@code I18n.get} passes every
     * argument as an object. A player typing {@code %d} into the editor would otherwise throw
     * inside {@code String.format} on every render of that string, so overrides get the same
     * treatment on the way in — what we store is what vanilla would have stored.
     */
    private static final Pattern UNSUPPORTED_FORMAT = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");

    /**
     * Per-value cap. The longest shipped English string is ~314 chars and the longest book variant
     * runs to a few thousand, so this is generous headroom — it exists to stop a corrupt or hostile
     * file from being loaded wholesale into memory, not to constrain translators.
     */
    public static final int MAX_VALUE_CHARS = 8_000;

    /** Per-body cap, comfortably above the ~1100 lang keys and ~50 books a locale actually has. */
    public static final int MAX_ENTRIES = 5_000;

    public TranslationEdits {
        lang = Map.copyOf(lang);
        books = Map.copyOf(books);
    }

    public static TranslationEdits empty(String locale) {
        return new TranslationEdits(locale, Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return lang.isEmpty() && books.isEmpty();
    }

    public int size() {
        return lang.size() + books.size();
    }

    /** This snapshot plus {@code key -> value} in the lang body (or minus it, when null/blank). */
    public TranslationEdits withLang(String key, String value) {
        return new TranslationEdits(locale, put(lang, key, value), books);
    }

    /** This snapshot plus {@code id -> value} in the book body (or minus it, when null/blank). */
    public TranslationEdits withBook(String id, String value) {
        return new TranslationEdits(locale, lang, put(books, id, value));
    }

    /**
     * {@code other} layered on top of this one — the caller's own edits win over an inherited
     * layer. This is how local edits beat relay-approved translations, which beat the jar.
     */
    public TranslationEdits mergedWith(TranslationEdits other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        return new TranslationEdits(locale, merge(lang, other.lang), merge(books, other.books));
    }

    private static Map<String, String> merge(Map<String, String> base, Map<String, String> top) {
        Map<String, String> out = new LinkedHashMap<>(base);
        out.putAll(top);
        return out;
    }

    /**
     * A copy of {@code source} with {@code key} set, or removed when {@code value} is null or
     * blank — clearing an override is how a player reverts to the shipped translation, so it has
     * to delete the entry rather than store an empty string that would render as nothing.
     */
    private static Map<String, String> put(Map<String, String> source, String key, String value) {
        if (key == null || key.isEmpty()) {
            return source;
        }
        Map<String, String> out = new LinkedHashMap<>(source);
        String normalized = normalizeValue(value);
        if (normalized == null) {
            out.remove(key);
        } else if (out.size() < MAX_ENTRIES || out.containsKey(key)) {
            out.put(key, normalized);
        }
        return out;
    }

    /**
     * A value as it should be stored, or {@code null} when it means "no override": blank input,
     * or text past {@link #MAX_VALUE_CHARS}. Applies vanilla's format-specifier rewrite.
     */
    public static String normalizeValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_CHARS) {
            return null;
        }
        return UNSUPPORTED_FORMAT.matcher(value).replaceAll("%$1s");
    }
}
