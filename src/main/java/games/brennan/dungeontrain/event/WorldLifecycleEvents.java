package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PendingWorldChoices;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Bridges client-side world-creation choices into server-side per-world
 * persistence, and handles session cleanup that would otherwise pollute a
 * subsequent world.
 *
 * On {@link ServerStartedEvent} for the integrated server: if the player just
 * made choices on the world-creation sub-screen, commit them into
 * {@link DungeonTrainWorldData} for the overworld and clear the holder.
 *
 * On {@link ServerStoppedEvent}: invalidate the global template cache in
 * {@link CarriageTemplateStore} so the next world's carriage size check runs
 * against a fresh disk read rather than a template cached from the previous
 * world's dims. This mirrors the split between mod-scoped state (cached here)
 * and world-scoped state (per-world SavedData).
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
        CarriageDims dims = PendingWorldChoices.dims();

        DungeonTrainWorldData.get(overworld).apply(trainY, startsWithTrain, dims);
        overworld.getDataStorage().save();
        PendingWorldChoices.clear();

        LOGGER.info(
            "[DungeonTrain] Committed world-creation choices: trainY={} startsWithTrain={} dims={}x{}x{}",
            trainY, startsWithTrain, dims.length(), dims.width(), dims.height());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CarriageTemplateStore.clearCache();
        LOGGER.debug("[DungeonTrain] Cleared carriage-template cache on server stop.");
    }
}
