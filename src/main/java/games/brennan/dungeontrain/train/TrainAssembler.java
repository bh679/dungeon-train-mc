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
        Vector3d shipyardOriginVec = new Vector3d(origin.getX(), origin.getY(), origin.getZ());
        ship.getTransform().getWorldToShip().transformPosition(shipyardOriginVec);
        BlockPos shipyardOrigin = BlockPos.containing(shipyardOriginVec.x, shipyardOriginVec.y, shipyardOriginVec.z);

        ship.setTransformProvider(new TrainTransformProvider(velocity, shipyardOrigin, count, level.dimension(), initialPIdx));
        // Skip VS dynamics pipeline (COM/inertia recompute, Bullet integration) — rolling-window
        // block add/erase otherwise shifts the ship's COM every tick and causes visible jitter.
        ship.setStatic(true);
        LOGGER.info("[DungeonTrain] Assembly returned ship id={} — attached kinematic transform provider, marked static (shipyardOrigin={}, count={}, initialPIdx={})",
            ship.getId(), shipyardOrigin, count, initialPIdx);
        return ship;
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
