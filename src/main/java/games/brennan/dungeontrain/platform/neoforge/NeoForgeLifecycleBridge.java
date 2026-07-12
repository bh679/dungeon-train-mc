package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtPriority;
import games.brennan.dungeontrain.platform.event.DtServerStartedCallback;
import games.brennan.dungeontrain.platform.event.DtServerStartingCallback;
import games.brennan.dungeontrain.platform.event.DtServerStoppedCallback;
import games.brennan.dungeontrain.platform.event.DtServerStoppingCallback;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the four server-lifecycle events.
 * Auto-registered via {@link EventBusSubscriber}. Exact semantic passthrough — no
 * logic. Each event exposes only {@code getServer()}, which the bridge forwards.
 *
 * <p><b>Priority preservation:</b> DT's lifecycle handlers span several
 * {@code EventPriority} tiers, which order them against OTHER mods' listeners on
 * the NeoForge bus. To preserve that, this bridge subscribes each event once per
 * tier DT actually uses — each method annotated with the matching
 * {@code EventPriority} — and fires only that tier's bucket
 * ({@code DtEvents.*.listeners(DtPriority.*)}). Other mods' listeners interleave
 * between DT's tiers exactly as before. Tiers used today:
 * <ul>
 *   <li>Starting: HIGHEST, HIGH, NORMAL</li>
 *   <li>Started: NORMAL, LOW</li>
 *   <li>Stopping: NORMAL, LOWEST</li>
 *   <li>Stopped: HIGHEST, NORMAL</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NeoForgeLifecycleBridge {

    private NeoForgeLifecycleBridge() {}

    // ---- ServerStartingEvent (HIGHEST, HIGH, NORMAL) ----------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStartingHighest(ServerStartingEvent event) {
        for (DtServerStartingCallback cb : DtEvents.SERVER_STARTING.listeners(DtPriority.HIGHEST)) {
            cb.onServerStarting(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerStartingHigh(ServerStartingEvent event) {
        for (DtServerStartingCallback cb : DtEvents.SERVER_STARTING.listeners(DtPriority.HIGH)) {
            cb.onServerStarting(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onServerStartingNormal(ServerStartingEvent event) {
        for (DtServerStartingCallback cb : DtEvents.SERVER_STARTING.listeners(DtPriority.NORMAL)) {
            cb.onServerStarting(event.getServer());
        }
    }

    // ---- ServerStartedEvent (NORMAL, LOW) ---------------------------------

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onServerStartedNormal(ServerStartedEvent event) {
        for (DtServerStartedCallback cb : DtEvents.SERVER_STARTED.listeners(DtPriority.NORMAL)) {
            cb.onServerStarted(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStartedLow(ServerStartedEvent event) {
        for (DtServerStartedCallback cb : DtEvents.SERVER_STARTED.listeners(DtPriority.LOW)) {
            cb.onServerStarted(event.getServer());
        }
    }

    // ---- ServerStoppingEvent (NORMAL, LOWEST) -----------------------------

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onServerStoppingNormal(ServerStoppingEvent event) {
        for (DtServerStoppingCallback cb : DtEvents.SERVER_STOPPING.listeners(DtPriority.NORMAL)) {
            cb.onServerStopping(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStoppingLowest(ServerStoppingEvent event) {
        for (DtServerStoppingCallback cb : DtEvents.SERVER_STOPPING.listeners(DtPriority.LOWEST)) {
            cb.onServerStopping(event.getServer());
        }
    }

    // ---- ServerStoppedEvent (HIGHEST, NORMAL) -----------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStoppedHighest(ServerStoppedEvent event) {
        for (DtServerStoppedCallback cb : DtEvents.SERVER_STOPPED.listeners(DtPriority.HIGHEST)) {
            cb.onServerStopped(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onServerStoppedNormal(ServerStoppedEvent event) {
        for (DtServerStoppedCallback cb : DtEvents.SERVER_STOPPED.listeners(DtPriority.NORMAL)) {
            cb.onServerStopped(event.getServer());
        }
    }
}
