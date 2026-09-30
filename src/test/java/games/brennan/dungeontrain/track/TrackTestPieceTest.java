package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the model ids a Tracks-category Test the Carriage accepts to the ones the editor lists, so a
 * kind added to the Tracks list cannot quietly lose its Test button.
 */
final class TrackTestPieceTest {

    /** Every model id the Tracks category lists, spelled the way {@code EditorCategory.trackModels} builds them. */
    private static List<String> listedModelIds() {
        List<String> out = new ArrayList<>();
        out.add(new Template.Track("n").id());
        for (PillarSection s : PillarSection.values()) out.add(new Template.Pillar(s, "n").id());
        for (PillarAdjunct a : PillarAdjunct.values()) out.add(new Template.Adjunct(a, "n").id());
        for (TunnelPlacer.TunnelVariant v : TunnelPlacer.TunnelVariant.values()) {
            out.add(new Template.Tunnel(v, "n").id());
        }
        return out;
    }

    @Test
    @DisplayName("Every Tracks model id is a testable piece, and every piece is one the Tracks list shows")
    void coversTheTracksList() {
        List<String> listed = listedModelIds();
        for (String id : listed) {
            assertTrue(TrackTestPiece.ofModelId(id).isPresent(), id);
        }
        assertEquals(listed.size(), TrackTestPiece.values().length);
    }

    @Test
    @DisplayName("A template id round-trips to its piece and name")
    void templateIdRoundTrips() {
        for (TrackTestPiece p : TrackTestPiece.values()) {
            Optional<TrackTestPiece.Named> back = TrackTestPiece.parseTemplateId(p.templateId("mossy_arch"));
            assertEquals(Optional.of(new TrackTestPiece.Named(p, "mossy_arch")), back);
        }
    }

    @Test
    @DisplayName("Anything that is not a piece and a name parses to nothing")
    void rejectsOtherIds() {
        assertTrue(TrackTestPiece.parseTemplateId("pen").isEmpty());
        assertTrue(TrackTestPiece.parseTemplateId("portal_room:house").isEmpty());
        assertTrue(TrackTestPiece.parseTemplateId(":house").isEmpty());
        assertTrue(TrackTestPiece.parseTemplateId("track:").isEmpty());
        assertTrue(TrackTestPiece.parseTemplateId(null).isEmpty());
        assertTrue(TrackTestPiece.ofModelId("tile").isEmpty());
    }
}
