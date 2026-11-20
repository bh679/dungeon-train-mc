package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player copy of each client's Tribute webhooks ({@link TributeWebhook}), seeded on login
 * and on every change by {@link games.brennan.dungeontrain.net.TributeWebhookSyncPacket}.
 *
 * <p>Memory only: never written to disk and never logged. Dropped on logout and wholesale on server stop,
 * so the links are held only while their owner is online. Only links that pass {@link TributeWebhook#isValid}
 * are ever stored — this is where the server's own check happens, whatever the client sent.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeWebhookMirror {

    private static final Map<UUID, List<String>> LINKS = new ConcurrentHashMap<>();

    private TributeWebhookMirror() {}

    /**
     * Store {@code player}'s links: only the Discord webhook links among {@code urls}, without repeats,
     * at most {@link TributeWebhook#MAX_LINKS}. None left clears them.
     */
    public static void set(ServerPlayer player, List<String> urls) {
        if (player == null) return;
        List<String> links = TributeWebhook.cleanList(urls);
        if (links.isEmpty()) {
            LINKS.remove(player.getUUID());
        } else {
            LINKS.put(player.getUUID(), links);
        }
    }

    /** {@code playerId}'s links this session; empty when none. */
    public static List<String> get(UUID playerId) {
        return playerId == null ? List.of() : LINKS.getOrDefault(playerId, List.of());
    }

    /** Whether {@code playerId} has at least one link set. */
    public static boolean has(UUID playerId) {
        return !get(playerId).isEmpty();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LINKS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LINKS.clear();
    }
}
