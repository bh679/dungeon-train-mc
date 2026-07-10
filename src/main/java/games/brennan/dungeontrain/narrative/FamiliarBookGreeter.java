package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.relay.BookStatsClient;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Greets an author who is holding a community-pool LOOT copy of a book they wrote with a quiet
 * "a familiar book…" line reporting how the book is doing in the wild (see {@link FamiliarBookMessage}).
 *
 * <p>Only loot copies carry {@link SharedBookReadTag} (the relay pool id) — a player's own signed copy
 * burns on sharing and is never kept — so this naturally fires only when you stumble on your own book
 * out in the world as chest loot. Authorship is verified by the relay (UUID match), never by the book's
 * author-name text, so a renamed / spoofed author line can't surface someone else's stats.</p>
 *
 * <p>Fires at most once per (player, bookId) per login session. The relay lookup is async, so the pair
 * is marked attempted BEFORE the fetch — deduping both author hits and non-author misses — and a
 * player's set is dropped on logout.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FamiliarBookGreeter {

    private FamiliarBookGreeter() {}

    /** player uuid -> pool ids already looked up this session (dedupe; cleared on logout). */
    private static final Map<UUID, Set<Integer>> ATTEMPTED = new ConcurrentHashMap<>();

    /**
     * Attempt to greet {@code player} for the community book {@code stack}. No-op unless the stack is a
     * discovered loot copy (carries a pool id), the player has granted network consent (the lookup sends
     * their uuid), and this (player, book) pair hasn't been tried this session. The gray line is sent
     * only when the relay confirms the player authored the book.
     */
    public static void maybeGreet(ServerPlayer player, ItemStack stack) {
        OptionalInt idOpt = SharedBookReadTag.readId(stack);
        if (idOpt.isEmpty()) return;
        if (!NetworkConsentMirror.isGranted(player)) return; // the lookup sends the player's uuid
        int id = idOpt.getAsInt();
        UUID uuid = player.getUUID();
        Set<Integer> seen = ATTEMPTED.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(id)) return; // already looked up this book this session

        BookStatsClient.fetch(id, uuid, stats -> {
            if (!stats.isAuthor()) return;
            // Back onto the server thread before touching the player.
            player.server.execute(() -> {
                if (player.hasDisconnected()) return;
                player.sendSystemMessage(FamiliarBookMessage.build(stats, player.getRandom()));
            });
        });
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ATTEMPTED.remove(player.getUUID());
        }
    }
}
