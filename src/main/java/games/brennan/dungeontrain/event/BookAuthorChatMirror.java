package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player mirror of each client's "book author burn chat" preference. The client is
 * the authoritative store ({@code ClientDisplayConfig.BOOK_AUTHOR_BURN_CHAT}); it seeds this mirror
 * on login and on change via {@link games.brennan.dungeontrain.net.BookAuthorChatSyncPacket}.
 *
 * <p>Read by {@link games.brennan.dungeontrain.narrative.BookBurnAuthorMessage} when a burnable book
 * ignites, to decide who is told who wrote it.</p>
 *
 * <p>Unknown = {@code false}, unlike {@link PoliticalFilterMirror}'s locale fallback: this option is
 * off by default, so a player whose sync hasn't landed (or whose client is too old to send it) must
 * get silence, not chat they never asked for.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BookAuthorChatMirror {

    /** Per-player preference, seeded from the client sync. Absent = off. */
    private static final Map<UUID, Boolean> ENABLED = new ConcurrentHashMap<>();

    private BookAuthorChatMirror() {}

    /** Seed / update the mirror from the client's sync packet. Server thread. */
    public static void set(ServerPlayer player, boolean enabled) {
        if (player == null) return;
        ENABLED.put(player.getUUID(), enabled);
    }

    /** Whether {@code player} wants the author line when a book burns. Unsynced players: no. */
    public static boolean isEnabled(ServerPlayer player) {
        if (player == null) return false;
        return Boolean.TRUE.equals(ENABLED.get(player.getUUID()));
    }

    /** Drop a player's mirrored preference when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            ENABLED.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every client re-seeds its state on the next login.
        ENABLED.clear();
    }
}
