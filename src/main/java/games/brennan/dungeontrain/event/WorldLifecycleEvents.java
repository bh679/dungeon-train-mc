package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PendingWorldChoices;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Bridges client-side world-creation choices into server-side per-world
 * persistence.
 *
 * On {@link ServerStartedEvent} for the integrated server: if the player just
 * made choices on the world-creation sub-screen, commit them into
 * {@link DungeonTrainWorldData} for the overworld and clear the holder.
 *
 * Holder lifecycle: set when the user clicks Done on the sub-screen; consumed
 * here. Stale values can persist across cancelled world creations within a
 * single Minecraft session — that's intentional: re-opening the sub-screen
 * pre-fills with the previous choice so the user can review and change. JVM
 * exit clears the static field.
 *
 * Dist-gated to CLIENT — the entire class is never loaded on a dedicated
 * server. Dedicated servers fall through to the SavedData defaults sourced
 * from {@code DungeonTrainConfig}.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WorldLifecycleEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private WorldLifecycleEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!PendingWorldChoices.isPresent()) return;

        ServerLevel overworld = event.getServer().overworld();
        int trainY = PendingWorldChoices.trainY();
        boolean startsWithTrain = PendingWorldChoices.startsWithTrain();

        DungeonTrainWorldData.get(overworld).apply(trainY, startsWithTrain);
        overworld.getDataStorage().save();
        PendingWorldChoices.clear();

        LOGGER.info("[DungeonTrain] Committed world-creation choices: trainY={} startsWithTrain={}",
            trainY, startsWithTrain);
    }
}
