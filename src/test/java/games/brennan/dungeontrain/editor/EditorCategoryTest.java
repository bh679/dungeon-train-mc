package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EditorCategory} ordering + membership and the
 * {@link EditorCategory#fromId(String)} parser. The {@code locate} method
 * needs a live server + player and is covered by the in-game Gate 2 flow.
 */
final class EditorCategoryTest {

    @Test
    @DisplayName("fromId: case-insensitive, round-trips valid ids")
    void fromId_roundTrips() {
        assertEquals(Optional.of(EditorCategory.CARRIAGES), EditorCategory.fromId("carriages"));
        assertEquals(Optional.of(EditorCategory.CARRIAGES), EditorCategory.fromId("CARRIAGES"));
        assertEquals(Optional.of(EditorCategory.TRACKS), EditorCategory.fromId("tracks"));
        assertEquals(Optional.of(EditorCategory.ARCHITECTURE), EditorCategory.fromId("architecture"));
        assertFalse(EditorCategory.fromId("nope").isPresent());
        assertFalse(EditorCategory.fromId(null).isPresent());
    }

    @Test
    @DisplayName("id(): stable lower-case token")
    void id_isLowercase() {
        assertEquals("carriages", EditorCategory.CARRIAGES.id());
        assertEquals("tracks", EditorCategory.TRACKS.id());
        assertEquals("architecture", EditorCategory.ARCHITECTURE.id());
    }

    @Test
    @DisplayName("displayName(): human-readable")
    void displayName_isReadable() {
        assertEquals("Carriages", EditorCategory.CARRIAGES.displayName());
        assertEquals("Tracks", EditorCategory.TRACKS.displayName());
        assertEquals("Architecture", EditorCategory.ARCHITECTURE.displayName());
    }

    @Test
    @DisplayName("CARRIAGES: includes all four built-in variants, first is standard")
    void carriages_containBuiltins() {
        List<EditorModel> models = EditorCategory.CARRIAGES.models();
        assertTrue(models.size() >= CarriageType.values().length,
            "expected at least " + CarriageType.values().length + " carriage models, got " + models.size());
        EditorModel first = EditorCategory.CARRIAGES.firstModel().orElseThrow();
        assertInstanceOf(EditorModel.CarriageModel.class, first);
        // First entry should be the STANDARD built-in (enum-ordered first).
        assertEquals("standard", first.id());
    }

    @Test
    @DisplayName("TRACKS: pillars ground-up then tunnels in enum order")
    void tracks_orderIsGroundUpThenTunnels() {
        List<EditorModel> models = EditorCategory.TRACKS.models();
        assertEquals(PillarSection.values().length + TunnelVariant.values().length, models.size());
        assertInstanceOf(EditorModel.PillarModel.class, models.get(0));
        assertEquals(PillarSection.BOTTOM, ((EditorModel.PillarModel) models.get(0)).section());
        assertEquals(PillarSection.MIDDLE, ((EditorModel.PillarModel) models.get(1)).section());
        assertEquals(PillarSection.TOP, ((EditorModel.PillarModel) models.get(2)).section());
        assertInstanceOf(EditorModel.TunnelModel.class, models.get(3));
        assertEquals(TunnelVariant.SECTION, ((EditorModel.TunnelModel) models.get(3)).variant());
        assertEquals(TunnelVariant.PORTAL, ((EditorModel.TunnelModel) models.get(4)).variant());
    }

    @Test
    @DisplayName("TRACKS firstModel: pillar_bottom (ground-up entry point)")
    void tracks_firstIsPillarBottom() {
        EditorModel first = EditorCategory.TRACKS.firstModel().orElseThrow();
        assertEquals("pillar_bottom", first.id());
    }

    @Test
    @DisplayName("ARCHITECTURE: empty models, no firstModel")
    void architecture_isEmpty() {
        assertTrue(EditorCategory.ARCHITECTURE.models().isEmpty());
        assertFalse(EditorCategory.ARCHITECTURE.firstModel().isPresent());
    }
}
