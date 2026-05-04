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

    @Test
    @DisplayName("Phase 3: variantName() returns the template's name field for named records")
    void phase3_variantName() {
        // Carriage / Contents return the variant id (their constructor takes an id-bearing type).
        assertEquals("standard",
            new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD)).variantName());
        assertEquals("custom_thing",
            new Template.Carriage(CarriageVariant.custom("custom_thing")).variantName());

        // Track / Pillar / Adjunct / Tunnel all default to "default" via no-arg constructors.
        assertEquals("default", new Template.Track().variantName());
        assertEquals("default", new Template.Pillar(PillarSection.TOP).variantName());
        assertEquals("default", new Template.Adjunct(PillarAdjunct.STAIRS).variantName());
        assertEquals("default", new Template.Tunnel(TunnelVariant.SECTION).variantName());

        // Named overloads carry through.
        assertEquals("custom_pillar",
            new Template.Pillar(PillarSection.TOP, "custom_pillar").variantName());

        // Part defaults to "default" too; named Part returns its name.
        assertEquals("default",
            new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR).variantName());
        assertEquals("custom_floor",
            new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR, "custom_floor").variantName());
    }

    @Test
    @DisplayName("Phase 3: hasBundledTier() == canPromote() (default body keeps them in sync)")
    void phase3_hasBundledTierDefault() {
        // Custom carriage: no bundled tier (cannot promote).
        Template custom = new Template.Carriage(CarriageVariant.custom("c"));
        assertEquals(custom.canPromote(), custom.hasBundledTier());
        assertFalse(custom.hasBundledTier());

        // Built-in carriage: has bundled tier.
        Template standard = new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD));
        assertTrue(standard.hasBundledTier());

        // Contents and tunnel: no bundled tier today.
        assertFalse(new Template.Contents(games.brennan.dungeontrain.train.CarriageContents.of(
            games.brennan.dungeontrain.train.CarriageContents.ContentsType.DEFAULT)).hasBundledTier());
        assertFalse(new Template.Tunnel(TunnelVariant.SECTION).hasBundledTier());

        // Track / Pillar / Adjunct / Part: have a bundled tier.
        assertTrue(new Template.Track().hasBundledTier());
        assertTrue(new Template.Pillar(PillarSection.TOP).hasBundledTier());
        assertTrue(new Template.Adjunct(PillarAdjunct.STAIRS).hasBundledTier());
        assertTrue(new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR).hasBundledTier());
    }

    @Test
    @DisplayName("Phase 3: restampPlot() default no-op leaves Part untouched (smoke test)")
    void phase3_restampPlot_partIsNoOp() {
        // Part.restampPlot should fall through the default no-op without throwing —
        // exhaustive sealed-permits coverage means a future kind that forgets to
        // override can still call this safely. We pass nulls because the no-op
        // body never dereferences its args.
        Template.Part p = new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR);
        p.restampPlot(null, null); // must not throw
    }

    @Test
    @DisplayName("Phase 4 Goal 2: placeAt() default no-op — Track / Pillar / Adjunct don't override")
    void phase4_placeAt_defaultNoOp() {
        // Track / Pillar / Adjunct placement happens via TrackGenerator (outside
        // editor scope); their Template.placeAt falls through the default no-op.
        // Verify no exception when called with null world args (the default body
        // never dereferences them).
        new Template.Track().placeAt(null, null, null, PlaceContext.EMPTY);
        new Template.Pillar(PillarSection.TOP).placeAt(null, null, null, PlaceContext.EMPTY);
        new Template.Adjunct(PillarAdjunct.STAIRS).placeAt(null, null, null, PlaceContext.EMPTY);
    }

    @Test
    @DisplayName("Phase 4 Goal 2: PlaceContext convenience constructors")
    void phase4_placeContext() {
        assertEquals(0L, PlaceContext.EMPTY.seed());
        assertEquals(0, PlaceContext.EMPTY.carriageIndex());
        assertFalse(PlaceContext.EMPTY.mirrorX());

        PlaceContext partsCtx = PlaceContext.forParts(42L, 7);
        assertEquals(42L, partsCtx.seed());
        assertEquals(7, partsCtx.carriageIndex());
        assertFalse(partsCtx.mirrorX());

        PlaceContext portalCtx = PlaceContext.forPortal(true);
        assertEquals(0L, portalCtx.seed());
        assertEquals(0, portalCtx.carriageIndex());
        assertTrue(portalCtx.mirrorX());
    }

    @Test
    @DisplayName("Phase 4: plotSize() returns the per-kind footprint for SaveCommand.isPlotEmpty scan")
    void phase4_plotSize() {
        games.brennan.dungeontrain.train.CarriageDims dims =
            games.brennan.dungeontrain.train.CarriageDims.clamp(20, 7, 5);

        // Carriage / Contents == full carriage shell dims.
        assertEquals(new net.minecraft.core.Vec3i(20, 5, 7),
            new Template.Carriage(CarriageVariant.of(CarriageType.STANDARD)).plotSize(dims));
        assertEquals(new net.minecraft.core.Vec3i(20, 5, 7),
            new Template.Contents(games.brennan.dungeontrain.train.CarriageContents.of(
                games.brennan.dungeontrain.train.CarriageContents.ContentsType.DEFAULT)).plotSize(dims));

        // Track tile is fixed length × height × dims.width (4 × 2 × N).
        assertEquals(new net.minecraft.core.Vec3i(
                games.brennan.dungeontrain.track.TrackPlacer.TILE_LENGTH,
                games.brennan.dungeontrain.track.TrackPlacer.HEIGHT,
                dims.width()),
            new Template.Track().plotSize(dims));

        // Pillar is a 1-block-wide column at the section's height × dims.width.
        for (PillarSection section : PillarSection.values()) {
            assertEquals(new net.minecraft.core.Vec3i(1, section.height(), dims.width()),
                new Template.Pillar(section).plotSize(dims));
        }

        // Adjunct uses the per-adjunct fixed dims.
        assertEquals(new net.minecraft.core.Vec3i(
                PillarAdjunct.STAIRS.xSize(),
                PillarAdjunct.STAIRS.ySize(),
                PillarAdjunct.STAIRS.zSize()),
            new Template.Adjunct(PillarAdjunct.STAIRS).plotSize(dims));

        // Tunnel is fixed at LENGTH × HEIGHT × WIDTH for both section and portal.
        net.minecraft.core.Vec3i tunnelSize = new net.minecraft.core.Vec3i(
            games.brennan.dungeontrain.tunnel.TunnelPlacer.LENGTH,
            games.brennan.dungeontrain.tunnel.TunnelPlacer.HEIGHT,
            games.brennan.dungeontrain.tunnel.TunnelPlacer.WIDTH);
        assertEquals(tunnelSize, new Template.Tunnel(TunnelVariant.SECTION).plotSize(dims));
        assertEquals(tunnelSize, new Template.Tunnel(TunnelVariant.PORTAL).plotSize(dims));

        // Part delegates to CarriagePartKind.dims.
        Template.Part part = new Template.Part(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR);
        assertEquals(games.brennan.dungeontrain.train.CarriagePartKind.FLOOR.dims(dims),
            part.plotSize(dims));
    }
}
