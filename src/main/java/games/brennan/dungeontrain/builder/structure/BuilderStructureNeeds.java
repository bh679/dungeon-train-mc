package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderTrackPlot;
import games.brennan.dungeontrain.builder.BuilderTrackScene;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Which structures each kind of template needs around it, and where they go.
 *
 * <p><b>This is the table.</b> One method per kind of thing you can open, named for it, in the order
 * a builder meets them — so the file reads as the answer to "what does this template need?" rather
 * than as an implementation of a renderer. Everything else in this package serves this list: the
 * client draws it, the server can stand it up, and the pause-menu control decides which.</p>
 *
 * <table border="1">
 *   <caption>What each template needs</caption>
 *   <tr><th>Open template</th><th>Needs</th></tr>
 *   <tr><td>Train Outside</td><td>flatbed ends, tracks</td></tr>
 *   <tr><td>Train Outside — one carriage</td><td>…and the other carriages</td></tr>
 *   <tr><td>Carriage Inside</td><td>tracks, the shell around you, the rest of the train</td></tr>
 *   <tr><td>Tracks</td><td>other tracks</td></tr>
 *   <tr><td>Tunnels</td><td>tracks, tunnels either side (one side for a portal), one staircase</td></tr>
 *   <tr><td>Pillars</td><td>tracks higher up, other pillars</td></tr>
 *   <tr><td>Stairs</td><td>tracks higher up, pillars</td></tr>
 *   <tr><td>Dimensional Carriages</td><td>the tiling, the bedrock boundary</td></tr>
 * </table>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>The template.</b> Whatever is open is the build, and a structure that overlapped it would be
 * drawn over real blocks and — in Solid — written over work somebody had just done. The one surround
 * that shares a build's volume, {@link BuilderStructure.Kind.CarriageShell}, is the exact complement
 * of the box a carriage room saves ({@link BuilderStructure#onSkin}). Everything else is kept out by
 * consumers filtering on the build volumes, which is one rule in two places rather than a hole cut
 * in every arm below.</p>
 *
 * <p><b>The platform.</b> That is {@link BuilderEnvironment} — you stand on it while deciding what
 * the structures should be, so it cannot be something the structure control can take away.</p>
 *
 * <h2>Recomputed, never stored</h2>
 *
 * <p>Everything {@link #around} needs is already known to both sides, so it is a pure function of
 * {@link Context} called on demand. That makes a second call idempotent, which is the property that
 * kills the bug class the mechanisms this replaces all shared: they kept a captured copy of the
 * blocks they had lifted, and reading the world a second time found what the first read had taken.</p>
 */
public final class BuilderStructureNeeds {

    /**
     * How far either side of the open piece the line and its columns are drawn.
     *
     * <p>Enough for a couple of columns and the arch between them at the scene's own spacing, which
     * is what makes the pattern legible; past that it is cells nobody is looking at.</p>
     */
    public static final int SCENE_RADIUS_X = 64;

    private BuilderStructureNeeds() {}

    /**
     * Everything the answer depends on, gathered so {@link #around} can stay pure.
     *
     * <p>Both sides can fill this in — the server from {@code DungeonTrainWorldData}, the client from
     * what {@code BuilderBoundsPacket} already tells it — and both running the same function is what
     * makes them agree without a packet keeping them in step.</p>
     *
     * @param subType   what is being authored, for the modes that have sub types; null otherwise
     * @param trackKind which track-side kind is on the plot; null for a carriage or room build
     * @param buildName the open template's name — the room arms resolve their copies through it
     * @param parked    how many carriages are actually standing on the track
     * @param roomMode  what the open room does at its walls; null when no room is open
     * @param roomSize  the open room's size; null when no room is open
     * @param variantId the carriage variant the world was stamped from
     */
    public record Context(BuilderMode mode, BuilderNewOptions.SubType subType, TrackKind trackKind,
                          String buildName, CarriageDims dims, int parked,
                          PortalRoomMode roomMode, Vec3i roomSize, String variantId) {}

    /**
     * What surrounds this build. Empty is an ordinary answer, not a failure.
     *
     * <p><b>The order is load-bearing where two structures overlap: later wins.</b> It is the
     * generator's own order — columns and their arch, then the line laid over them, then the
     * staircases, then the tunnel around all of it — and it decides who owns a cell at the seams,
     * of which the arch meeting the bed is the obvious one. Both consumers honour it, so a ghosted
     * seam and a solid seam are the same blocks; a consumer that iterated in any other order would
     * show a scene the world would never build, and would show a <em>different</em> one from the
     * other consumer.</p>
     */
    public static List<BuilderStructure.Placement> around(Context ctx) {
        if (ctx == null || ctx.mode() == null || ctx.dims() == null) {
            return List.of();
        }
        List<BuilderStructure.Placement> out = new ArrayList<>();
        switch (ctx.mode()) {
            case TRAIN_OUTSIDE -> trainOutside(out, ctx);
            case INSIDE_CARRIAGE -> insideCarriage(out, ctx);
            case TRACKS_TUNNELS -> trackSide(out, ctx);
            case TRAIN_DIMENSIONS -> dimensionalCarriages(out, ctx);
        }
        return List.copyOf(out);
    }

    // ---- Train Outside ----

    /**
     * <b>Flatbed ends and tracks</b>, and — when only one carriage is parked — <b>the other
     * carriages</b>.
     *
     * <p>What you are judging from the platform is a silhouette, and a silhouette needs the run it
     * belongs to. Opening a whole carriage parks the full group, so nothing is missing but the caps;
     * opening a room or a part parks one, because one template is one carriage, and then the slots
     * either side have to be declared or the build reads as a train of exactly one.</p>
     */
    private static void trainOutside(List<BuilderStructure.Placement> out, Context ctx) {
        groundTrack(out, ctx.dims());
        carriageGroup(out, ctx);
    }

    // ---- Carriage Inside ----

    /**
     * <b>Tracks, and the train outsides</b> — both the shell immediately around you and the rest of
     * the run beyond it.
     *
     * <p>A train reads the same from either side of its wall: from outside you look along it, from
     * inside you look out of the middle of it. So the group here is the same group Train Outside
     * declares, and the addition is the shell you are standing in.</p>
     *
     * <p>The shell is declared for a <b>carriage room</b> and not for a <b>part</b>. A part
     * <em>is</em> a piece of the shell, so there the shell is the build — declaring it would draw the
     * thing being edited on top of itself.</p>
     */
    private static void insideCarriage(List<BuilderStructure.Placement> out, Context ctx) {
        groundTrack(out, ctx.dims());
        carriageGroup(out, ctx);

        if (ctx.subType() != BuilderNewOptions.SubType.CARRIAGE_ROOM || ctx.parked() != 1) {
            return;
        }
        out.add(new BuilderStructure.Placement(
                new BuilderStructure.Kind.CarriageShell(ctx.variantId()),
                new BlockPos(BuilderWorldLayout.trainStartX(1, ctx.dims()),
                        BuilderWorldLayout.TRAIN_Y, 0)));
    }

    // ---- Tracks & Tunnels ----

    /** Route to the arm for whichever track-side kind is on the plot. */
    private static void trackSide(List<BuilderStructure.Placement> out, Context ctx) {
        TrackKind kind = ctx.trackKind();
        if (kind == null) {
            return;   // the mode is open but nothing is on the plot yet
        }
        switch (kind) {
            case TILE -> tracks(out, ctx);
            case TUNNEL_SECTION, TUNNEL_PORTAL -> tunnels(out, ctx, kind);
            case PILLAR_TOP, PILLAR_MIDDLE, PILLAR_BOTTOM -> pillars(out, ctx);
            case ADJUNCT_STAIRS -> stairs(out, ctx);
            case ADJUNCT_STAIRS_ENTRANCE -> stairsEntrance(out, ctx);
            // Not authored on a track plot at all — Train Dimensions opens it as its own volume, and
            // this mode never puts one up. Asking BuilderTrackPlot for its origin would throw.
            case PORTAL_ROOM -> { }
        }
    }

    /**
     * <b>Other tracks.</b>
     *
     * <p>The whole question a tile answers is whether it joins the next one, so the run either side
     * of it is the entire structure it needs. Each tile in the run rolls its own variant the way the
     * generator rolls it — a line of one repeated tile would preview something the game never
     * builds.</p>
     */
    private static void tracks(List<BuilderStructure.Placement> out, Context ctx) {
        elevatedTrack(out, ctx.dims());
    }

    /**
     * <b>Tracks, tunnels either side, and one staircase going up.</b>
     *
     * <p>A tunnel is only ever seen as part of a run, and what you are judging is the seam between
     * consecutive sections and the mouth you come out of. So a <b>section</b> gets
     * {@code portal | section | you | section | portal} — run on both sides, capped either end.</p>
     *
     * <p>A <b>portal</b> gets tunnel on <em>one</em> side, because that is what a portal is: the
     * mouth. Tunnel behind it, open air in front. Running tunnel both sides would draw the one thing
     * the piece exists to deny.</p>
     */
    private static void tunnels(List<BuilderStructure.Placement> out, Context ctx, TrackKind open) {
        CarriageDims dims = ctx.dims();
        elevatedTrack(out, dims);
        stairsFlight(out, dims);

        TrackGeometry g = BuilderTrackScene.geometry(dims);
        TunnelGeometry tg = TunnelGeometry.from(g);
        int y = tg.floorY();
        int z = tg.wallMinZ() + 1;
        int len = TunnelPlacer.LENGTH;
        int plotMinX = BuilderTrackPlot.origin(open, dims).getX();

        if (open == TrackKind.TUNNEL_PORTAL) {
            // Behind the mouth only. The plot is the entrance portal, which the generator stamps
            // unmirrored at the low-X end of a run, so the tunnel runs away from it on +X.
            tunnelSection(out, plotMinX + len, y, z);
            tunnelSection(out, plotMinX + 2 * len, y, z);
        } else {
            tunnelSection(out, plotMinX - len, y, z);
            tunnelSection(out, plotMinX + len, y, z);
            // Entrance at the low-X mouth, exit at the high-X one — mirrored, as the generator has it.
            tunnelPortal(out, plotMinX - 2 * len, y, z, false);
            tunnelPortal(out, plotMinX + 2 * len, y, z, true);
        }
    }

    /**
     * <b>Tracks higher up, and other pillars.</b>
     *
     * <p>A pillar section is a slab until you can see the column it belongs to, its neighbours at
     * their real spacing, and the arch reaching between them. "Other pillars" therefore includes the
     * rest of the column the open section is part of — a middle on its own tells you nothing about
     * how it meets the top — with the section itself removed by the build-volume filter.</p>
     */
    private static void pillars(List<BuilderStructure.Placement> out, Context ctx) {
        pillarColumns(out, ctx.dims());
        elevatedTrack(out, ctx.dims());
    }

    /**
     * <b>Tracks higher up, and pillars</b> — plus the rest of the flight the open stamp is one step
     * of.
     *
     * <p>A staircase is one 8-tall template stamped over and over from deck height down to the
     * ground; a single stamp on its own is a flight ending in mid-air, which is not a thing you can
     * judge. The open stamp's own rows come out again with the build volume.</p>
     */
    private static void stairs(List<BuilderStructure.Placement> out, Context ctx) {
        pillarColumns(out, ctx.dims());
        elevatedTrack(out, ctx.dims());
        stairsFlight(out, ctx.dims());
    }

    /**
     * A stairs <em>entrance</em> is the one track-side piece that does not belong to the elevated
     * line: it caps a shaft climbing out of a line that runs underground.
     *
     * <p>So it gets the ground-level version of the Stairs row — the corridor where the line actually
     * is, and the flight climbing out of it — rather than a bridge it would never be built on.</p>
     */
    private static void stairsEntrance(List<BuilderStructure.Placement> out, Context ctx) {
        CarriageDims dims = ctx.dims();
        groundTrack(out, dims);

        int centreX = BuilderTrackPlot.editedColumnCentreX();
        int floorY = BuilderTrackScene.shaftFloorY(dims);
        int topY = BuilderTrackScene.shaftTopY(dims);
        out.add(new BuilderStructure.Placement(
                new BuilderStructure.Kind.StairsFlight(centreX, topY - floorY + 1),
                new BlockPos(BuilderTrackScene.shaftMinX(centreX), floorY,
                        BuilderTrackScene.shaftMinZ(dims))));
    }

    // ---- Dimensional Carriages ----

    /**
     * <b>The tiling, and the bedrock.</b>
     *
     * <p>A room's mode is the coarsest thing about it and the least visible: a Bedrock Lock room and
     * a Repeating room are stamped identically here and behave nothing alike in a run. Declaring the
     * boundary the mode implies is the only way that difference is on screen at all.</p>
     *
     * <p>The window is the one the game will actually build — {@link PortalRoomTiling}'s own budget,
     * not a number chosen here — because a preview that tiled further than the real thing would be a
     * confident lie about how far the space goes. It fades out at the last ring for the same reason:
     * the real space ends in fog, not at a line.</p>
     */
    private static void dimensionalCarriages(List<BuilderStructure.Placement> out, Context ctx) {
        PortalRoomMode roomMode = ctx.roomMode();
        Vec3i size = ctx.roomSize();
        String name = ctx.buildName();
        if (roomMode == null || size == null || name == null || name.isEmpty()
                || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return;
        }
        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(size);

        if (!roomMode.tiles()) {
            // Bedrockless is the mode whose boundary is *nothing at all* for a clearance either side.
            // Declaring anything for it would contradict the one thing it says about itself.
            if (!roomMode.clearsSurroundings()) {
                out.add(new BuilderStructure.Placement(
                        new BuilderStructure.Kind.RoomBedrock(name), origin));
            }
            return;
        }

        int radius = tilingRadius(size);
        for (int tx = -radius; tx <= radius; tx++) {
            for (int tz = -radius; tz <= radius; tz++) {
                int ring = Math.max(Math.abs(tx), Math.abs(tz));
                if (ring == 0) {
                    continue;   // the room itself is real blocks, not a copy of one
                }
                out.add(new BuilderStructure.Placement(
                        new BuilderStructure.Kind.RoomTile(name, detailFor(roomMode, ring)),
                        origin.offset(tx * size.getX(), 0, tz * size.getZ()),
                        fadeFor(ring, radius)));
            }
        }
    }

    /**
     * How many rings this room may tile, from the same budget the world uses.
     *
     * <p>{@code budgetTiles} answers in tiles over the whole square window, so it comes back as a
     * radius: a budget of 9 tiles is a 3×3 window, which is radius 1.</p>
     */
    public static int tilingRadius(Vec3i size) {
        int blocksPerTile = size.getX() * size.getY() * size.getZ();
        int side = (int) Math.floor(Math.sqrt(PortalRoomTiling.budgetTiles(blocksPerTile)));
        return Math.max(0, Math.min(PortalRoomTiling.MAX_RADIUS, (side - 1) / 2));
    }

    /**
     * How opaque ring {@code ring} is drawn, as a fraction of the base alpha.
     *
     * <p>Fades to nothing at the outer ring rather than stopping at full strength, so the window ends
     * the way the room's own fog would end it instead of announcing a hard edge the real space does
     * not have.</p>
     */
    public static float fadeFor(int ring, int maxRing) {
        if (maxRing <= 0 || ring <= 0) {
            return 1.0F;
        }
        return Math.max(0.0F, 1.0F - (float) (ring - 1) / Math.max(1, maxRing));
    }

    /**
     * How much of one copy to draw.
     *
     * <p>A room with no walls is drawn as its floor and ceiling at every distance — a shell of it
     * would draw the walls it says it doesn't have. Everything else is drawn cell-for-cell in the
     * ring you can reach out and touch, and as a silhouette past that: a full window is over a
     * hundred tiles, and a default room has on the order of a thousand exposed faces.</p>
     */
    private static BuilderStructure.Detail detailFor(PortalRoomMode roomMode, int ring) {
        if (!roomMode.tilesWholeRoom()) {
            return BuilderStructure.Detail.PLANES;
        }
        return ring <= 1 ? BuilderStructure.Detail.FULL : BuilderStructure.Detail.SHELL;
    }

    // ---- shared pieces ----

    /**
     * The corridor under a parked train, across the whole platform.
     *
     * <p>Whole-platform rather than the stretch under the carriages: a run that stopped where the
     * train ends would draw a hard edge across the line at a point that means nothing, and that moves
     * every time the parked count changes.</p>
     */
    private static void groundTrack(List<BuilderStructure.Placement> out, CarriageDims dims) {
        TrackGeometry g = TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
        trackRun(out, BuilderWorldLayout.MIN_XZ, BuilderWorldLayout.MAX_XZ, g);
    }

    /** The line up on the columns, either side of the piece being edited. */
    private static void elevatedTrack(List<BuilderStructure.Placement> out, CarriageDims dims) {
        int editedX = BuilderTrackPlot.editedColumnCentreX();
        trackRun(out, editedX - SCENE_RADIUS_X, editedX + SCENE_RADIUS_X,
                BuilderTrackScene.geometry(dims));
    }

    /**
     * One run of line. The kind carries the X range because that is what decides which variants roll;
     * the placement carries the height, which is the only difference between a corridor on the ground
     * and a line on a bridge.
     */
    private static void trackRun(List<BuilderStructure.Placement> out, int fromX, int toX,
                                 TrackGeometry g) {
        out.add(new BuilderStructure.Placement(
                new BuilderStructure.Kind.TrackRun(fromX, toX),
                new BlockPos(fromX, g.bedY(), g.trackZMin())));
    }

    /** Every column within the scene radius, stacked from the ground the way the generator stacks it. */
    private static void pillarColumns(List<BuilderStructure.Placement> out, CarriageDims dims) {
        TrackGeometry g = BuilderTrackScene.geometry(dims);
        int editedX = BuilderTrackPlot.editedColumnCentreX();
        for (int centreX : BuilderTrackScene.pillarCentresX()) {
            if (Math.abs(centreX - editedX) > SCENE_RADIUS_X) {
                continue;
            }
            out.add(new BuilderStructure.Placement(
                    new BuilderStructure.Kind.PillarColumn(centreX, BuilderTrackScene.COLUMN_HEIGHT),
                    new BlockPos(BuilderTrackScene.columnMinX(centreX), BuilderTrackScene.groundY(),
                            g.trackZMin())));
        }
    }

    /** The staircase beside the edited column, running from deck height down to the ground. */
    private static void stairsFlight(List<BuilderStructure.Placement> out, CarriageDims dims) {
        int centreX = BuilderTrackPlot.editedColumnCentreX();
        int floorY = BuilderTrackScene.groundY();
        int topY = BuilderTrackScene.stairsTopY();
        out.add(new BuilderStructure.Placement(
                new BuilderStructure.Kind.StairsFlight(centreX, topY - floorY + 1),
                new BlockPos(centreX - 1, floorY, BuilderTrackScene.stairsMinZ(false, dims))));
    }

    private static void tunnelSection(List<BuilderStructure.Placement> out, int x, int y, int z) {
        out.add(new BuilderStructure.Placement(
                new BuilderStructure.Kind.TunnelSection(TrackKind.DEFAULT_NAME, false),
                new BlockPos(x, y, z)));
    }

    private static void tunnelPortal(List<BuilderStructure.Placement> out, int x, int y, int z,
                                     boolean mirrorX) {
        out.add(new BuilderStructure.Placement(
                new BuilderStructure.Kind.TunnelPortal(TrackKind.DEFAULT_NAME, mirrorX),
                new BlockPos(x, y, z)));
    }

    /**
     * The rest of the train: the pads capping the group, and the slots the parked carriage isn't in.
     *
     * <p>The pads go up whatever is parked. A full group stamps them today and a one-carriage world
     * never had them, and neither is what a pad <em>is</em> — it frames the silhouette, and it is not
     * part of the build either way.</p>
     *
     * <p>The slot the parked carriage occupies is matched on position rather than assumed to be the
     * middle one. It <em>is</em> the middle one for an odd group, but matching on position means an
     * even group, or a layout change, degrades to one redundant copy instead of a copy drawn straight
     * through the blocks you are editing.</p>
     */
    private static void carriageGroup(List<BuilderStructure.Placement> out, Context ctx) {
        int full = BuilderWorldLayout.ghostGroupCarriages(ctx.mode());
        if (full <= 0) {
            return;
        }
        CarriageDims dims = ctx.dims();
        int length = dims.length();
        int pad = BuilderWorldLayout.usesPads(full) ? CarriagePlacer.halfPadLen(dims) : 0;
        int startX = -(full * length + 2 * pad) / 2;
        int enclosedX = startX + pad;
        int y = BuilderWorldLayout.TRAIN_Y;

        if (pad > 0) {
            out.add(new BuilderStructure.Placement(
                    new BuilderStructure.Kind.FlatbedPad(CarriagePlacer.HalfPadSide.BACK),
                    new BlockPos(startX, y, 0)));
            out.add(new BuilderStructure.Placement(
                    new BuilderStructure.Kind.FlatbedPad(CarriagePlacer.HalfPadSide.FRONT),
                    new BlockPos(enclosedX + full * length, y, 0)));
        }

        if (ctx.parked() != 1) {
            return;   // a full group is missing no slot, and has no single carriage to sit in
        }
        int parkedMinX = BuilderWorldLayout.trainStartX(1, dims);
        for (int i = 0; i < full; i++) {
            int slotX = enclosedX + i * length;
            if (slotX == parkedMinX) {
                continue;
            }
            out.add(new BuilderStructure.Placement(
                    new BuilderStructure.Kind.Carriage(ctx.variantId()), new BlockPos(slotX, y, 0)));
        }
    }

    /**
     * Whether a placement may be drawn or stood up at all.
     *
     * <p>One rule in one place, consulted by both the renderer and the materialiser: nothing outside
     * the platform, nothing in the two courses the environment owns, and nothing inside a build
     * volume. The last is what keeps the structures off the template — a ghost over a real block
     * shows it twice, and a solid one over it would overwrite work.</p>
     *
     * <p>The bedrock and grass rows are excluded for the reason {@link BuilderEnvironment} exists:
     * you stand on them while deciding what the structures should be, so the structure control must
     * not be able to take them away — and a hole in the floor of a builder world is not a recoverable
     * mistake.</p>
     *
     * <p>The carriage shell is the deliberate exception to the volume rule: it shares the build's
     * volume by construction and is already restricted to the complement of what a room saves
     * ({@link BuilderStructure#onSkin}), so filtering it here would remove the whole thing.</p>
     */
    public static boolean allows(BuilderStructure.Kind kind, BlockPos pos,
                                 List<BoundingBox> buildVolumes) {
        if (pos.getY() <= BuilderWorldLayout.Y_GRASS) {
            return false;
        }
        if (kind instanceof BuilderStructure.Kind.CarriageShell) {
            return true;
        }
        if (!BuilderWorldLayout.inPlatform(pos.getX(), pos.getZ())) {
            return false;
        }
        for (BoundingBox box : buildVolumes) {
            if (box.isInside(pos)) {
                return false;
            }
        }
        return true;
    }

    /** How tall one stamp of the staircase template is — the repeat a flight is built from. */
    public static int stairsStampHeight() {
        return PillarAdjunct.STAIRS.ySize();
    }
}
