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
 * Assembles a train as N independent Sable sub-levels — one per carriage —
 * all sharing a {@link TrainTransformProvider#getTrainId() trainId}.
 *
 * <p>Each carriage is its own kinematic body. Because each sub-level's blocks
 * are placed once at assembly and never modified after, the per-sub-level
 * MassTracker / rotationPoint stays constant — eliminating the COM-drift
 * jitter we hit with the previous "one big sub-level for the whole train"
 * model. Trains are reassembled into logical groups via {@link Trains}.</p>
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
     * Spawn a fresh N-carriage train as N single-carriage Sable sub-levels.
     * Deletes any previously-loaded Dungeon Train sub-levels first, so the
     * world starts with exactly this one new train.
     *
     * @return the lead carriage's {@link ManagedShip} — the one with the
     *     highest pIdx, useful for callers that just want a representative
     *     handle (the bootstrap log, the {@code /dt spawn} command output,
     *     etc.). The full train is reachable via
     *     {@link Trains#findById(ServerLevel, UUID)}.
     */
    public static ManagedShip spawnTrain(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims) {
        int deleted = deleteExistingTrains(level);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Deleted {} existing carriage(s) before spawn", deleted);
        }
        return spawnCore(level, origin, velocity, count, spawnerWorldPos, dims);
    }

    /**
     * Spawn an additional N-carriage train without deleting any existing
     * trains in {@code level}. Each invocation creates a new {@link UUID}
     * trainId; carriages from this call do NOT join any pre-existing train.
     *
     * <p>Used by {@code /dt debug pair} to place two independent
     * single-carriage trains side by side. The legacy {@code globalPIdxBase}
     * parameter is preserved for ABI compatibility but ignored in the
     * per-carriage architecture.</p>
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
        // exist yet, so initialPIdx is computed in plain world space — the
        // player's X offset from origin equals their pIdx * length.
        int initialPIdx = (int) Math.floor((spawnerWorldPos.x() - origin.getX()) / (double) dims.length());
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        int firstIdx = initialPIdx - halfBack;
        int lastIdx = initialPIdx + halfFront;

        UUID trainId = UUID.randomUUID();

        // World-space track corridor (Y/Z bounds) — same for every carriage
        // in this train. Compute once and reuse.
        TrackGeometry geometry = new TrackGeometry(
            origin.getY() - 2,
            origin.getY() - 1,
            origin.getZ(),
            origin.getZ() + dims.width() - 1);

        LOGGER.info("[DungeonTrain] Spawning train trainId={} pIdx range [{}, {}] count={} dims={}x{}x{} velocity={} origin={}",
            trainId, firstIdx, lastIdx, count,
            dims.length(), dims.height(), dims.width(),
            velocity, origin);

        List<ManagedShip> ships = new ArrayList<>(count);
        for (int i = firstIdx; i <= lastIdx; i++) {
            BlockPos carriageOrigin = origin.offset(i * dims.length(), 0, 0);
            ManagedShip ship = spawnCarriage(level, carriageOrigin, velocity, i, dims, trainId);
            // Attach the shared track geometry so per-carriage code that
            // needs the corridor (debug, future bed checks) can read it
            // directly off any ship in the train.
            if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
                provider.setTrackGeometry(geometry);
            }
            ships.add(ship);
        }

        // Bootstrap track-gen pending chunks on the tail (lowest pIdx).
        // The tail rarely changes once the train is moving forward — it
        // only changes if the appender extends the train backward — so the
        // queue stays put across most of the train's lifetime. When the
        // tail does change (player walks behind initial spawn), the new
        // tail starts with empty queues and re-discovers chunks; painting
        // is idempotent.
        ManagedShip tail = ships.get(0); // first iteration was firstIdx == lowest
        if (tail.getKinematicDriver() instanceof TrainTransformProvider tailProvider) {
            TrackGenerator.bootstrapPendingChunks(level, tail, tailProvider);
        }

        // Return lead (last iteration was lastIdx == highest). Callers that
        // need the full train iterate via Trains.findById(level, trainId).
        return ships.get(ships.size() - 1);
    }

    /**
     * Assemble a single Dungeon Train carriage into its own Sable sub-level.
     * Used both by {@link #spawnTrain}'s initial loop and by
     * {@link TrainCarriageAppender} when a player advances past the train's
     * current pIdx range.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Clear the carriage's world-space footprint so trapped terrain
     *       doesn't wedge the new sub-level on assembly.</li>
     *   <li>Stamp the carriage's shell blocks at world coords (variant +
     *       parts overlay; contents deferred).</li>
     *   <li>Assemble the block set into a Sable sub-level via the
     *       {@link Shipyard} port.</li>
     *   <li>Resolve the sub-level's shipyard origin and stamp contents in
     *       shipyard coords (so block-entities and any spawned entities
     *       ride the carriage rather than stay in world space).</li>
     *   <li>Attach a {@link TrainTransformProvider} keyed on
     *       {@code (pIdx, trainId)}. Mark the body static (no-op on Sable
     *       but keeps the API consistent).</li>
     * </ol>
     *
     * @param origin  carriage's world-space minimum corner (the floor's
     *                ground-level block)
     * @param pIdx    this carriage's index along the train's velocity axis
     * @param trainId UUID shared by every carriage in the same train
     */
    public static ManagedShip spawnCarriage(ServerLevel level, BlockPos origin, Vector3dc velocity, int pIdx, CarriageDims dims, UUID trainId) {
        int cleared = clearCarriageVolume(level, origin, dims);
        if (cleared > 0) {
            LOGGER.debug("[DungeonTrain] Cleared {} world blocks in carriage footprint pIdx={} at {}", cleared, pIdx, origin);
        }

        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();
        CarriageVariant variant = CarriageTemplate.variantForIndex(pIdx, genCfg);

        // applyContents=false: contents (which may spawn entities) are
        // deferred until AFTER assembly so they land in shipyard space.
        Set<BlockPos> blocks = new HashSet<>(
            CarriageTemplate.placeAt(level, origin, variant, dims, genCfg, pIdx, false));
        if (blocks.isEmpty()) {
            LOGGER.warn("[DungeonTrain] spawnCarriage produced empty block set pIdx={} variant={} at {}",
                pIdx, variant.id(), origin);
        }

        ManagedShip ship = Shipyards.of(level).assemble(blocks, 1.0);

        // Resolve the sub-level's shipyard origin. Round-to-nearest matches
        // Sable's own block-placement coordinate, so the +Z face of the
        // carriage isn't lost to a 1-block sliver.
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.worldToShip(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        // Contents pass at shipyard coords so block-entities and any
        // spawned entities are placed inside the sub-level.
        CarriageTemplate.applyContentsAt(level, shipyardOrigin, variant, dims, genCfg, pIdx);

        TrainTransformProvider provider = new TrainTransformProvider(
            velocity, shipyardOrigin, level.dimension(), pIdx, dims, trainId);
        ship.setKinematicDriver(provider);
        ship.setStatic(true);

        LOGGER.info("[DungeonTrain] Spawned carriage pIdx={} variant={} trainId={} ship id={} shipyardOrigin={} blocks={}",
            pIdx, variant.id(), trainId, ship.id(), shipyardOrigin, blocks.size());

        return ship;
    }

    /**
     * Return every loaded {@link TrainTransformProvider} carriage in
     * {@code level}, ungrouped. Drop-in usage for legacy callers (e.g.
     * {@code /dt speed}, {@code /dt carriages}) that iterate providers and
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
     * Delete every loaded carriage sub-level in {@code level} (any sub-level
     * whose driver is a {@link TrainTransformProvider}). Returns the number
     * of carriages deleted across all trains.
     */
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
        // Persistence store from the rolling-window era still holds keys for
        // any prior trains; clear so new trainIds can't collide with stale
        // saved state.
        CarriagePersistenceStore.clear(level);
        return trains.size();
    }

    /**
     * Replace every world block inside a single carriage's axis-aligned
     * footprint with air. Runs before block placement so trapped terrain
     * doesn't wedge the new sub-level on assembly.
     */
    private static int clearCarriageVolume(ServerLevel level, BlockPos origin, CarriageDims dims) {
        int cleared = 0;
        for (int dx = 0; dx < dims.length(); dx++) {
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
