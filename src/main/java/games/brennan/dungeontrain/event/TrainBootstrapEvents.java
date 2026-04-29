package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

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
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainBootstrapEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int DEFAULT_CARRIAGE_COUNT = 10;
    static final Vector3dc TRAIN_VELOCITY = new Vector3d(2.0, 0.0, 0.0);

    private TrainBootstrapEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event) {
        // SavedData lives on the overworld's data store regardless of which
        // dimension the train spawns in — a single source of truth for all
        // per-world DT choices.
        ServerLevel overworld = event.getServer().overworld();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);

        if (!data.startsWithTrain()) {
            LOGGER.info("[DungeonTrain] startsWithTrain=false — skipping bootstrap auto-spawn");
            return;
        }

        StartingDimension startingDim = data.startingDimension();
        ServerLevel target = event.getServer().getLevel(startingDim.levelKey());
        if (target == null) {
            // Defensive: e.g. a datapack disabled the chosen dimension. Fall
            // back to overworld so the player still gets a train somewhere.
            LOGGER.warn("[DungeonTrain] Configured starting dimension {} not loaded — falling back to overworld", startingDim);
            target = overworld;
        }

        if (findTrain(target) != null) {
            LOGGER.debug("[DungeonTrain] Train already present in {} — bootstrap skipped", target.dimension().location());
            return;
        }

        int trainY = data.getTrainY();
        BlockPos trainOrigin = new BlockPos(0, trainY, 0);
        CarriageDims dims = data.dims();
        Vector3dc spawnerPos = new Vector3d(trainOrigin.getX(), trainOrigin.getY(), trainOrigin.getZ());

        LOGGER.info("[DungeonTrain] Bootstrap auto-spawning {} carriages at {} in {} dims={}x{}x{}",
            DEFAULT_CARRIAGE_COUNT, trainOrigin, target.dimension().location(), dims.length(), dims.width(), dims.height());
        try {
            TrainAssembler.spawnTrain(target, trainOrigin, TRAIN_VELOCITY, DEFAULT_CARRIAGE_COUNT, spawnerPos, dims);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Bootstrap train auto-spawn failed", t);
        }
    }

    private static ManagedShip findTrain(ServerLevel level) {
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                return ship;
            }
        }
        return null;
    }
}
