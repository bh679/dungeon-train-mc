package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Layout tab's empty states: an absent roster is loading; a filtered-out one is not. */
final class EditorLayoutPaneTest {

    @Test
    @DisplayName("no roster yet reads as loading; a roster the filters emptied says so; rows say nothing")
    void emptyNotes() {
        assertEquals(EditorScreenLang.text(EditorScreenLang.NO_ROSTER),
            EditorLayoutPane.emptyNote(EditorRosterIndex.EMPTY, List.of()));
        assertEquals(EditorScreenLang.text(EditorScreenLang.NO_ROSTER),
            EditorLayoutPane.emptyNote(null, List.of()));
        EditorRosterIndex loaded = EditorLayoutPageTest.sample();
        assertEquals(EditorScreenLang.text(EditorScreenLang.LAYOUT_NO_MATCHES),
            EditorLayoutPane.emptyNote(loaded, List.of()));
        List<EditorLayoutPage.Row> rows = EditorLayoutPage.rows(loaded, k -> { }, s -> { });
        assertNull(EditorLayoutPane.emptyNote(loaded, rows));
    }
}
