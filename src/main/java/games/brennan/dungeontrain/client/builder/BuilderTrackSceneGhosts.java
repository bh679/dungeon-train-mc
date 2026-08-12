package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderTrackPlot;
import games.brennan.dungeontrain.builder.BuilderTrackScene;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.TrackPlacer;
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
    static Map<BlockPos, BlockState> build(long worldSeed, CarriageDims dims, BoundingBox plot) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        TrackGeometry g = BuilderTrackScene.geometry(dims);
        int editedX = BuilderTrackPlot.editedColumnCentreX();

        for (int centreX : BuilderTrackScene.pillarCentresX()) {
            if (Math.abs(centreX - editedX) > SCENE_RADIUS_X) {
                continue;
            }
            appendColumn(cells, worldSeed, dims, g, centreX);
            appendArch(cells, worldSeed, dims, g, centreX);
        }
        appendLine(cells, worldSeed, dims, g, editedX);
        for (BuilderTrackScene.Stairs stairs : BuilderTrackScene.stairs(worldSeed)) {
            if (Math.abs(stairs.x() - editedX) <= SCENE_RADIUS_X) {
                appendStairs(cells, worldSeed, dims, stairs);
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
    private static void appendColumn(Map<BlockPos, BlockState> cells, long worldSeed,
                                     CarriageDims dims, TrackGeometry g, int centreX) {
        int groundY = BuilderTrackScene.groundY();
        int capY = BuilderTrackScene.bedY() - 1;
        int botH = PillarSection.BOTTOM.height();
        int topH = PillarSection.TOP.height();
        int midH = BuilderTrackScene.COLUMN_HEIGHT - botH - topH;

        Map<BlockPos, BlockState> bottom = sectionCells(TrackKind.PILLAR_BOTTOM, worldSeed, centreX, dims);
        Map<BlockPos, BlockState> middle = sectionCells(TrackKind.PILLAR_MIDDLE, worldSeed, centreX, dims);
        Map<BlockPos, BlockState> top = sectionCells(TrackKind.PILLAR_TOP, worldSeed, centreX, dims);

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
    private static void appendArch(Map<BlockPos, BlockState> cells, long worldSeed,
                                   CarriageDims dims, TrackGeometry g, int centreX) {
        int[] profile = BuilderTrackScene.archProfile();
        if (profile.length == 0) {
            return;
        }
        int capY = BuilderTrackScene.bedY() - 1;
        int topH = PillarSection.TOP.height();
        Map<BlockPos, BlockState> middle = sectionCells(TrackKind.PILLAR_MIDDLE, worldSeed, centreX, dims);
        Map<BlockPos, BlockState> top = sectionCells(TrackKind.PILLAR_TOP, worldSeed, centreX, dims);

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
    private static void appendLine(Map<BlockPos, BlockState> cells, long worldSeed,
                                   CarriageDims dims, TrackGeometry g, int editedX) {
        int from = editedX - SCENE_RADIUS_X;
        int to = editedX + SCENE_RADIUS_X;
        long firstTile = Math.floorDiv((long) from, (long) TrackPlacer.TILE_LENGTH);
        long lastTile = Math.floorDiv((long) to, (long) TrackPlacer.TILE_LENGTH);

        for (long tile = firstTile; tile <= lastTile; tile++) {
            Map<BlockPos, BlockState> template =
                    BuilderGhostTemplates.cellsForPick(TrackKind.TILE, worldSeed, tile, dims);
            int originX = (int) (tile * TrackPlacer.TILE_LENGTH);
            for (Map.Entry<BlockPos, BlockState> entry : template.entrySet()) {
                BlockPos local = entry.getKey();
                cells.put(new BlockPos(originX + local.getX(),
                                BuilderTrackScene.bedY() + local.getY(),
                                g.trackZMin() + local.getZ()),
                        entry.getValue());
            }
        }
    }

    /**
     * A staircase beside its column, repeated downward to the ground.
     *
     * <p>The generator stamps the 8-tall template over and over from deck height until it reaches
     * the floor, which is how one template becomes a staircase of any height. The mirror on the
     * {@code +Z} side is skipped here: a ghost is read as a silhouette, and a mirrored copy of the
     * same blocks in the same footprint reads identically at this alpha.</p>
     */
    private static void appendStairs(Map<BlockPos, BlockState> cells, long worldSeed,
                                     CarriageDims dims, BuilderTrackScene.Stairs stairs) {
        Map<BlockPos, BlockState> template = BuilderGhostTemplates.cellsForPick(
                TrackKind.ADJUNCT_STAIRS, worldSeed, stairs.x(), dims);
        if (template.isEmpty()) {
            return;
        }
        int originX = stairs.x() - 1;
        int originZ = BuilderTrackScene.stairsMinZ(stairs.minusZ(), dims);
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

    /** A pillar section's template, rolled on the column's world X the way the generator keys it. */
    private static Map<BlockPos, BlockState> sectionCells(TrackKind kind, long worldSeed,
                                                          int centreX, CarriageDims dims) {
        return BuilderGhostTemplates.cellsForPick(kind, worldSeed, centreX, dims);
    }
}
