package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spawns a carriage-shaped Valkyrien Skies ship and drives it at constant
 * velocity via a {@link TrainTransformProvider} (kinematic drive — bypasses
 * the force-based mass threshold that froze 20-carriage trains).
 *
 * All VS API usage is confined to this file + {@link TrainTransformProvider}
 * so a future VS version bump touches a minimal surface area.
 */
public final class TrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TrainAssembler() {}

    /**
     * Spawn an N-carriage train as a single VS ship moving at {@code velocity}.
     * All carriages share one rigid body and one kinematic transform provider.
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
    public static ServerShip spawnTrain(ServerLevel level, BlockPos origin, Vector3dc velocity, int count, Vector3dc spawnerWorldPos) {
        int deleted = deleteExistingTrains(level);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Deleted {} existing train(s) before spawn", deleted);
        }

        // At spawn time the ship's worldToShip is a pure translation, so the
        // player's shipyard-space X offset from shipyardOrigin equals their
        // world X offset from origin — we can compute pIdx without the ship.
        int initialPIdx = (int) Math.floor((spawnerWorldPos.x() - origin.getX()) / (double) CarriageTemplate.LENGTH);
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        int firstIdx = initialPIdx - halfBack;
        int lastIdx = initialPIdx + halfFront;

        int cleared = clearBoundingBox(level, origin, firstIdx, lastIdx);
        LOGGER.info("[DungeonTrain] Cleared {} world blocks in train footprint (indices {} to {})", cleared, firstIdx, lastIdx);

        Set<BlockPos> blocks = new HashSet<>();
        for (int i = firstIdx; i <= lastIdx; i++) {
            BlockPos carriageOrigin = origin.offset(i * CarriageTemplate.LENGTH, 0, 0);
            blocks.addAll(CarriageTemplate.placeAt(level, carriageOrigin, CarriageTemplate.typeForIndex(i)));
        }
        LOGGER.info("[DungeonTrain] Placed {} blocks ({} carriages, initialPIdx={}), assembling...", blocks.size(), count, initialPIdx);

        ServerShip ship = ShipAssembler.assembleToShip(level, blocks, 1.0);

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
        // `[originZ, originZ + WIDTH)` would miss the +Z face of every
        // initial carriage, leaving a 1-block sliver.
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.getTransform().getWorldToShip().transformPosition(shipyardOriginVec);
        BlockPos shipyardOrigin = new BlockPos(
            (int) Math.round(shipyardOriginVec.x),
            (int) Math.round(shipyardOriginVec.y),
            (int) Math.round(shipyardOriginVec.z));

        TrainTransformProvider provider = new TrainTransformProvider(velocity, shipyardOrigin, count, level.dimension(), initialPIdx);
        ship.setTransformProvider(provider);
        // Skip VS dynamics pipeline (COM/inertia recompute, Bullet integration) — rolling-window
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
            origin.getZ() + CarriageTemplate.WIDTH - 1);
        provider.setTrackGeometry(geometry);

        LOGGER.info("[DungeonTrain] Assembly returned ship id={} — attached kinematic transform provider, marked static (shipyardOrigin={}, count={}, initialPIdx={}, track={})",
            ship.getId(), shipyardOrigin, count, initialPIdx, geometry);

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
        for (LoadedServerShip loaded : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (loaded.getTransformProvider() instanceof TrainTransformProvider p) {
                providers.add(p);
            }
        }
        return providers;
    }

    /**
     * Delete every loaded ship in {@code level} whose transform provider is a
     * {@link TrainTransformProvider} — i.e. every Dungeon Train we've previously
     * spawned. Returns the number of ships deleted.
     */
    private static int deleteExistingTrains(ServerLevel level) {
        List<LoadedServerShip> trains = new ArrayList<>();
        for (LoadedServerShip loaded : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (loaded.getTransformProvider() instanceof TrainTransformProvider) {
                trains.add(loaded);
            }
        }
        for (LoadedServerShip ship : trains) {
            ShipAssembler.INSTANCE.deleteShip(level, ship, true, false);
        }
        return trains.size();
    }

    /**
     * Replace every world block inside the new train's axis-aligned bounding
     * box with air. Runs before block placement so the hollow interior does
     * not trap existing terrain inside the assembled ship.
     *
     * Clears the X range {@code [firstIdx * LENGTH, (lastIdx + 1) * LENGTH)}
     * relative to {@code origin} — matching the shifted carriage placement
     * anchored on index 0.
     */
    private static int clearBoundingBox(ServerLevel level, BlockPos origin, int firstIdx, int lastIdx) {
        int startDx = firstIdx * CarriageTemplate.LENGTH;
        int endDx = (lastIdx + 1) * CarriageTemplate.LENGTH;
        int cleared = 0;
        for (int dx = startDx; dx < endDx; dx++) {
            for (int dy = 0; dy < CarriageTemplate.HEIGHT; dy++) {
                for (int dz = 0; dz < CarriageTemplate.WIDTH; dz++) {
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
