package games.brennan.dungeontrain.locale;

import java.util.Locale;
import java.util.Set;

/**
 * Keeps a bare {@code String.toLowerCase()} / {@code toUpperCase()} from using Turkish or Azerbaijani
 * casing, where {@code I} lowercases to a dotless {@code ı}. Plenty of mods build codec and file names
 * that way: WorldWeaver's {@code ExtendXYZ.HeightPropagation} and {@code StructurePlacement} turn
 * {@code SPIKES_DOWN} into {@code spıkes_down}, BetterNether's data then fails to parse, and on a
 * Turkish PC "New Game" drops straight back to the title screen.
 *
 * <p>Only the <b>base</b> default locale is moved to {@link Locale#ROOT} — the one those casing calls read.
 * The FORMAT and DISPLAY categories keep the player's locale, so numbers and dates still read as theirs.
 * Minecraft's own translations follow the in-game language setting and never look at either.</p>
 */
public final class CaseLocaleGuard {

    /** Languages whose case rules differ from {@link Locale#ROOT} for ASCII letters. */
    private static final Set<String> DOTLESS_I_LANGUAGES = Set.of("tr", "az");

    /** What {@link #apply()} changed; {@code original} is null when it left the locale alone. */
    public record Result(Locale original) {
        public boolean changed() {
            return original != null;
        }
    }

    public static boolean needsGuard(Locale locale) {
        return locale != null && DOTLESS_I_LANGUAGES.contains(locale.getLanguage());
    }

    /** Pins the base default locale to ROOT if it would mangle ASCII casing; idempotent. */
    public static synchronized Result apply() {
        Locale original = Locale.getDefault();
        if (!needsGuard(original)) return new Result(null);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale.setDefault(Locale.ROOT);
        Locale.setDefault(Locale.Category.FORMAT, format);
        Locale.setDefault(Locale.Category.DISPLAY, display);
        return new Result(original);
    }

    private CaseLocaleGuard() {}
}
