package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "View source" link's target. A search URL is easy to get subtly wrong — an unquoted needle
 * matches word-by-word, a bare {@code +} reads as a space — and a translator who lands on the wrong
 * results has no way to tell that from the string genuinely not being there.
 */
class TranslationSourceLinkTest {

    private static TranslationUnit lang(String namespace, String key) {
        return new TranslationUnit(TranslationUnit.Type.LANG, namespace, key, "English", "", false);
    }

    private static TranslationUnit book(String id) {
        return new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", id, "English", "",
            false);
    }

    @Test
    @DisplayName("a lang key is searched exactly, scoped to this repo")
    void langKeyUrl() {
        String url = TranslationSourceLink.urlFor(lang("dungeontrain", "echo.dungeontrain.mob_name"));
        assertEquals("https://github.com/search?q=repo%3Abh679%2Fdungeon-train-mc"
            + "%20%22echo.dungeontrain.mob_name%22&type=code", url);
        // Quoted, so the search is the whole key rather than its dot-separated words.
        assertTrue(url.contains("%22echo.dungeontrain.mob_name%22"));
    }

    @Test
    @DisplayName("spaces are percent-encoded, never left as a bare plus")
    void spacesAreEncoded() {
        String url = TranslationSourceLink.urlFor(lang("dungeontrain", "a.key"));
        assertFalse(url.contains("+"), "a bare + would be read as a literal plus by GitHub");
        assertTrue(url.contains("%20"));
    }

    @Test
    @DisplayName("a book field is searched by its book path, not by book#field")
    void bookUrlUsesThePath() {
        // The field suffix exists only in the editor's own id — no file on disk carries it.
        String url = TranslationSourceLink.urlFor(book("stories/augustus_park#variants.0"));
        assertTrue(url.contains("%22stories%2Faugustus_park%22"), url);
        assertFalse(url.contains("variants.0"), url);
    }

    @Test
    @DisplayName("sibling mods have no link — their strings live in their own repositories")
    void siblingNamespacesAreUnavailable() {
        assertTrue(TranslationSourceLink.available(lang("dungeontrain", "a.key")));
        assertFalse(TranslationSourceLink.available(lang("adventureitemnames", "a.key")));
        assertFalse(TranslationSourceLink.available(lang("playermob", "a.key")));
        assertFalse(TranslationSourceLink.available(null));
    }
}
