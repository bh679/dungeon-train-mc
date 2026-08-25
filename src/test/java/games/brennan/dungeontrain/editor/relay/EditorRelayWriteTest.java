package games.brennan.dungeontrain.editor.relay;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two questions an editor upload has to get right, pinned here because nothing downstream can
 * check either one.
 *
 * <p>The naming decides which relay row a save lands in — a wrong sub kind quietly starts a second
 * profile entry for a template that already had one, and a wrong kind offers a portal room to a
 * train as a carriage. The volume decides which blocks go up, and the one kind whose plot is not
 * what its save captures is contents.</p>
 */
final class EditorRelayWriteTest {

    private static final CarriageDims DIMS = new CarriageDims(9, 5, 5);

    // ----- naming -----

    @Test
    @DisplayName("a carriage and a room are filed under their own kind, with a flat namespace")
    void carriageAndContents() {
        EditorRelayWrite.Naming carriage =
                EditorRelayWrite.namingOf(new Template.Carriage(CarriageVariant.custom("my_cart")));
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE, carriage.kind());
        assertEquals("", carriage.subKind());
        assertEquals("my_cart", carriage.id());

        EditorRelayWrite.Naming contents =
                EditorRelayWrite.namingOf(new Template.Contents(CarriageContents.custom("my_room")));
        assertEquals(BuilderPhotoPaths.Kind.CONTENTS, contents.kind());
        assertEquals("", contents.subKind());
        assertEquals("my_room", contents.id());
    }

    @Test
    @DisplayName("a part carries its kind, because a part id is only unique within one")
    void partCarriesItsKind() {
        EditorRelayWrite.Naming floor =
                EditorRelayWrite.namingOf(new Template.Part(CarriagePartKind.FLOOR, "standard"));
        EditorRelayWrite.Naming doors =
                EditorRelayWrite.namingOf(new Template.Part(CarriagePartKind.DOORS, "standard"));

        assertEquals(BuilderPhotoPaths.Kind.PART, floor.kind());
        assertEquals(CarriagePartKind.FLOOR.id(), floor.subKind());
        assertEquals(CarriagePartKind.DOORS.id(), doors.subKind());
        // Same name, same kind, different sub kind — so they cannot collapse onto one relay row.
        assertNotEquals(key(floor), key(doors));
    }

    @Test
    @DisplayName("every track-side kind is a TRACK told apart by its TrackKind")
    void trackSideKinds() {
        EditorRelayWrite.Naming tile = EditorRelayWrite.namingOf(new Template.Track("tile2"));
        assertEquals(BuilderPhotoPaths.Kind.TRACK, tile.kind());
        assertEquals(TrackKind.TILE.id(), tile.subKind());
        assertEquals("tile2", tile.id());

        for (PillarSection section : PillarSection.values()) {
            EditorRelayWrite.Naming n =
                    EditorRelayWrite.namingOf(new Template.Pillar(section, "carved"));
            assertEquals(BuilderPhotoPaths.Kind.TRACK, n.kind(), section + " is a track-side kind");
            assertNotEquals("", n.subKind(), section + " must name its TrackKind");
        }
        for (PillarAdjunct adjunct : PillarAdjunct.values()) {
            EditorRelayWrite.Naming n =
                    EditorRelayWrite.namingOf(new Template.Adjunct(adjunct, "carved"));
            assertEquals(BuilderPhotoPaths.Kind.TRACK, n.kind(), adjunct + " is a track-side kind");
            assertNotEquals("", n.subKind(), adjunct + " must name its TrackKind");
        }
        for (TunnelVariant variant : TunnelVariant.values()) {
            EditorRelayWrite.Naming n =
                    EditorRelayWrite.namingOf(new Template.Tunnel(variant, "carved"));
            assertEquals(BuilderPhotoPaths.Kind.TRACK, n.kind(), variant + " is a track-side kind");
            assertNotEquals("", n.subKind(), variant + " must name its TrackKind");
        }
    }

    @Test
    @DisplayName("no two track-side kinds share a sub kind, so 'default' stays eight distinct rows")
    void trackSubKindsAreDistinct() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PillarSection section : PillarSection.values()) {
            assertNotNull(EditorRelayWrite.namingOf(new Template.Pillar(section, "x")));
            seen.add(EditorRelayWrite.namingOf(new Template.Pillar(section, "x")).subKind());
        }
        int afterPillars = seen.size();
        assertEquals(PillarSection.values().length, afterPillars,
                "each pillar section needs its own TrackKind");
        for (TunnelVariant variant : TunnelVariant.values()) {
            seen.add(EditorRelayWrite.namingOf(new Template.Tunnel(variant, "x")).subKind());
        }
        assertEquals(afterPillars + TunnelVariant.values().length, seen.size(),
                "each tunnel variant needs its own TrackKind, distinct from the pillars'");
    }

    @Test
    @DisplayName("a portal room is its own kind, not a track with a kind attached")
    void portalRoomIsItsOwnKind() {
        EditorRelayWrite.Naming room = EditorRelayWrite.namingOf(new Template.PortalRoom("cavern"));
        assertEquals(BuilderPhotoPaths.Kind.PORTAL_ROOM, room.kind());
        assertEquals("", room.subKind());
        assertEquals("cavern", room.id());
    }

    @Test
    @DisplayName("a whole carriage has no editor plot and so nothing to upload")
    void wholeCarriageIsNotAnEditorTemplate() {
        assertNull(EditorRelayWrite.namingOf(new Template.WholeCarriage(
                games.brennan.dungeontrain.train.WholeCarriage.of("built_in_builder"))));
        assertNull(EditorRelayWrite.namingOf(null));
    }

    @Test
    @DisplayName("only a carriage may be submitted to the train, whichever tool authored it")
    void onlyCarriagesJoinTheTrain() {
        assertEquals(BuilderRelayKinds.CARRIAGE, BuilderRelayKinds.idOf(
                EditorRelayWrite.namingOf(new Template.Carriage(CarriageVariant.custom("c"))).kind()));
        for (Template model : new Template[]{
                new Template.Contents(CarriageContents.custom("r")),
                new Template.Part(CarriagePartKind.FLOOR, "p"),
                new Template.Track("t"),
                new Template.PortalRoom("room")}) {
            assertNotEquals(BuilderRelayKinds.CARRIAGE,
                    BuilderRelayKinds.idOf(EditorRelayWrite.namingOf(model).kind()),
                    model + " is a piece of something, not a thing a train slot can hold");
        }
    }

    // ----- volume -----

    @Test
    @DisplayName("contents capture the interior, not the carriage wrapped around it")
    void contentsCaptureTheInterior() {
        Template.Contents room = new Template.Contents(CarriageContents.custom("my_room"));
        BlockPos plot = new BlockPos(100, 250, 80);

        assertEquals(plot.offset(1, 1, 1), EditorRelayWrite.capturedOrigin(room, plot),
                "the contents editor writes from one block in on every axis");

        Vec3i captured = EditorRelayWrite.capturedSize(room, DIMS);
        Vec3i plotSize = room.plotSize(DIMS);
        assertNotEquals(plotSize, captured, "the plot is a whole carriage; the template is its inside");
        assertEquals(new Vec3i(plotSize.getX() - 2, plotSize.getY() - 2, plotSize.getZ() - 2), captured);
    }

    @Test
    @DisplayName("every other kind captures its plot exactly as the plot is")
    void otherKindsCaptureTheWholePlot() {
        BlockPos plot = new BlockPos(-40, 250, 0);
        for (Template model : new Template[]{
                new Template.Carriage(CarriageVariant.custom("c")),
                new Template.Part(CarriagePartKind.WALLS, "p"),
                new Template.Track("t"),
                new Template.Pillar(PillarSection.TOP, "carved"),
                new Template.Adjunct(PillarAdjunct.STAIRS, "carved"),
                new Template.Tunnel(TunnelVariant.SECTION, "carved")}) {
            assertEquals(plot, EditorRelayWrite.capturedOrigin(model, plot), model + " origin");
            assertEquals(model.plotSize(DIMS), EditorRelayWrite.capturedSize(model, DIMS),
                    model + " size");
        }
    }

    // ----- the two tools agree -----

    @Test
    @DisplayName("editor and builder file the same template under the same relay key")
    void editorAndBuilderShareOneKey() {
        // A part, keyed as BuilderSave.savePart keys it: kind id as the sub kind, name as the id.
        EditorRelayWrite.Naming part =
                EditorRelayWrite.namingOf(new Template.Part(CarriagePartKind.DOORS, "arched"));
        assertEquals(
                BuilderRelayBuilds.keyOf(BuilderRelayKinds.PART, CarriagePartKind.DOORS.id(), "arched"),
                key(part),
                "a part saved in the editor must update the profile entry the builder made, not a second one");

        // A tunnel, keyed as BuilderSave.saveTrack keys it: TrackKind id as the sub kind.
        EditorRelayWrite.Naming tunnel =
                EditorRelayWrite.namingOf(new Template.Tunnel(TunnelVariant.SECTION, "arched"));
        assertEquals(
                BuilderRelayBuilds.keyOf(BuilderRelayKinds.TRACK, tunnel.subKind(), "arched"),
                key(tunnel));
    }

    private static String key(EditorRelayWrite.Naming naming) {
        return BuilderRelayBuilds.keyOf(
                BuilderRelayKinds.idOf(naming.kind()), naming.subKind(), naming.id());
    }
}
