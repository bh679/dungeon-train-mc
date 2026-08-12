package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.progress.ProgressSyncService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side lifecycle hooks for relay-backed progress ({@link ProgressSyncService}).
 *
 * <p>The pull is NOT hooked here — it starts when the client's credential packet arrives, which is the
 * first moment the server knows whose account to read. This class owns the pushes:</p>
 *
 * <ul>
 *   <li><b>Logout</b> — the natural end of a session, and the push that matters most.</li>
 *   <li><b>Periodically</b> — so a crash, a power cut, or an Alt-F4 loses at most one interval rather
 *       than the whole session. Progress is state, not events, so each push simply carries the current
 *       picture; there is nothing to accumulate or replay.</li>
 *   <li><b>Server stopping</b> — covers "Save and Quit" and a dedicated server shutting down.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ProgressSyncEvents {

    /** ~5 minutes at 20 tps. Frequent enough to bound loss, rare enough to be invisible. */
    private static final int PUSH_INTERVAL_TICKS = 20 * 60 * 5;

    private static int ticks;

    private ProgressSyncEvents() {}

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressSyncService.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks < PUSH_INTERVAL_TICKS) return;
        ticks = 0;
        ProgressSyncService.pushAll(event.getServer(), "periodic");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        ProgressSyncService.pushAll(server, "server stopping");
        ProgressSyncService.clear();
    }
}
