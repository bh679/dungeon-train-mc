package games.brennan.dungeontrain.builder;

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
     * The lowest-corner block the template is stamped at.
     *
     * <p>Corridor plots are aligned to the {@link TrackPlacer#TILE_LENGTH} grid the runtime track
     * painter uses. That alignment is load-bearing for the tile: {@code TrackGenerator} re-stamps
     * every {@code worldX mod TILE_LENGTH}, so a plot off the grid would sit half a tile out of
     * phase with the track running away from it on both sides, and the seam you were judging would
     * be a lie.</p>
     */
    public static BlockPos origin(TrackKind kind, CarriageDims dims) {
        Vec3i size = footprint(kind, dims);
        if (inCorridor(kind)) {
            // Centred on the track's Z axis, which for the tile is the corridor exactly (its width
            // is the corridor's) and for a tunnel is the arch sitting symmetrically over it.
            int z = (int) Math.round(BuilderWorldLayout.trackCenterZ(dims) - size.getZ() / 2.0);
            return new BlockPos(alignedX(size.getX()), BuilderWorldLayout.Y_TRACK_BED, z);
        }
        // Clear of the corridor on +Z — the side the builder spawns on, so it is in front of them
        // rather than behind the track.
        return new BlockPos(alignedX(size.getX()),
                BuilderWorldLayout.Y_STAND,
                dims.width() + OFF_CORRIDOR_MARGIN);
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
     * Where to stand to look at this plot: back off the far side of it on Z, on the grass.
     *
     * <p>Far enough to fit the whole footprint in frame, using the same framing arithmetic the
     * carriage spawn uses so a tunnel and a train are judged from comparable distances.</p>
     */
    public static BlockPos viewPos(TrackKind kind, CarriageDims dims) {
        BoundingBox box = volume(kind, dims);
        int centreX = (box.minX() + box.maxX()) / 2;
        double standoff = Math.max(box.getXSpan(), box.getYSpan());
        int z = box.maxZ() + (int) Math.ceil(standoff) + OFF_CORRIDOR_MARGIN;
        return new BlockPos(centreX, BuilderWorldLayout.Y_STAND, z);
    }
}
