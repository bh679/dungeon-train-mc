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
 * Server-side, per-player copy of each client's own Discord webhooks ({@link TributeWebhook}) and the
 * feeds each gets ({@link WebhookLink}), seeded on login
 * and on every change by {@link games.brennan.dungeontrain.net.TributeWebhookSyncPacket}.
 *
 * <p>Memory only: never written to disk and never logged. Dropped on logout and wholesale on server stop,
 * so the links are held only while their owner is online. Only links that pass {@link TributeWebhook#isValid}
 * are ever stored — this is where the server's own check happens, whatever the client sent.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeWebhookMirror {

    private static final Map<UUID, List<WebhookLink>> LINKS = new ConcurrentHashMap<>();

    private TributeWebhookMirror() {}

    /**
     * Store {@code player}'s links: only the Discord webhook links among {@code links}, without repeats,
     * at most {@link TributeWebhook#MAX_LINKS}, and only those that get at least one feed. None left clears them.
     */
    public static void set(ServerPlayer player, List<WebhookLink> links) {
        if (player == null) return;
        List<WebhookLink> kept = WebhookLink.cleanList(links).stream().filter(l -> l.feeds() != 0).toList();
        if (kept.isEmpty()) {
            LINKS.remove(player.getUUID());
        } else {
            LINKS.put(player.getUUID(), kept);
        }
    }

    /** {@code playerId}'s links that get {@code feed} this session; empty when none. */
    public static List<String> get(UUID playerId, WebhookFeed feed) {
        return playerId == null ? List.of() : WebhookLink.urlsFor(LINKS.getOrDefault(playerId, List.of()), feed);
    }

    /** Whether {@code playerId} has at least one link that gets {@code feed}. */
    public static boolean has(UUID playerId, WebhookFeed feed) {
        return !get(playerId, feed).isEmpty();
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
