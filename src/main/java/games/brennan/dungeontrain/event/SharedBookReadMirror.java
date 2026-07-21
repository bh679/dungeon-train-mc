package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player session mirror of each client's GLOBAL community-book read history — the relay
 * pool ids a player has read across every world on their machine. The durable store is client-side
 * ({@code ClientDisplayConfig.readSharedIds()}, in {@code dungeontrain-client.toml}); the client seeds this
 * mirror on login and tops it up as it reads more, via
 * {@link games.brennan.dungeontrain.net.SharedBookReadSyncPacket}.
 *
 * <p>This is the <b>fallback</b> source for the shared-book loot selector's unread-first step
 * ({@code SharedBookSelector} via {@code NarrativeBookEvents.resolvePending}). The PRIMARY signal is the
 * relay itself: sending {@code &uuid=} makes {@code /books/pool} already-globally-unread-biased. This mirror
 * covers the cases the relay can't — an older relay, a player who declined network consent (so the relay has
 * no reads for them), or an unreachable relay — because it is client-authoritative and does not depend on
 * telemetry consent. It replaces the retired per-world {@code NarrativeProgressData.sharedBooksReadByPlayer}.</p>
 *
 * <p>Mirrors {@link NetworkConsentMirror}: a concurrent map (the sync packet handler hops to the server
 * thread, but concurrent is cheap insurance), cleared per-player on logout ({@link #forget}, from
 * {@link PlayerJoinEvents}) and wholesale on server stop (each client re-seeds on its next login). Unlike
 * the consent mirror this is NOT fail-closed: an absent id simply reads as "unread" — the selector's
 * unread-first is a soft preference, and the caller never falls back to a built-in book merely because a
 * player has read everything (see {@code SharedBookSelector}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedBookReadMirror {

    /** Per-player set of read relay pool ids, seeded/updated from the client login + read syncs. */
    private static final Map<UUID, Set<Integer>> READ = new ConcurrentHashMap<>();

    private SharedBookReadMirror() {}

    /** Union {@code ids} into {@code player}'s mirrored read-set (login full-set or a single new id). Server thread. */
    public static void add(ServerPlayer player, Collection<Integer> ids) {
        if (player == null || ids == null || ids.isEmpty()) return;
        Set<Integer> set = READ.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        for (Integer id : ids) {
            if (id != null && id > 0) set.add(id);
        }
    }

    /** Whether {@code player}'s client has synced that they read the community book with relay pool {@code id}. */
    public static boolean has(ServerPlayer player, int id) {
        if (player == null) return false;
        Set<Integer> set = READ.get(player.getUUID());
        return set != null && set.contains(id);
    }

    /** Drop a player's mirrored read-set when they leave; called from {@link PlayerJoinEvents} logout. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            READ.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Nothing leaks into the next world: every client re-seeds its read-set on the next login.
        READ.clear();
    }
}
