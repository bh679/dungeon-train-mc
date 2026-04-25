package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link EditorModel} id / displayName across all three types. */
final class EditorModelTest {

    @Test
    @DisplayName("CarriageModel id matches variant id")
    void carriage_id() {
        EditorModel.CarriageModel model = new EditorModel.CarriageModel(CarriageVariant.of(CarriageType.STANDARD));
        assertEquals("standard", model.id());
        assertEquals("standard", model.displayName());
    }

    @Test
    @DisplayName("CarriageModel preserves custom variant names")
    void carriage_customName() {
        EditorModel.CarriageModel model = new EditorModel.CarriageModel(CarriageVariant.custom("my_carriage"));
        assertEquals("my_carriage", model.id());
    }

    @Test
    @DisplayName("PillarModel id has pillar_ prefix")
    void pillar_id() {
        assertEquals("pillar_top", new EditorModel.PillarModel(PillarSection.TOP).id());
        assertEquals("pillar_middle", new EditorModel.PillarModel(PillarSection.MIDDLE).id());
        assertEquals("pillar_bottom", new EditorModel.PillarModel(PillarSection.BOTTOM).id());
    }

    @Test
    @DisplayName("TunnelModel id has tunnel_ prefix")
    void tunnel_id() {
        assertEquals("tunnel_section", new EditorModel.TunnelModel(TunnelVariant.SECTION).id());
        assertEquals("tunnel_portal", new EditorModel.TunnelModel(TunnelVariant.PORTAL).id());
    }

    @Test
    @DisplayName("AdjunctModel id has adjunct_ prefix; displayName uses the adjunct id")
    void adjunct_id() {
        EditorModel.AdjunctModel m = new EditorModel.AdjunctModel(PillarAdjunct.STAIRS);
        assertEquals("adjunct_stairs", m.id());
        assertEquals("stairs / default", m.displayName());

        EditorModel.AdjunctModel named =
            new EditorModel.AdjunctModel(PillarAdjunct.STAIRS, "carved");
        assertEquals("stairs / carved", named.displayName());
    }

    @Test
    @DisplayName("TrackModel id is the fixed 'track' token")
    void track_id() {
        assertEquals("track", new EditorModel.TrackModel().id());
    }

    @Test
    @DisplayName("Records reject null constructor args")
    void rejectsNull() {
        assertThrows(NullPointerException.class,
            () -> new EditorModel.CarriageModel(null));
        assertThrows(NullPointerException.class,
            () -> new EditorModel.PillarModel(null));
        assertThrows(NullPointerException.class,
            () -> new EditorModel.TunnelModel(null));
        assertThrows(NullPointerException.class,
            () -> new EditorModel.AdjunctModel(null));
    }
}
