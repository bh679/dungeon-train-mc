package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;

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
        int y = pos.getY();
        if (!inPlatform(pos.getX(), pos.getZ())) {
            return false;
        }
        if (y == Y_BEDROCK || y == Y_GRASS) {
            return true;
        }
        return (y == Y_TRACK_BED || y == Y_TRACK_RAIL) && inCorridor(pos.getZ(), dims);
    }

    /** World spawn: on the grass, a few blocks clear of the track on Z. */
    public static BlockPos spawnPos(CarriageDims dims) {
        return new BlockPos(0, Y_STAND, dims.width() + SPAWN_Z_MARGIN);
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
