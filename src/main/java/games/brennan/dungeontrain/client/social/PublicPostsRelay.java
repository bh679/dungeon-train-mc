package games.brennan.dungeontrain.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The relay half of the Public posts switch ({@code ClientDisplayConfig.isPublicPosts()}).
 *
 * <ul>
 *   <li>{@code POST /public-posts {uuid, on}} tells the relay whether this player's photos and deaths may
 *       be posted: it skips an opted-out player's story candidates and drops their death and photo posts
 *       to Discord, which also covers someone else tributing their photo while they are offline. Sent on
 *       every login as well as on a change, so the relay always holds this player's real setting even if
 *       someone else wrote to it.</li>
 *   <li>{@code GET /public-posts/featured?uuid=} lists this player's stories that posted and that they
 *       have not been told about; {@code POST /public-posts/featured/ack {uuid, ids}} marks them told.</li>
 * </ul>
 *
 * <p>Nothing is sent without the internet connection consent. An older relay answers 404, which is
 * treated as "nothing to do". All calls resolve off-thread and never throw.</p>
 */
public final class PublicPostsRelay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String SET_PATH = "/public-posts";
    static final String FEATURED_PATH = "/public-posts/featured";
    static final String ACK_PATH = "/public-posts/featured/ack";

    /** One platform a story went to, and the link to it there. */
    public record Link(String platform, String url) {}

    /** One story that posted: what it showed, and every platform it went to, in order. Never empty. */
    public record Featured(String id, String kind, List<Link> links,
                           @Nullable Integer carriage, @Nullable String title) {
        public Featured {
            links = List.copyOf(links);
        }

        public boolean isDeath() {
            return "death".equals(kind);
        }
    }

    private PublicPostsRelay() {}

    /** Send the current switch to the relay. Fire and forget. */
    public static void syncNow() {
        String uuid = ownUuid();
        if (uuid == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("on", ClientDisplayConfig.isPublicPosts());
        post(SET_PATH, body).exceptionally(t -> null);
    }

    /** This player's un-told stories; empty on any failure, without consent, or with the switch off. */
    public static CompletableFuture<List<Featured>> fetchFeatured() {
        String uuid = ownUuid();
        if (uuid == null || !ClientDisplayConfig.isPublicPosts()) return CompletableFuture.completedFuture(List.of());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + FEATURED_PATH + "?uuid=" + uuid))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 ? parseFeatured(r.body()) : List.<Featured>of())
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] public-posts featured fetch failed: {}", t.toString());
                    return List.of();
                });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] public-posts featured fetch failed: {}", e.toString());
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** Mark these stories told, so they are not shown again. Fire and forget. */
    public static void ack(Collection<String> ids) {
        String uuid = ownUuid();
        if (uuid == null || ids.isEmpty()) return;
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        JsonArray arr = new JsonArray();
        ids.forEach(arr::add);
        body.add("ids", arr);
        post(ACK_PATH, body).exceptionally(t -> null);
    }

    /**
     * The {@code /featured} body's items, skipping any without an id or a usable https link. Each item's
     * {@code links} list wins; an item without one falls back to its single {@code platform}/{@code url}. Pure.
     */
    static List<Featured> parseFeatured(String body) {
        List<Featured> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("items")) return out;
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("items")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = str(o, "id");
                List<Link> links = links(o);
                if (id == null || links.isEmpty()) continue;
                Integer carriage = o.has("carriage") && o.get("carriage").isJsonPrimitive()
                    && o.get("carriage").getAsJsonPrimitive().isNumber() ? o.get("carriage").getAsInt() : null;
                out.add(new Featured(id, str(o, "kind"), links, carriage, str(o, "title")));
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("[DungeonTrain] public-posts featured body unreadable: {}", ex.toString());
        }
        return out;
    }

    /** An item's usable links: its {@code links} list, else its single platform/url. https only. */
    private static List<Link> links(JsonObject o) {
        List<Link> out = new ArrayList<>();
        if (o.has("links") && o.get("links").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("links")) {
                if (e.isJsonObject()) addLink(out, str(e.getAsJsonObject(), "platform"), str(e.getAsJsonObject(), "url"));
            }
        }
        if (out.isEmpty()) addLink(out, str(o, "platform"), str(o, "url"));
        return out;
    }

    private static void addLink(List<Link> out, @Nullable String platform, @Nullable String url) {
        if (url != null && url.startsWith("https://")) out.add(new Link(platform == null ? "" : platform, url));
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static CompletableFuture<Void> post(String path, JsonObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenAccept(r -> {
                    if (r.statusCode() / 100 != 2) {
                        LOGGER.debug("[DungeonTrain] public-posts {} answered {}", path, r.statusCode());
                    }
                })
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] public-posts {} failed: {}", path, t.toString());
                    return null;
                });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] public-posts {} failed: {}", path, e.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** This player's uuid without dashes, or null without the internet connection consent. */
    @Nullable
    private static String ownUuid() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
        if (uuid == null || !DiscordPresenceClientConfig.isGranted()) return null;
        return uuid.toString().replace("-", "");
    }
}
