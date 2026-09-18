package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The plot key {@link EditorPlotScope#standingIn} compares on, and the category
 * {@link EditorCategory#of} files a model under. {@code standingIn} itself needs a live server +
 * player (it runs the {@link EditorCategory#locate} cascade) and is covered by the in-game Gate 2
 * flow; what can be pinned here is that two spellings of "the same plot" — the one a caller is
 * about to enter and the one located under the player — reduce to the same key, and that
 * different plots never do.
 */
final class EditorPlotScopeKeyTest {

    private static String key(Template model) {
        return EditorPlotScope.keyFor(EditorCategory.of(model), model);
    }

    @Test
    @DisplayName("four segments: category:kind:id:name")
    void key_hasFourSegments() {
        List<Template> models = List.of(
            new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD)),
            new Template.Contents(CarriageContents.custom("books")),
            new Template.Part(CarriagePartKind.FLOOR, "stone"),
            new Template.Track(),
            new Template.Pillar(PillarSection.TOP),
            new Template.Adjunct(PillarAdjunct.values()[0]),
            new Template.Tunnel(TunnelVariant.values()[0]),
            new Template.PortalRoom("lobby"));
        for (Template m : models) {
            String[] parts = key(m).split(":", -1);
            assertEquals(4, parts.length, key(m));
            assertEquals(EditorCategory.of(m).name(), parts[0]);
            assertEquals(m.kind().name(), parts[1]);
            assertEquals(m.id(), parts[2]);
            assertEquals(m.variantName(), parts[3]);
        }
    }

    @Test
    @DisplayName("Located and (category, model) spell the same key")
    void key_locatedMatchesDirect() {
        Template room = new Template.PortalRoom("lobby");
        EditorCategory.Located located = new EditorCategory.Located(EditorCategory.PORTALS, room);
        assertEquals(EditorPlotScope.keyFor(located), key(room));

        Template part = new Template.Part(CarriagePartKind.WALLS, "brick");
        assertEquals(EditorPlotScope.keyFor(new EditorCategory.Located(EditorCategory.CARRIAGES, part)), key(part));
    }

    @Test
    @DisplayName("portal rooms share an id — only the name segment tells them apart")
    void key_portalRoomsDifferByName() {
        Template a = new Template.PortalRoom("lobby");
        Template b = new Template.PortalRoom("vault");
        assertEquals(a.id(), b.id(), "same id — the reason the key carries the name");
        assertNotEquals(key(a), key(b));
        assertEquals(key(a), key(new Template.PortalRoom("lobby")));
        // Exact compare: a raw-cased name is a different key — callers canonicalise via the registry.
        assertNotEquals(key(a), key(new Template.PortalRoom("Lobby")));
    }

    @Test
    @DisplayName("default-named vs custom-named track-side plots are different keys")
    void key_defaultVersusNamed() {
        assertNotEquals(key(new Template.Pillar(PillarSection.TOP)), key(new Template.Pillar(PillarSection.TOP, "custom")));
        assertNotEquals(key(new Template.Track()), key(new Template.Track("wide")));
        TunnelVariant tv = TunnelVariant.values()[0];
        assertNotEquals(key(new Template.Tunnel(tv)), key(new Template.Tunnel(tv, "dark")));
        assertNotEquals(key(new Template.Pillar(PillarSection.TOP)), key(new Template.Pillar(PillarSection.BOTTOM)));
    }

    @Test
    @DisplayName("carriages differ by id; a part differs from its carriage")
    void key_carriagesAndParts() {
        Template std = new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD));
        Template custom = new Template.Carriage(CarriageVariant.custom("my_carriage"));
        assertNotEquals(key(std), key(custom));
        assertEquals(key(std), key(new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD))));
        assertNotEquals(key(new Template.Part(CarriagePartKind.FLOOR, "stone")),
            key(new Template.Part(CarriagePartKind.ROOF, "stone")));
        assertNotEquals(key(new Template.Part(CarriagePartKind.FLOOR, "stone")),
            key(new Template.Part(CarriagePartKind.FLOOR, "wood")));
    }

    @Test
    @DisplayName("EditorCategory.of: the category locate() reports each model under")
    void of_mapsEveryRecord() {
        assertEquals(EditorCategory.CARRIAGES, EditorCategory.of(new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD))));
        assertEquals(EditorCategory.CARRIAGES, EditorCategory.of(new Template.Part(CarriagePartKind.FLOOR, "stone")),
            "parts are stamped as part of the CARRIAGES set");
        assertEquals(EditorCategory.CONTENTS, EditorCategory.of(new Template.Contents(CarriageContents.custom("books"))));
        assertEquals(EditorCategory.TRACKS, EditorCategory.of(new Template.Track()));
        assertEquals(EditorCategory.TRACKS, EditorCategory.of(new Template.Pillar(PillarSection.MIDDLE)));
        assertEquals(EditorCategory.TRACKS, EditorCategory.of(new Template.Adjunct(PillarAdjunct.values()[0])));
        assertEquals(EditorCategory.TRACKS, EditorCategory.of(new Template.Tunnel(TunnelVariant.values()[0])));
        assertEquals(EditorCategory.PORTALS, EditorCategory.of(new Template.PortalRoom("lobby")));
    }
}
