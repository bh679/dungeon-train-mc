package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.SharedBookPool;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Periodically refreshes the {@link SharedBookPool} snapshot from the relay for the shared-books
 * DISCOVERY half, so chest loot always has a warm, reasonably fresh pool of approved community books
 * to draw from — without any per-roll network I/O (the loot roll only reads the cached snapshot).
 *
 * <ul>
 *   <li>{@link ServerStartedEvent} — schedules a first refresh shortly after start (a short tick delay
 *       so the relay-base-url + world are settled), gated on {@link SharedBookGate#canDiscover()}.</li>
 *   <li>{@link ServerTickEvent.Post} — a throttled refresh every {@link #REFRESH_PERIOD_TICKS} ticks
 *       (~30 s), also gated on discovery being enabled. {@link SharedBookPool#refreshAsync(String)} is
 *       fire-and-forget and skips overlapping fetches, so this can never block a tick.</li>
 * </ul>
 *
 * <p>When discovery is disabled the ticker does nothing (no fetches, no cost). The tick counter is a
 * plain server-thread int — {@link ServerTickEvent.Post} is single-threaded.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedBookRefreshEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Refresh cadence in server ticks (20 ticks = 1 s → ~30 s). */
    static final int REFRESH_PERIOD_TICKS = 600;

    /** Short delay after server start before the first refresh, so relay-base-url + world are settled. */
    static final int FIRST_REFRESH_DELAY_TICKS = 100;

    private static int tickCounter = 0;
    /** Counts down after start; a first refresh fires when it reaches zero. -1 = no pending first refresh. */
    private static int firstRefreshCountdown = -1;

    private SharedBookRefreshEvents() {}

        public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        // Start fresh each world; a first refresh is scheduled a short delay out.
        tickCounter = 0;
        firstRefreshCountdown = FIRST_REFRESH_DELAY_TICKS;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SharedBookGate.canDiscover()) return;

        if (firstRefreshCountdown > 0) {
            firstRefreshCountdown--;
            if (firstRefreshCountdown == 0) {
                firstRefreshCountdown = -1;
                SharedBookPool.refreshAsync(WorldLanguage.hostLocale(event.getServer()));
                return;
            }
        }

        tickCounter++;
        if (tickCounter >= REFRESH_PERIOD_TICKS) {
            tickCounter = 0;
            SharedBookPool.refreshAsync(WorldLanguage.hostLocale(event.getServer()));
        }
    }
}
