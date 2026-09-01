package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The relay-kind arm of {@link EditorDirtyCheck#dirtyKeyFor(BuilderPhotoPaths.Kind, String, String)}.
 *
 * <p>Worth pinning because a wrong key fails <em>silently</em> in the direction that loses work: the
 * download path looks the key up in the dirty set, finds nothing, and installs over a plot full of
 * unsaved edits without asking. The expected strings here are the literal
 * {@code DirtyEntry.modelId()} shapes the scan passes in {@code EditorDirtyCheck} emit, written out
 * by hand on purpose — deriving them from the same helper the code under test uses would pass no
 * matter what either side said.</p>
 */
final class EditorDirtyKeyTest {

    @Test
    @DisplayName("carriages and contents are keyed by their bare id")
    void flatNamespaces() {
        assertEquals("brick_cabin",
                EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.CARRIAGE, "", "brick_cabin"));
        assertEquals("brick_cabin",
                EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.CONTENTS, "", "brick_cabin"));
    }

    @Test
    @DisplayName("a portal room is keyed portal_room.<name>")
    void portalRoom() {
        assertEquals("portal_room.bb",
                EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "bb"));
        // A room uploaded as a TRACK sub-kind lands in the same key space.
        assertEquals("portal_room.bb",
                EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.TRACK, TrackKind.PORTAL_ROOM.id(), "bb"));
    }

    @Test
    @DisplayName("every track sub-kind keys the way its scan pass writes it")
    void trackSubKinds() {
        assertEquals("track.sleeperless", key(TrackKind.TILE, "sleeperless"));
        assertEquals("pillar_top.stone", key(TrackKind.PILLAR_TOP, "stone"));
        assertEquals("pillar_middle.stone", key(TrackKind.PILLAR_MIDDLE, "stone"));
        assertEquals("pillar_bottom.stone", key(TrackKind.PILLAR_BOTTOM, "stone"));
        assertEquals("adjunct_stairs.ladder", key(TrackKind.ADJUNCT_STAIRS, "ladder"));
        assertEquals("adjunct_stairs_entrance.ladder", key(TrackKind.ADJUNCT_STAIRS_ENTRANCE, "ladder"));
        assertEquals("tunnel_section.brick", key(TrackKind.TUNNEL_SECTION, "brick"));
        assertEquals("tunnel_portal.brick", key(TrackKind.TUNNEL_PORTAL, "brick"));
    }

    @Test
    @DisplayName("a kind with no plot of its own asks no question")
    void noPlotNoKey() {
        // A part is stamped inside the carriages plots and a group is a builder-world build, so
        // neither has a plot whose unsaved edits a download could destroy.
        assertNull(EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.PART, "door", "brass_door"));
        assertNull(EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "", "my_run"));
        // Nothing to look up, rather than a key built out of nothing.
        assertNull(EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.CARRIAGE, "", ""));
        assertNull(EditorDirtyCheck.dirtyKeyFor(null, "", "brick_cabin"));
        // A track sub-kind this build of the mod does not know.
        assertNull(EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.TRACK, "gantry", "brick"));
    }

    @Test
    @DisplayName("every kind with a key also has a category to look it up in")
    void keyAndCategoryAgree() {
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            String subKind = kind == BuilderPhotoPaths.Kind.TRACK ? TrackKind.TILE.id() : "";
            String key = EditorDirtyCheck.dirtyKeyFor(kind, subKind, "thing");
            if (key == null) continue;
            assertNotNull(BuilderRelayKinds.categoryIdFor(kind, subKind),
                    kind + " has a dirty key but no editor category to find it in");
        }
    }

    private static String key(TrackKind kind, String id) {
        return EditorDirtyCheck.dirtyKeyFor(BuilderPhotoPaths.Kind.TRACK, kind.id(), id);
    }
}
