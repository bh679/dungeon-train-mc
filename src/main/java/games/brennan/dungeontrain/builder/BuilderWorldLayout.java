package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where everything sits in a Train Builder world.
 *
 * <p>The whole world is one 300×300 slab of grass on bedrock floating in void, with a straight
 * track running across it and (depending on the mode) an empty train parked on the track. All of
 * that is fixed geometry rather than saved state, which is what lets the block-protection guard
 * and the setup stamp agree without either of them persisting anything.</p>
 *
 * <pre>
 *   y=4  train floor       ← carriages stamped here (editable — this is what you build)
 *   y=3  rail row      ┐
 *   y=2  track bed     ┘   protected
 *   y=1  grass         ┐
 *   y=0  bedrock       ┘   protected, and the world bottom (dimension_type builder.json min_y=0)
 * </pre>
 *
 * <p>Pure — no level access — so the layout and the protection rule are unit-testable.</p>
 */
public final class BuilderWorldLayout {

    /** The builder world's dimension type — see {@code data/dungeontrain/dimension_type/builder.json}. */
    public static final ResourceKey<DimensionType> BUILDER_DIMENSION_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder"));

    /** Platform edge length in blocks, on both X and Z. */
    public static final int SIZE = 300;

    public static final int MIN_XZ = -SIZE / 2;      // -150
    public static final int MAX_XZ = SIZE / 2 - 1;   //  149

    public static final int Y_BEDROCK = 0;
    public static final int Y_GRASS = 1;
    /** Stand here — the first free block above the grass. */
    public static final int Y_STAND = Y_GRASS + 1;

    /**
     * Track rows. {@code TrackGeometry.from(dims, trainY)} defines bed at {@code trainY-2} and
     * rail at {@code trainY-1}, so parking the train at {@link #TRAIN_Y} puts the bed directly on
     * the grass.
     */
    public static final int TRAIN_Y = 4;
    public static final int Y_TRACK_BED = TRAIN_Y - 2;   // 2
    public static final int Y_TRACK_RAIL = TRAIN_Y - 1;  // 3

    /** How many enclosed carriages each mode parks on the track. */
    public static final int OUTSIDE_CARRIAGES = 3;
    public static final int INSIDE_CARRIAGES = 1;

    /** Keeps the world spawn clear of the corridor so you don't materialise inside the train. */
    private static final int SPAWN_Z_MARGIN = 4;

    /**
     * Half of the horizontal field of view the spawn standoff is sized for, in degrees.
     *
     * <p>Deliberately narrower than what a default client actually has (~51° at FOV 70 on a 16:9
     * window): FOV is a per-player setting that can go down to 30, and a 4:3 window is narrower
     * still, so sizing for the default would put the end carriages off-screen for anyone who
     * isn't on it. Standing a little further back than strictly necessary costs nothing; not
     * being able to see the ends of your own train is the whole problem.</p>
     */
    private static final double FRAMING_HALF_ANGLE_DEG = 38.0;

    /** Blocks of clearance beyond the geometric fit, so the train doesn't touch the screen edges. */
    private static final double FRAMING_MARGIN = 3.0;

    /**
     * Closest the spawn ever stands to the track centre. Floors the standoff for a one-carriage
     * build and for the carriage-less modes, where the geometric fit would otherwise plant you
     * with your nose against the hull.
     */
    private static final double MIN_VIEW_DISTANCE = 9.0;

    private BuilderWorldLayout() {}

    public static boolean inPlatform(int x, int z) {
        return x >= MIN_XZ && x <= MAX_XZ && z >= MIN_XZ && z <= MAX_XZ;
    }

    /**
     * The corridor runs from z=0 to the carriage width, matching
     * {@code TrackGeometry.from(dims, trainY)} — which is what the train and the track stamp both
     * derive from, so this stays in step with a non-default {@link CarriageDims}.
     */
    public static boolean inCorridor(int z, CarriageDims dims) {
        return z >= 0 && z < dims.width();
    }

    /**
     * The scenery is not yours to edit: the platform you stand on and the track the train sits on
     * are fixed, so a build can't accidentally start by digging a hole in the floor or pulling up
     * a rail. Everything from the train floor up stays editable — that is the build.
     */
    public static boolean isProtected(BlockPos pos, CarriageDims dims) {
        return isProtected(pos, dims, null);
    }

