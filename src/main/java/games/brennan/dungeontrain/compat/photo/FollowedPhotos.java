package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.PoolSelection.PoolPhoto;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Favourite Author's photos: per player, a small window of unseen photos by the authors they follow,
 * from the relay's {@code /photos/followed}. Kept apart from {@link SharedPhotos}' shared pool, which
 * is one window for the whole server; this one belongs to one player and is drawn only for them.
 * A window runs dry, then refills on the next ask once {@link #REFETCH_MS} has passed.
 */
final class FollowedPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Most photos fetched per player at once. */
    static final int LIMIT = 4;
    /** Least time between two fetches for one player. */
    static final long REFETCH_MS = 60_000;

    private record Window(List<PoolPhoto> photos, long fetchedAt, boolean inFlight) {}

    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();

    private FollowedPhotos() {}

    /**
     * Take one of {@code player}'s followed photos, or null when none is held. Asks for more when the
     * window is empty. Server thread.
     */
    static PoolPhoto take(ServerPlayer player, List<String> tokens, Collection<Integer> spent) {
        UUID id = player.getUUID();
        Window w = WINDOWS.getOrDefault(id, new Window(List.of(), 0L, false));
        List<PoolPhoto> left = w.photos().stream().filter(p -> !spent.contains(p.id())).toList();
        if (left.isEmpty()) {
            fetch(player, tokens, spent, w);
            return null;
        }
        PoolPhoto photo = left.get(0);
        WINDOWS.put(id, new Window(left.subList(1, left.size()), w.fetchedAt(), w.inFlight()));
        return photo;
    }

    private static void fetch(ServerPlayer player, List<String> tokens, Collection<Integer> spent, Window w) {
        long now = System.currentTimeMillis();
        if (tokens.isEmpty() || w.inFlight() || now - w.fetchedAt() < REFETCH_MS) return;
        UUID id = player.getUUID();
        WINDOWS.put(id, new Window(List.of(), now, true));
        MinecraftServer server = player.getServer();
        String base = DungeonTrain.relayBaseUrl();
        String follower = id.toString().replace("-", "");
        StringBuilder url = new StringBuilder(base).append("/photos/followed?limit=").append(LIMIT)
            .append("&follower=").append(follower)
            .append("&follow=").append(URLEncoder.encode(String.join(",", tokens), StandardCharsets.UTF_8));
        String exclude = spent.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (!exclude.isEmpty()) url.append("&exclude=").append(exclude);
        if (WorldLanguage.hostFetchesKidSafeBooks(server)) url.append("&kidsafe=1");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(SharedPhotos.REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
            SharedPhotos.HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) return List.<PoolPhoto>of();
                    List<PoolSelection.Row> rows = PoolSelection.parseRows(response.body(), total -> { });
                    return PoolSelection.download(rows, List.of(), SharedPhotos.imageFetcher(base), now, Set.of(follower));
                })
                .whenComplete((photos, error) -> server.execute(() -> {
                    List<PoolPhoto> got = error == null && photos != null ? photos : List.of();
                    if (error != null) LOGGER.debug("[DungeonTrain] Followed photos fetch failed: {}", error.toString());
                    WINDOWS.put(id, new Window(new ArrayList<>(got), now, false));
                }));
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Followed photos fetch failed to start: {}", e.toString());
            WINDOWS.put(id, new Window(List.of(), now, false));
        }
    }

    static void forget(UUID player) {
        WINDOWS.remove(player);
    }

    static void clear() {
        WINDOWS.clear();
    }
}
