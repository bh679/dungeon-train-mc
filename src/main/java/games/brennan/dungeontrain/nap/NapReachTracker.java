package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Once a second, samples every player standing on the train's level ({@link NapGateData#onTrainLevel}):
 * widens their life's reach ({@link PlayerRunState#noteReach}), so a later nap gates at the furthest
 * point reached rather than at the bed, and records their train-level X for when they step off it.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapReachTracker {

    private static final int PERIOD_TICKS = 20;

    private NapReachTracker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % PERIOD_TICKS != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isSpectator() || !player.isAlive() || !NapGateData.onTrainLevel(player)) continue;
            double x = player.getX();
            if (!Double.isFinite(x)) continue;
            PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
            run.noteReach(x);
            NapGateData.rememberTrainX(player, run, x);
        }
    }
}
