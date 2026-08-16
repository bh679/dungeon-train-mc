package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderTrackPlot;
import games.brennan.dungeontrain.builder.BuilderTrackScene;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each kind of template declares around it.
 *
 * <p>One test per row of the specification, written as a statement of what that row <em>is</em>
 * rather than as a regression on how it came out — the table is the feature, so it is the thing
 * worth pinning. The invariants at the bottom are the properties the mechanisms this replaced each
 * failed at least once: a carriage appearing in a mode that parks none, a copy drawn over the build
 * it surrounds, and a second call returning something different from the first.</p>
 */
final class BuilderStructureNeedsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final String VARIANT = "standard";
    /** The default portal room, which the tiling budget lets reach the full window. */
    private static final Vec3i ROOM = new Vec3i(11, 7, 13);

    // ---- context builders, one per shape of build ----

    private static BuilderStructureNeeds.Context carriage(BuilderMode mode,
                                                          BuilderNewOptions.SubType subType,
                                                          int parked) {
        return new BuilderStructureNeeds.Context(mode, subType, null, "", DIMS, parked,
                null, null, VARIANT);
    }

    private static BuilderStructureNeeds.Context track(TrackKind kind) {
        return new BuilderStructureNeeds.Context(BuilderMode.TRACKS_TUNNELS, null, kind,
                "default", DIMS, 0, null, null, VARIANT);
    }

    private static BuilderStructureNeeds.Context room(PortalRoomMode roomMode) {
        return new BuilderStructureNeeds.Context(BuilderMode.TRAIN_DIMENSIONS, null, null,
                "hall", DIMS, 0, roomMode, ROOM, VARIANT);
    }

    // ---- readers ----

    private static Set<BuilderStructure.Category> categories(
            List<BuilderStructure.Placement> placements) {
        Set<BuilderStructure.Category> out = EnumSet.noneOf(BuilderStructure.Category.class);
        placements.forEach(p -> out.add(p.kind().category()));
        return out;
    }

    private static <T extends BuilderStructure.Kind> List<BuilderStructure.Placement> of(
            List<BuilderStructure.Placement> placements, Class<T> type) {
        return placements.stream().filter(p -> type.isInstance(p.kind())).collect(Collectors.toList());
    }

    // ---- Train Outside ----

    @Test
    @DisplayName("Train Outside needs flatbed ends and tracks")
    void trainOutsideNeedsFlatbedEndsAndTracks() {
        List<BuilderStructure.Placement> out = BuilderStructureNeeds.around(
                carriage(BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.WHOLE_CARRIAGE, 3));

        assertEquals(
                EnumSet.of(BuilderStructure.Category.FLATBED_ENDS, BuilderStructure.Category.TRACKS),
                categories(out));
        // Two caps, one either end — the silhouette a real group produces.
        assertEquals(2, of(out, BuilderStructure.Kind.FlatbedPad.class).size());
        // A full group is missing no slot, so nothing stands in for a carriage.
        assertTrue(of(out, BuilderStructure.Kind.Carriage.class).isEmpty());
    }

    @Test
    @DisplayName("Train Outside with one carriage parked also needs the other carriages")
    void trainOutsideSingleCarriageAlsoNeedsOtherCarriages() {
        List<BuilderStructure.Placement> out = BuilderStructureNeeds.around(
                carriage(BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.CARRIAGE_ROOM, 1));

        List<BuilderStructure.Placement> carriages = of(out, BuilderStructure.Kind.Carriage.class);
        assertEquals(2, carriages.size(), "the two slots the parked carriage does not fill");

        // Never on the parked carriage itself: a copy there would be drawn over the build.
        int parkedMinX = BuilderWorldLayout.trainStartX(1, DIMS);
        assertTrue(carriages.stream().noneMatch(p -> p.origin().getX() == parkedMinX));
        // And on the group's own grid, so they read as the run rather than as three loose boxes.
        assertTrue(carriages.stream()
                .allMatch(p -> Math.floorMod(p.origin().getX() - parkedMinX, DIMS.length()) == 0));
    }

    // ---- Carriage Inside ----

    @Test
    @DisplayName("Carriage Inside needs tracks and the train outsides around it")
    void insideCarriageNeedsTracksAndTrainOutsides() {
        List<BuilderStructure.Placement> out = BuilderStructureNeeds.around(
                carriage(BuilderMode.INSIDE_CARRIAGE, BuilderNewOptions.SubType.CARRIAGE_ROOM, 1));

        assertEquals(EnumSet.of(BuilderStructure.Category.TRACKS,
                        BuilderStructure.Category.CARRIAGE_SHELL,
                        BuilderStructure.Category.CARRIAGES,
                        BuilderStructure.Category.FLATBED_ENDS),
                categories(out));

        // The shell is the carriage you are standing in — exactly on the parked one, not beside it.
        List<BuilderStructure.Placement> shell = of(out, BuilderStructure.Kind.CarriageShell.class);
        assertEquals(1, shell.size());
        assertEquals(new BlockPos(BuilderWorldLayout.trainStartX(1, DIMS),
                BuilderWorldLayout.TRAIN_Y, 0), shell.get(0).origin());
    }

    @Test
    @DisplayName("A part is a piece of the shell, so no shell is declared around it")
    void insideCarriagePartsHasNoShell() {
        // Declaring one would draw the thing being edited on top of itself.
        List<BuilderStructure.Placement> out = BuilderStructureNeeds.around(
                carriage(BuilderMode.INSIDE_CARRIAGE, BuilderNewOptions.SubType.PARTS, 1));
        assertTrue(of(out, BuilderStructure.Kind.CarriageShell.class).isEmpty());
        // The rest of the run is still there — a part is judged in a train like anything else.
        assertFalse(of(out, BuilderStructure.Kind.Carriage.class).isEmpty());
    }

    // ---- Tracks ----

    @Test
    @DisplayName("Tracks need other tracks, and nothing else")
    void tracksNeedOtherTracksOnly() {
        List<BuilderStructure.Placement> out = BuilderStructureNeeds.around(track(TrackKind.TILE));

        assertEquals(EnumSet.of(BuilderStructure.Category.TRACKS), categories(out));
        // Up on the line, where a tile is authored — not on the ground corridor.
        assertEquals(BuilderTrackScene.bedY(), out.get(0).origin().getY());
    }

    // ---- Tunnels ----

    @Test
    @DisplayName("A tunnel section needs run on both sides, capped by portals, and one staircase")
    void tunnelSectionNeedsRunBothSidesCappedByPortals() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(track(TrackKind.TUNNEL_SECTION));

        assertEquals(EnumSet.of(BuilderStructure.Category.TRACKS, BuilderStructure.Category.TUNNELS,
                BuilderStructure.Category.STAIRS), categories(out));
        assertEquals(2, of(out, BuilderStructure.Kind.TunnelSection.class).size());
        assertEquals(2, of(out, BuilderStructure.Kind.TunnelPortal.class).size());
        assertEquals(1, of(out, BuilderStructure.Kind.StairsFlight.class).size(),
                "a single stairs going up");

        // One mouth each way: the far portal is mirrored, as the generator stamps an exit.
        Set<Boolean> mirrored = of(out, BuilderStructure.Kind.TunnelPortal.class).stream()
                .map(p -> ((BuilderStructure.Kind.TunnelPortal) p.kind()).mirrorX())
                .collect(Collectors.toSet());
        assertEquals(Set.of(false, true), mirrored);
    }

    @Test
    @DisplayName("A tunnel portal is a mouth, so it gets tunnel on one side only")
    void tunnelPortalNeedsRunOnOneSideOnly() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(track(TrackKind.TUNNEL_PORTAL));

        // Tunnel behind it, open air in front. Running tunnel both sides would draw the one thing
        // the piece exists to deny.
        assertTrue(of(out, BuilderStructure.Kind.TunnelPortal.class).isEmpty(),
                "a portal is not capped by another portal");
        List<BuilderStructure.Placement> sections =
                of(out, BuilderStructure.Kind.TunnelSection.class);
        assertEquals(2, sections.size());

        int plotMinX = BuilderTrackPlot.origin(TrackKind.TUNNEL_PORTAL, DIMS).getX();
        assertTrue(sections.stream().allMatch(p -> p.origin().getX() >= plotMinX + TunnelPlacer.LENGTH),
                "every section runs away from the mouth on +X");
    }

    // ---- Pillars ----

    @Test
    @DisplayName("Pillars need tracks higher up and other pillars")
    void pillarsNeedElevatedTracksAndOtherPillars() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(track(TrackKind.PILLAR_MIDDLE));

        assertEquals(EnumSet.of(BuilderStructure.Category.TRACKS, BuilderStructure.Category.PILLARS),
                categories(out));

        // "Higher up" is the whole point: a column is only legible against the line it holds.
        List<BuilderStructure.Placement> line = of(out, BuilderStructure.Kind.TrackRun.class);
        assertEquals(BuilderTrackScene.bedY(), line.get(0).origin().getY());
        assertTrue(BuilderTrackScene.bedY() > BuilderWorldLayout.Y_STAND);

        // More than one column, at the generator's own spacing — one column shows no pattern.
        List<BuilderStructure.Placement> columns = of(out, BuilderStructure.Kind.PillarColumn.class);
        assertTrue(columns.size() > 1, "neighbouring columns at their real spacing");
        assertTrue(columns.stream()
                .allMatch(p -> p.origin().getY() == BuilderTrackScene.groundY()),
                "every column stands on the ground");
    }

    // ---- Stairs ----

    @Test
    @DisplayName("Stairs need tracks higher up and pillars, plus the rest of their own flight")
    void stairsNeedElevatedTracksAndPillars() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(track(TrackKind.ADJUNCT_STAIRS));

        assertEquals(EnumSet.of(BuilderStructure.Category.TRACKS, BuilderStructure.Category.PILLARS,
                BuilderStructure.Category.STAIRS), categories(out));

        // A flight taller than one stamp: a single stamp is a staircase ending in mid-air.
        BuilderStructure.Kind.StairsFlight flight = (BuilderStructure.Kind.StairsFlight)
                of(out, BuilderStructure.Kind.StairsFlight.class).get(0).kind();
        assertTrue(flight.height() > BuilderStructureNeeds.stairsStampHeight());
    }

    @Test
    @DisplayName("A stairs entrance is judged against the ground line it climbs out of")
    void stairsEntranceGetsTheGroundScene() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(track(TrackKind.ADJUNCT_STAIRS_ENTRANCE));

        // Not the elevated line. An entrance caps a shaft out of a line that runs *underground*, so
        // previewing it against a bridge would be previewing the wrong thing entirely.
        List<BuilderStructure.Placement> line = of(out, BuilderStructure.Kind.TrackRun.class);
        assertEquals(1, line.size());
        assertNotEquals(BuilderTrackScene.bedY(), line.get(0).origin().getY());
        assertEquals(1, of(out, BuilderStructure.Kind.StairsFlight.class).size());
        assertTrue(of(out, BuilderStructure.Kind.PillarColumn.class).isEmpty(),
                "there is no column to stand under an underground line");
    }

    // ---- Dimensional Carriages ----

    @Test
    @DisplayName("A repeating room tiles into copies that fade out at the edge of the window")
    void dimensionalCarriagesTileAndFadeOut() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(room(PortalRoomMode.ENDLESS_REPETITION));

        assertEquals(EnumSet.of(BuilderStructure.Category.ROOM_TILING), categories(out));
        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(ROOM);
        // The room itself is real blocks, never a copy of one.
        assertTrue(out.stream().noneMatch(p -> p.origin().equals(origin)));
        // Every copy lands on the tile grid.
        assertTrue(out.stream().allMatch(p ->
                Math.floorMod(p.origin().getX() - origin.getX(), ROOM.getX()) == 0
                        && Math.floorMod(p.origin().getZ() - origin.getZ(), ROOM.getZ()) == 0));

        // The ring you can touch is drawn cell-for-cell; past it, only the silhouette.
        int radius = BuilderStructureNeeds.tilingRadius(ROOM);
        assertTrue(radius >= 2, "a default room reaches past the first ring");
        assertEquals(BuilderStructure.Detail.FULL, detailAt(out, origin, ROOM, 1, 0));
        assertEquals(BuilderStructure.Detail.SHELL, detailAt(out, origin, ROOM, radius, 0));

        // And it thins out rather than ending at a line the real space does not have.
        assertTrue(BuilderStructureNeeds.fadeFor(radius, radius)
                < BuilderStructureNeeds.fadeFor(1, radius));
    }

    @Test
    @DisplayName("Endless Open tiles as floor and ceiling, because it has no walls to draw")
    void endlessOpenTilesAsPlanes() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(room(PortalRoomMode.ENDLESS_OPEN));

        assertFalse(out.isEmpty());
        assertTrue(out.stream().allMatch(p ->
                ((BuilderStructure.Kind.RoomTile) p.kind()).detail()
                        == BuilderStructure.Detail.PLANES));
    }

    @Test
    @DisplayName("A Bedrock Lock room declares its boundary and no tiling")
    void bedrockLockDeclaresItsBoundary() {
        List<BuilderStructure.Placement> out =
                BuilderStructureNeeds.around(room(PortalRoomMode.BEDROCK_LOCK));

        assertEquals(EnumSet.of(BuilderStructure.Category.ROOM_BEDROCK), categories(out));
        assertEquals(BuilderWorldLayout.portalRoomOrigin(ROOM), out.get(0).origin());
    }

    @Test
    @DisplayName("A Bedrockless room declares nothing, which is what it says about itself")
    void bedrocklessDeclaresNothing() {
        // Its boundary is *nothing at all* for a clearance either side. Declaring a skin or a copy
        // would contradict the one claim the mode makes.
        assertTrue(BuilderStructureNeeds.around(room(PortalRoomMode.BEDROCKLESS)).isEmpty());
    }

    // ---- invariants ----

    @Test
    @DisplayName("No track or room build ever declares a carriage")
    void noTrackOrRoomBuildDeclaresACarriage() {
        // The mechanism this replaces kept a captured copy of what it had lifted, so making the
        // scenery solid in Tracks & Tunnels stood a *carriage* up in the middle of a track build.
        Set<BuilderStructure.Category> train = EnumSet.of(BuilderStructure.Category.CARRIAGES,
                BuilderStructure.Category.CARRIAGE_SHELL, BuilderStructure.Category.FLATBED_ENDS);

        for (TrackKind kind : TrackKind.values()) {
            Set<BuilderStructure.Category> got = categories(BuilderStructureNeeds.around(track(kind)));
            got.retainAll(train);
            assertTrue(got.isEmpty(), kind.id() + " declared part of a train");
        }
        for (PortalRoomMode roomMode : PortalRoomMode.values()) {
            Set<BuilderStructure.Category> got = categories(BuilderStructureNeeds.around(room(roomMode)));
            got.retainAll(train);
            assertTrue(got.isEmpty(), roomMode.id() + " declared part of a train");
        }
    }

    @Test
    @DisplayName("Recomputing gives the same answer, so a second pass cannot destroy the first")
    void recomputingIsIdempotent() {
        // The old mechanism read the world to find its surroundings, so a second read found what the
        // first had taken and replaced the only copy with an empty one — the carriage you stood in
        // could never get its walls back. A pure function of the context cannot do that.
        List<BuilderStructureNeeds.Context> contexts = List.of(
                carriage(BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.CARRIAGE_ROOM, 1),
                carriage(BuilderMode.INSIDE_CARRIAGE, BuilderNewOptions.SubType.CARRIAGE_ROOM, 1),
                track(TrackKind.TILE), track(TrackKind.TUNNEL_SECTION), track(TrackKind.PILLAR_TOP),
                room(PortalRoomMode.ENDLESS_REPETITION), room(PortalRoomMode.BEDROCK_LOCK));

        for (BuilderStructureNeeds.Context ctx : contexts) {
            assertEquals(BuilderStructureNeeds.around(ctx), BuilderStructureNeeds.around(ctx));
        }
    }

    @Test
    @DisplayName("A null or half-built context declares nothing rather than guessing")
    void anEmptyContextDeclaresNothing() {
        assertTrue(BuilderStructureNeeds.around(null).isEmpty());
        assertTrue(BuilderStructureNeeds.around(new BuilderStructureNeeds.Context(
                null, null, null, "", DIMS, 0, null, null, VARIANT)).isEmpty());
        // A track mode with nothing on the plot yet: open, but not holding anything to surround.
        assertTrue(BuilderStructureNeeds.around(track(null)).isEmpty());
        // A room mode with no room open.
        assertTrue(BuilderStructureNeeds.around(room(null)).isEmpty());
    }

    @Test
    @DisplayName("The carriage shell is exactly the complement of what a carriage room saves")
    void theShellIsTheComplementOfWhatARoomSaves() {
        // Silent and bad in both directions if this drifts: drawing the shell would start showing
        // blocks somebody placed in their room, and standing it up would start overwriting them.
        Vec3i interior = CarriageContentsPlacer.interiorSize(DIMS);
        BlockPos interiorOrigin = CarriageContentsPlacer.interiorOrigin(BlockPos.ZERO);

        for (int x = 0; x < DIMS.length(); x++) {
            for (int y = 0; y < DIMS.height(); y++) {
                for (int z = 0; z < DIMS.width(); z++) {
                    boolean saved = x >= interiorOrigin.getX() && x < interiorOrigin.getX() + interior.getX()
                            && y >= interiorOrigin.getY() && y < interiorOrigin.getY() + interior.getY()
                            && z >= interiorOrigin.getZ() && z < interiorOrigin.getZ() + interior.getZ();
                    assertNotEquals(saved,
                            BuilderStructure.onSkin(x, y, z, DIMS.length(), DIMS.height(), DIMS.width()),
                            "cell " + x + "," + y + "," + z + " is both shell and contents");
                }
            }
        }
    }

    @Test
    @DisplayName("Categories are stable, so the later per-category controls have something to key on")
    void everyKindReportsACategory() {
        for (BuilderStructureNeeds.Context ctx : List.of(
                carriage(BuilderMode.INSIDE_CARRIAGE, BuilderNewOptions.SubType.CARRIAGE_ROOM, 1),
                track(TrackKind.TUNNEL_SECTION), track(TrackKind.ADJUNCT_STAIRS),
                room(PortalRoomMode.ENDLESS_REPETITION))) {
            BuilderStructureNeeds.around(ctx)
                    .forEach(p -> assertTrue(p.kind().category() != null, "no category"));
        }
    }

    /** The detail of the copy {@code (tx,tz)} tiles away, for reading the ring rule back out. */
    private static BuilderStructure.Detail detailAt(List<BuilderStructure.Placement> out,
                                                    BlockPos origin, Vec3i size, int tx, int tz) {
        BlockPos want = origin.offset(tx * size.getX(), 0, tz * size.getZ());
        return out.stream()
                .filter(p -> p.origin().equals(want))
                .map(p -> ((BuilderStructure.Kind.RoomTile) p.kind()).detail())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no copy at tile " + tx + "," + tz));
    }
}
