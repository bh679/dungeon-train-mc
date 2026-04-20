package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.Set;

/**
 * Spawns a carriage-shaped Valkyrien Skies ship and attaches a
 * {@link TrainForcesInducer} to drive it at constant velocity.
 *
 * All VS API usage is confined to this file + {@link TrainForcesInducer}
 * so a future VS version bump touches a minimal surface area.
 */
public final class TrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrainAssembler() {}

    public static ServerShip spawnCarriage(ServerLevel level, BlockPos origin, Vector3dc velocity) {
        Set<BlockPos> blocks = CarriageTemplate.placeAt(level, origin);
        LOGGER.info("[DungeonTrain] Placed {} blocks, assembling...", blocks.size());

        ServerShip ship = ShipAssembler.assembleToShip(level, blocks, 1.0);
        LOGGER.info("[DungeonTrain] Assembly returned ship id={} runtime={}",
            ship.getId(), ship.getClass().getName());

        // ShipAssembler returns a ShipData (cold storage). The LoadedServerShip
        // becomes available once VS finishes the assembly's async chunk
        // loading — defer the inducer attach to a server tick.
        TrainRegistry.enqueueAttach(ship.getId(), velocity);
        return ship;
    }
}
