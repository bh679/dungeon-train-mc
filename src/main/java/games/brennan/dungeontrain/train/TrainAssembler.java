package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
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
import java.util.List;
import java.util.Set;

/**
 * Spawns a carriage-shaped Valkyrien Skies ship and drives it at constant
 * velocity via a {@link TrainTransformProvider} (kinematic drive — bypasses
 * the force-based mass threshold that froze 20-carriage trains).
 *
 * <p>All VS API usage is confined to this file + {@link TrainTransformProvider}
 * so a future VS version bump touches a minimal surface area.
 *
 * <p>Each train has a {@code seed} that feeds {@link CarriageSpecGenerator} —
 * carriage index N always resolves to the same spec for the life of the train,
 * so the rolling-window manager can add/erase carriages without visual drift.
 */
public final class TrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TrainAssembler() {}

    /**
     * Spawn an N-carriage train as a single VS ship moving at {@code velocity}.
     * {@code seed} drives the per-carriage spec generator; pass a random long
     * to get a unique train, or a fixed value for reproducible spawns.
     */
    public static ServerShip spawnTrain(
        ServerLevel level,
        BlockPos origin,
        Vector3dc velocity,
        int count,
        long seed
    ) {
        int deleted = deleteExistingTrains(level);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Deleted {} existing train(s) before spawn", deleted);
        }

        int cleared = clearBoundingBox(level, origin, count);
        LOGGER.info("[DungeonTrain] Cleared {} world blocks in train footprint", cleared);

        List<CarriageSpec> initialSpecs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            initialSpecs.add(CarriageSpecGenerator.specForIndex(i, seed));
        }

        Set<BlockPos> blocks = CarriageTemplate.placeTrainAt(level, origin, initialSpecs);
        LOGGER.info("[DungeonTrain] Placed {} blocks ({} carriages), assembling...", blocks.size(), count);

        ServerShip ship = ShipAssembler.assembleToShip(level, blocks, 1.0);

        // assembleToShip moves the world blocks to a shipyard region; translate the
        // original world origin through worldToShip to find where they ended up.
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.getTransform().getWorldToShip().transformPosition(shipyardOriginVec);
        BlockPos shipyardOrigin = BlockPos.containing(
            shipyardOriginVec.x, shipyardOriginVec.y, shipyardOriginVec.z
        );

        ship.setTransformProvider(new TrainTransformProvider(
            velocity, shipyardOrigin, count, level.dimension(), seed
        ));

        LOGGER.info(
            "[DungeonTrain] Assembly returned ship id={} — transform provider attached "
                + "(shipyardOrigin={}, count={}, seed={})",
            ship.getId(), shipyardOrigin, count, seed
        );

        populateInitialCarriages(level, ship, shipyardOrigin, count, seed);

        return ship;
    }

    /**
     * Place contents for the {@code count} carriages we just assembled.
     * Entities and block-entity contents go onto the ship via
     * {@code level.setBlock} at the ship's current world-space projection of
     * each carriage centre. Later carriages (added by the rolling-window
     * manager) are populated by that manager on demand.
     */
    private static void populateInitialCarriages(
        ServerLevel level, ServerShip ship, BlockPos shipyardOrigin, int count, long seed
    ) {
        TrainTransformProvider provider = (TrainTransformProvider) ship.getTransformProvider();
        if (provider == null) return;
        for (int i = 0; i < count; i++) {
            CarriageSpec spec = CarriageSpecGenerator.specForIndex(i, seed);
            ContentsPopulator.populate(level, ship, i, shipyardOrigin, spec);
            provider.getPopulatedIndices().add(i);
        }
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
     */
    private static int clearBoundingBox(ServerLevel level, BlockPos origin, int count) {
        int lengthTotal = count * CarriageTemplate.LENGTH;
        int cleared = 0;
        for (int dx = 0; dx < lengthTotal; dx++) {
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
