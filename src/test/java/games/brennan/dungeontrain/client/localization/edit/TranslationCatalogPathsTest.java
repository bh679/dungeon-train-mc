package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mapping between a book's English original and the locale-relative path the overlay names it
 * by — the two directions the catalog now walks.
 *
 * <p>It walks both because the catalog is driven by the ENGLISH books: a book or a field the locale
 * has never touched has no file and no entry, and under the old locale-driven collection was simply
 * absent from the editor. Deriving the locale's path from English is what makes an untranslated
 * book appear, blank, instead of not appearing at all — so the round trip below is the property the
 * whole fix rests on.</p>
 */
class TranslationCatalogPathsTest {

    @Test
    @DisplayName("ordinary narrative books round-trip between English path and book path")
    void narrativeRoundTrip() {
        assertEquals("stories/augustus_park",
            TranslationCatalog.bookPathFor("data/dungeontrain/narratives/stories/augustus_park.json"));
        assertEquals("data/dungeontrain/narratives/stories/augustus_park.json",
            TranslationCatalog.englishPathFor("stories/augustus_park"));
    }

    @Test
    @DisplayName("death lore keeps its own top-level English directory, as the overlay expects")
    void deathLoreRoundTrip() {
        // The one asymmetry in the mapping: the locale calls it death_lore/x, but English keeps it
        // outside narratives/ entirely. Inverting that wrongly would silently lose every lore file.
        assertEquals("death_lore/drowned",
            TranslationCatalog.bookPathFor("data/dungeontrain/death_lore/drowned.json"));
        assertEquals("data/dungeontrain/death_lore/drowned.json",
            TranslationCatalog.englishPathFor("death_lore/drowned"));
    }

    @Test
    @DisplayName("every English path this maps comes back to the same path it started from")
    void roundTripIsStable() {
        for (String bookPath : new String[] {
            "stories/augustus_park", "random_books/a", "death_lore/drowned", "death_lore/fall/high"
        }) {
            assertEquals(bookPath,
                TranslationCatalog.bookPathFor(TranslationCatalog.englishPathFor(bookPath)),
                bookPath);
        }
    }

    @Test
    @DisplayName("a path under neither English root is left alone rather than mangled")
    void unknownRootIsUntouched() {
        assertEquals("data/dungeontrain/somewhere_else/x",
            TranslationCatalog.bookPathFor("data/dungeontrain/somewhere_else/x.json"));
    }
}
