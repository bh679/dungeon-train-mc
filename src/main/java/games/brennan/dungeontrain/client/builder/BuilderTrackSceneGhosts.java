package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderTrackPlot;
import games.brennan.dungeontrain.builder.BuilderTrackScene;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Walks {@link BuilderTrackScene} into ghost blocks, from the real templates.
 *
 * <p>The line, the columns holding it up, the arch reaching between them and the staircases climbing
 * to it — everything the generator would have put there, drawn where it would have put it, out of
 * the blocks it would have used. The one thing left out is the plot, because that is the piece the
 * builder is actually editing and it is really there.</p>
 *
 * <p>Client-side rather than in {@code builder/} beside the scene arithmetic, because this is the
 * half that needs {@link BuilderGhostTemplates} and therefore a level. The arithmetic it walks stays
 * pure and tested next door.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTrackSceneGhosts {

    /**
     * How far either side of the edited column to build the scene.
     *
     * <p>Enough for a couple of columns and the arch between them at the scene's spacing, which is
     * what makes the pattern legible; past that it is cells nobody is looking at.</p>
     */
    private static final int SCENE_RADIUS_X = 64;

    private BuilderTrackSceneGhosts() {}

    /**
     * Every ghost block of the scene, keyed by position.
     *
     * <p>Later stages overwrite earlier ones, in the generator's own order — pillars and arch first,
     * then the line over them, then the staircases. That ordering is not cosmetic: the real feature
     * runs the same way, and it is what decides who wins where two of them meet.</p>
     */
    static Map<BlockPos, BlockState> build(long worldSeed, CarriageDims dims, BoundingBox plot,
                                           TrackKind openKind, String openName,
                                           Map<BlockPos, BlockState> livePlot) {
        Live live = new Live(worldSeed, dims, openKind, openName, livePlot);
        if (openKind == TrackKind.ADJUNCT_STAIRS_ENTRANCE) {
            return buildDownStairsScene(live, dims, plot);
        }

        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        TrackGeometry g = BuilderTrackScene.geometry(dims);
        int editedX = BuilderTrackPlot.editedColumnCentreX();

        for (int centreX : BuilderTrackScene.pillarCentresX()) {
            if (Math.abs(centreX - editedX) > SCENE_RADIUS_X) {
                continue;
            }
            appendColumn(cells, live, g, centreX);
            appendArch(cells, live, g, centreX);
        }
        appendLine(cells, live, g, editedX);
        for (BuilderTrackScene.Stairs stairs : BuilderTrackScene.stairs(worldSeed)) {
            if (Math.abs(stairs.x() - editedX) <= SCENE_RADIUS_X) {
                appendStairs(cells, live, dims, stairs.x(), stairs.minusZ());
            }
        }
        // The piece being edited brings its own surroundings: a staircase needs the rest of itself
        // running down to the ground, and a tunnel needs the run it sits in.
        if (BuilderTrackGhostShapes.isAdjunct(openKind)) {
            appendStairs(cells, live, dims, BuilderTrackPlot.editedColumnCentreX(), false);
        }
        if (BuilderTrackGhostShapes.isTunnel(openKind)) {
            appendTunnelRun(cells, live, g, plot);
        }

        if (plot != null) {
            cells.keySet().removeIf(plot::isInside);
        }
        return cells;
    }

    /**
     * Where a ghost's blocks come from.
     *
     * <p>Off disk for every piece except the ones showing the template the builder currently has
     * open — those read the plot's <em>live</em> blocks instead, so placing a block on your tile
     * changes every other copy of that tile down the line as you place it. Without this the ghosts
     * would show the last save, which is the one thing a preview must never do: quietly disagree
     * with the work in front of you.</p>
     */
    private record Live(long worldSeed, CarriageDims dims, TrackKind openKind, String openName,
                        Map<BlockPos, BlockState> plotCells) {

        /** The blocks for a piece of {@code kind} at the generator's {@code index}. */
        Map<BlockPos, BlockState> cells(TrackKind kind, long index) {
            if (kind == openKind && !plotCells.isEmpty()
                    && BuilderGhostTemplates.pickedName(kind, worldSeed, index).equals(openName)) {
                return plotCells;
            }
            return BuilderGhostTemplates.cellsForPick(kind, worldSeed, index, dims);
        }

        /** The blocks for the open template itself, whatever index it would have rolled at. */
        Map<BlockPos, BlockState> open(TrackKind kind) {
            return plotCells.isEmpty()
                    ? BuilderGhostTemplates.cells(kind, openName, dims)
                    : plotCells;
        }
    }

    /**
     * The scene a stairs entrance belongs to: an underground line, and a shaft climbing out of it.
     *
     * <p>Everything else here previews the elevated line, because everything else is part of it. An
     * entrance is not — it caps a <em>down</em>-staircase, which the generator only builds where the
     * line runs underground. So the picture is the one that case actually produces: the track down at
     * ground level, a tunnel around it, a staircase climbing from the tunnel deck to the surface, and
     * the entrance on top. Previewing it against a bridge would be previewing the wrong thing.</p>
     */
    private static Map<BlockPos, BlockState> buildDownStairsScene(Live live, CarriageDims dims,
                                                                  BoundingBox plot) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        TrackGeometry g = BuilderTrackScene.groundGeometry(dims);
        int shaftX = BuilderTrackPlot.editedColumnCentreX();

        appendLine(cells, live, g, shaftX);

        // The tunnel the line runs through — a run either side of the shaft, since a down-staircase
        // only exists where there is tunnel to come up out of.
        TunnelGeometry tg = TunnelGeometry.from(g);
        int length = TunnelPlacer.LENGTH;
        int originZ = tg.wallMinZ() + 1;
        Map<BlockPos, BlockState> section = BuilderGhostTemplates.cells(
                TrackKind.TUNNEL_SECTION, TrackKind.DEFAULT_NAME, dims);
        for (int repeat = -1; repeat <= 1; repeat++) {
            putTunnel(cells, section, shaftX - length / 2 + repeat * length,
                    tg.floorY(), originZ, length, false);
        }

        // The staircase climbing the shaft, repeated the way the generator repeats it — from just
        // under the surface down to the deck it starts from.
        Map<BlockPos, BlockState> stairs = BuilderGhostTemplates.cells(
                TrackKind.ADJUNCT_STAIRS, TrackKind.DEFAULT_NAME, dims);
        int floorY = BuilderTrackScene.shaftFloorY(dims);
        int height = PillarAdjunct.STAIRS.ySize();
        int originX = shaftX - 1;
        int shaftZ = BuilderTrackScene.shaftMinZ(dims);
        for (int top = BuilderTrackScene.shaftSurfaceY(dims) - 1; top >= floorY; top -= height) {
            int base = top - height + 1;
            for (Map.Entry<BlockPos, BlockState> entry : stairs.entrySet()) {
                BlockPos local = entry.getKey();
                int y = base + local.getY();
                if (y < floorY || y > top) {
                    continue;
                }
                cells.put(new BlockPos(originX + local.getX(), y, shaftZ + local.getZ()),
                        entry.getValue());
            }
        }

        if (plot != null) {
            cells.keySet().removeIf(plot::isInside);
        }
        return cells;
    }

    /**
     * One column, stacked the way {@code placePillarSlice} stacks it.
     *
     * <p>Bottom from the ground up, top hanging from the cap, middles repeating between — and the
     * variant for each section picked on the pillar's world X, which is the key the generator uses,
     * so two adjacent columns can legitimately differ.</p>
     */
    private static void appendColumn(Map<BlockPos, BlockState> cells, Live live,
                                     TrackGeometry g, int centreX) {
        int groundY = BuilderTrackScene.groundY();
        int capY = BuilderTrackScene.bedY() - 1;
        int botH = PillarSection.BOTTOM.height();
        int topH = PillarSection.TOP.height();
        int midH = BuilderTrackScene.COLUMN_HEIGHT - botH - topH;

        Map<BlockPos, BlockState> bottom = live.cells(TrackKind.PILLAR_BOTTOM, centreX);
        Map<BlockPos, BlockState> middle = live.cells(TrackKind.PILLAR_MIDDLE, centreX);
        Map<BlockPos, BlockState> top = live.cells(TrackKind.PILLAR_TOP, centreX);

        for (int x = BuilderTrackScene.columnMinX(centreX); x <= BuilderTrackScene.columnMaxX(centreX); x++) {
            for (int row = 0; row < botH; row++) {
                putSlice(cells, bottom, x, groundY + row, row, g);
            }
            for (int i = 0; i < midH; i++) {
                putSlice(cells, middle, x, groundY + botH + i, 0, g);
            }
            for (int row = 0; row < topH; row++) {
                putSlice(cells, top, x, capY - topH + 1 + row, row, g);
            }
        }
    }

    /**
     * The arch: a stepped corbel hanging from the cap, stepping out from both sides of the column.
     *
     * <p>Its blocks are a top-anchored copy of the pillar's own face — the top section's rows read
     * downward, then the middle's row repeating — which is why an authored pillar carries its own
     * look into the arch instead of the arch being a separate template.</p>
     */
    private static void appendArch(Map<BlockPos, BlockState> cells, Live live,
                                   TrackGeometry g, int centreX) {
        int[] profile = BuilderTrackScene.archProfile();
        if (profile.length == 0) {
            return;
        }
        int capY = BuilderTrackScene.bedY() - 1;
        int topH = PillarSection.TOP.height();
        Map<BlockPos, BlockState> middle = live.cells(TrackKind.PILLAR_MIDDLE, centreX);
        Map<BlockPos, BlockState> top = live.cells(TrackKind.PILLAR_TOP, centreX);

        int minX = BuilderTrackScene.columnMinX(centreX);
        int maxX = BuilderTrackScene.columnMaxX(centreX);
        for (int step = 0; step < profile.length; step++) {
            int count = profile[step];
            appendArchColumn(cells, middle, top, maxX + 1 + step, capY, count, topH, g);
            appendArchColumn(cells, middle, top, minX - 1 - step, capY, count, topH, g);
        }
    }

    private static void appendArchColumn(Map<BlockPos, BlockState> cells,
                                         Map<BlockPos, BlockState> middle,
                                         Map<BlockPos, BlockState> top,
                                         int x, int capY, int count, int topH, TrackGeometry g) {
        for (int i = 0; i < count; i++) {
            Map<BlockPos, BlockState> source = i < topH ? top : middle;
            int row = i < topH ? topH - 1 - i : 0;
            putSlice(cells, source, x, capY - i, row, g);
        }
    }

    /** One row of a 1×H×W section, across the corridor's width. */
    private static void putSlice(Map<BlockPos, BlockState> cells, Map<BlockPos, BlockState> source,
                                 int x, int y, int row, TrackGeometry g) {
        for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
            BlockState state = source.get(new BlockPos(0, row, z - g.trackZMin()));
            if (state != null) {
                cells.put(new BlockPos(x, y, z), state);
            }
        }
    }

    /**
     * The line: tiles laid on the {@code TILE_LENGTH} grid, each rolling its own variant.
     *
     * <p>Rolled per tile index rather than fixed, because that is what the generator does — a line
     * of one repeated tile would be a preview of something the game never builds.</p>
     */
    private static void appendLine(Map<BlockPos, BlockState> cells, Live live,
                                   TrackGeometry g, int editedX) {
        int from = editedX - SCENE_RADIUS_X;
        int to = editedX + SCENE_RADIUS_X;
        long firstTile = Math.floorDiv((long) from, (long) TrackPlacer.TILE_LENGTH);
        long lastTile = Math.floorDiv((long) to, (long) TrackPlacer.TILE_LENGTH);

        for (long tile = firstTile; tile <= lastTile; tile++) {
            Map<BlockPos, BlockState> template = live.cells(TrackKind.TILE, tile);
            int originX = (int) (tile * TrackPlacer.TILE_LENGTH);
            for (Map.Entry<BlockPos, BlockState> entry : template.entrySet()) {
                BlockPos local = entry.getKey();
                cells.put(new BlockPos(originX + local.getX(),
                                g.bedY() + local.getY(),
                                g.trackZMin() + local.getZ()),
                        entry.getValue());
            }
        }
    }

    /**
     * A staircase beside its column, repeated downward to the ground.
     *
     * <p>The generator stamps the 8-tall template over and over from deck height until it reaches
     * the floor, which is how one template becomes a staircase of any height — so a preview that
     * drew a single stamp would show a flight ending in mid-air. The mirror on the {@code +Z} side
     * is skipped: at this alpha a mirrored copy of the same blocks in the same footprint reads
     * identically.</p>
     */
    private static void appendStairs(Map<BlockPos, BlockState> cells, Live live, CarriageDims dims,
                                     int centreX, boolean minusZ) {
        // Live blocks only when the staircase itself is what's open. An entrance is a 5×8×5 pavilion
        // that happens to share the family — repeating it as a flight of stairs would draw a tower
        // of doorways down to the ground.
        Map<BlockPos, BlockState> template = live.openKind() == TrackKind.ADJUNCT_STAIRS
                ? live.open(TrackKind.ADJUNCT_STAIRS)
                : live.cells(TrackKind.ADJUNCT_STAIRS, centreX);
        if (template.isEmpty()) {
            return;
        }
        int originX = centreX - 1;
        int originZ = BuilderTrackScene.stairsMinZ(minusZ, dims);
        int floorY = BuilderTrackScene.groundY();
        int height = PillarAdjunct.STAIRS.ySize();

        for (int top = BuilderTrackScene.stairsTopY(); top >= floorY; top -= height) {
            int base = top - height + 1;
            for (Map.Entry<BlockPos, BlockState> entry : template.entrySet()) {
                BlockPos local = entry.getKey();
                int y = base + local.getY();
                if (y < floorY || y > top) {
                    continue;   // the last repeat runs past the ground; the generator clips it too
                }
                cells.put(new BlockPos(originX + local.getX(), y, originZ + local.getZ()),
                        entry.getValue());
            }
        }
    }

    /**
     * The tunnel run around the open piece.
     *
     * <p>A tunnel is only ever seen as part of a run, and what you are judging is the seam between
     * consecutive sections and the mouth you come out of. A <b>section</b> therefore gets
     * {@code portal | section | you | section | portal} — run on both sides, capped either end.</p>
     *
     * <p>A <b>portal</b> gets tunnel on <em>one</em> side only, because that is what a portal is: the
     * mouth. Tunnel behind it, open air in front. Ghosting a run on both sides would draw the one
     * thing the piece exists to deny.</p>
     */
    private static void appendTunnelRun(Map<BlockPos, BlockState> cells, Live live,
                                        TrackGeometry g, BoundingBox plot) {
        if (plot == null) {
            return;
        }
        TunnelGeometry tg = TunnelGeometry.from(g);
        int length = TunnelPlacer.LENGTH;
        int originZ = tg.wallMinZ() + 1;
        int plotMinX = plot.minX();

        Map<BlockPos, BlockState> section = live.openKind() == TrackKind.TUNNEL_SECTION
                ? live.open(TrackKind.TUNNEL_SECTION)
                : BuilderGhostTemplates.cells(TrackKind.TUNNEL_SECTION, TrackKind.DEFAULT_NAME,
                        live.dims());

        if (live.openKind() == TrackKind.TUNNEL_PORTAL) {
            // Behind the mouth only. The plot is the entrance portal, which the generator stamps
            // unmirrored at the low-X end of a run, so the tunnel runs away from it on +X.
            putTunnel(cells, section, plotMinX + length, tg.floorY(), originZ, length, false);
            putTunnel(cells, section, plotMinX + 2 * length, tg.floorY(), originZ, length, false);
            return;
        }

        Map<BlockPos, BlockState> portal =
                BuilderGhostTemplates.cells(TrackKind.TUNNEL_PORTAL, TrackKind.DEFAULT_NAME,
                        live.dims());
        putTunnel(cells, section, plotMinX - length, tg.floorY(), originZ, length, false);
        putTunnel(cells, section, plotMinX + length, tg.floorY(), originZ, length, false);
        // Entrance at the low-X mouth, exit at the high-X one — mirrored, as the generator stamps it.
        putTunnel(cells, portal, plotMinX - 2 * length, tg.floorY(), originZ, length, false);
        putTunnel(cells, portal, plotMinX + 2 * length, tg.floorY(), originZ, length, true);
    }

    private static void putTunnel(Map<BlockPos, BlockState> cells, Map<BlockPos, BlockState> template,
                                  int originX, int originY, int originZ, int length, boolean mirrorX) {
        for (Map.Entry<BlockPos, BlockState> entry : template.entrySet()) {
            BlockPos local = entry.getKey();
            int x = mirrorX ? length - 1 - local.getX() : local.getX();
            cells.put(new BlockPos(originX + x, originY + local.getY(), originZ + local.getZ()),
                    entry.getValue());
        }
    }

}
