package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.home.HomeRoomDeathKit;
import games.brennan.dungeontrain.portal.home.HomeRoomVisitService;
import games.brennan.dungeontrain.portal.home.HomeRoomVisitSessions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The home room visit's lifecycle around the visit itself: the drops remembered at death
 * ({@link HomeRoomDeathKit}), a visitor who logs out, and a server that stops with visitors inside.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HomeRoomEvents {

    private HomeRoomEvents() {}

    /** After every other handler has had its say on the drops, so the kit is what really fell. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        if (player.getServer() != null && player.getServer().isHardcore()) return;
        HomeRoomDeathKit.remember(player.getUUID(), event.getDrops());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        HomeRoomDeathKit.forget(player.getUUID());
        if (HomeRoomVisitSessions.isVisiting(player.getUUID())) HomeRoomVisitService.end(player, true);
    }

    /** Ahead of the world's own save, so a Quit from inside the room still keeps the room. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        if (HomeRoomVisitSessions.isEmpty()) return;
        HomeRoomVisitService.saveAll(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HomeRoomVisitSessions.clear();
        HomeRoomDeathKit.clear();
        games.brennan.dungeontrain.portal.home.HomeRoomRegistry.clear();
    }
}
