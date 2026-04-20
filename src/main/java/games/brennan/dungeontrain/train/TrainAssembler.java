package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.Set;

/**
 * Spawns a carriage-shaped Valkyrien Skies ship at a location and
 * attaches a {@link TrainForcesInducer} to drive it at constant velocity.
 *
 * All VS API usage is confined to this file + {@link TrainForcesInducer}
 * so a future VS version bump touches a minimal surface area.
 */
public final class TrainAssembler {

    private TrainAssembler() {}

    /**
     * Place a carriage blueprint at {@code origin} and convert it into a
     * moving VS ship. Returns the spawned ship.
     *
     * @param level   server-side level to spawn in
     * @param origin  minimum-corner block position for the blueprint
     * @param velocity  target world-space velocity (m/s)
     */
    public static ServerShip spawnCarriage(ServerLevel level, BlockPos origin, Vector3dc velocity) {
        Set<BlockPos> blocks = CarriageTemplate.placeAt(level, origin);
        ServerShip ship = ShipAssembler.assembleToShip(level, blocks, 1.0);
        // Freshly-assembled ship lives in the chunk claim we just filled, so
        // it's already a LoadedServerShip. Cast to use the generic-typed
        // setAttachment (cleaner inference than ServerShip.saveAttachment).
        ((LoadedServerShip) ship).setAttachment(TrainForcesInducer.class, new TrainForcesInducer(velocity));
        return ship;
    }
}
