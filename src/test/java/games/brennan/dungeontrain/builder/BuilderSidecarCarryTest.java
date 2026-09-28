package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The coordinate and naming arithmetic that decides where a builder's authored variant pools and
 * container contents land on the template a save writes.
 *
 * <p>Both are pure, and both are the kind of thing that fails silently: a wrong offset puts a pool
 * one block off in a template nobody re-opens for a month, and a wrong key writes a document under
 * a filename the placer never reads (which is exactly what
 * {@code ContainerContentsStore.trackPlotKey} exists to have fixed once already). The file-backed
 * halves need a server level, so they are covered by the in-game pass instead.</p>
 */
final class BuilderSidecarCarryTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("A template that IS the build volume needs no shift")
    void wholeVolumeKindsHaveNoOffset() {
        assertEquals(Vec3i.ZERO, BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.CARRIAGE, null, DIMS));
        assertEquals(Vec3i.ZERO, BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.TRACK, null, DIMS));
        assertEquals(Vec3i.ZERO, BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, null, DIMS));
        // A group is several volumes and no sidecar; zero is the harmless answer, not a claim.
        assertEquals(Vec3i.ZERO, BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, null, DIMS));
    }

    @Test
    @DisplayName("A carriage room starts one block inside the shell, on every axis")
    void contentsOffsetIsTheInteriorCorner() {
        assertEquals(new Vec3i(1, 1, 1),
                BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.CONTENTS, null, DIMS));
    }

    @Test
    @DisplayName("A part's offset is its first placement — the unmirrored master copy Save captures")
    void partOffsetIsTheFirstPlacement() {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            if (kind.placements(DIMS).isEmpty()) continue;
            assertEquals(kind.placements(DIMS).get(0).originOffset(),
                    BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.PART, kind, DIMS),
                    "wrong offset for part kind " + kind.id());
        }
    }

    @Test
    @DisplayName("A part with no kind falls back to no shift rather than throwing mid-save")
    void partWithoutKindDoesNotThrow() {
        assertEquals(Vec3i.ZERO, BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.PART, null, DIMS));
    }

    @Test
    @DisplayName("An open names the same plot key the matching save writes to")
    void openAndSaveAgreeOnTheKey() {
        assertEquals(BlockVariantPlot.carriageKey("desert"),
                new BuilderOpenRequest(BuilderPhotoPaths.Kind.CARRIAGE, "desert", null).templatePlotKey());
        assertEquals(BlockVariantPlot.contentsKey("mess_hall"),
                new BuilderOpenRequest(BuilderPhotoPaths.Kind.CONTENTS, "mess_hall", null).templatePlotKey());
        assertEquals(BlockVariantPlot.partKey(CarriagePartKind.DOORS, "slatted"),
                new BuilderOpenRequest(BuilderPhotoPaths.Kind.PART, "slatted", CarriagePartKind.DOORS)
                        .templatePlotKey());
        assertEquals(BlockVariantPlot.trackKey(TrackKind.TUNNEL_SECTION, "brick"),
                BuilderOpenRequest.forTrack(TrackKind.TUNNEL_SECTION, "brick").orElseThrow()
                        .templatePlotKey());
        assertEquals(BlockVariantPlot.trackKey(TrackKind.PORTAL_ROOM, "library"),
                BuilderOpenRequest.forPortalRoom("library").templatePlotKey());
    }

    @Test
    @DisplayName("A carriage group has no sidecar of its own to seed from")
    void carriageGroupHasNoPlotKey() {
        assertNull(new BuilderOpenRequest(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "run", null)
                .templatePlotKey());
    }

    @Test
    @DisplayName("A part id is only unique within its kind, and the key says so")
    void partKeysAreScopedByKind() {
        assertNotEquals(BlockVariantPlot.partKey(CarriagePartKind.FLOOR, "standard"),
                BlockVariantPlot.partKey(CarriagePartKind.DOORS, "standard"));
    }

    @Test
    @DisplayName("The four key formats are the ones resolveByKey parses")
    void keyFormatsAreStable() {
        assertEquals("carriage:desert", BlockVariantPlot.carriageKey("desert"));
        assertEquals("contents:mess_hall", BlockVariantPlot.contentsKey("mess_hall"));
        assertEquals("part:doors:slatted", BlockVariantPlot.partKey(CarriagePartKind.DOORS, "slatted"));
        assertEquals("track:portal_room:library", BlockVariantPlot.trackKey(TrackKind.PORTAL_ROOM, "library"));
    }
}
