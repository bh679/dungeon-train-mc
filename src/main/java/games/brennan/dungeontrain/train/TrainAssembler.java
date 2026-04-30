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

        // Capture per-train at spawn-time. Subsequent runtime changes to
        // CarriageGenerationConfig.groupSize affect future spawnTrain
        // calls only; this train keeps its size for life.
        //
        // groupSize is the count of ENCLOSED carriages per sub-level
        // (= the pIdx span of one group). Each sub-level is wrapped with
        // a length/2-block HALF_FLATBED_BACK pad at its low-X edge and a
        // (length - length/2)-block HALF_FLATBED_FRONT pad at its high-X
        // edge. The pads sit OUTSIDE the integer carriage-slot grid:
        // adjacent sub-levels' front+back pads sum to exactly one full
        // carriage length of continuous floor across each seam, while
        // each pad remains physically attached (no air gap) to the
        // enclosed carriages within its own sub-level.
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();
        int groupSize = genCfg.groupSize();
        int length = dims.length();
        // Sub-level world span = groupSize enclosed carriages
        // (groupSize × length blocks) plus one carriage's worth of
        // half-flatbed padding (back_pad + front_pad = length blocks),
        // plus 1 block of inter-sub-level air gap matching the
        // appender's MIN_GAP_BLOCKS bias. Without this gap, adjacent
        // bootstrap-spawned bodies sit at exactly touching world-X
        // positions and Sable's collision broad-phase treats them as
        // contact, producing visible jitter on the first physics ticks.
        int subLevelWorldStride = (groupSize + 1) * length + 1;

        // Snap the requested pIdx range outward to group anchors. Anchors
        // step by groupSize (in pIdx); the half-flatbed pads are not
        // pIdx-counted. Math.floorDiv handles negative pIdx correctly
        // (e.g. floorDiv(-4, 3) = -2).
        int firstAnchor = Math.floorDiv(firstPIdx, groupSize) * groupSize;
        int lastAnchor = Math.floorDiv(lastPIdx, groupSize) * groupSize;

        UUID trainId = UUID.randomUUID();

        TrackGeometry geometry = new TrackGeometry(
            origin.getY() - 2,
            origin.getY() - 1,
            origin.getZ(),
            origin.getZ() + dims.width() - 1);

        LOGGER.info("[DungeonTrain] Spawning train trainId={} requested pIdx range [{}, {}] (count={}) → groups [{}, {}] groupSize={} (enclosed; +1 length for half-flatbed pads) dims={}x{}x{} velocity={} origin={}",
            trainId, firstPIdx, lastPIdx, count,
            firstAnchor, lastAnchor, groupSize,
            length, dims.height(), dims.width(),
            velocity, origin);

        List<ManagedShip> ships = new ArrayList<>();
        for (int anchor = firstAnchor; anchor <= lastAnchor; anchor += groupSize) {
            int groupIdx = Math.floorDiv(anchor, groupSize);
            BlockPos groupOrigin = origin.offset(groupIdx * subLevelWorldStride, 0, 0);
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
     * Assemble a single GROUP of {@code groupSize} enclosed carriages
     * into one Sable sub-level, wrapped on each end by a half-flatbed
     * pad. Used both by {@link #spawnTrain}'s initial loop and by
     * {@link TrainCarriageAppender} when a player advances past the
     * train's current pIdx range.
     *
     * <p>Sub-level world layout:
     * <pre>
     *   [0, back_pad - 1]                   → HALF_FLATBED_BACK kept zone
     *                                         (back_pad = length / 2)
     *   [back_pad, back_pad + groupSize×length - 1]
     *                                       → groupSize enclosed carriages,
     *                                         each at its own length-block slot
     *   [back_pad + groupSize×length, (groupSize+1)×length - 1]
     *                                       → HALF_FLATBED_FRONT kept zone
     *                                         (front_pad = length - back_pad)
     * </pre>
     * Sub-level total length: {@code (groupSize + 1) × dims.length()} blocks.
     * Adjacent sub-levels' front+back pads tile to one full length of
     * contiguous floor at every seam.</p>
     *
     * <p>Variant selection for enclosed slots is per-carriage via
     * {@link CarriageTemplate#enclosedVariantForIndex(int, CarriageGenerationConfig)},
     * deterministic on absolute pIdx.</p>
     *
     * <p>Edge case: when {@code groupSize == 1} (e.g. {@code /dt debug pair}
     * probe), the sub-level holds a single enclosed carriage with NO
     * half-flatbed wrapping — pads only make sense when there are
     * enclosed slots to flank.</p>
     *
     * @param origin       sub-level's world-space minimum corner = the
     *                     back pad's start (lowest world X)
     * @param anchorPIdx   absolute pIdx of the LOWEST enclosed carriage
     *                     in this group
     * @param groupSize    number of enclosed carriages (≥ 1)
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
        int length = dims.length();
        int backPad = length / 2;
        int frontPad = length - backPad;
        boolean wrapWithHalfFlatbeds = groupSize > 1;
        int physicalSlotCount = wrapWithHalfFlatbeds ? groupSize + 2 : groupSize;

        // Stamp every slot in the sub-level.
        //
        // BACK pad (slot 0): the FLATBED stamp is OFFSET by -front_pad
        // so the kept HIGH-dx half (visual "end of flatbed") lands at
        // world X = origin..origin + back_pad - 1 (the BACK pad's
        // intended zone). The erased low-dx half is at world X
        // origin - front_pad..origin - 1, BEFORE the sub-level — set to
        // air, not part of this sub-level's footprint after the erase
        // completes and assemble() collects only non-air blocks.
        //
        // Enclosed slots [1, groupSize]: full carriages at world X =
        // origin + back_pad + (i × length).
        //
        // FRONT pad (last slot): the FLATBED stamp is at world X =
        // origin + back_pad + groupSize × length, with the kept LOW-dx
        // half (visual "start of flatbed") landing at the FRONT pad's
        // intended zone.
        //
        // For groupSize == 1, the wrapping is skipped: the lone enclosed
        // carriage sits at world X = origin.
        Set<BlockPos> blocks = new HashSet<>();
        CarriageVariant[] variantBySlot = new CarriageVariant[physicalSlotCount];
        BlockPos[] worldOriginBySlot = new BlockPos[physicalSlotCount];
        int[] carriagePIdxBySlot = new int[physicalSlotCount];

        for (int slot = 0; slot < physicalSlotCount; slot++) {
            CarriageVariant variant;
            BlockPos carriageOrigin;
            int carriagePIdx;
            if (!wrapWithHalfFlatbeds) {
                // groupSize == 1: single enclosed carriage at origin.
                carriagePIdx = anchorPIdx;
                carriageOrigin = origin;
                variant = CarriageTemplate.enclosedVariantForIndex(carriagePIdx, genCfg);
            } else if (slot == 0) {
                // BACK pad. Stamp offset by -front_pad so the kept high-dx
                // half lands at world X = origin..origin + back_pad - 1.
                // Carriage pIdx is unused (flatbeds skip contents) — pass
                // anchorPIdx for stable logging.
                carriagePIdx = anchorPIdx;
                carriageOrigin = origin.offset(-frontPad, 0, 0);
                variant = CarriageTemplate.HALF_FLATBED_BACK_VARIANT;
            } else if (slot == physicalSlotCount - 1) {
                // FRONT pad at world X = origin + back_pad + groupSize × length.
                carriagePIdx = anchorPIdx + groupSize - 1;
                carriageOrigin = origin.offset(backPad + groupSize * length, 0, 0);
                variant = CarriageTemplate.HALF_FLATBED_FRONT_VARIANT;
            } else {
                // Enclosed slot s ∈ [1, groupSize] → enclosed index s-1
                // → carriage pIdx anchor + (s-1) → world X = origin + back_pad
                // + (s-1) × length.
                int enclosedIdx = slot - 1;
                carriagePIdx = anchorPIdx + enclosedIdx;
                carriageOrigin = origin.offset(backPad + enclosedIdx * length, 0, 0);
                variant = CarriageTemplate.enclosedVariantForIndex(carriagePIdx, genCfg);
            }
            variantBySlot[slot] = variant;
            worldOriginBySlot[slot] = carriageOrigin;
            carriagePIdxBySlot[slot] = carriagePIdx;

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

        // Resolve the sub-level's shipyard origin (= back pad's start in
        // shipyard coords).
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.worldToShip(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        // Contents pass at shipyard coords for each slot. Half-flatbeds
        // skip contents internally (CarriageTemplate.applyContents
        // returns early for FLATBED-like variants), but we still pass
        // their shipyard origin so the gate can evolve without breaking
        // positional assumptions.
        int worldOriginX = origin.getX();
        for (int slot = 0; slot < physicalSlotCount; slot++) {
            int carriageWorldDx = worldOriginBySlot[slot].getX() - worldOriginX;
            BlockPos carriageShipyardOrigin = shipyardOrigin.offset(carriageWorldDx, 0, 0);
            CarriageTemplate.applyContentsAt(level, carriageShipyardOrigin, variantBySlot[slot], dims, genCfg, carriagePIdxBySlot[slot]);
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
     * Clear every world block inside the sub-level's axis-aligned
     * footprint. Span is {@code (groupSize + 1) × length} blocks
     * (groupSize enclosed carriages plus one carriage's worth of
     * half-flatbed padding) when wrapped, or just {@code length} blocks
     * when {@code groupSize == 1} (no wrapping).
     */
    private static int clearGroupVolume(ServerLevel level, BlockPos origin, int groupSize, CarriageDims dims) {
        int cleared = 0;
        int totalLength = (groupSize == 1) ? dims.length() : (groupSize + 1) * dims.length();
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
