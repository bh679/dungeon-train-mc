package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles a train as N independent Sable sub-levels — each holding a
 * {@code groupSize}-carriage <i>group</i> — all sharing a
 * {@link TrainTransformProvider#getTrainId() trainId}.
 *
 * <p>Each group is its own kinematic body. Because each sub-level's blocks
 * are placed once at assembly and never modified after, the per-sub-level
 * MassTracker / rotationPoint stays constant — eliminating the COM-drift
 * jitter we hit with the previous "one big sub-level for the whole train"
 * model. Trains are reassembled into logical groups via {@link Trains}.</p>
 *
 * <p>{@code groupSize} comes from the per-world
 * {@link CarriageGenerationConfig#groupSize() generation config} and is
 * captured per-train at spawn time. Runtime config changes only affect
 * future {@code spawnTrain} calls — existing groups keep their groupSize.</p>
 *
 * <p>All ship-physics interaction goes through the {@link Shipyard} port,
 * so a future swap to a different physics mod only changes the
 * {@code ship.sable.*} adapter package.</p>
 */
public final class TrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TrainAssembler() {}

    /**
     * Spawn a fresh train consisting of enough groups to cover the
     * requested {@code count} carriages, snapped outward to group
     * boundaries. Deletes any previously-loaded Dungeon Train sub-levels
     * first so the world starts with exactly this one new train.
     *
     * @return the lead group's {@link ManagedShip} — useful for callers
     *     that want a representative handle (the bootstrap log,
     *     {@code /dt spawn} output, etc.). The full train is reachable
     *     via {@link Trains#findById(ServerLevel, UUID)}.
     */
    public static ManagedShip spawnTrain(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims) {
        int deleted = deleteExistingTrains(level);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Deleted {} existing carriage(s) before spawn", deleted);
        }
        return spawnCore(level, origin, velocity, count, spawnerWorldPos, dims);
    }

    /**
     * Spawn an additional train without deleting any existing trains in
     * {@code level}. Each invocation creates a new {@link UUID} trainId;
     * carriages from this call do NOT join any pre-existing train.
     *
     * <p>Used by {@code /dt debug pair} to place two independent trains
     * side by side. The legacy {@code globalPIdxBase} parameter is
     * preserved for ABI compatibility but ignored.</p>
     */
    @SuppressWarnings("unused")
    public static ManagedShip spawnSuccessor(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims, int legacyGlobalPIdxBase) {
        return spawnCore(level, origin, velocity, count, spawnerWorldPos, dims);
    }

    private static ManagedShip spawnCore(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims) {
        if (count <= 0) {
            throw new IllegalArgumentException("Cannot spawn a train with count <= 0 (got " + count + ")");
        }

        // At spawn time, worldToShip is undefined for sub-levels that don't
        // exist yet, so initialPIdx is computed in plain world space. The
        // pIdx-to-world convention: origin.x is the world X of pIdx 0's
        // ENCLOSED CARRIAGE start (NOT the sub-level's back pad — the
        // back pad sits at origin.x − halfPadLen).
        int initialPIdx = (int) Math.floor((spawnerWorldPos.x() - origin.getX()) / (double) dims.length());

        // Each Sable sub-level packs `groupSize` enclosed carriages PLUS
        // a half-flatbed pad on each side, sitting OUTSIDE the integer
        // carriage-slot grid:
        //
        //   [BACK pad | groupSize × enclosed | FRONT pad]
        //   ←halfPadLen→ ← groupSize × length → ←halfPadLen→
        //
        // Both pads use a SINGLE half-sized NBT derived once from
        // FLATBED — BACK stamps it as-is, FRONT stamps it mirrored
        // (Mirror.FRONT_BACK). Adjacent groups' BACK + FRONT pads at
        // every seam combine to 2×halfPadLen blocks of contiguous floor.
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();
        int groupSize = genCfg.groupSize();
        int length = dims.length();
        int halfPadLen = CarriageTemplate.halfPadLen(dims);
        int subLevelStride = groupSize * length + 2 * halfPadLen;

        // Bootstrap spawns ONLY the seed group — the one containing the
        // player's initial pIdx. {@link TrainCarriageAppender} extends
        // the train backward and forward over the next several ticks
        // (capped at one new group per tick) until it covers the
        // player's window of `count` carriages.
        //
        // Why not bulk-spawn all `count` carriages here:
        // {@code Shipyards.assemble} returns a {@link ManagedShip} whose
        // backing Sable {@code ServerSubLevel} is queued for asynchronous
        // plot-loading. A burst spawn of N sub-levels in one tick races
        // against Sable's lazy load: the appender on subsequent ticks
        // sees only a subset of just-created sub-levels in
        // {@code SubLevelContainer.getAllSubLevels()} and was previously
        // tricked into stacking duplicates at the same anchor. Spawning
        // one per tick gives Sable's plot-loader a full tick to register
        // each new sub-level before the next is requested.
        int seedAnchor = Math.floorDiv(initialPIdx, groupSize) * groupSize;
        int seedGroupIdx = Math.floorDiv(seedAnchor, groupSize);
        int seedGroupOriginX = origin.getX() + seedGroupIdx * subLevelStride - halfPadLen;
        BlockPos seedGroupOrigin = new BlockPos(seedGroupOriginX, origin.getY(), origin.getZ());

        UUID trainId = UUID.randomUUID();

        TrackGeometry geometry = new TrackGeometry(
            origin.getY() - 2,
            origin.getY() - 1,
            origin.getZ(),
            origin.getZ() + dims.width() - 1);

        LOGGER.info("[DungeonTrain] Spawning train trainId={} seedAnchor={} (initialPIdx={}, targetCount={}) groupSize={} halfPadLen={} subLevelStride={} dims={}x{}x{} velocity={} origin={} — appender will extend",
            trainId, seedAnchor, initialPIdx, count, groupSize, halfPadLen, subLevelStride,
            length, dims.height(), dims.width(),
            velocity, origin);

        ManagedShip seedShip = spawnGroup(level, seedGroupOrigin, velocity, seedAnchor, groupSize, dims, trainId);
        if (seedShip.getKinematicDriver() instanceof TrainTransformProvider seedProvider) {
            seedProvider.setTrackGeometry(geometry);
            TrackGenerator.bootstrapPendingChunks(level, seedShip, seedProvider);
        }

        return seedShip;
    }

    /**
     * Assemble a single GROUP of {@code groupSize} enclosed carriages,
     * wrapped on each end by a half-flatbed pad (when groupSize > 1),
     * into one Sable sub-level. Used both by {@link #spawnTrain}'s
     * initial loop and by {@link TrainCarriageAppender} when a player
     * advances past the train's current pIdx range.
     *
     * <p>Sub-level world layout (groupSize > 1):
     * <pre>
     *   [BACK pad | enclosed₀ | enclosed₁ | … | enclosed_{n-1} | FRONT pad]
     *   ←halfPadLen→ ← groupSize × dims.length() blocks → ←halfPadLen→
     * </pre>
     * Sub-level total span: {@code groupSize × length + 2 × halfPadLen}
     * blocks (37 for length=9, groupSize=3). Both pads use the same
     * half-sized NBT extracted from FLATBED — BACK stamps as-is, FRONT
     * stamps with {@link Mirror#FRONT_BACK}.</p>
     *
     * <p>Variant selection for enclosed slots is per-carriage via
     * {@link CarriageTemplate#enclosedVariantForIndex(int, CarriageGenerationConfig)},
     * deterministic on absolute pIdx — never produces a flatbed-like
     * variant (the pads already provide bed continuity at every seam).</p>
     *
     * <p>Edge case: when {@code groupSize == 1} (e.g. {@code /dt debug pair}
     * probe), the sub-level holds a single enclosed carriage at
     * {@code origin} with NO pads — pads only make sense when there
     * are enclosed carriages to flank.</p>
     *
     * @param origin       sub-level's world-space minimum corner. For
     *                     groupSize > 1: the BACK pad's lowest-X corner
     *                     (= anchor enclosed carriage's lowest-X minus
     *                     halfPadLen). For groupSize == 1: the lone
     *                     enclosed carriage's lowest-X corner.
     * @param anchorPIdx   absolute pIdx of the LOWEST enclosed carriage
     *                     in this group
     * @param groupSize    number of enclosed carriages (≥ 1)
     * @param trainId      UUID shared by every group in the same train
     */
    public static ManagedShip spawnGroup(ServerLevel level, BlockPos origin, Vector3dc velocity, int anchorPIdx, int groupSize, CarriageDims dims, UUID trainId) {
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be ≥ 1, got " + groupSize);
        }

        int length = dims.length();
        int halfPadLen = CarriageTemplate.halfPadLen(dims);
        boolean wrapWithPads = groupSize > 1;
        int subLevelLength = wrapWithPads ? (groupSize * length + 2 * halfPadLen) : length;
        int enclosedStartOffset = wrapWithPads ? halfPadLen : 0;

        int cleared = clearSubLevelVolume(level, origin, subLevelLength, dims);
        if (cleared > 0) {
            LOGGER.debug("[DungeonTrain] Cleared {} world blocks in sub-level footprint anchorPIdx={} groupSize={} subLevelLength={} at {}",
                cleared, anchorPIdx, groupSize, subLevelLength, origin);
        }

        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();

        // Place every enclosed carriage in the group at world coords.
        // For groupSize > 1, the enclosed run starts at
        // origin + halfPadLen; pad placement runs separately afterwards.
        // For groupSize == 1, the lone enclosed carriage sits at origin
        // and there are no pads.
        Set<BlockPos> blocks = new HashSet<>();
        CarriageVariant[] enclosedBySlot = new CarriageVariant[groupSize];

        for (int slot = 0; slot < groupSize; slot++) {
            int carriagePIdx = anchorPIdx + slot;
            BlockPos carriageOrigin = origin.offset(enclosedStartOffset + slot * length, 0, 0);
            CarriageVariant variant = CarriageTemplate.enclosedVariantForIndex(carriagePIdx, genCfg);
            enclosedBySlot[slot] = variant;

            // applyContents=false: defer until after assembly so entities
            // land in shipyard space, not world space.
            Set<BlockPos> carriageBlocks = CarriageTemplate.placeAt(
                level, carriageOrigin, variant, dims, genCfg, carriagePIdx, false);
            if (carriageBlocks.isEmpty()) {
                LOGGER.warn("[DungeonTrain] spawnGroup produced empty enclosed block set anchorPIdx={} slot={} carriagePIdx={} variant={} at {}",
                    anchorPIdx, slot, carriagePIdx, variant.id(), carriageOrigin);
            }
            blocks.addAll(carriageBlocks);
        }

        // Place the half-flatbed pads at sub-level boundaries.
        if (wrapWithPads) {
            BlockPos backPadOrigin = origin;
            BlockPos frontPadOrigin = origin.offset(halfPadLen + groupSize * length, 0, 0);
            blocks.addAll(CarriageTemplate.placeHalfFlatbedPad(
                level, backPadOrigin, CarriageTemplate.HalfPadSide.BACK, dims));
            blocks.addAll(CarriageTemplate.placeHalfFlatbedPad(
                level, frontPadOrigin, CarriageTemplate.HalfPadSide.FRONT, dims));
        }

        ManagedShip ship = Shipyards.of(level).assemble(blocks, 1.0);

        // Resolve the sub-level's shipyard origin = the back pad's
        // lowest-X corner in shipyard coords (or, for groupSize == 1,
        // the anchor carriage's lowest-X corner — same thing since
        // origin == anchor in that case). Contents placement and the
        // appender's pIdx-from-player calc both shift by enclosedStartOffset.
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.worldToShip(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        // Contents pass at shipyard coords for each enclosed carriage.
        for (int slot = 0; slot < groupSize; slot++) {
            int carriagePIdx = anchorPIdx + slot;
            BlockPos carriageShipyardOrigin = shipyardOrigin.offset(enclosedStartOffset + slot * length, 0, 0);
            CarriageTemplate.applyContentsAt(level, carriageShipyardOrigin, enclosedBySlot[slot], dims, genCfg, carriagePIdx);
        }

        TrainTransformProvider provider = new TrainTransformProvider(
            velocity, shipyardOrigin, level.dimension(), anchorPIdx, groupSize, dims, trainId);
        ship.setKinematicDriver(provider);
        ship.setStatic(true);

        // Register in the spawn-time truth source. Sable's SubLevelContainer
        // is lazy after assembly (a freshly-assembled sub-level can take
        // several ticks to appear in getAllSubLevels()), so the appender
        // can't trust Trains.byTrainId for "what anchors does this train
        // already own." The registry is the authoritative answer.
        Trains.registerSpawned(trainId, anchorPIdx, ship);

        LOGGER.info("[DungeonTrain] Spawned group anchorPIdx={} groupSize={} enclosed=[{}] pads={} trainId={} ship id={} shipyardOrigin={} blocks={}",
            anchorPIdx, groupSize, summariseVariants(enclosedBySlot),
            wrapWithPads ? "back+front" : "none",
            trainId, ship.id(), shipyardOrigin, blocks.size());

        return ship;
    }

    private static String summariseVariants(CarriageVariant[] variants) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < variants.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(variants[i].id());
        }
        return sb.toString();
    }

    /**
     * Return every loaded {@link TrainTransformProvider} group in
     * {@code level}, ungrouped. Drop-in usage for legacy callers
     * ({@code /dt speed}, settings screen) that iterate providers and
     * mutate them. Per-train operations should prefer
     * {@link Trains#byTrainId(ServerLevel)}.
     */
    public static List<TrainTransformProvider> getActiveTrainProviders(ServerLevel level) {
        List<TrainTransformProvider> providers = new ArrayList<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider p) {
                providers.add(p);
            }
        }
        return providers;
    }

    /**
     * Public deletion helper for callers that need a clean slate before
     * spawning their own carriages outside the {@link #spawnTrain} path —
     * e.g. {@code /dt debug pair}, which spawns single-carriage groups
     * directly via {@link #spawnGroup}. Returns the count of carriages
     * deleted.
     */
    public static int deleteAllTrains(ServerLevel level) {
        return deleteExistingTrains(level);
    }

    private static int deleteExistingTrains(ServerLevel level) {
        // Wipe the spawn-time registry first so any trainIds being torn
        // down here don't linger as ghost entries that would block a
        // future appender from re-spawning at those anchors.
        Trains.clearRegistry();
        Shipyard shipyard = Shipyards.of(level);
        List<ManagedShip> trains = new ArrayList<>();
        for (ManagedShip ship : shipyard.findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                trains.add(ship);
            }
        }
        for (ManagedShip ship : trains) {
            shipyard.delete(ship);
        }
        CarriagePersistenceStore.clear(level);
        return trains.size();
    }

    /**
     * Clear every world block inside the sub-level's axis-aligned
     * footprint — the union of {@code subLevelLength × height × width}
     * blocks at {@code origin}, covering the BACK pad, all enclosed
     * carriage slots, and the FRONT pad in one pass.
     */
    private static int clearSubLevelVolume(ServerLevel level, BlockPos origin, int subLevelLength, CarriageDims dims) {
        int cleared = 0;
        for (int dx = 0; dx < subLevelLength; dx++) {
            for (int dy = 0; dy < dims.height(); dy++) {
                for (int dz = 0; dz < dims.width(); dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, AIR, 3);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }
}
