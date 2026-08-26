package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which strings the editor treats as variations of the same thing, and how it walks a set.
 * Pure — no Minecraft bootstrap, which is why the rule lives outside the screens that use it.
 */
class TranslationGroupsTest {

    private static final TranslationEdits NOTHING_APPROVED = TranslationEdits.empty("de_de");

    private static TranslationUnit lang(String key, boolean aiUnreviewed) {
        return new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain", key,
            "Depart", "Abfahren", aiUnreviewed);
    }

    private static TranslationUnit book(String id, boolean aiUnreviewed) {
        return new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", id,
            "The Lost Conductor", "Der verlorene Schaffner", aiUnreviewed);
    }

    /** The type/namespace scope is an implementation detail; these tests assert the path shape. */
    private static String path(TranslationUnit unit) {
        String key = TranslationGroups.groupKeyOf(unit);
        return key.isEmpty() ? "" : key.substring(key.lastIndexOf('|') + 1);
    }

    private static List<String> ids(List<TranslationUnit> units) {
        return units.stream().map(TranslationUnit::id).toList();
    }

    // ---- the rule -------------------------------------------------------------------------------

    @Test
    @DisplayName("a trailing index is what varies")
    void trailingIndex() {
        assertEquals("chat.dungeontrain.familiar_book.#",
            path(lang("chat.dungeontrain.familiar_book.7", true)));
    }

    @Test
    @DisplayName("a key with no numeric segment belongs to no set")
    void noIndexNoGroup() {
        assertEquals("", TranslationGroups.groupKeyOf(lang("gui.dungeontrain.options.title", true)));
    }

    @Test
    @DisplayName("the LAST index is the varying one — variants OF letter 2, not one of the letters")
    void lastIndexWins() {
        assertEquals("stories/x#letters.2.variants.#",
            path(book("stories/x#letters.2.variants.1", true)));
    }

    @Test
    @DisplayName("death lore varies by a LEADING index, and groups by the field it repeats")
    void leadingIndex() {
        assertEquals("death_lore/default##.narration",
            path(book("death_lore/default#0.narration", true)));
        assertEquals(TranslationGroups.groupKeyOf(book("death_lore/default#0.narration", true)),
            TranslationGroups.groupKeyOf(book("death_lore/default#38.narration", true)));
        assertNotEquals(TranslationGroups.groupKeyOf(book("death_lore/default#0.narration", true)),
            TranslationGroups.groupKeyOf(book("death_lore/default#0.question", true)));
    }

    @Test
    @DisplayName("book variants group per book, never across books")
    void bookScoped() {
        assertNotEquals(TranslationGroups.groupKeyOf(book("random_books/a#variants.0", true)),
            TranslationGroups.groupKeyOf(book("random_books/b#variants.0", true)));
    }

    @Test
    @DisplayName("a lang key and a book field that flatten alike are still different sets")
    void typeScoped() {
        assertNotEquals(TranslationGroups.groupKeyOf(lang("variants.0", true)),
            TranslationGroups.groupKeyOf(book("variants.0", true)));
    }

    // ---- membership -----------------------------------------------------------------------------

    @Test
    @DisplayName("one string carrying a group key is not a set of anything")
    void loneMemberIsNoSet() {
        List<TranslationUnit> all = List.of(lang("a.1", true), lang("b.title", true));
        assertTrue(TranslationGroups.index(all).isEmpty());
        assertTrue(TranslationGroups.membersOf(TranslationGroups.index(all), all.get(0)).isEmpty());
    }

    @Test
    @DisplayName("members come back in catalog order")
    void membersKeepOrder() {
        List<TranslationUnit> all =
            List.of(lang("a.1", true), lang("other", true), lang("a.2", true), lang("a.3", true));
        List<TranslationUnit> members =
            TranslationGroups.membersOf(TranslationGroups.index(all), all.get(0));
        assertEquals(List.of("a.1", "a.2", "a.3"), ids(members));
        assertEquals(0, TranslationGroups.indexIn(members, all.get(0)));
        assertEquals(2, TranslationGroups.indexIn(members, all.get(3)));
    }

    // ---- walking the set ------------------------------------------------------------------------

    @Test
    @DisplayName("Next skips what has been reviewed and wraps past the end")
    void nextWraps() {
        TranslationUnit one = lang("a.1", true);
        TranslationUnit two = lang("a.2", false);   // human-translated: never in the queue
        TranslationUnit three = lang("a.3", true);
        List<TranslationUnit> members = List.of(one, two, three);

        assertSame(three, TranslationGroups.nextNeedingReview(members, one, NOTHING_APPROVED, null));
        // From the last member it comes back round rather than going dead with work above it.
        assertSame(one, TranslationGroups.nextNeedingReview(members, three, NOTHING_APPROVED, null));
    }

    @Test
    @DisplayName("Next is null when this is the only member left needing a human")
    void nextExhausted() {
        TranslationUnit one = lang("a.1", true);
        List<TranslationUnit> members = List.of(one, lang("a.2", false));
        assertNull(TranslationGroups.nextNeedingReview(members, one, NOTHING_APPROVED, null));
    }

    @Test
    @DisplayName("an approved member has had its review and is not offered again")
    void approvedIsSkipped() {
        TranslationUnit one = lang("a.1", true);
        TranslationUnit two = lang("a.2", true);
        TranslationEdits approved = new TranslationEdits("de_de", Map.of("a.2", "Fertig"), Map.of());
        assertNull(TranslationGroups.nextNeedingReview(List.of(one, two), one, approved, null));
        assertEquals(1, TranslationGroups.needingReview(List.of(one, two), approved, null));
    }

    @Test
    @DisplayName("a member marked good as is leaves the set's count and the Next chain")
    void dismissedIsSkipped() {
        TranslationUnit one = lang("a.1", true);
        TranslationUnit two = lang("a.2", true);
        Set<String> dismissed = Set.of("a.2");
        assertNull(TranslationGroups.nextNeedingReview(List.of(one, two), one, NOTHING_APPROVED,
            u -> dismissed.contains(u.id())));
        assertEquals(1, TranslationGroups.needingReview(List.of(one, two), NOTHING_APPROVED,
            u -> dismissed.contains(u.id())));
    }

    // ---- collapsing -----------------------------------------------------------------------------

    @Test
    @DisplayName("each set becomes one row; ungrouped strings pass through in place")
    void collapseKeepsOrder() {
        List<TranslationUnit> units = List.of(
            lang("gui.first", true), lang("a.1", true), lang("a.2", true), lang("gui.last", true));
        assertEquals(List.of("gui.first", "a.1", "gui.last"),
            ids(TranslationGroups.collapse(units, TranslationGroups.index(units),
                NOTHING_APPROVED, null)));
    }

    @Test
    @DisplayName("the row that opens is the first member still needing a human, not the lowest index")
    void representativeIsWorkToDo() {
        List<TranslationUnit> units = List.of(lang("a.1", false), lang("a.2", true));
        assertEquals(List.of("a.2"), ids(TranslationGroups.collapse(units,
            TranslationGroups.index(units), NOTHING_APPROVED, null)));
    }

    @Test
    @DisplayName("a set nobody needs to review still collapses, onto its first member")
    void representativeFallsBackToFirst() {
        List<TranslationUnit> units = List.of(lang("a.1", false), lang("a.2", false));
        assertEquals(List.of("a.1"), ids(TranslationGroups.collapse(units,
            TranslationGroups.index(units), NOTHING_APPROVED, null)));
    }

    @Test
    @DisplayName("the set is the catalog's, but the row standing for it is one the filters left")
    void collapseRepresentsWithAVisibleRow() {
        List<TranslationUnit> catalog =
            List.of(lang("a.1", true), lang("a.2", true), lang("a.3", true));
        Map<String, List<TranslationUnit>> index = TranslationGroups.index(catalog);
        // A search matched only the third variation. The row is that one — it is the only one
        // there is to draw — while the set it stands for is still all three.
        List<TranslationUnit> visible = List.of(catalog.get(2));
        assertEquals(List.of("a.3"),
            ids(TranslationGroups.collapse(visible, index, NOTHING_APPROVED, null)));
        assertEquals(3, TranslationGroups.membersOf(index, catalog.get(2)).size());
    }
}
