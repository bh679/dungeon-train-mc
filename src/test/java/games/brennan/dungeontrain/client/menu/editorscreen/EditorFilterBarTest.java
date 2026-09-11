package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.BuilderProfileFilters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the collapsed filter row shows: exactly the filters in force, in row order. */
final class EditorFilterBarTest {

    private static List<EditorFilterBar.ActiveKind> kinds(List<EditorFilterBar.ActiveChip> chips) {
        return chips.stream().map(EditorFilterBar.ActiveChip::kind).toList();
    }

    @Test
    @DisplayName("the defaults show Mine and Imported; Imported only where its chip is offered")
    void defaults() {
        var d = EditorRosterIndex.Filters.DEFAULT;
        assertEquals(List.of(EditorFilterBar.ActiveKind.MINE, EditorFilterBar.ActiveKind.IMPORTED),
            kinds(EditorFilterBar.activeChips(EditorCategoryFilter.ALL, "", d, false, BuilderProfileFilters.ALL, false, true, false, "")));
        assertEquals(List.of(EditorFilterBar.ActiveKind.MINE),
            kinds(EditorFilterBar.activeChips(EditorCategoryFilter.ALL, "", d, false, BuilderProfileFilters.ALL, false, false, false, "")));
        assertEquals(List.of(),
            kinds(EditorFilterBar.activeChips(EditorCategoryFilter.ALL, "", EditorRosterIndex.Filters.NONE, false, BuilderProfileFilters.ALL, false, true, false, "")));
    }

    @Test
    @DisplayName("a category and a type lead the row; a type under All is not a filter")
    void categoryAndType() {
        var none = EditorRosterIndex.Filters.NONE;
        List<EditorFilterBar.ActiveChip> chips = EditorFilterBar.activeChips(EditorCategoryFilter.CARRIAGES, "Floor",
            none.withBuiltin(true), false, BuilderProfileFilters.ALL, false, true, false, "");
        assertEquals(List.of(EditorFilterBar.ActiveKind.CATEGORY, EditorFilterBar.ActiveKind.TYPE,
            EditorFilterBar.ActiveKind.BUILTIN), kinds(chips));
        assertEquals("Floor", chips.get(1).label());
        assertEquals(List.of(),
            kinds(EditorFilterBar.activeChips(EditorCategoryFilter.ALL, "Floor", none, false, BuilderProfileFilters.ALL, false, true, false, "")));
    }

    @Test
    @DisplayName("creator mode swaps the provenance chips for status, starred and whose builds these are")
    void creatorMode() {
        var d = EditorRosterIndex.Filters.DEFAULT;
        List<EditorFilterBar.ActiveChip> chips = EditorFilterBar.activeChips(EditorCategoryFilter.CONTENTS, "", d, true,
            BuilderReviewState.SUBMITTED, true, true, true, "Edda");
        assertEquals(List.of(EditorFilterBar.ActiveKind.CATEGORY, EditorFilterBar.ActiveKind.STATUS,
            EditorFilterBar.ActiveKind.STARRED, EditorFilterBar.ActiveKind.PLAYER), kinds(chips));
        assertEquals("Edda", chips.get(3).label());
        // With everything at its default the creator chip is the only thing left to say.
        assertEquals(List.of(EditorFilterBar.ActiveKind.PLAYER),
            kinds(EditorFilterBar.activeChips(EditorCategoryFilter.ALL, "", d, true, BuilderProfileFilters.ALL, false, true, true, "Edda")));
    }

    @Test
    @DisplayName("the status cycle wraps and an unknown state restarts it")
    void statusCycle() {
        assertEquals(BuilderReviewState.NONE, EditorFilterBar.nextStatus(BuilderProfileFilters.ALL));
        assertEquals(BuilderProfileFilters.ALL, EditorFilterBar.nextStatus(BuilderReviewState.DECLINED));
        assertEquals(BuilderProfileFilters.ALL, EditorFilterBar.nextStatus("nonsense"));
    }
}
