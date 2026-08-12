package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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

    /**
     * How many carriages this selection actually parks on the track.
     *
     * <p>{@link BuilderMode#carriageCount()} answers "how much train does this mode want to see",
     * which is the right question for a <b>whole carriage</b>: you are judging a silhouette from the
     * platform, and one carriage in isolation tells you nothing about how the run reads. It is the
     * wrong question for everything else you can open. A carriage room and a part are each <em>one
     * template</em>, and stamping three copies of one template is three answers to a question that
     * has one — it reads as a generic train rather than as the thing you clicked.</p>
     *
     * <p>A null sub type means the world has never had a New or an Open in it: the mode is a
     * title-screen click and nothing has narrowed it yet, so the mode's own count stands. That is
     * the {@code setupIfNeeded} path, and it must keep behaving exactly as it did.</p>
     */
    public static int parkedCarriages(BuilderMode mode, BuilderNewOptions.SubType subType) {
        if (mode == null || mode.carriageCount() <= 0) {
            return 0;   // the track and portal modes park none, whatever is being authored
        }
        if (subType == null || subType == BuilderNewOptions.SubType.WHOLE_CARRIAGE) {
            return mode.carriageCount();
        }
        return 1;
    }

    /**
     * How long a run the ghosts draw around the build — how much train there is to <em>see</em>,
     * which is a different question from how much of it is real.
     *
     * <p>{@link #parkedCarriages} answers the second one, and answers it small on purpose: one
     * template is one carriage, and inside a carriage there is only ever one you can stand in. But a
     * train reads the same from either side of the wall, so both carriage modes show the same run —
     * from outside you are looking along it, and from inside you are looking out of the middle of
     * it. Sizing the ghosts from the parked count instead is why Inside Carriage drew none at all:
     * its count is 1, so there was never a slot left to fill.</p>
     */
    public static int ghostGroupCarriages(BuilderMode mode) {
        if (mode == null || mode.carriageCount() <= 0) {
            return 0;   // the track and portal modes have no train to imply one around
        }
        return OUTSIDE_CARRIAGES;
    }

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
     *
     * <p><b>The mode is not optional, and used not to be asked.</b> Train Dimensions has no scenery
     * at all — a portal room stands in open void — and the rows this protects overlap the room
     * itself: {@link #Y_TRACK_BED} is 2, which is also {@link #Y_STAND}, the row a room's floor sits
     * on, and {@link #inCorridor} spans {@code z ∈ [0, width)}, which a room centred on the origin
     * straddles. Without the mode, part of an open room's own floor is unbreakable and the author
     * cannot edit the thing they opened.</p>
     */
    public static boolean isProtected(BlockPos pos, CarriageDims dims) {
        return isProtected(pos, dims, null, false);
    }

    /**
     * As above, for a world that may be holding a track-side build or building in void.
     *
     * <p>Two ways the track rows stop being protected, and they are different reasons:</p>
     * <ul>
     *   <li><b>A track build is open.</b> By then those rows are not the track — opening a track
     *       template erases the corridor so the line can be drawn as ghosts, and the build itself
     *       goes down there; a pillar column stands from {@link #Y_STAND}, which is the bed row.
     *       Protecting them defends nothing and locks the bottom of every column being authored.</li>
     *   <li><b>The mode has no scenery at all.</b> Train Dimensions builds in open void, and
     *       {@link #Y_TRACK_BED} is also {@link #Y_STAND}, the row a portal room's floor sits on,
     *       over a z-range a room centred on the origin straddles — so part of an open room's own
     *       floor was unbreakable.</li>
     * </ul>
     *
     * <p>The floor and the grass stay protected in the track case. Nothing is ever authored there,
     * and a hole in the world floor of a builder dimension is not a recoverable mistake — but in a
     * void mode there is no floor to hole.</p>
     *
     * @param mode           the builder mode, or null when it isn't known
     * @param trackBuildOpen whether a track-side template is currently open in this world
     */
    /** As above, for a caller that knows about a track build but not about the mode. */
    public static boolean isProtected(BlockPos pos, CarriageDims dims, boolean trackBuildOpen) {
        return isProtected(pos, dims, null, trackBuildOpen);
    }

    public static boolean isProtected(BlockPos pos, CarriageDims dims, BuilderMode mode,
                                      boolean trackBuildOpen) {
        if (mode != null && !BuilderWorldSetup.hasScenery(mode)) {
            return false;   // nothing there to protect
        }
        int y = pos.getY();
        if (!inPlatform(pos.getX(), pos.getZ())) {
            return false;
        }
        if (y == Y_BEDROCK || y == Y_GRASS) {
            return true;
        }
        if (trackBuildOpen) {
            return false;
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

    /**
     * Lowest corner of the portal room the Train Dimensions mode opens, for a room of {@code size}.
     *
     * <p>Centred on the origin on both horizontal axes and standing on the grass, so the room sits
     * where the train would and the spawn framing already points at it. The mode parks no carriages
     * ({@link BuilderMode#carriageCount()} is zero), so nothing is in the way.</p>
     *
     * <p>The floor row is at {@link #Y_STAND} rather than {@link #Y_GRASS}: a room template carries
     * its own floor, so laying it on the grass would bury that floor one block deep and leave the
     * author standing on the wrong surface.</p>
     *
     * <p>The biggest room the game allows is {@code PortalRoomLayout.MAX_LENGTH} × {@code MAX_HEIGHT}
     * × {@code MAX_WIDTH} — 48 × 11 × 48 — against a {@link #SIZE}-block platform 96 tall, so even
     * the largest sits inside the world with room to walk around it.</p>
     */
    public static BlockPos portalRoomOrigin(Vec3i size) {
        int x = -size.getX() / 2;
        int z = -size.getZ() / 2;
        return new BlockPos(x, Y_STAND, z);
    }

    /**
     * Where to stand a player who is editing a room of {@code size} — inside it, one block above its
     * floor, at the middle.
     *
     * <p>Not {@link #spawnPos}, which stands off the track at platform level looking at a train.
     * There is no platform in this mode: spawning at that position would drop the player through
     * open void from the first tick. Inside the room is also simply where the work is.</p>
     */
    public static BlockPos portalRoomSpawn(Vec3i size) {
        BlockPos origin = portalRoomOrigin(size);
        return new BlockPos(
                origin.getX() + size.getX() / 2,
                origin.getY() + 1,
                origin.getZ() + size.getZ() / 2);
    }
}
