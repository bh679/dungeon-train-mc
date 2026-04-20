package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.TrainPersistentData;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.events.ShipLoadEvent;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.impl.hooks.VSEvents;

import java.util.function.Consumer;

/**
 * Re-attaches {@link TrainTransformProvider} to any ship that VS loads from
 * disk carrying a {@link TrainPersistentData} attachment — the one piece of
 * state VS cannot serialize for us, without which the train falls out of the
 * sky on rejoin.
 *
 * Register once from {@code DungeonTrain.commonSetup} — VS's
 * {@code SingleEvent.on(...)} is a process-lifetime registration.
 */
public final class TrainShipLoadHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrainShipLoadHandler() {}

    public static void register() {
        Consumer<ShipLoadEvent> handler = TrainShipLoadHandler::onShipLoad;
        VSEvents.INSTANCE.getShipLoadEvent().on(handler);
        LOGGER.info("[DungeonTrain] Registered VS ship-load handler for train persistence");
    }

    private static void onShipLoad(ShipLoadEvent event) {
        LoadedServerShip ship = event.getShip();
        TrainPersistentData data = ship.getAttachment(TrainPersistentData.class);
        if (data == null) return;

        // Idempotent: the freshly-spawned ship may also fire this event while its
        // provider is already attached. Skip to avoid overwriting the in-memory
        // instance and resetting lockedRotation / canonicalPos mid-spawn.
        if (ship.getTransformProvider() instanceof TrainTransformProvider) return;

        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(data.getDimensionKey())
        );
        Vector3d velocity = new Vector3d(data.getVelocityX(), data.getVelocityY(), data.getVelocityZ());
        BlockPos shipyardOrigin = new BlockPos(
            data.getShipyardOriginX(),
            data.getShipyardOriginY(),
            data.getShipyardOriginZ()
        );

        ship.setTransformProvider(new TrainTransformProvider(velocity, shipyardOrigin, data.getCount(), dimension));
        LOGGER.info("[DungeonTrain] Restored train provider on ship id={} (count={}, shipyardOrigin={})",
            ship.getId(), data.getCount(), shipyardOrigin);
    }
}
