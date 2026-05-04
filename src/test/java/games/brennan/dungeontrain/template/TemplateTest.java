package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Template} id / displayName / metadata across all permittees. */
final class TemplateTest {

    @Test
    @DisplayName("Carriage id matches variant id")
    void carriage_id() {
        Template.Carriage model = new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD));
        assertEquals("standard", model.id());
        assertEquals("standard", model.displayName());
        assertEquals(TemplateKind.CARRIAGE, model.kind());
        assertTrue(model.isBuiltin());
        assertTrue(model.canPromote());
        assertEquals(CarriageType.STANDARD, model.type().orElseThrow());
    }

    @Test
    @DisplayName("Carriage preserves custom variant names; customs are not built-in and cannot promote")
    void carriage_customName() {
        Template.Carriage model = new Template.Carriage(CarriageVariant.custom("my_carriage"));
        assertEquals("my_carriage", model.id());
        assertFalse(model.isBuiltin());
        assertFalse(model.canPromote());
        assertTrue(model.type().isEmpty());
    }

    @Test
    @DisplayName("Pillar id has pillar_ prefix; type returns the section")
    void pillar_id() {
        assertEquals("pillar_top", new Template.Pillar(PillarSection.TOP).id());
        assertEquals("pillar_middle", new Template.Pillar(PillarSection.MIDDLE).id());
        assertEquals("pillar_bottom", new Template.Pillar(PillarSection.BOTTOM).id());
        Template.Pillar m = new Template.Pillar(PillarSection.TOP);
        assertEquals(TemplateKind.PILLAR, m.kind());
        assertEquals(PillarSection.TOP, m.type().orElseThrow());
    }

    @Test
    @DisplayName("Tunnel id has tunnel_ prefix; tunnel cannot promote (no bundled tier)")
    void tunnel_id() {
        Template.Tunnel section = new Template.Tunnel(TunnelVariant.SECTION);
        assertEquals("tunnel_section", section.id());
        assertEquals("tunnel_portal", new Template.Tunnel(TunnelVariant.PORTAL).id());
        assertEquals(TemplateKind.TUNNEL, section.kind());
        assertEquals(TunnelVariant.SECTION, section.type().orElseThrow());
        assertFalse(section.canPromote());
    }

    @Test
    @DisplayName("Adjunct id has adjunct_ prefix; displayName uses the adjunct id; kind() = STAIRS")
    void adjunct_id() {
        Template.Adjunct m = new Template.Adjunct(PillarAdjunct.STAIRS);
        assertEquals("adjunct_stairs", m.id());
        assertEquals("stairs / default", m.displayName());
        assertEquals(TemplateKind.STAIRS, m.kind());
        assertEquals(PillarAdjunct.STAIRS, m.type().orElseThrow());

        Template.Adjunct named =
            new Template.Adjunct(PillarAdjunct.STAIRS, "carved");
        assertEquals("stairs / carved", named.displayName());
        assertFalse(named.isBuiltin());
    }

    @Test
    @DisplayName("Track id is the fixed 'track' token; no sub-type today")
    void track_id() {
        Template.Track t = new Template.Track();
        assertEquals("track", t.id());
        assertEquals(TemplateKind.TRACK, t.kind());
        assertTrue(t.type().isEmpty());
        assertTrue(t.isBuiltin());
        assertTrue(t.canPromote());
    }

    @Test
    @DisplayName("Part id has part_ prefix; type returns the part kind")
    void part_id() {
        Template.Part m = new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR);
        assertEquals("part_floor", m.id());
        assertEquals("part / floor / default", m.displayName());
        assertEquals(TemplateKind.PART, m.kind());
        assertEquals(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR, m.type().orElseThrow());
        assertFalse(m.isBuiltin());
        assertTrue(m.canPromote());
    }

    @Test
    @DisplayName("Records reject null constructor args")
    void rejectsNull() {
        assertThrows(NullPointerException.class,
            () -> new Template.Carriage(null));
        assertThrows(NullPointerException.class,
            () -> new Template.Pillar(null));
        assertThrows(NullPointerException.class,
            () -> new Template.Tunnel(null));
        assertThrows(NullPointerException.class,
            () -> new Template.Adjunct(null));
        assertThrows(NullPointerException.class,
            () -> new Template.Part(null));
    }

    @Test
    @DisplayName("Phase 2: every record returns a non-null store() with the right kind()")
    void phase2_storeIsBound() {
        assertTemplateBindings(new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD)), TemplateKind.CARRIAGE);
        assertTemplateBindings(new Template.Contents(games.brennan.dungeontrain.train.CarriageContents.of(
            games.brennan.dungeontrain.train.CarriageContents.ContentsType.DEFAULT)), TemplateKind.CONTENTS);
        assertTemplateBindings(new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR), TemplateKind.PART);
        assertTemplateBindings(new Template.Track(), TemplateKind.TRACK);
        assertTemplateBindings(new Template.Pillar(PillarSection.TOP), TemplateKind.PILLAR);
        assertTemplateBindings(new Template.Adjunct(PillarAdjunct.STAIRS), TemplateKind.STAIRS);
        assertTemplateBindings(new Template.Tunnel(TunnelVariant.SECTION), TemplateKind.TUNNEL);
    }

    private static void assertTemplateBindings(Template t, TemplateKind expected) {
        assertEquals(expected, t.store().kind(),
            "store().kind() must report the same kind as the template (" + t.id() + ")");
        assertEquals(expected, t.registry().kind(),
            "registry().kind() must report the same kind as the template (" + t.id() + ")");
    }
}
