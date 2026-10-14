package games.brennan.dungeontrain.narrative;

import java.util.function.Predicate;

/**
 * The prose half of the client's "Human translations only" option, as the integrated server sees it:
 * which localized books to skip in favour of their English base, and whether AIN item names should
 * stay English.
 *
 * <p>Prose is world-global server data that follows the host's language, so a per-client choice can
 * only reach it where the host IS this client — single-player and a LAN host, where the integrated
 * server shares the JVM. The client writes the snapshot ({@code HumanOnlyTranslations.publishProse});
 * a dedicated server never has one written, so {@link #skipsBook} is always false there and its prose
 * is untouched.</p>
 *
 * <p>Keyed by locale: a snapshot only applies while the narrative content locale is the one it was
 * published for, so a stale one can never strip prose from a different language.</p>
 */
public final class HumanOnlyProse {

    private record Snapshot(String locale, Predicate<String> flaggedBook, boolean englishItemNames) {}

    private static volatile Snapshot snapshot;

    private HumanOnlyProse() {}

    /**
     * @param locale           the client locale this applies to
     * @param flaggedBook      whether a book path (e.g. {@code random_books/deathnote}) is AI-unreviewed
     * @param englishItemNames whether the locale's AIN item names are all AI-unreviewed
     */
    public static void set(String locale, Predicate<String> flaggedBook, boolean englishItemNames) {
        snapshot = new Snapshot(locale, flaggedBook, englishItemNames);
    }

    public static void clear() {
        snapshot = null;
    }

    /** Whether the {@code locale} variant of the book at {@code bookPath} should be skipped. */
    public static boolean skipsBook(String locale, String bookPath) {
        Snapshot s = snapshot;
        return s != null && s.locale().equals(locale) && s.flaggedBook().test(bookPath);
    }

    /** The locale AIN item names should be overlaid in — {@code ""} (English) when they are all AI. */
    public static String itemNameLocale(String locale) {
        Snapshot s = snapshot;
        return s != null && s.englishItemNames() && s.locale().equals(locale) ? "" : locale;
    }
}
