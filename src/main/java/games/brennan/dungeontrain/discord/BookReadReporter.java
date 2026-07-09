package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.BookReadClosedPacket;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Fire-and-forget uploader for book-READ telemetry — the "how are the books actually read" half of the
 * data-explorer's Books page. When a player finishes reading a Dungeon Train book (a random loot book,
 * a discovered community book, a narrative letter, or a welcome/starting book) the client measures the read
 * ({@link games.brennan.dungeontrain.client.BookReadClientEvents}) and sends a
 * {@link BookReadClosedPacket}; the server gates it on the player's network consent, enriches the
 * narrative fields, and POSTs a compact record to the relay's {@code /telemetry/book-read} endpoint.
 *
 * <p>Mirrors {@link SharedBookReporter} / {@link WorldInfoReporter}: a shared static {@link HttpClient},
 * a Gson-built JSON body, and an off-thread {@link HttpClient#sendAsync} whose result is only logged at
 * debug. The whole call is no-throw — a failed or slow report can never disrupt the packet handler or
 * cost a tick.</p>
 *
 * <p><b>Metadata + timings only.</b> The body carries ids, a display title/author (already public game
 * text) and timings — <em>never page text</em>. This matches the relay's rule (see {@code analytics.js}):
 * game-generated metadata is safe to store, verbatim player free-text is not.</p>
 */
public final class BookReadReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private BookReadReporter() {}

    /**
     * Build and fire the book-read report. No-op on any error. The caller (the packet handler) has
     * already gated on network consent; this method just does the transport. Narrative enrichment
     * ({@code story}/{@code letter}/{@code storyLetters}/{@code storyCompleted}) is server-computed and
     * only emitted for {@code bookType == "narrative"}.
     *
     * @param playerId       the reading player's UUID (sent dash-stripped, matching the other reporters)
     * @param playerName     the player's name, or {@code null}
     * @param p              the client-measured read (identity + timings, never page text)
     * @param story          narrative story basename (ignored unless narrative)
     * @param letter         narrative letter index (ignored unless narrative)
     * @param storyLetters   total letters in the story (server-resolved; ignored unless narrative)
     * @param storyCompleted whether the whole story is now read by this player (ignored unless narrative)
     */
    public static void report(UUID playerId, String playerName, BookReadClosedPacket p,
                              String story, int letter, int storyLetters, boolean storyCompleted) {
        try {
            if (playerId == null || p == null) return;
            String uuid = playerId.toString().replace("-", "");
            post(uuid, buildPayload(uuid, playerName, p, story, letter, storyLetters, storyCompleted).toString());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book-read report failed to build: {}", t.toString());
        }
    }

    /** Pure JSON assembly — package-private so the shape can be unit-tested without a running server. */
    static JsonObject buildPayload(String uuid, String playerName, BookReadClosedPacket p,
                                   String story, int letter, int storyLetters, boolean storyCompleted) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (playerName != null && !playerName.isEmpty()) body.addProperty("player", playerName);
        body.addProperty("bookType", p.bookType());
        body.addProperty("bookId", p.bookId());
        if (("random".equals(p.bookType()) || "starting".equals(p.bookType())) && p.variantIndex() >= 0) {
            body.addProperty("variantIndex", p.variantIndex());
        }
        if (p.title() != null && !p.title().isEmpty()) body.addProperty("title", p.title());
        if (p.author() != null && !p.author().isEmpty()) body.addProperty("author", p.author());
        body.addProperty("pageCount", p.pageCount());
        body.addProperty("pagesViewed", p.pagesViewed());
        body.addProperty("maxPage", p.maxPage());
        body.addProperty("completed", p.completed());
        body.addProperty("durationMs", p.durationMs());
        JsonArray dwell = new JsonArray();
        for (int v : p.pageDwellMs()) dwell.add(v);
        body.add("pageDwellMs", dwell);
        if ("narrative".equals(p.bookType())) {
            body.addProperty("story", story == null ? "" : story);
            body.addProperty("letter", letter);
            body.addProperty("storyLetters", storyLetters);
            body.addProperty("storyCompleted", storyCompleted);
        }
        return body;
    }

    private static void post(String uuid, String json) {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(DungeonTrain.relayBaseUrl() + "/telemetry/book-read"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> LOGGER.debug(
                        "[DungeonTrain] book-read report for {} -> HTTP {}.", uuid, resp.statusCode()))
                .exceptionally(e -> {
                    LOGGER.debug("[DungeonTrain] book-read report for {} failed: {}", uuid, e.toString());
                    return null;
                });
    }
}
