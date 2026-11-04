package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.editor.PlotCategory.Verb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden strings for every command {@link PlotCategory#command} and {@link PlotCategory#levelCommand}
 * emit — the client half of the client-server contract, written out by hand so a change to the
 * table has to be a change here too.
 *
 * <p>Two of these settle disagreements the seven screens used to have between them: a track-side
 * weight always carries the name (the server tree is {@code tracks weight <kind> <name>}), and a
 * portal room is removed by {@code <kind> <name>} from everywhere, not by the plot stood in.</p>
 */
final class PlotCategoryCommandsTest {

    private static String cmd(PlotCategory c, Verb v, String id, String name) {
        return c.command(v, id, name).orElse(null);
    }

    @Test
    @DisplayName("weight: id-addressed kinds, <kind> <name> on the track side, name alone for buildings")
    void weight() {
        assertEquals("dungeontrain editor weight brick_cabin", cmd(PlotCategory.CARRIAGES, Verb.WEIGHT, "brick_cabin", "brick_cabin"));
        assertEquals("dungeontrain editor contents weight maze", cmd(PlotCategory.CONTENTS, Verb.WEIGHT, "maze", "maze"));
        assertEquals("dungeontrain editor whole weight lounge", cmd(PlotCategory.WHOLE, Verb.WEIGHT, "lounge", "lounge"));
        assertEquals("dungeontrain editor whole group weight night_mail", cmd(PlotCategory.WHOLE_GROUP, Verb.WEIGHT, "night_mail", ""));
        assertEquals("dungeontrain editor whole half weight nook", cmd(PlotCategory.WHOLE_HALF, Verb.WEIGHT, "nook", ""));
        assertEquals("dungeontrain editor tracks weight pillar_top stone", cmd(PlotCategory.TRACKS, Verb.WEIGHT, "pillar_top", "stone"));
        assertEquals("dungeontrain editor portals weight portal_room library", cmd(PlotCategory.PORTALS, Verb.WEIGHT, "portal_room", "library"));
        assertEquals("dungeontrain editor buildings weight tower", cmd(PlotCategory.BUILDINGS, Verb.WEIGHT, "building", "tower"));
        for (PlotCategory c : new PlotCategory[] {PlotCategory.PARTS, PlotCategory.CHUNK_FRAMES, PlotCategory.LOST_CITY,
                PlotCategory.ARCHITECTURE, PlotCategory.WORKBENCH}) {
            assertEquals(Optional.empty(), c.command(Verb.WEIGHT, "x", "x"), c.name());
        }
    }

    @Test
    @DisplayName("a track-side weight without a name is withheld, not sent half-formed (the old EditorPlotTeleport bug)")
    void trackSideNeedsName() {
        assertEquals(Optional.empty(), PlotCategory.TRACKS.command(Verb.WEIGHT, "track", ""));
        assertEquals(Optional.empty(), PlotCategory.TRACKS.command(Verb.PHASE, "track", null));
        assertEquals(Optional.empty(), PlotCategory.TRACKS.levelCommand("minlevel", "track", ""));
        assertEquals(Optional.empty(), PlotCategory.PORTALS.command(Verb.WEIGHT, "portal_room", ""));
    }

    @Test
    @DisplayName("phase and level prefixes mirror weight")
    void phaseAndLevel() {
        assertEquals("dungeontrain editor phase standard", cmd(PlotCategory.CARRIAGES, Verb.PHASE, "standard", ""));
        assertEquals("dungeontrain editor contents phase maze", cmd(PlotCategory.CONTENTS, Verb.PHASE, "maze", ""));
        assertEquals("dungeontrain editor tracks phase tunnel_section brick", cmd(PlotCategory.TRACKS, Verb.PHASE, "tunnel_section", "brick"));
        assertEquals("dungeontrain editor portals phase portal_room library", cmd(PlotCategory.PORTALS, Verb.PHASE, "portal_room", "library"));
        assertEquals("dungeontrain editor whole phase lounge", cmd(PlotCategory.WHOLE, Verb.PHASE, "lounge", ""));
        assertEquals("dungeontrain editor whole group phase run", cmd(PlotCategory.WHOLE_GROUP, Verb.PHASE, "run", ""));
        assertEquals("dungeontrain editor whole half phase nook", cmd(PlotCategory.WHOLE_HALF, Verb.PHASE, "nook", ""));
        assertEquals(Optional.empty(), PlotCategory.BUILDINGS.command(Verb.PHASE, "building", "tower"));

        assertEquals("dungeontrain editor minlevel standard", PlotCategory.CARRIAGES.levelCommand("minlevel", "standard", "").orElseThrow());
        assertEquals("dungeontrain editor contents maxlevel maze", PlotCategory.CONTENTS.levelCommand("maxlevel", "maze", "").orElseThrow());
        assertEquals("dungeontrain editor tracks minlevel track sleeperless", PlotCategory.TRACKS.levelCommand("minlevel", "track", "sleeperless").orElseThrow());
        assertEquals("dungeontrain editor portals maxlevel portal_room library", PlotCategory.PORTALS.levelCommand("maxlevel", "portal_room", "library").orElseThrow());
        assertEquals("dungeontrain editor whole half minlevel nook", PlotCategory.WHOLE_HALF.levelCommand("minlevel", "nook", "").orElseThrow());
        assertEquals(Optional.empty(), PlotCategory.BUILDINGS.levelCommand("minlevel", "building", "tower"));
        assertEquals(Optional.empty(), PlotCategory.CARRIAGES.levelCommand("", "standard", ""));
    }

    @Test
    @DisplayName("enter: by id, by name for rooms / frames / buildings / staged builds, kind+name for parts, none for tracks")
    void enter() {
        assertEquals("dungeontrain editor enter brick_cabin", cmd(PlotCategory.CARRIAGES, Verb.ENTER, "brick_cabin", ""));
        assertEquals("dungeontrain editor contents enter maze", cmd(PlotCategory.CONTENTS, Verb.ENTER, "maze", ""));
        assertEquals("dungeontrain editor whole enter lounge", cmd(PlotCategory.WHOLE, Verb.ENTER, "lounge", ""));
        assertEquals("dungeontrain editor whole group enter run", cmd(PlotCategory.WHOLE_GROUP, Verb.ENTER, "run", ""));
        assertEquals("dungeontrain editor whole half enter nook", cmd(PlotCategory.WHOLE_HALF, Verb.ENTER, "nook", ""));
        assertEquals("dungeontrain editor portals enter library", cmd(PlotCategory.PORTALS, Verb.ENTER, "portal_room", "library"));
        assertEquals("dungeontrain editor part enter floor stone", cmd(PlotCategory.PARTS, Verb.ENTER, "floor", "stone"));
        assertEquals("dungeontrain editor chunkframe enter ivy", cmd(PlotCategory.CHUNK_FRAMES, Verb.ENTER, "chunk_frame", "ivy"));
        assertEquals("dungeontrain editor buildings goto tower", cmd(PlotCategory.BUILDINGS, Verb.ENTER, "building", "tower"));
        assertEquals("dungeontrain editor buildings lostcity goto bank", cmd(PlotCategory.LOST_CITY, Verb.ENTER, "lost_city", "bank"));
        assertEquals("dungeontrain editor workbench goto s1", cmd(PlotCategory.WORKBENCH, Verb.ENTER, "staged", "s1"));
        // Track-side enter is model-shaped (track / pillar / tunnel) and answered per Template.
        assertEquals(Optional.empty(), PlotCategory.TRACKS.command(Verb.ENTER, "track", "default"));
        assertEquals(Optional.empty(), PlotCategory.ARCHITECTURE.command(Verb.ENTER, "x", "x"));
    }

    @Test
    @DisplayName("remove: reset / delete / remove by kind; a portal room by <kind> <name> everywhere, a track kind by the plot stood in")
    void remove() {
        assertEquals("dungeontrain editor reset brick_cabin", cmd(PlotCategory.CARRIAGES, Verb.REMOVE, "brick_cabin", ""));
        assertEquals("dungeontrain editor contents reset maze", cmd(PlotCategory.CONTENTS, Verb.REMOVE, "maze", ""));
        assertEquals("dungeontrain editor tracks reset pillar_top", cmd(PlotCategory.TRACKS, Verb.REMOVE, "pillar_top", "stone"));
        assertEquals("dungeontrain editor portals reset portal_room library", cmd(PlotCategory.PORTALS, Verb.REMOVE, "portal_room", "library"));
        assertEquals(Optional.empty(), PlotCategory.PORTALS.command(Verb.REMOVE, "portal_room", ""));
        assertEquals("dungeontrain editor whole reset lounge", cmd(PlotCategory.WHOLE, Verb.REMOVE, "lounge", ""));
        assertEquals("dungeontrain editor whole group reset run", cmd(PlotCategory.WHOLE_GROUP, Verb.REMOVE, "run", ""));
        assertEquals("dungeontrain editor whole half reset nook", cmd(PlotCategory.WHOLE_HALF, Verb.REMOVE, "nook", ""));
        assertEquals("dungeontrain editor chunkframe delete ivy", cmd(PlotCategory.CHUNK_FRAMES, Verb.REMOVE, "chunk_frame", "ivy"));
        assertEquals("dungeontrain editor buildings delete tower", cmd(PlotCategory.BUILDINGS, Verb.REMOVE, "building", "tower"));
        assertEquals("dungeontrain editor workbench remove s1", cmd(PlotCategory.WORKBENCH, Verb.REMOVE, "staged", "s1"));
        assertEquals("dungeontrain editor part reset floor stone", cmd(PlotCategory.PARTS, Verb.REMOVE, "floor", "stone"));
        assertEquals(Optional.empty(), PlotCategory.LOST_CITY.command(Verb.REMOVE, "lost_city", "bank"));
        assertEquals(Optional.empty(), PlotCategory.ARCHITECTURE.command(Verb.REMOVE, "x", "x"));
    }

    @Test
    @DisplayName("label: the display label by id, a room by <kind> <name>, a frame's rename; none elsewhere")
    void label() {
        assertEquals("dungeontrain editor label brick_cabin", cmd(PlotCategory.CARRIAGES, Verb.LABEL, "brick_cabin", ""));
        assertEquals("dungeontrain editor contents label maze", cmd(PlotCategory.CONTENTS, Verb.LABEL, "maze", ""));
        assertEquals("dungeontrain editor portals label portal_room library", cmd(PlotCategory.PORTALS, Verb.LABEL, "portal_room", "library"));
        assertEquals("dungeontrain editor whole label lounge", cmd(PlotCategory.WHOLE, Verb.LABEL, "lounge", ""));
        assertEquals("dungeontrain editor whole group label run", cmd(PlotCategory.WHOLE_GROUP, Verb.LABEL, "run", ""));
        assertEquals("dungeontrain editor whole half label nook", cmd(PlotCategory.WHOLE_HALF, Verb.LABEL, "nook", ""));
        assertEquals("dungeontrain editor chunkframe rename ivy", cmd(PlotCategory.CHUNK_FRAMES, Verb.LABEL, "chunk_frame", "ivy"));
        for (PlotCategory c : new PlotCategory[] {PlotCategory.PARTS, PlotCategory.TRACKS, PlotCategory.BUILDINGS,
                PlotCategory.LOST_CITY, PlotCategory.ARCHITECTURE, PlotCategory.WORKBENCH}) {
            assertEquals(Optional.empty(), c.command(Verb.LABEL, "x", "x"), c.name());
        }
    }

    @Test
    @DisplayName("builder: the kinds with a credit, a room by <kind> <name>")
    void builder() {
        assertEquals("dungeontrain editor builder brick_cabin", cmd(PlotCategory.CARRIAGES, Verb.BUILDER, "brick_cabin", ""));
        assertEquals("dungeontrain editor contents builder maze", cmd(PlotCategory.CONTENTS, Verb.BUILDER, "maze", ""));
        assertEquals("dungeontrain editor portals builder portal_room library", cmd(PlotCategory.PORTALS, Verb.BUILDER, "portal_room", "library"));
        assertEquals("dungeontrain editor whole builder lounge", cmd(PlotCategory.WHOLE, Verb.BUILDER, "lounge", ""));
        assertEquals("dungeontrain editor whole group builder run", cmd(PlotCategory.WHOLE_GROUP, Verb.BUILDER, "run", ""));
        assertEquals("dungeontrain editor whole half builder nook", cmd(PlotCategory.WHOLE_HALF, Verb.BUILDER, "nook", ""));
        for (PlotCategory c : new PlotCategory[] {PlotCategory.PARTS, PlotCategory.CHUNK_FRAMES, PlotCategory.TRACKS,
                PlotCategory.BUILDINGS, PlotCategory.LOST_CITY, PlotCategory.ARCHITECTURE, PlotCategory.WORKBENCH}) {
            assertEquals(Optional.empty(), c.command(Verb.BUILDER, "x", "x"), c.name());
        }
    }

    @Test
    @DisplayName("new: only the track-side kinds have a typed New, addressed by kind alone")
    void newCommand() {
        assertEquals("dungeontrain editor tracks new pillar_top", cmd(PlotCategory.TRACKS, Verb.NEW, "pillar_top", "stone"));
        assertEquals("dungeontrain editor portals new portal_room", cmd(PlotCategory.PORTALS, Verb.NEW, "portal_room", ""));
        assertEquals(Optional.empty(), PlotCategory.TRACKS.command(Verb.NEW, "", ""));
        for (PlotCategory c : PlotCategory.values()) {
            if (c == PlotCategory.TRACKS || c == PlotCategory.PORTALS) continue;
            assertEquals(Optional.empty(), c.command(Verb.NEW, "x", "x"), c.name());
        }
    }

    @Test
    @DisplayName("clear: one command, for every category with a single plot to stand in")
    void clear() {
        for (PlotCategory c : PlotCategory.values()) {
            Optional<String> cmd = c.command(Verb.CLEAR, "x", "x");
            if (c == PlotCategory.TRACKS || c == PlotCategory.LOST_CITY || c == PlotCategory.ARCHITECTURE) {
                assertEquals(Optional.empty(), cmd, c.name());
            } else {
                assertEquals("dungeontrain editor clear", cmd.orElseThrow(), c.name());
            }
        }
    }

    @Test
    @DisplayName("an empty id is never sent")
    void emptyIdWithheld() {
        for (PlotCategory c : PlotCategory.values()) {
            for (Verb v : Verb.values()) {
                if (v == Verb.CLEAR) continue;
                Optional<String> cmd = c.command(v, "", "");
                assertTrue(cmd.isEmpty(), c + "/" + v + " -> " + cmd);
            }
        }
    }
}
