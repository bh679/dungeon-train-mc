package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.WorkbenchCommit;
import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The commit screen's defaults and the command its last row runs. */
final class WorkbenchCommitScreenTest {

    @Test
    @DisplayName("the roster label's name and kind become the defaults")
    void parseLabel() {
        WorkbenchCommitScreen.Parsed p = WorkbenchCommitScreen.parseLabel("Brick Cabin  ·  portal_room", "brick-cabin-4271");
        assertEquals("brick_cabin", p.name());
        assertEquals(BuilderPhotoPaths.Kind.PORTAL_ROOM, p.kind());
        assertEquals("", p.subKind());
    }

    @Test
    @DisplayName("a track label carries its sub kind; a part label too")
    void parseLabelWithSubKind() {
        WorkbenchCommitScreen.Parsed t = WorkbenchCommitScreen.parseLabel("Bridge  ·  track/tunnel_section", "bridge-1");
        assertEquals(BuilderPhotoPaths.Kind.TRACK, t.kind());
        assertEquals("tunnel_section", t.subKind());
        WorkbenchCommitScreen.Parsed part = WorkbenchCommitScreen.parseLabel("Tiles  ·  part/floor", "tiles-2");
        assertEquals(BuilderPhotoPaths.Kind.PART, part.kind());
        assertEquals("floor", part.subKind());
    }

    @Test
    @DisplayName("a label without the separator, or an unknown kind, falls back to the staged id and the first kind")
    void parseLabelFallbacks() {
        WorkbenchCommitScreen.Parsed none = WorkbenchCommitScreen.parseLabel(null, "x-9");
        assertEquals("x-9", none.name());
        assertEquals(WorkbenchCommitScreen.kinds().get(0), none.kind());
        WorkbenchCommitScreen.Parsed odd = WorkbenchCommitScreen.parseLabel("Thing  ·  lost_city", "thing-3");
        assertEquals(WorkbenchCommitScreen.kinds().get(0), odd.kind(), "a kind that cannot be committed is not offered");
    }

    @Test
    @DisplayName("the offered kinds are exactly the committable ones, and every one maps to an editor category")
    void kinds() {
        for (BuilderPhotoPaths.Kind kind : WorkbenchCommitScreen.kinds()) {
            assertTrue(WorkbenchCommit.commitsAs(kind), kind.toString());
            PlotCategory category = WorkbenchCommitScreen.categoryOf(kind);
            assertTrue(category != null, kind + " has no editor category");
        }
        assertFalse(WorkbenchCommitScreen.kinds().contains(BuilderPhotoPaths.Kind.CARRIAGE_GROUP));
        assertFalse(WorkbenchCommitScreen.kinds().contains(BuilderPhotoPaths.Kind.LOST_CITY));
        assertFalse(WorkbenchCommitScreen.kinds().contains(BuilderPhotoPaths.Kind.CHUNK_FRAME));
    }

    @Test
    @DisplayName("the command prefix spells the commit command with a dash for no sub kind")
    void commandPrefix() {
        WorkbenchCommitScreen screen = new WorkbenchCommitScreen("Brick-Cabin-4271", "Brick Cabin  ·  contents");
        assertEquals("dungeontrain editor workbench commit brick-cabin-4271 contents - ", screen.commandPrefix());
        WorkbenchCommitScreen track = new WorkbenchCommitScreen("bridge-1", "Bridge  ·  track/tunnel_section");
        assertEquals("dungeontrain editor workbench commit bridge-1 track tunnel_section ", track.commandPrefix());
    }
}
