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
        // exist yet, so initialPIdx is computed in plain world space.
        int initialPIdx = (int) Math.floor((spawnerWorldPos.x() - origin.getX()) / (double) dims.length());
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        int firstPIdx = initialPIdx - halfBack;
        int lastPIdx = initialPIdx + halfFront;

        // Capture groupSize per-train at spawn-time. Subsequent runtime
        // changes to CarriageGenerationConfig.groupSize affect future
        // spawnTrain calls only; this train keeps its size for life.
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();
        int groupSize = genCfg.groupSize();

        // Snap the requested pIdx range outward to group anchors so every
        // group spawned spans exactly groupSize consecutive pIdx values.
        // Math.floorDiv handles negative pIdx correctly (e.g. floorDiv(-4, 3) = -2).
        int firstAnchor = Math.floorDiv(firstPIdx, groupSize) * groupSize;
        int lastAnchor = Math.floorDiv(lastPIdx, groupSize) * groupSize;

        UUID trainId = UUID.randomUUID();

        TrackGeometry geometry = new TrackGeometry(
            origin.getY() - 2,
            origin.getY() - 1,
            origin.getZ(),
            origin.getZ() + dims.width() - 1);

        LOGGER.info("[DungeonTrain] Spawning train trainId={} requested pIdx range [{}, {}] (count={}) → groups [{}, {}] step={} dims={}x{}x{} velocity={} origin={}",
            trainId, firstPIdx, lastPIdx, count,
            firstAnchor, lastAnchor, groupSize,
            dims.length(), dims.height(), dims.width(),
            velocity, origin);

        List<ManagedShip> ships = new ArrayList<>();
        for (int anchor = firstAnchor; anchor <= lastAnchor; anchor += groupSize) {
            BlockPos groupOrigin = origin.offset(anchor * dims.length(), 0, 0);
            ManagedShip ship = spawnGroup(level, groupOrigin, velocity, anchor, groupSize, dims, trainId);
            if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
                provider.setTrackGeometry(geometry);
            }
            ships.add(ship);
        }

        // Bootstrap track-gen pending chunks on the tail (lowest anchor).
        ManagedShip tail = ships.get(0);
        if (tail.getKinematicDriver() instanceof TrainTransformProvider tailProvider) {
            TrackGenerator.bootstrapPendingChunks(level, tail, tailProvider);
        }

        // Return lead (highest anchor's group). Callers needing the full
        // train iterate via Trains.findById(level, trainId).
        return ships.get(ships.size() - 1);
    }

    /**
     * Assemble a single GROUP of {@code groupSize} consecutive carriages
     * into one Sable sub-level. Used both by {@link #spawnTrain}'s initial
     * loop and by {@link TrainCarriageAppender} when a player advances past
     * the train's current pIdx range.
     *
     * <p>All carriages in the group share one rigid body — their relative
     * position within the group is fixed by their integer block coords in
     * shipyard space. Variant selection is per-carriage via
     * {@link CarriageTemplate#variantForIndex(int, CarriageGenerationConfig)},
     * deterministic on absolute pIdx.</p>
     *
     * @param origin       group's world-space minimum corner = the
     *                     anchor (lowest-pIdx) carriage's lowest-X corner
     * @param anchorPIdx   absolute pIdx of the LOWEST carriage in this group
     * @param groupSize    number of carriages in this group (≥ 1)
     * @param trainId      UUID shared by every group in the same train
     */
    public static ManagedShip spawnGroup(ServerLevel level, BlockPos origin, Vector3dc velocity, int anchorPIdx, int groupSize, CarriageDims dims, UUID trainId) {
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be ≥ 1, got " + groupSize);
        }

        int cleared = clearGroupVolume(level, origin, groupSize, dims);
        if (cleared > 0) {
            LOGGER.debug("[DungeonTrain] Cleared {} world blocks in group footprint anchorPIdx={} groupSize={} at {}",
                cleared, anchorPIdx, groupSize, origin);
        }

        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();

        // Place every carriage in the group at world coords. Stash the
        // resolved variant per slot so the post-assembly contents pass
        // can re-use the same picks (no re-roll, deterministic per pIdx).
        Set<BlockPos> blocks = new HashSet<>();
        CarriageVariant[] variantBySlot = new CarriageVariant[groupSize];
        for (int slot = 0; slot < groupSize; slot++) {
            int carriagePIdx = anchorPIdx + slot;
            BlockPos carriageOrigin = origin.offset(slot * dims.length(), 0, 0);
            CarriageVariant variant = CarriageTemplate.variantForIndex(carriagePIdx, genCfg);
            variantBySlot[slot] = variant;

            // applyContents=false: defer until after assembly so entities
            // land in shipyard space, not world space.
            Set<BlockPos> carriageBlocks = CarriageTemplate.placeAt(
                level, carriageOrigin, variant, dims, genCfg, carriagePIdx, false);
            if (carriageBlocks.isEmpty()) {
                LOGGER.warn("[DungeonTrain] spawnGroup produced empty block set anchorPIdx={} slot={} carriagePIdx={} variant={} at {}",
                    anchorPIdx, slot, carriagePIdx, variant.id(), carriageOrigin);
            }
            blocks.addAll(carriageBlocks);
        }

        ManagedShip ship = Shipyards.of(level).assemble(blocks, 1.0);

        // Resolve the sub-level's shipyard origin (the anchor carriage's
        // lowest-X corner in shipyard coords).
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.worldToShip(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        // Contents pass at shipyard coords for each carriage in the group.
        for (int slot = 0; slot < groupSize; slot++) {
            int carriagePIdx = anchorPIdx + slot;
            BlockPos carriageShipyardOrigin = shipyardOrigin.offset(slot * dims.length(), 0, 0);
            CarriageTemplate.applyContentsAt(level, carriageShipyardOrigin, variantBySlot[slot], dims, genCfg, carriagePIdx);
        }

        TrainTransformProvider provider = new TrainTransformProvider(
            velocity, shipyardOrigin, level.dimension(), anchorPIdx, groupSize, dims, trainId);
        ship.setKinematicDriver(provider);
        ship.setStatic(true);

        LOGGER.info("[DungeonTrain] Spawned group anchorPIdx={} groupSize={} variants=[{}] trainId={} ship id={} shipyardOrigin={} blocks={}",
            anchorPIdx, groupSize, summariseVariants(variantBySlot), trainId, ship.id(), shipyardOrigin, blocks.size());

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
     * Clear every world block inside the group's axis-aligned footprint
     * (the union of all groupSize carriage volumes).
     */
    private static int clearGroupVolume(ServerLevel level, BlockPos origin, int groupSize, CarriageDims dims) {
        int cleared = 0;
        int totalLength = groupSize * dims.length();
        for (int dx = 0; dx < totalLength; dx++) {
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
