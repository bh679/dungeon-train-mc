package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Uploader for player-written "lectern letters". When a player signs a book &amp; quill that was
 * opened from a lectern (routed by {@code ServerGamePacketListenerImplSignBookMixin}), the signed
 * text is submitted to the Dungeon Train relay's {@code /narratives/submit} endpoint as the next
 * letter in that player's current-life narrative series, so it can enter the moderation queue and
 * surface in the data explorer's Narratives view.
 *
 * <p>Mirrors {@link SharedBookReporter}: a Gson-built JSON body handed to the durable
 * {@link RelayOutbox}, which persists it and delivers at-least-once on the next flush (surviving a
 * relay outage / offline launch rather than being dropped). The whole call is no-throw — a failed or
 * slow submit can never disrupt the signing packet handler or cost a tick. Adds {@code seriesId} +
 * {@code letterIndex} over the shared-book payload so the relay can group letters into a series.</p>
 */
public final class LetterReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LetterReporter() {}

    /**
     * Build and fire the letter submission for {@code playerId}. No-op on any error. Callers are
     * already gated (feature enabled + client network consent) by
     * {@code games.brennan.dungeontrain.event.SharedBookGate#canWriteLetter}; this method just does
     * the transport.
     *
     * @param playerId    the authoring player's UUID (sent dash-stripped, matching SharedBookReporter)
     * @param seriesId    opaque per-life series id (new life → new series)
     * @param letterIndex 1-based index of this letter within the series
     * @param title       the signed book title (player-typed; "Letter X" fallback resolved by caller)
     * @param author      the author name to credit (the signing player's name)
     * @param pages       the book pages in order (raw signed page text)
     */
    public static void submit(UUID playerId, String seriesId, int letterIndex,
                              String author, String title, List<String> pages) {
        try {
            if (playerId == null) return;
            String uuid = playerId.toString().replace("-", "");
            JsonObject payload = buildPayload(uuid, seriesId, letterIndex, author, title, pages);
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] letter submit failed to build: {}", t.toString());
        }
    }

    /**
     * Pure assembly of the {@code /narratives/submit} JSON body — package-private so the shape can be
     * unit-tested without a running server. Matches the relay contract exactly:
     * {@code {"uuid","seriesId","letterIndex","author","title","pages":[...]}}. A {@code null}
     * author/title/seriesId is emitted as an empty string; a {@code null} pages list as an empty array.
     */
    static JsonObject buildPayload(String uuid, String seriesId, int letterIndex,
                                   String author, String title, List<String> pages) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("seriesId", seriesId == null ? "" : seriesId);
        body.addProperty("letterIndex", letterIndex);
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
        RelayOutbox.get().enqueue("/narratives/submit", json);
        LOGGER.debug("[DungeonTrain] letter submit for {} queued to the relay outbox.", uuid);
    }
}
