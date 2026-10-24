package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient.Restore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * When to ask the relay about an admin inventory restore, and when to apply one.
 *
 * <p>Asks once per login only — a restore queued while the player is in-game arrives at their next
 * login. An answer is parked here and applied once the player has been in the world
 * {@value #SETTLE_TICKS} ticks, after the login intro, starting book and loadout handlers have
 * finished writing to their inventory.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class InventoryRestoreEvents {

    /** 30 seconds in-world before anything is applied. */
    static final int SETTLE_TICKS = 20 * 30;
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** Server-thread only. One parked restore per player; a newer answer replaces it. */
    private static final Map<UUID, Restore> PARKED = new HashMap<>();
    private static int tick = 0;

    private InventoryRestoreEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ask(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PARKED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PARKED.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tick++;
        if (tick % CHECK_INTERVAL_TICKS == 0 && !PARKED.isEmpty()) applySettled(server);
    }

    private static void ask(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        InventoryRestoreClient.fetch(uuid, restore -> server.execute(() -> PARKED.put(uuid, restore)));
    }

    private static void applySettled(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Restore>> it = PARKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Restore> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.tickCount < SETTLE_TICKS || !player.isAlive()) continue;
            it.remove();
            InventoryRestore.apply(player, e.getValue());
        }
    }
}
