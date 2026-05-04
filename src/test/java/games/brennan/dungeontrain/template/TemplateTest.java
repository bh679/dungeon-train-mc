package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Template} id / displayName / metadata across all permittees. */
final class TemplateTest {

    @Test
    @DisplayName("CarriageModel id matches variant id")
    void carriage_id() {
        Template.CarriageModel model = new Template.CarriageModel(CarriageVariant.of(CarriageType.STANDARD));
        assertEquals("standard", model.id());
        assertEquals("standard", model.displayName());
        assertEquals(TemplateKind.CARRIAGE, model.kind());
        assertTrue(model.isBuiltin());
        assertTrue(model.canPromote());
        assertEquals(CarriageType.STANDARD, model.type().orElseThrow());
    }

    @Test
    @DisplayName("CarriageModel preserves custom variant names; customs are not built-in and cannot promote")
    void carriage_customName() {
        Template.CarriageModel model = new Template.CarriageModel(CarriageVariant.custom("my_carriage"));
        assertEquals("my_carriage", model.id());
        assertFalse(model.isBuiltin());
        assertFalse(model.canPromote());
        assertTrue(model.type().isEmpty());
    }

    @Test
    @DisplayName("PillarModel id has pillar_ prefix; type returns the section")
    void pillar_id() {
        assertEquals("pillar_top", new Template.PillarModel(PillarSection.TOP).id());
        assertEquals("pillar_middle", new Template.PillarModel(PillarSection.MIDDLE).id());
        assertEquals("pillar_bottom", new Template.PillarModel(PillarSection.BOTTOM).id());
        Template.PillarModel m = new Template.PillarModel(PillarSection.TOP);
        assertEquals(TemplateKind.PILLAR, m.kind());
        assertEquals(PillarSection.TOP, m.type().orElseThrow());
    }

    @Test
    @DisplayName("TunnelModel id has tunnel_ prefix; tunnel cannot promote (no bundled tier)")
    void tunnel_id() {
        Template.TunnelModel section = new Template.TunnelModel(TunnelVariant.SECTION);
        assertEquals("tunnel_section", section.id());
        assertEquals("tunnel_portal", new Template.TunnelModel(TunnelVariant.PORTAL).id());
        assertEquals(TemplateKind.TUNNEL, section.kind());
        assertEquals(TunnelVariant.SECTION, section.type().orElseThrow());
        assertFalse(section.canPromote());
    }

    @Test
    @DisplayName("AdjunctModel id has adjunct_ prefix; displayName uses the adjunct id; kind() = STAIRS")
    void adjunct_id() {
        Template.AdjunctModel m = new Template.AdjunctModel(PillarAdjunct.STAIRS);
        assertEquals("adjunct_stairs", m.id());
        assertEquals("stairs / default", m.displayName());
        assertEquals(TemplateKind.STAIRS, m.kind());
        assertEquals(PillarAdjunct.STAIRS, m.type().orElseThrow());

        Template.AdjunctModel named =
            new Template.AdjunctModel(PillarAdjunct.STAIRS, "carved");
        assertEquals("stairs / carved", named.displayName());
        assertFalse(named.isBuiltin());
    }

    @Test
    @DisplayName("TrackModel id is the fixed 'track' token; no sub-type today")
    void track_id() {
        Template.TrackModel t = new Template.TrackModel();
        assertEquals("track", t.id());
        assertEquals(TemplateKind.TRACK, t.kind());
        assertTrue(t.type().isEmpty());
        assertTrue(t.isBuiltin());
        assertTrue(t.canPromote());
    }

    @Test
    @DisplayName("PartModel id has part_ prefix; type returns the part kind")
    void part_id() {
        Template.PartModel m = new Template.PartModel(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR);
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
            () -> new Template.CarriageModel(null));
        assertThrows(NullPointerException.class,
            () -> new Template.PillarModel(null));
        assertThrows(NullPointerException.class,
            () -> new Template.TunnelModel(null));
        assertThrows(NullPointerException.class,
            () -> new Template.AdjunctModel(null));
        assertThrows(NullPointerException.class,
            () -> new Template.PartModel(null));
    }

    @Test
    @DisplayName("Phase 2: every record returns a non-null store() with the right kind()")
    void phase2_storeIsBound() {
        assertTemplateBindings(new Template.CarriageModel(CarriageVariant.of(CarriageType.STANDARD)), TemplateKind.CARRIAGE);
        assertTemplateBindings(new Template.ContentsModel(games.brennan.dungeontrain.train.CarriageContents.of(
            games.brennan.dungeontrain.train.CarriageContents.ContentsType.DEFAULT)), TemplateKind.CONTENTS);
        assertTemplateBindings(new Template.PartModel(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR), TemplateKind.PART);
        assertTemplateBindings(new Template.TrackModel(), TemplateKind.TRACK);
        assertTemplateBindings(new Template.PillarModel(PillarSection.TOP), TemplateKind.PILLAR);
        assertTemplateBindings(new Template.AdjunctModel(PillarAdjunct.STAIRS), TemplateKind.STAIRS);
        assertTemplateBindings(new Template.TunnelModel(TunnelVariant.SECTION), TemplateKind.TUNNEL);
    }

    private static void assertTemplateBindings(Template t, TemplateKind expected) {
        assertEquals(expected, t.store().kind(),
            "store().kind() must report the same kind as the template (" + t.id() + ")");
        assertEquals(expected, t.registry().kind(),
            "registry().kind() must report the same kind as the template (" + t.id() + ")");
    }
}
