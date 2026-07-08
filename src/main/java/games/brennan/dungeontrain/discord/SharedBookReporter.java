package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Fire-and-forget uploader for the community "shared books" CONTRIBUTION half. When a player signs a
 * book &amp; quill (intercepted by {@code ServerGamePacketListenerImplSignBookMixin}), the signed text is
 * POSTed to the Dungeon Train relay's {@code /books/submit} endpoint so it can enter the moderation
 * queue and — once approved — appear in other players' chest loot via {@link SharedBookPool}.
 *
 * <p>Mirrors {@link WorldInfoReporter}: a shared static {@link HttpClient}, a Gson-built JSON body, and
 * an off-thread {@link HttpClient#sendAsync} whose result is only logged at debug. The whole call is
 * no-throw — a failed or slow submit can never disrupt the signing packet handler or cost a tick. The
 * relay already carries the capability in {@link DungeonTrain#relayBaseUrl()}; the response is ignored
 * per the relay contract.</p>
 */
public final class SharedBookReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private SharedBookReporter() {}

    /**
     * Build and fire the shared-book submission for {@code playerId}. No-op on any error. Callers are
     * already gated (feature enabled + client network consent) by
     * {@code games.brennan.dungeontrain.event.SharedBookGate}; this method just does the transport.
     *
     * @param playerId the contributing player's UUID (sent dash-stripped, matching WorldInfoReporter)
     * @param author   the author name to credit on the book
     * @param title    the book title (raw signed title)
     * @param pages    the book pages in order (raw signed page text)
     */
    public static void submit(UUID playerId, String author, String title, List<String> pages) {
        try {
            if (playerId == null) return;
            String uuid = playerId.toString().replace("-", "");
            JsonObject payload = buildPayload(uuid, author, title, pages);
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-book submit failed to build: {}", t.toString());
        }
    }

    /**
     * Pure assembly of the {@code /books/submit} JSON body — package-private so the shape can be
     * unit-tested without a running server. Matches the relay contract exactly:
     * {@code {"uuid","author","title","pages":[...]}}. A {@code null} author/title is emitted as an
     * empty string; a {@code null} pages list as an empty array.
     */
    static JsonObject buildPayload(String uuid, String author, String title, List<String> pages) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("author", author == null ? "" : author);
        body.addProperty("title", title == null ? "" : title);
        JsonArray pagesArr = new JsonArray();
        if (pages != null) {
            for (String page : pages) {
                pagesArr.add(page == null ? "" : page);
            }
        }
        body.add("pages", pagesArr);
        return body;
    }

    private static void post(String uuid, String json) {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(DungeonTrain.relayBaseUrl() + "/books/submit"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> LOGGER.debug(
                        "[DungeonTrain] shared-book submit for {} -> HTTP {}.", uuid, resp.statusCode()))
                .exceptionally(e -> {
                    LOGGER.debug("[DungeonTrain] shared-book submit for {} failed: {}", uuid, e.toString());
                    return null;
                });
    }
}
