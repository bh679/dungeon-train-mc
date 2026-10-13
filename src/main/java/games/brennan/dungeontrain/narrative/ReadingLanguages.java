package games.brennan.dungeontrain.narrative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A player's extra reading languages for community books, each with a weight — the languages they read
 * besides their game language (which is always read, and always first).
 *
 * <p>Stored client-side as {@code "<locale>:<weight>"} strings ({@code "uk_ua:2"}), synced to the server
 * as {@link Entry} records, and turned into the selector's family → weight map by {@link #families}.
 * Every path goes through {@link #clean}, so a hand-edited config or a hostile packet can only ever
 * produce at most {@link #MAX_EXTRA} entries, one per family, with weights in
 * [{@link #MIN_WEIGHT}, {@link #MAX_WEIGHT}].</p>
 */
public final class ReadingLanguages {

    /** Most extra languages a player can add. */
    public static final int MAX_EXTRA = 8;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 5;
    /** Weight a newly added language (and the game language) starts at. */
    public static final int DEFAULT_WEIGHT = 1;
    /** Longest locale code kept — matches the relay's {@code cleanLang}. */
    static final int MAX_LOCALE_LENGTH = 16;
    /** Longest locale string accepted off the wire — generous, {@link #cleanLocale} trims it after. */
    public static final int MAX_LOCALE_LENGTH_WIRE = 64;

    /** One extra reading language: a Minecraft locale code and its weight. */
    public record Entry(String locale, int weight) {
        public String family() {
            return LanguageFamily.of(locale);
        }

        /** The config form, {@code "<locale>:<weight>"}. */
        public String encode() {
            return locale + ":" + weight;
        }
    }

    private ReadingLanguages() {}

    /** Clamp a weight into range. */
    public static int clampWeight(int weight) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
    }

    /** Lower-case, {@code [a-z0-9_]} only, at most {@link #MAX_LOCALE_LENGTH}; {@code null} if nothing is left. */
    public static String cleanLocale(String locale) {
        if (locale == null) return null;
        String out = locale.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (out.isEmpty()) return null;
        return out.length() > MAX_LOCALE_LENGTH ? out.substring(0, MAX_LOCALE_LENGTH) : out;
    }

    /** Parse one config string; {@code null} when malformed. A missing weight means {@link #DEFAULT_WEIGHT}. */
    public static Entry parse(String raw) {
        if (raw == null) return null;
        int colon = raw.indexOf(':');
        String locale = cleanLocale(colon < 0 ? raw : raw.substring(0, colon));
        if (locale == null) return null;
        int weight = DEFAULT_WEIGHT;
        if (colon >= 0) {
            try {
                weight = Integer.parseInt(raw.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Entry(locale, clampWeight(weight));
    }

    /** Parse every config string, then {@link #clean} against {@code gameLocale}. */
    public static List<Entry> parseAll(List<? extends String> raw, String gameLocale) {
        List<Entry> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                Entry e = parse(s);
                if (e != null) out.add(e);
            }
        }
        return clean(out, gameLocale);
    }

    /**
     * Keep the first entry per family, drop the game language's family, clean locales, clamp weights,
     * and stop at {@link #MAX_EXTRA}. Immutable result, order kept.
     */
    public static List<Entry> clean(List<Entry> entries, String gameLocale) {
        if (entries == null) return List.of();
        Set<String> seen = new HashSet<>();
        seen.add(LanguageFamily.of(gameLocale));
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e == null || out.size() >= MAX_EXTRA) continue;
            String locale = cleanLocale(e.locale());
            if (locale == null || !seen.add(LanguageFamily.of(locale))) continue;
            out.add(new Entry(locale, clampWeight(e.weight())));
        }
        return Collections.unmodifiableList(out);
    }

    /** Family → weight, game language's family first, for {@link SharedBookSelector.PlayerContext}. */
    public static Map<String, Integer> families(String gameLocale, int gameWeight, List<Entry> extras) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(LanguageFamily.of(gameLocale), clampWeight(gameWeight));
        for (Entry e : clean(extras, gameLocale)) out.put(e.family(), e.weight());
        return Collections.unmodifiableMap(out);
    }

    /**
     * The locale to file a newly signed book under: {@code chosen} when it is the game language or one of
     * {@code extras} (matched by family; the stored entry's locale wins), else the game locale.
     */
    public static String bookLocale(String chosen, String gameLocale, List<Entry> extras) {
        String clean = cleanLocale(chosen);
        if (clean == null) return gameLocale;
        String family = LanguageFamily.of(clean);
        if (family.equals(LanguageFamily.of(gameLocale))) return gameLocale;
        for (Entry e : clean(extras, gameLocale)) {
            if (e.family().equals(family)) return e.locale();
        }
        return gameLocale;
    }
}