    /**
     * As {@link #isProtected(BlockPos, CarriageDims)}, with the track plot carved out of it.
     *
     * <p>Authoring a track tile means editing rails on the track bed, which the rule above exists to
     * forbid — and rightly, for every other build. So the plot is an explicit hole in it: inside
     * {@code trackPlot} the bed and rail rows are the build and are yours, and one block outside
     * them they are scenery again.</p>
     *
     * <p>The bedrock floor is never carved out, whatever the plot says. Nothing is authored at
     * {@link #Y_BEDROCK} — no track kind's footprint reaches it — and a hole in the world floor of a
     * builder dimension is not a recoverable mistake.</p>
     *
     * @param trackPlot the volume of the open track template, or null when this build isn't one
     */
    public static boolean isProtected(BlockPos pos, CarriageDims dims, BoundingBox trackPlot) {
        int y = pos.getY();
        if (!inPlatform(pos.getX(), pos.getZ())) {
            return false;
        }
        if (y == Y_BEDROCK) {
            return true;
        }
        if (trackPlot != null && trackPlot.isInside(pos)) {
            return false;
        }
        if (y == Y_GRASS) {
            return true;
        }
        return (y == Y_TRACK_BED || y == Y_TRACK_RAIL) && inCorridor(pos.getZ(), dims);
    }

    /**
     * World spawn before the mode is known — the nearest standoff, clear of the corridor.
     *
     * <p>Used by the server-start anchor, which runs before the client has said which tile was
     * clicked. {@code BuilderSetupPacket} re-anchors with {@link #spawnPos(CarriageDims, int)}
     * once it knows how much train there is.</p>
     */
    public static BlockPos spawnPos(CarriageDims dims) {
        return spawnPos(dims, 0);
    }

    /**
     * World spawn: on the grass, far enough back on Z that the whole template fits in view.
     *
     * <p>Standing a fixed few blocks off the corridor was fine for one carriage and useless for
     * three — a 37-block run seen from 8 blocks away is a wall, and you can't judge a silhouette
     * you can't fit on screen. The standoff is therefore sized from the train's actual length
     * against {@link #FRAMING_HALF_ANGLE_DEG}, floored at {@link #MIN_VIEW_DISTANCE} so a short
     * build doesn't put you inside it.</p>
     */
    public static BlockPos spawnPos(CarriageDims dims, int carriages) {
        int z = (int) Math.ceil(trackCenterZ(dims) + viewDistance(carriages, dims));
        return new BlockPos(0, Y_STAND, Math.max(z, dims.width() + SPAWN_Z_MARGIN));
    }

    /**
     * How far back from the track centre the spawn stands, in blocks. Half the train's length
     * has to subtend no more than {@link #FRAMING_HALF_ANGLE_DEG}, plus a margin.
     */
    public static double viewDistance(int carriages, CarriageDims dims) {
        if (carriages <= 0) {
            return MIN_VIEW_DISTANCE;
        }
        double halfLength = totalTrainLength(carriages, dims) / 2.0;
        double fit = halfLength / Math.tan(Math.toRadians(FRAMING_HALF_ANGLE_DEG)) + FRAMING_MARGIN;
        return Math.max(fit, MIN_VIEW_DISTANCE);
    }

    /** Centre of the track corridor on Z — the axis the train is centred on. */
    public static double trackCenterZ(CarriageDims dims) {
        return dims.width() / 2.0;
    }

    /**
     * Lowest-X block of the parked train, centred on the origin.
     *
     * <p>A multi-carriage run is laid out the way {@code TrainAssembler.spawnGroup} lays out a
     * group — {@code [BACK pad | n × enclosed | FRONT pad]} — so the builder sees the same
     * silhouette the game produces. A single carriage gets no pads, matching the
     * {@code groupSize == 1} case there.</p>
     */
    public static int trainStartX(int carriages, CarriageDims dims) {
        return -totalTrainLength(carriages, dims) / 2;
    }

    public static int totalTrainLength(int carriages, CarriageDims dims) {
        int enclosed = carriages * dims.length();
        return usesPads(carriages) ? enclosed + 2 * CarriagePlacer.halfPadLen(dims) : enclosed;
    }

    /** Pads wrap a run of more than one carriage, exactly as {@code spawnGroup} does. */
    public static boolean usesPads(int carriages) {
        return carriages > 1;
    }
}
