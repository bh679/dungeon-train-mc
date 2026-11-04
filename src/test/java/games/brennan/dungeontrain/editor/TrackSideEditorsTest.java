package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The track-side editors answer exactly what the four static editors used to: the same snapshot
 * keys (or {@code editor unsaved} reads a baseline nobody wrote), the same footprints, the same
 * plots at the same origins, and nothing at all while Tracks is not the resident category.
 */
final class TrackSideEditorsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @BeforeEach
    void freshRegistry() {
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
        EditorStampedCategoryState.set(null, null);
    }

    @AfterEach
    void noResidentCategory() {
        EditorStampedCategoryState.set(null, null);
        TrackVariantRegistry.clear();
    }

    @Test
    @DisplayName("snapshot keys are the legacy spellings and the dirty scan's")
    void snapshotKeysUnchanged() {
        assertEquals(EditorPlotSnapshots.key("tracks", "track:x"), TrackSideEditors.tile().snapshotKey(new Template.Track("x")));
        assertEquals(EditorPlotSnapshots.key("tracks", "pillar_top:x"),
            TrackSideEditors.pillar(PillarSection.TOP).snapshotKey(new Template.Pillar(PillarSection.TOP, "x")));
        assertEquals(EditorPlotSnapshots.key("tracks", "adjunct_stairs:x"),
            TrackSideEditors.adjunct(PillarAdjunct.STAIRS).snapshotKey(new Template.Adjunct(PillarAdjunct.STAIRS, "x")));
        assertEquals(EditorPlotSnapshots.key("tracks", "tunnel_portal:x"),
            TrackSideEditors.tunnel(TunnelVariant.PORTAL).snapshotKey(new Template.Tunnel(TunnelVariant.PORTAL, "x")));
        for (TrackKind kind : TrackKind.values()) {
            if (kind == TrackKind.PORTAL_ROOM) continue;
            assertEquals(EditorDirtyCheck.snapshotKeyFor(kind, "x"), keyFor(kind, "x"), kind.name());
        }
    }

    @Test
    @DisplayName("the legacy static keys and the instance keys agree")
    void legacyDelegatesAgree() {
        assertEquals(TrackEditor.snapshotKey("x"), keyFor(TrackKind.TILE, "x"));
        assertEquals(PillarEditor.sectionSnapshotKey(PillarSection.MIDDLE, "x"), keyFor(TrackKind.PILLAR_MIDDLE, "x"));
        assertEquals(PillarEditor.adjunctSnapshotKey(PillarAdjunct.STAIRS_ENTRANCE, "x"), keyFor(TrackKind.ADJUNCT_STAIRS_ENTRANCE, "x"));
        assertEquals(TunnelEditor.tunnelSnapshotKey(TunnelVariant.SECTION, "x"), keyFor(TrackKind.TUNNEL_SECTION, "x"));
    }

    @Test
    @DisplayName("every editor's footprint is its record's plotSize and its origin is TrackSidePlots'")
    void sizeAndOriginMatchTheRecords() {
        for (TrackKind kind : TrackKind.values()) {
            if (kind == TrackKind.PORTAL_ROOM) continue;
            TrackSidePlotEditor<? extends Template> editor = TrackSideEditors.forKind(kind);
            assertSame(kind, editor.kind());
            assertEquals(kind.dims(DIMS), sizeOf(editor, "default"), kind.name());
            assertEquals(TrackSidePlots.plotOrigin(kind, "default", DIMS), originOf(editor, "default"), kind.name());
            assertEquals(kind.dims(DIMS), modelOf(editor, "default").plotSize(DIMS), kind.name() + " record plotSize");
        }
    }

    @Test
    @DisplayName("plotContaining answers only while Tracks is resident, then finds each kind's default plot")
    void plotContainingGatedOnResidency() {
        for (TrackKind kind : TrackKind.values()) {
            if (kind != TrackKind.PORTAL_ROOM) TrackVariantRegistry.register(kind, "default");
        }
        TrackSidePlotEditor<Template.Track> tile = TrackSideEditors.tile();
        BlockPos inside = TrackSidePlots.plotOrigin(TrackKind.TILE, "default", DIMS).offset(1, 1, 1);
        assertTrue(tile.plotContaining(inside, DIMS).isEmpty(), "no resident category");
        EditorStampedCategoryState.set(null, EditorCategory.CARRIAGES);
        assertTrue(tile.plotContaining(inside, DIMS).isEmpty(), "another category resident");

        EditorStampedCategoryState.set(null, EditorCategory.TRACKS);
        for (TrackKind kind : TrackKind.values()) {
            if (kind == TrackKind.PORTAL_ROOM) continue;
            TrackSidePlotEditor<? extends Template> editor = TrackSideEditors.forKind(kind);
            BlockPos probe = TrackSidePlots.plotOrigin(kind, "default", DIMS);
            Optional<? extends Template> hit = editor.plotContaining(probe, DIMS);
            assertTrue(hit.isPresent(), kind.name());
            assertEquals("default", hit.get().variantName(), kind.name());
        }
        // A second tile variant resolves to itself, not to default.
        TrackVariantRegistry.register(TrackKind.TILE, "wide");
        BlockPos wide = TrackSidePlots.plotOrigin(TrackKind.TILE, "wide", DIMS);
        assertEquals("wide", tile.plotContaining(wide, DIMS).map(Template.Track::name).orElse(null));
        assertEquals("wide", TrackEditor.resolveName(wide, DIMS));
    }

    @Test
    @DisplayName("editorFor resolves each track-side record to its kind's editor")
    void editorForResolvesTrackSideRecords() {
        assertSame(TrackSideEditors.tile(), PlotEditors.editorFor(new Template.Track()).orElseThrow());
        assertSame(TrackSideEditors.pillar(PillarSection.BOTTOM),
            PlotEditors.editorFor(new Template.Pillar(PillarSection.BOTTOM)).orElseThrow());
        assertSame(TrackSideEditors.tunnel(TunnelVariant.SECTION),
            PlotEditors.editorFor(new Template.Tunnel(TunnelVariant.SECTION)).orElseThrow());
    }

    private static <T extends Template> String keyFor(TrackKind kind, String name) {
        return keyOf(TrackSideEditors.forKind(kind), name);
    }

    private static <T extends Template> String keyOf(TrackSidePlotEditor<T> editor, String name) {
        return editor.snapshotKey(editor.model(name));
    }

    private static <T extends Template> net.minecraft.core.Vec3i sizeOf(TrackSidePlotEditor<T> editor, String name) {
        return editor.size(editor.model(name), DIMS);
    }

    private static <T extends Template> BlockPos originOf(TrackSidePlotEditor<T> editor, String name) {
        return editor.origin(editor.model(name), DIMS);
    }

    private static <T extends Template> T modelOf(TrackSidePlotEditor<T> editor, String name) {
        return editor.model(name);
    }
}
