package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every category lays out from the same origin — no category reserves a Z range for another.
 * The parts grid still sits past the carriage row, because that is inside one category.
 */
final class EditorLayoutSharedOriginTest {

    @Test
    @DisplayName("contents, tracks and portals all start at the shared Z origin")
    void everyCategoryStartsAtZero() {
        assertEquals(0, EditorLayout.CONTENTS_FIRST_Z);
        assertEquals(0, EditorLayout.TRACKS_FIRST_Z);
        assertEquals(EditorLayout.TRACKS_FIRST_Z, TrackSidePlots.Z_BASELINE);
    }

    @Test
    @DisplayName("the parts grid is still one GAP past the widest carriage row")
    void partsGridFollowsCarriageRow() {
        assertEquals(games.brennan.dungeontrain.train.CarriageDims.MAX_WIDTH + EditorLayout.GAP,
            EditorLayout.PARTS_FIRST_Z);
    }
}
