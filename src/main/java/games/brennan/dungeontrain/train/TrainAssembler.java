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

/**
 * Spawns a carriage-shaped managed ship and drives it at constant velocity
 * via a {@link TrainTransformProvider} (kinematic drive — bypasses the
 * force-based mass threshold that froze 20-carriage trains).
 *
 * <p>All ship-physics interaction goes through the {@link Shipyard} port,
 * so a future swap to a different physics mod (Sable) only changes the
 * {@code ship.vs.*} adapter package.</p>
 */
public final class TrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TrainAssembler() {}

    /**
     * Spawn an N-carriage train as a single managed ship moving at
     * {@code velocity}. All carriages share one rigid body and one kinematic
     * transform driver.
     *
     * {@code origin} anchors carriage index 0 in world space. The physical
     * placement is shifted so the initial carriages cover indices
     * {@code [initialPIdx − halfBack, initialPIdx + halfFront]} — centered on
     * {@code spawnerWorldPos} — which lets the rolling-window manager start
     * with zero churn on its first tick.
     *
     * Before assembly: deletes any pre-existing Dungeon Train ships in the level
     * and clears world blocks inside the new train's bounding volume so trapped
     * terrain does not wedge the ship against the world.
     */
    public static ManagedShip spawnTrain(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims) {
        int deleted = deleteExistingTrains(level);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Deleted {} existing train(s) before spawn", deleted);
        }
        return spawnTrainCore(level, origin, velocity, count, spawnerWorldPos, dims, 0);
    }

    /**
     * Spawn a successor train ahead of an existing one — does NOT delete
     * existing trains or wipe persistence, so two trains coexist and the
     * player can walk from one onto the next. {@code globalPIdxBase} keeps
     * the HUD's "carriage number" continuous across the chain. See
     * {@link games.brennan.dungeontrain.train.TrainChainManager} and
     * {@code plans/floofy-floating-dahl.md} for the chain architecture.
     *
     * @param origin carriage-index-0 anchor in world for the new train
     * @param spawnerWorldPos world position that seeds {@code initialPIdx}
     *     (typically the same as origin for a successor — its first
     *     carriage lives at origin and the window extends forward).
     */
    public static ManagedShip spawnSuccessor(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims, int globalPIdxBase) {
        return spawnTrainCore(level, origin, velocity, count, spawnerWorldPos, dims, globalPIdxBase);
    }

    private static ManagedShip spawnTrainCore(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos, CarriageDims dims, int globalPIdxBase) {
        // At spawn time the ship's worldToShip is a pure translation, so the
        // player's shipyard-space X offset from shipyardOrigin equals their
        // world X offset from origin — we can compute pIdx without the ship.
        int initialPIdx = (int) Math.floor((spawnerWorldPos.x() - origin.getX()) / (double) dims.length());
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        int firstIdx = initialPIdx - halfBack;
        int lastIdx = initialPIdx + halfFront;

        int cleared = clearBoundingBox(level, origin, firstIdx, lastIdx, dims);
        LOGGER.info("[DungeonTrain] Cleared {} world blocks in train footprint (indices {} to {})", cleared, firstIdx, lastIdx);

        // Pull mode + groupSize from config and seed from per-world SavedData
        // so the variants selected here match what TrainWindowManager picks
        // when the rolling window replaces the same carriages later.
        CarriageGenerationConfig genCfg = DungeonTrainWorldData.get(level).getGenerationConfig();

        Set<BlockPos> blocks = new HashSet<>();
        int emptyCarriages = 0;
        // Stash the variant per index so the post-assembly contents pass
        // doesn't re-pick and risk desyncing with what was placed here.
        CarriageVariant[] variantByIdx = new CarriageVariant[lastIdx - firstIdx + 1];
        for (int i = firstIdx; i <= lastIdx; i++) {
            BlockPos carriageOrigin = origin.offset(i * dims.length(), 0, 0);
            CarriageVariant variant = CarriageTemplate.variantForIndex(i, genCfg);
            variantByIdx[i - firstIdx] = variant;
            // applyContents=false: contents (which may spawn entities) are
            // deferred until AFTER ShipAssembler.assembleToShip because that
            // call only moves blocks into shipyard space. Entities spawned in
            // world space here would stay in world space, visible at the
            // train's original spawn point rather than riding the ship.
            Set<BlockPos> carriageBlocks = CarriageTemplate.placeAt(level, carriageOrigin, variant, dims, genCfg, i, false);
            if (carriageBlocks.isEmpty()) {
                emptyCarriages++;
                LOGGER.warn("[DungeonTrain] Spawn produced empty carriage idx={} variant={} at {}",
                    i, variant.id(), carriageOrigin);
            }
            blocks.addAll(carriageBlocks);
        }
        // [spacing] — log the WORLD-space x-extent of every initial carriage
        // so we can correlate against the post-assembly shipyard extents and
        // the appender's later spawns. These are the integer ranges we asked
        // Sable to assemble; any mismatch with what the ship actually
        // contains afterwards is a Sable rounding / anchor issue.
        for (int i = firstIdx; i <= lastIdx; i++) {
            int worldMinX = origin.getX() + i * dims.length();
            int worldMaxX = worldMinX + dims.length() - 1;
            LOGGER.info("[DungeonTrain] [spacing] init idx={} world x=[{}, {}] (length={})",
                i, worldMinX, worldMaxX, dims.length());
        }

        LOGGER.info("[DungeonTrain] Placed {} blocks ({} carriages, {} empty, initialPIdx={}, dims={}x{}x{}), assembling...",
            blocks.size(), count, emptyCarriages, initialPIdx, dims.length(), dims.width(), dims.height());

        ManagedShip ship = Shipyards.of(level).assemble(blocks, 1.0);

        // shipyardOrigin anchors carriage index 0. Even though we didn't place a
        // carriage at `origin` when firstIdx != 0, worldToShip is still a pure
        // translation at this moment, so the shipyard position of index 0 is
        // the shipyard translation of `origin`.
        //
        // Round-to-nearest (NOT floor via BlockPos.containing) so we match
        // VS's own block placement. When worldToShip lands `origin` at e.g.
        // z = 12290044.9999669 (micro-fraction below the next integer), VS
        // stores the initial block at shipyard z = 12290045 while a floored
        // origin would give 12290044 — and the rolling-window erase loop
        // `[originZ, originZ + width)` would miss the +Z face of every
        // initial carriage, leaving a 1-block sliver.
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.worldToShip(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        // [spacing] — log raw worldToShip output so we can see the
        // sub-block fraction that's being rounded away. A persistent
        // ~0.5 fraction would indicate Sable's anchor lands the train at
        // half-block coords; rounding the wrong direction would skew the
        // appender's idx*length math against where the initial blocks
        // actually live.
        LOGGER.info("[DungeonTrain] [spacing] worldOrigin={} shipyardOriginRaw=({}, {}, {}) shipyardOriginRounded={} fracX={} fracY={} fracZ={}",
            origin,
            String.format("%.6f", shipyardOriginVec.x),
            String.format("%.6f", shipyardOriginVec.y),
            String.format("%.6f", shipyardOriginVec.z),
            shipyardOrigin,
            String.format("%.6f", shipyardOriginVec.x - Math.round(shipyardOriginVec.x)),
            String.format("%.6f", shipyardOriginVec.y - Math.round(shipyardOriginVec.y)),
            String.format("%.6f", shipyardOriginVec.z - Math.round(shipyardOriginVec.z)));

        // [spacing] — what the appender will assume for the initial range,
        // and the ship's actual measured bounds. If these disagree, the
        // appender's idx*length math will produce gaps or overlaps at the
        // seam between the initial set and the first appended carriage.
        org.joml.primitives.AABBdc actualBounds = ship.worldAABB();
        LOGGER.info("[DungeonTrain] [spacing] expectedShipyardX=[{}, {}) actualWorldAABB=[{}, {}, {}] -> [{}, {}, {}]",
            shipyardOrigin.getX() + firstIdx * dims.length(),
            shipyardOrigin.getX() + (lastIdx + 1) * dims.length(),
            String.format("%.3f", actualBounds.minX()),
            String.format("%.3f", actualBounds.minY()),
            String.format("%.3f", actualBounds.minZ()),
            String.format("%.3f", actualBounds.maxX()),
            String.format("%.3f", actualBounds.maxY()),
            String.format("%.3f", actualBounds.maxZ()));

        for (int i = firstIdx; i <= lastIdx; i++) {
            int sMinX = shipyardOrigin.getX() + i * dims.length();
            int sMaxX = sMinX + dims.length() - 1;
            LOGGER.info("[DungeonTrain] [spacing] init idx={} expected shipyard x=[{}, {}]",
                i, sMinX, sMaxX);
        }

        // Second pass: apply contents at SHIPYARD coordinates now that the
        // ship has absorbed our world-space blocks. Entity placement in
        // shipyard chunks routes through VS's shipyard-entity mixin so
        // armor stands, paintings, and item frames ride the ship.
        for (int i = firstIdx; i <= lastIdx; i++) {
            CarriageVariant variant = variantByIdx[i - firstIdx];
            BlockPos carriageShipyardOrigin = shipyardOrigin.offset(i * dims.length(), 0, 0);
            CarriageTemplate.applyContentsAt(level, carriageShipyardOrigin, variant, dims, genCfg, i);
        }

        TrainTransformProvider provider = new TrainTransformProvider(velocity, shipyardOrigin, count, level.dimension(), initialPIdx, dims, globalPIdxBase);
        ship.setKinematicDriver(provider);
        // Skip dynamics pipeline (COM/inertia recompute, Bullet integration) — rolling-window
        // block add/erase otherwise shifts the ship's COM every tick and causes visible jitter.
        ship.setStatic(true);

        // Capture world-space track geometry before any ship motion so the
        // values stay anchored to the spawn point. Bed sits 2 below the
        // carriage floor (origin.y); rails one above the bed (= one below
        // the floor). Z spans the full carriage width.
        TrackGeometry geometry = new TrackGeometry(
            origin.getY() - 2,
            origin.getY() - 1,
            origin.getZ(),
            origin.getZ() + dims.width() - 1);
        provider.setTrackGeometry(geometry);

        LOGGER.info("[DungeonTrain] Assembly returned ship id={} — attached kinematic transform driver, marked static (shipyardOrigin={}, count={}, initialPIdx={}, globalPIdxBase={}, track={})",
            ship.id(), shipyardOrigin, count, initialPIdx, globalPIdxBase, geometry);

        // Bootstrap: enqueue every chunk already loaded in the Z corridor.
        // These chunks fired ChunkEvent.Load before this train existed and
        // so were skipped by TrackChunkEvents — without explicit enqueue
        // they'd stay permanently unpainted as gaps in the bed.
        TrackGenerator.bootstrapPendingChunks(level, ship, provider);

        return ship;
    }

    /**
     * Return the {@link TrainTransformProvider} for every loaded Dungeon Train
     * ship in {@code level}. Used by commands and the settings screen to apply
     * speed/count changes to live trains.
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
     * Delete every loaded ship in {@code level} whose kinematic driver is a
     * {@link TrainTransformProvider} — i.e. every Dungeon Train we've previously
     * spawned. Returns the number of ships deleted.
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
        // Wipe per-carriage persistence snapshots from the previous train —
        // a fresh spawn should never restore stale state keyed on the old
        // train's carriage indices. See CarriagePersistenceStore.
        CarriagePersistenceStore.clear(level);
        return trains.size();
    }

    /**
     * Replace every world block inside the new train's axis-aligned bounding
     * box with air. Runs before block placement so the hollow interior does
     * not trap existing terrain inside the assembled ship.
     *
     * Clears the X range {@code [firstIdx * length, (lastIdx + 1) * length)}
     * relative to {@code origin} — matching the shifted carriage placement
     * anchored on index 0.
     */
    private static int clearBoundingBox(ServerLevel level, BlockPos origin, int firstIdx, int lastIdx, CarriageDims dims) {
        int startDx = firstIdx * dims.length();
        int endDx = (lastIdx + 1) * dims.length();
        int cleared = 0;
        for (int dx = startDx; dx < endDx; dx++) {
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
