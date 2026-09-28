package games.brennan.dungeontrain.client.menu;

import java.util.IllegalFormatException;
import net.minecraft.locale.Language;

/**
 * The world-space editor menus' lang lookup. Every label in {@code client/menu} goes through here,
 * under one prefix, so a test can pin that each key exists in {@code en_us.json} without a
 * constant per string — see {@code MenuLangKeysTest}.
 *
 * <p>Labels stay plain {@link String}s on purpose: the menus rebuild their rows every tick, so a
 * language switch shows up on the next frame, and the painter already shrinks a label that
 * outgrows its cell.</p>
 *
 * <p>Reads {@link Language#getInstance()} rather than {@code I18n}, which captures the language
 * once at class-load: the menu tests install the shipped {@code en_us.json} through
 * {@link Language#inject} and assert on what a player reads, the way the narrative tests do.</p>
 */
public final class MenuLang {

    public static final String PREFIX = "gui.dungeontrain.editor_menu.";

    private MenuLang() {}

    /** The translated label for {@code PREFIX + suffix}, formatted with {@code args}. */
    public static String t(String suffix, Object... args) {
        String pattern = Language.getInstance().getOrDefault(PREFIX + suffix);
        if (args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (IllegalFormatException e) {
            return "Format error: " + pattern;
        }
    }

    /**
     * The count-dependent line {@code PREFIX + base} in the plural form the client's language
     * wants for {@code n} — {@code plural("changes.count", 5)} resolves {@code …changes.count.many}
     * in Russian and {@code …changes.count.other} in English. {@code n} is passed first, then
     * {@code extra}, so a line can also name the thing being counted.
     */
    public static String plural(String base, long n, Object... extra) {
        String locale = games.brennan.dungeontrain.client.ClientLanguage.selected();
        String form = games.brennan.dungeontrain.narrative.PluralRules.category(locale, n);
        Object[] args = new Object[extra.length + 1];
        args[0] = n;
        System.arraycopy(extra, 0, args, 1, extra.length);
        return t(base + "." + form, args);
    }

    /**
     * A translated name for a data-driven id — a portal-room setting, a stage palette row — under
     * {@code PREFIX + group + "." + id}, or {@code fallback} (the code's own display name) when
     * no locale has a line for it yet.
     */
    public static String named(String group, String id, String fallback) {
        String key = PREFIX + group + "." + id;
        Language lang = Language.getInstance();
        return lang.has(key) ? lang.getOrDefault(key) : fallback;
    }

    /**
     * The display form of a type name the server pushed in English ({@code "Carriages"},
     * {@code "Dimensional Carriage"}, {@code "Pillar Top"}). The English string stays the identity
     * the roster and the type strip compare by; this is only for the moment it is drawn.
     */
    public static String typeName(String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return "";
        }
        String slug = serverName.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        return named("type_name", slug, serverName);
    }
}
