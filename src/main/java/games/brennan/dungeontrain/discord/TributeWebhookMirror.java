package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player copy of each client's Tribute webhook ({@link TributeWebhook}), seeded on login
 * and on every change by {@link games.brennan.dungeontrain.net.TributeWebhookSyncPacket}.
 *
 * <p>Memory only: never written to disk and never logged. Dropped on logout and wholesale on server stop,
 * so the link is held only while its owner is online. Only a link that passes {@link TributeWebhook#isValid}
 * is ever stored — this is where the server's own check happens, whatever the client sent.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeWebhookMirror {

    private static final Map<UUID, String> LINKS = new ConcurrentHashMap<>();

    private TributeWebhookMirror() {}

    /** Store {@code player}'s link, or clear it when {@code url} is blank or not a Discord webhook link. */
    public static void set(ServerPlayer player, String url) {
        if (player == null) return;
        if (TributeWebhook.isValid(url)) {
            LINKS.put(player.getUUID(), TributeWebhook.normalize(url));
        } else {
            LINKS.remove(player.getUUID());
        }
    }

    /** {@code playerId}'s link, if they set one this session. */
    public static Optional<String> get(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(LINKS.get(playerId));
    }

    /** Whether {@code playerId} has a link set. */
    public static boolean has(UUID playerId) {
        return get(playerId).isPresent();
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
