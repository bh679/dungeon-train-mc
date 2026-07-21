package games.brennan.dungeontrain.narrative;

import java.util.Locale;
import java.util.Map;

/**
 * Collapse a Minecraft client language code (e.g. {@code "en_us"}, {@code "zh_cn"}, {@code "en_pt"})
 * into a LANGUAGE FAMILY key, so all variants of one natural language are treated as the same language.
 *
 * <p><b>Deliberate mirror of the relay's {@code langfamily.js}.</b> The relay already collapses locales
 * this way when it family-scopes {@code /books/pool}; the shared-book loot selector needs the same
 * notion PER-PLAYER (is this book in MY language?), and the two must agree or a book the relay
 * considers in-family would be bucketed as foreign here. Keep the two in sync — same overrides, same
 * default.</p>
 *
 * <ul>
 *   <li>English variants — {@code en_us}, {@code en_gb}, and the joke locales that are really English
 *       (Pirate {@code en_pt}, LOLCAT {@code lol}, Shakespearean {@code enws}, Upside-down
 *       {@code en_ud}) — collapse to {@code "en"}.</li>
 *   <li>Chinese variants — {@code zh_cn}, {@code zh_tw}, plus Classical Chinese {@code lzh} — collapse
 *       to {@code "zh"}.</li>
 *   <li>Everything else falls back to its ISO subtag: {@code pt_br}→{@code pt}, {@code es_es}→{@code es}.</li>
 * </ul>
 *
 * <p>Blank / unknown / untagged ({@code null}) → {@link #DEFAULT_FAMILY}. Legacy books uploaded before
 * language tagging carry no {@code lang}; treating them as English keeps them visible to the default
 * audience without leaking unknown-language content to non-English readers.</p>
 */
public final class LanguageFamily {

    /** The family that unknown / untagged / blank locales collapse to. */
    public static final String DEFAULT_FAMILY = "en";

    /**
     * Codes whose ISO-subtag prefix does NOT already land on the right family. The {@code en_*} joke
     * codes already prefix to {@code en}, so only the non-{@code en_}-prefixed ones need listing.
     */
    private static final Map<String, String> OVERRIDES = Map.of(
        "lol", "en",    // LOLCAT (lol_us)
        "enws", "en",   // Shakespearean / Early Modern English
        "lzh", "zh"     // Classical / Literary Chinese
    );

    private LanguageFamily() {}

    /**
     * Normalise a raw locale to its family key. Never throws; a null, blank or unusable value returns
     * {@link #DEFAULT_FAMILY}.
     */
    public static String of(String locale) {
        if (locale == null) return DEFAULT_FAMILY;
        String clean = locale.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (clean.isEmpty()) return DEFAULT_FAMILY;
        int us = clean.indexOf('_');
        String prefix = us > 0 ? clean.substring(0, us) : clean;
        // Override on the full code first (e.g. lol_us), then the prefix (e.g. lol, lzh).
        String full = OVERRIDES.get(clean);
        if (full != null) return full;
        String byPrefix = OVERRIDES.get(prefix);
        if (byPrefix != null) return byPrefix;
        return prefix;
    }

    /** Whether two raw locales belong to the same family (null/blank on either side → English). */
    public static boolean sameFamily(String a, String b) {
        return of(a).equals(of(b));
    }
}
