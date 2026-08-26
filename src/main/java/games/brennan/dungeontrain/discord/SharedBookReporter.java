package games.brennan.dungeontrain.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Uploader for the community "shared books" CONTRIBUTION half. When a player signs a book &amp; quill
 * (intercepted by {@code ServerGamePacketListenerImplSignBookMixin}), the signed text is submitted to
 * the Dungeon Train relay's {@code /books/submit} endpoint so it can enter the moderation queue and —
 * once approved — appear in other players' chest loot via {@link SharedBookPool}.
 *
 * <p>Mirrors {@link WorldInfoReporter}: a Gson-built JSON body handed to the durable
 * {@link RelayOutbox}, which persists it and delivers at-least-once on the next flush (surviving a
 * relay outage / offline launch rather than being dropped). The whole call is no-throw — a failed or
 * slow submit can never disrupt the signing packet handler or cost a tick.</p>
 */
public final class SharedBookReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

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
     * @param lang     the author's client language code (e.g. {@code "en_us"}); {@code null}/blank when
     *                 the client hasn't synced one — emitted as an empty string
     */
    public static void submit(UUID playerId, String author, String title, List<String> pages, String lang) {
        try {
            if (playerId == null) return;
            String uuid = playerId.toString().replace("-", "");
            // One idempotency handle per SIGNING, minted here and carried in the body the outbox
            // persists — so every at-least-once re-send of this upload carries the same key.
            JsonObject payload = buildPayload(uuid, author, title, pages, lang,
                    UUID.randomUUID().toString().replace("-", ""));
            post(uuid, payload.toString());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-book submit failed to build: {}", t.toString());
        }
    }

    /**
     * Pure assembly of the {@code /books/submit} JSON body — package-private so the shape can be
     * unit-tested without a running server. Matches the relay contract exactly:
     * {@code {"uuid","author","title","pages":[...],"lang","key"}}. A {@code null} author/title/lang is
     * emitted as an empty string; a {@code null} pages list as an empty array.
     *
     * <p>{@code key} is the upload's IDEMPOTENCY handle. {@link RelayOutbox} is at-least-once: it holds
     * an item until the relay confirms a 2xx, so a book the relay stored whose response never got home
     * (a restart, a timeout, an aborted connection) is re-sent byte-for-byte. Because the key lives in
     * the persisted BODY, every one of those re-sends carries the same value, and the relay can tell
     * the retry apart from a real re-upload instead of suspending a writer for its own hiccup. A blank
     * key is simply omitted — an older relay ignores the field either way.</p>
     */
    static JsonObject buildPayload(String uuid, String author, String title, List<String> pages, String lang,
                                   String key) {
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
        body.addProperty("lang", lang == null ? "" : lang);
        if (key != null && !key.isBlank()) {
            body.addProperty("key", key);
        }
        return body;
    }

    private static void post(String uuid, String json) {
        RelayOutbox.get().enqueue("/books/submit", json);
        LOGGER.debug("[DungeonTrain] shared-book submit for {} queued to the relay outbox.", uuid);
    }
}
