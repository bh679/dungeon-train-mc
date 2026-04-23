package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Assembles the starter train at {@link ServerStartedEvent} — before any
 * player joins — so the world's first frame already contains the train
 * instead of the player seeing a brief "vanilla spawn → train materialises →
 * teleport" sequence.
 *
 * Runs at {@link EventPriority#LOW} so
 * {@link WorldLifecycleEvents#onServerStarted} (NORMAL priority, client-only)
 * commits any pending world-creation choices into
 * {@link DungeonTrainWorldData} first. Common-scope (not Dist-gated) so
 * dedicated servers also get the auto-spawn.
 *
 * The initial carriage window is centred on the train origin ({@code pIdx=0}).
 * Whichever side the player is later teleported to, the rolling-window
 * manager retargets on its first tick — one-shot adjustment is negligible
 * compared to the visual improvement of removing the spawn-time flash.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrainBootstrapEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int DEFAULT_CARRIAGE_COUNT = 10;
    static final Vector3dc TRAIN_VELOCITY = new Vector3d(2.0, 0.0, 0.0);

    private TrainBootstrapEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);

        if (!data.startsWithTrain()) {
            LOGGER.info("[DungeonTrain] startsWithTrain=false — skipping bootstrap auto-spawn");
            return;
        }
        if (findTrain(overworld) != null) {
            LOGGER.debug("[DungeonTrain] Train already present — bootstrap skipped");
            return;
        }

        int trainY = data.getTrainY();
        BlockPos trainOrigin = new BlockPos(0, trainY, 0);
        CarriageDims dims = data.dims();
        Vector3dc spawnerPos = new Vector3d(trainOrigin.getX(), trainOrigin.getY(), trainOrigin.getZ());

        LOGGER.info("[DungeonTrain] Bootstrap auto-spawning {} carriages at {} dims={}x{}x{}",
            DEFAULT_CARRIAGE_COUNT, trainOrigin, dims.length(), dims.width(), dims.height());
        try {
            TrainAssembler.spawnTrain(overworld, trainOrigin, TRAIN_VELOCITY, DEFAULT_CARRIAGE_COUNT, spawnerPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Bootstrap train auto-spawn failed", t);
        }
    }

    private static LoadedServerShip findTrain(ServerLevel level) {
        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (ship.getTransformProvider() instanceof TrainTransformProvider) {
                return ship;
            }
        }
        return null;
    }
}
