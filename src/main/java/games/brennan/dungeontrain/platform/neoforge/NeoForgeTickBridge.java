package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEntityTickCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtLevelTickCallback;
import games.brennan.dungeontrain.platform.event.DtPlayerTickCallback;
import games.brennan.dungeontrain.platform.event.DtServerTickCallback;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the tick events. Auto-registered
 * via {@link EventBusSubscriber}. Exact semantic passthrough — no logic. Every DT
 * tick handler was NORMAL priority, so each event is subscribed once at NORMAL
 * and fires {@code listeners()} (all buckets, HIGHEST→LOWEST) in order — which for
 * a single populated tier is identical to the old behaviour. {@code isEmpty()}
 * guards keep the per-tick cost to a single check when nothing is registered.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NeoForgeTickBridge {

    private NeoForgeTickBridge() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (DtEvents.LEVEL_TICK.isEmpty()) {
            return;
        }
        for (DtLevelTickCallback cb : DtEvents.LEVEL_TICK.listeners()) {
            cb.onLevelTick(event.getLevel());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DtEvents.SERVER_TICK.isEmpty()) {
            return;
        }
        for (DtServerTickCallback cb : DtEvents.SERVER_TICK.listeners()) {
            cb.onServerTick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (DtEvents.PLAYER_TICK.isEmpty()) {
            return;
        }
        for (DtPlayerTickCallback cb : DtEvents.PLAYER_TICK.listeners()) {
            cb.onPlayerTick(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (DtEvents.ENTITY_TICK.isEmpty()) {
            return;
        }
        for (DtEntityTickCallback cb : DtEvents.ENTITY_TICK.listeners()) {
            cb.onEntityTick(event.getEntity());
        }
    }
}
