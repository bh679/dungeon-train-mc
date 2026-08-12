package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where a track-side template is authored in the Train Builder's world.
 *
 * <p>The Train Editor parks every track model in a grid at Y=250, in the air, because it is a
 * catalogue — you walk the rows to find the one you want. The Builder is not a catalogue: it opens
 * one template and shows it to you where that kind of thing actually lives. So the plots split two
 * ways, on the only question that matters, <em>does this thing wrap the track or stand beside
 * it?</em>:</p>
 *
 * <ul>
 *   <li><b>In the corridor</b> — {@link TrackKind#TILE} and both tunnel kinds. A track tile is
 *       {@code 4 × 2 × width}, which is exactly a four-block slice of the corridor
 *       {@code BuilderWorldSetup.stampTrack} already lays down; a tunnel is the arch over it. Put
 *       them anywhere else and you would be authoring rails next to a rail line rather than the rail
 *       line itself, judging the join by memory.</li>
 *   <li><b>Beside it, on the grass</b> — pillars and the stairs adjunct. These hang <em>below</em>
 *       the track bed in a real world, and there is nothing below it here: the builder platform is
 *       bedrock at {@link BuilderWorldLayout#Y_BEDROCK} and grass at {@link BuilderWorldLayout#Y_GRASS},
 *       with the bed one block above that. So they stand free on the platform, clear of the corridor,
 *       the way the editor stands them.</li>
 * </ul>
 *
 * <p>Pure arithmetic over {@link BuilderWorldLayout} and {@link TrackKind#dims}, deliberately —
 * these are the coordinates the stamp writes to, the box the client washes around, and the origin
 * the save cuts from, and those three must agree exactly or a build saves the wrong blocks.</p>
 */
public final class BuilderTrackPlot {

    /**
     * Gap between the corridor and a free-standing plot, in blocks.
     *
     * <p>Wide enough that the plot reads as its own object rather than as something growing out of
     * the track, and narrow enough to stay in frame with it.</p>
     */
    public static final int OFF_CORRIDOR_MARGIN = 4;

    /**
     * How far the entrance pavilion sinks into the top of its staircase.
     *
     * <p>{@code ENTRANCE_OVERLAP_Y} in the generator: the bottom two rows of the entrance sit over
     * the last stair stamp so the two read as one structure rather than a hut balanced on a hole.</p>
     */
    private static final int ENTRANCE_OVERLAP_Y = 2;

    private BuilderTrackPlot() {}

    /** The template's footprint — the same box {@link TrackKind} tells every other caller about. */
    public static Vec3i footprint(TrackKind kind, CarriageDims dims) {
        return kind.dims(dims);
    }

    /** Whether this kind is authored in the track corridor rather than standing beside it. */
    public static boolean inCorridor(TrackKind kind) {
        return switch (kind) {
            case TILE, TUNNEL_SECTION, TUNNEL_PORTAL -> true;
            case PILLAR_TOP, PILLAR_MIDDLE, PILLAR_BOTTOM,
                 ADJUNCT_STAIRS, ADJUNCT_STAIRS_ENTRANCE -> false;
        };
    }

    /**
     * The lowest-corner block the template is stamped at — its real place in the scene.
     *
     * <p>Every kind lands where that kind actually lives on a line: a tile is four blocks of the
     * bed, a tunnel wraps it, a pillar section is its own rows of a column, a staircase hangs off
     * the side of one. The point is that the piece you edit is <em>in</em> the picture the ghosts
     * draw around it rather than parked beside it — you are judging a join, and a join you cannot
     * see is the thing this whole preview exists to fix.</p>
     *
     * <p>X is aligned to the {@link TrackPlacer#TILE_LENGTH} grid the runtime painter uses, and for
     * the column kinds to a real column centre. Both are load-bearing: {@code TrackGenerator}
     * re-stamps every {@code worldX mod TILE_LENGTH}, so a tile off the grid would sit half a tile
     * out of phase with the line running away from it, and a column off centre would have the arch
     * reaching from somewhere its pillar isn't.</p>
     */
    public static BlockPos origin(TrackKind kind, CarriageDims dims) {
        Vec3i size = footprint(kind, dims);
        TrackGeometry g = BuilderTrackScene.geometry(dims);
        return switch (kind) {
            // In the line itself, centred on the corridor: the tile is the bed's own width, and a
            // tunnel is the arch sitting symmetrically over it.
            case TILE, TUNNEL_SECTION, TUNNEL_PORTAL -> new BlockPos(
                    alignedX(size.getX()),
                    BuilderTrackScene.bedY(),
                    (int) Math.round(g.trackCenterZ() - size.getZ() / 2.0 + 0.5));
            // Its own rows of the edited column, counted the way placePillarSlice stacks them:
            // bottom from the ground up, top hanging from the cap, middles filling between.
            case PILLAR_BOTTOM -> columnOrigin(g, BuilderTrackScene.groundY());
            case PILLAR_MIDDLE -> columnOrigin(g,
                    BuilderTrackScene.groundY() + PillarSection.BOTTOM.height());
            case PILLAR_TOP -> columnOrigin(g,
                    BuilderTrackScene.bedY() - PillarSection.TOP.height());
            // Beside the column, flush against the corridor, reaching deck height — the generator's
            // own origin for an up-staircase.
            case ADJUNCT_STAIRS -> new BlockPos(
                    editedColumnCentreX() - 1,
                    BuilderTrackScene.stairsTopY() - size.getY() + 1,
                    BuilderTrackScene.stairsMinZ(false, dims));
            // The pavilion capping a down-stairs shaft: a 5x5 centred on the 3x3 shaft, its lowest
            // two rows overlapping the top of the staircase the way the generator overlaps them.
            case ADJUNCT_STAIRS_ENTRANCE -> new BlockPos(
                    editedColumnCentreX() - 2,
                    BuilderTrackScene.shaftSurfaceY(dims) - ENTRANCE_OVERLAP_Y,
                    BuilderTrackScene.shaftMinZ(dims) - 1);
        };
    }

    /**
     * The column the builder edits and the ghosts are drawn around.
     *
     * <p>The one nearest the origin, so you arrive looking at it rather than hunting down the line
     * for it.</p>
     */
    public static int editedColumnCentreX() {
        int spacing = BuilderTrackScene.spacing();
        return Math.floorDiv(0, spacing) * spacing;
    }

    /** A pillar section's origin: the edited column's low corner, at the row that section occupies. */
    private static BlockPos columnOrigin(TrackGeometry g, int y) {
        return new BlockPos(BuilderTrackScene.columnMinX(editedColumnCentreX()), y, g.trackZMin());
    }

    /**
     * Centred on the world origin, then floored onto the tile grid.
     *
     * <p>Floor rather than round so the answer never drifts above the centre for an odd length —
     * the grid phase is what matters, and one consistent rule keeps every kind in step.</p>
     */
    private static int alignedX(int length) {
        int centred = -length / 2;
        return Math.floorDiv(centred, TrackPlacer.TILE_LENGTH) * TrackPlacer.TILE_LENGTH;
    }

    /**
     * The plot as a box — what the client washes around and what the protection check consults.
     *
     * <p>One box, never a list: a track build is a single template, where a carriage build can be a
     * run of three.</p>
     */
    public static BoundingBox volume(TrackKind kind, CarriageDims dims) {
        BlockPos origin = origin(kind, dims);
        Vec3i size = footprint(kind, dims);
        return new BoundingBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX() - 1,
                origin.getY() + size.getY() - 1,
                origin.getZ() + size.getZ() - 1);
    }

    /**
     * Where to stand to look at this plot: back off it on Z, level with it.
     *
     * <p>Level with rather than on the grass, because the scene is in the air — a builder dropped on
     * the platform under a column twelve blocks up would be looking at the underside of a bridge and
     * wondering where their template went. Far enough back to fit the footprint in frame.</p>
     */
    public static BlockPos viewPos(TrackKind kind, CarriageDims dims) {
        BoundingBox box = volume(kind, dims);
        int centreX = (box.minX() + box.maxX()) / 2;
        double standoff = Math.max(box.getXSpan(), box.getYSpan());
        int z = box.maxZ() + (int) Math.ceil(standoff) + OFF_CORRIDOR_MARGIN;
        return new BlockPos(centreX, box.minY(), z);
    }
}
