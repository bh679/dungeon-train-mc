package games.brennan.dungeontrain.client.localization.edit;

/**
 * One editable string in the translation editor — a lang key, or one prose field of a narrative
 * book. The two live side by side in the same list, and in the same submission, which is what
 * this type exists to make possible.
 *
 * @param type      which body this came from
 * @param namespace the mod that declared it ({@code dungeontrain}, {@code adventureitemnames},
 *                  {@code playermob}, {@code discordpresence}); always {@code dungeontrain} for
 *                  books. Needed for export and provenance, never for the runtime lookup — see
 *                  {@link TranslationEdits}
 * @param id        the override key: the bare lang key, or {@code <book path>#<field>}
 * @param source    the English text, or {@code ""} when there is none to show. The sibling mods'
 *                  English lives in their own repos, so their units have no source — the same
 *                  gap {@code build-review-package.py} documents
 * @param shipped   what the jar/resource packs translate this to, before any override
 * @param aiUnreviewed whether provenance says this is machine translation nobody has reviewed
 */
public record TranslationUnit(Type type, String namespace, String id, String source,
                              String shipped, boolean aiUnreviewed) {

    public enum Type {
        /** A flat lang key, applied client-side and instantly. */
        LANG,
        /**
         * A narrative book field. Applied on the integrated server only — on a remote server the
         * prose comes from that server, so an edit here is submit-only until it is approved.
         */
        BOOK
    }

    /** The book path for a {@link Type#BOOK} unit (e.g. {@code random_books/deathnote}). */
    public String bookPath() {
        int hash = id.indexOf('#');
        return hash < 0 ? id : id.substring(0, hash);
    }

    /** The field within the book (e.g. {@code title}, {@code variants.0}); {@code ""} if none. */
    public String bookField() {
        int hash = id.indexOf('#');
        return hash < 0 ? "" : id.substring(hash + 1);
    }

    /** What the list shows in its left column — the key, or {@code book › field} for books. */
    public String label() {
        return type == Type.BOOK ? bookPath() + " › " + bookField() : id;
    }

    /** True when {@code needle} (already lowercased) appears in anything the player can see. */
    public boolean matches(String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(id, needle) || contains(source, needle) || contains(shipped, needle);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(java.util.Locale.ROOT).contains(lowerNeedle);
    }
}
