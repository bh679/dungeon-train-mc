package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
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
    @DisplayName("exactly five constants — PARTS is deliberately not one of them")
    void constants_areFrozenAtFive() {
        // Parts are addressable in the UI but are not their own plot set: they live inside the
        // CARRIAGES set (carriageModels folds Template.Part in, locate() reports CARRIAGES for a
        // part plot). A PARTS constant here would add a category-bar button and a
        // /dt editor parts token that stamp nothing. The UI-facing vocabulary that DOES include
        // parts is PlotCategory.
        assertEquals(5, EditorCategory.values().length);
        assertFalse(EditorCategory.fromId("parts").isPresent(),
            "PARTS belongs to PlotCategory, not EditorCategory");
    }

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
        List<Template> models = EditorCategory.CARRIAGES.models();
        assertTrue(models.size() >= CarriageType.values().length,
            "expected at least " + CarriageType.values().length + " carriage models, got " + models.size());
        Template first = EditorCategory.CARRIAGES.firstModel().orElseThrow();
        assertInstanceOf(Template.Carriage.class, first);
        // First entry should be the STANDARD built-in (enum-ordered first).
        assertEquals("standard", first.id());
    }

    @Test
    @DisplayName("TRACKS: track tile, then pillars ground-up, then adjuncts, then tunnels in enum order")
    void tracks_orderIsTrackThenPillarsThenAdjunctsThenTunnels() {
        List<Template> models = EditorCategory.TRACKS.models();
        assertEquals(
            1 + PillarSection.values().length + PillarAdjunct.values().length + TunnelVariant.values().length,
            models.size());
        assertInstanceOf(Template.Track.class, models.get(0));
        assertInstanceOf(Template.Pillar.class, models.get(1));
        assertEquals(PillarSection.BOTTOM, ((Template.Pillar) models.get(1)).section());
        assertEquals(PillarSection.MIDDLE, ((Template.Pillar) models.get(2)).section());
        assertEquals(PillarSection.TOP, ((Template.Pillar) models.get(3)).section());
        assertInstanceOf(Template.Adjunct.class, models.get(4));
        assertEquals(PillarAdjunct.STAIRS, ((Template.Adjunct) models.get(4)).adjunct());
        int tunnelStart = 1 + PillarSection.values().length + PillarAdjunct.values().length;
        assertInstanceOf(Template.Tunnel.class, models.get(tunnelStart));
        assertEquals(TunnelVariant.SECTION, ((Template.Tunnel) models.get(tunnelStart)).variant());
        assertEquals(TunnelVariant.PORTAL, ((Template.Tunnel) models.get(tunnelStart + 1)).variant());
    }

    @Test
    @DisplayName("TRACKS firstModel: track tile (most-used track model)")
    void tracks_firstIsTrack() {
        Template first = EditorCategory.TRACKS.firstModel().orElseThrow();
        assertEquals("track", first.id());
    }

    @Test
    @DisplayName("PORTALS: the pocket room, default first — and it is NOT in TRACKS")
    void portals_holdTheRoomAndOnlyTheRoom() {
        List<Template> models = EditorCategory.PORTALS.models();
        assertFalse(models.isEmpty(), "PORTALS should always expose the synthetic default room");
        assertInstanceOf(Template.PortalRoom.class, models.get(0));
        assertEquals("default", ((Template.PortalRoom) models.get(0)).name());
        assertEquals("portal_room", EditorCategory.PORTALS.firstModel().orElseThrow().id());

        // The room is its own category — TRACKS must not have grown a row for it.
        for (Template m : EditorCategory.TRACKS.models()) {
            assertFalse(m instanceof Template.PortalRoom,
                "portal rooms belong to PORTALS, not TRACKS");
        }
    }

    @Test
    @DisplayName("ARCHITECTURE: empty models, no firstModel")
    void architecture_isEmpty() {
        assertTrue(EditorCategory.ARCHITECTURE.models().isEmpty());
        assertFalse(EditorCategory.ARCHITECTURE.firstModel().isPresent());
    }

    @Test
    @DisplayName("Phase 4 Goal 3: CARRIAGES.models() includes Part rows when parts are registered")
    void carriages_includesParts_whenRegistered() {
        // Test environment may have no parts registered (CarriagePartRegistry
        // populates at server start); register a synthetic one and confirm
        // it appears in CARRIAGES.models() after every carriage shell.
        String testName = "phase4_goal3_test_part";
        try {
            games.brennan.dungeontrain.editor.CarriagePartRegistry.register(
                games.brennan.dungeontrain.train.CarriagePartKind.FLOOR, testName);
            List<Template> models = EditorCategory.CARRIAGES.models();
            // Find our part in the list.
            boolean found = false;
            for (int i = 0; i < models.size(); i++) {
                Template m = models.get(i);
                if (m instanceof Template.Part p
                        && p.partKind() == games.brennan.dungeontrain.train.CarriagePartKind.FLOOR
                        && testName.equals(p.name())) {
                    found = true;
                    // Parts must follow shells (kind-major ordering).
                    assertTrue(i >= games.brennan.dungeontrain.train.CarriageVariantRegistry.allVariants().size(),
                        "Part rows should appear after all carriage shells, got index " + i);
                    break;
                }
            }
            assertTrue(found, "Registered Part(FLOOR, " + testName + ") not in CARRIAGES.models()");
        } finally {
            games.brennan.dungeontrain.editor.CarriagePartRegistry.unregister(
                games.brennan.dungeontrain.train.CarriagePartKind.FLOOR, testName);
        }
    }
}
