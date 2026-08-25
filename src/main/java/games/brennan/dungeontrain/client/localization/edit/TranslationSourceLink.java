package games.brennan.dungeontrain.client.localization.edit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where a translation unit lives in the repository, as a URL a translator can open.
 *
 * <p>The editor says what a variable holds; this says where the string is used. When a line is
 * ambiguous — is "Echo of %s" a mob or a player? — the answer is in the code, and until now there
 * was no route to it from inside the game.</p>
 *
 * <p>A repo-scoped <b>code search</b> rather than a deep link to a line: most of these keys are
 * built at the call site from a prefix and an index, so a search finds both the {@code en_us.json}
 * entry and every usage, where a file link could only ever show the former.</p>
 *
 * <p><b>Caveat:</b> GitHub code search requires a signed-in account — a logged-out translator gets
 * a login wall rather than results. If that turns out to matter more than seeing the call sites,
 * swapping {@link #urlFor} for a {@code blob/main/…/en_us.json#L<n>} deep link (which works
 * anonymously) is a change to this class alone.</p>
 */
public final class TranslationSourceLink {

    /** The repository the editor's own strings live in. */
    private static final String REPO = "bh679/dungeon-train-mc";

    private TranslationSourceLink() {}

    /**
     * Whether this unit can be pointed at.
     *
     * <p>Only Dungeon Train's own strings: the sibling mods ship theirs from their own repos, so a
     * search of this one would come back empty. A row with no link shows no button at all, which
     * is honest — better than a button that lands on "0 results".</p>
     */
    public static boolean available(TranslationUnit unit) {
        return unit != null && "dungeontrain".equals(unit.namespace());
    }

    /**
     * The search URL for {@code unit} — the lang key, or the book's path for a book field, quoted
     * so the search is exact rather than word-by-word.
     */
    public static String urlFor(TranslationUnit unit) {
        String needle = unit.type() == TranslationUnit.Type.BOOK ? unit.bookPath() : unit.id();
        String query = "repo:" + REPO + " \"" + needle + "\"";
        return "https://github.com/search?q=" + encode(query) + "&type=code";
    }

    /** Percent-encoding, with {@code +} spelled out — GitHub reads a bare + as a space. */
    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
