package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BurritoClock;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Ticks the Everything Burrito's play-time clock ({@link BurritoClock#playTicks}) for every player in
 * the world: alive, not in Free Play, and with no pause screen open. On the train or off it, AFK or not.
 *
 * <p>Every pause screen stops it here, books and photos included, the same on a dedicated server and in
 * single player, where a pause screen stops the server ticking altogether. The time on a pausing screen
 * other than the pause menu is credited separately, measured on the client
 * ({@link games.brennan.dungeontrain.net.BurritoScreenTimePacket}), so nothing counts twice. Death screens
 * do not pause; a dead player is skipped here.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BurritoPlayTimeEvents {

    /** Sampling cadence, matching {@link PlayerActivityTracker}'s scans. */
    private static final int PERIOD_TICKS = 10;

    private BurritoPlayTimeEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PERIOD_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (counts(player)) BurritoClock.addPlayTicks(player.getUUID(), PERIOD_TICKS);
        }
    }

    /** Is this player's burrito clock running in the world right now? */
    static boolean counts(ServerPlayer player) {
        return player.isAlive()
            && !RunIntegrity.isCheated(player)
            && !PlayerActivityTracker.isPaused(player.getUUID());
    }
}
