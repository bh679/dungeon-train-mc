package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two key translations a sidecar edit travels through to reach the dirty scan: plot kind →
 * baseline key, and scan row → baseline key. If either drifts from the scan passes' own keys, a
 * variant-pool edit is recorded against a row nobody reads.
 */
final class EditorDirtyCheckKeysTest {

    @Test
    @DisplayName("every track kind has a distinct baseline key")
    void everyTrackKindHasAKey() {
        Set<String> keys = new HashSet<>();
        for (TrackKind kind : TrackKind.values()) {
            String key = EditorDirtyCheck.snapshotKeyFor(kind, "default");
            assertNotNull(key, kind.name());
            keys.add(key);
        }
        assertEquals(TrackKind.values().length, keys.size());
    }

    @Test
    @DisplayName("a scan row maps back to the key its pass stored the baseline under")
    void rowKeysRoundTrip() {
        assertEquals(EditorPlotSnapshots.key("carriages", "standard"),
            EditorDirtyCheck.snapshotKeyFor("carriages", "standard"));
        assertEquals(EditorPlotSnapshots.key("contents", "pen"),
            EditorDirtyCheck.snapshotKeyFor("contents", "pen"));
        assertEquals(PortalRoomEditor.snapshotKey("beam"),
            EditorDirtyCheck.snapshotKeyFor("portals", "portal_room.beam"));
        assertEquals(TrackEditor.snapshotKey("sleeperless"),
            EditorDirtyCheck.snapshotKeyFor("tracks", "track.sleeperless"));
        for (TrackKind kind : TrackKind.values()) {
            if (kind == TrackKind.PORTAL_ROOM) continue;
            String row = EditorDirtyCheck.dirtyKeyFor(
                games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind.TRACK, kind.id(), "x");
            assertEquals(EditorDirtyCheck.snapshotKeyFor(kind, "x"),
                EditorDirtyCheck.snapshotKeyFor("tracks", row), kind.name());
        }
    }

    @Test
    @DisplayName("rows the scan never emits have no key")
    void unknownRowsHaveNoKey() {
        assertNull(EditorDirtyCheck.snapshotKeyFor("parts", "roof"));
        assertNull(EditorDirtyCheck.snapshotKeyFor("tracks", "nodot"));
        assertNull(EditorDirtyCheck.snapshotKeyFor((String) null, "x"));
    }
}
