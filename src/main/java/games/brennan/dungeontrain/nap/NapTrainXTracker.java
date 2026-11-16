package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Once a second, records the X of every player standing on the train's level
 * ({@link NapGateData#onTrainLevel}), so the nap gate can be judged where they left the train when
 * they are in a room or another dimension.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapTrainXTracker {

    private static final int PERIOD_TICKS = 20;

    private NapTrainXTracker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % PERIOD_TICKS != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isSpectator() || !player.isAlive() || !NapGateData.onTrainLevel(player)) continue;
            double x = player.getX();
            if (!Double.isFinite(x)) continue;
            player.getData(games.brennan.dungeontrain.registry.ModDataAttachments.PLAYER_RUN_STATE.get()).noteTrainX(x);
            NapGateData.rememberTrainX(player, x);
        }
    }
}
