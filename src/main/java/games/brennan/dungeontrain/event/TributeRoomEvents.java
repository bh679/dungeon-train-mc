package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.tribute.TributeRoomDeathKit;
import games.brennan.dungeontrain.portal.tribute.TributeRoomVisitService;
import games.brennan.dungeontrain.portal.tribute.TributeRoomVisitSessions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * The tribute room visit's lifecycle around the visit itself: the drops remembered at death
 * ({@link TributeRoomDeathKit}), a visitor who logs out, and a server that stops with visitors inside.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeRoomEvents {

    private TributeRoomEvents() {}

    /** After every other handler has had its say on the drops, so the kit is what really fell. */
    /**
     * Dying inside the visit is not a death: the player is already dead for the run, and a second one
     * would open a second recap over the first. They are healed and stood back in front of the TV.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        TributeRoomVisitSessions.Session session = TributeRoomVisitSessions.get(player.getUUID());
        if (session == null) return;
        event.setCanceled(true);
        player.setHealth(player.getMaxHealth());
        player.clearFire();
        player.removeAllEffects();
        player.getFoodData().setFoodLevel(20);
        net.minecraft.server.level.ServerLevel overworld = player.getServer() == null ? null
            : player.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld != null) {
            games.brennan.dungeontrain.portal.tribute.TributeRoomCamera.Pose pose = session.pose();
            player.teleportTo(overworld, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        if (player.getServer() != null && player.getServer().isHardcore()) return;
        TributeRoomDeathKit.remember(player.getUUID(), event.getDrops());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        TributeRoomDeathKit.forget(player.getUUID());
        games.brennan.dungeontrain.portal.tribute.LastDeathReports.forget(player.getUUID());
        if (TributeRoomVisitSessions.isVisiting(player.getUUID())) TributeRoomVisitService.end(player, true);
    }

    /** Ahead of the world's own save, so a Quit from inside the room still keeps the room. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        if (TributeRoomVisitSessions.isEmpty()) return;
        TributeRoomVisitService.saveAll(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TributeRoomVisitSessions.clear();
        TributeRoomDeathKit.clear();
        games.brennan.dungeontrain.portal.tribute.TributeRoomRegistry.clear();
        games.brennan.dungeontrain.portal.tribute.LastDeathReports.clear();
    }
}
