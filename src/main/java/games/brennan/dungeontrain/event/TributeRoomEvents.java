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
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The tribute room visit's lifecycle around the visit itself: the drops remembered at death
 * ({@link TributeRoomDeathKit}), a visitor who logs out, and a server that stops with visitors inside.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeRoomEvents {

    private TributeRoomEvents() {}

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

    /**
     * Where everything was carried, while the inventory is still whole: the drops that follow have lost
     * it, and the kit is handed back in the same slots ({@link TributeRoomDeathKit#rememberLayout}).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeathLayout(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        if (player.getServer() != null && player.getServer().isHardcore()) return;
        if (TributeRoomVisitSessions.isVisiting(player.getUUID())) return;
        TributeRoomDeathKit.rememberLayout(player.getUUID(), player.getInventory());
    }

    /**
     * Start standing the room the moment they die, so the death cinematic and the perks menu both cover
     * the wait for its chunks. A tick later, so a handler that revives them after this one never starts a
     * room; the perks menu's own prepare is then a no-op.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeathPrepare(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null || server.isHardcore() || !games.brennan.dungeontrain.perks.PerksGate.isOn()) return;
        if (TributeRoomVisitSessions.isVisiting(player.getUUID())) return;
        server.execute(() -> {
            if (player.hasDisconnected() || !player.isDeadOrDying()) return;
            TributeRoomVisitService.prepare(player);
        });
    }

    /** After every other handler has had its say on the drops, so the kit is what really fell. */
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
        TributeRoomVisitService.discardPrepared(player);
    }

    /** A respawn that is not the visit (the vanilla button, a command): the room stood ahead is swept. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (TributeRoomVisitSessions.isVisiting(player.getUUID())) return;
        TributeRoomVisitService.discardPrepared(player);
    }

    /** Rooms being stood a step a tick, and sites swept once their visitors are out. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        TributeRoomVisitService.tick(event.getServer());
        games.brennan.dungeontrain.portal.tribute.TributeSiteCache.tick(event.getServer());
    }

    /** A perks world gets the kept visit site written in, or starts keeping one ({@code TributeSiteCache}). */
    @SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        games.brennan.dungeontrain.portal.tribute.TributeSiteCache.onServerStarted(event.getServer());
    }

    /**
     * Ahead of the world's own save, so a Quit from inside the room still keeps the room. Always waits
     * on the room files, since a visit that just ended may still have its save queued.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        TributeRoomVisitService.saveAll(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TributeRoomVisitSessions.clear();
        TributeRoomVisitService.clearPrepared();
        games.brennan.dungeontrain.portal.tribute.TributeSiteCache.clear();
        TributeRoomDeathKit.clear();
        games.brennan.dungeontrain.portal.tribute.TributeRoomRegistry.clear();
        games.brennan.dungeontrain.portal.tribute.LastDeathReports.clear();
    }
}
