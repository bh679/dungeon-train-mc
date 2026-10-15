package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.client.live.LiveChatRules;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The relay side of taking over the Live Eye from someone already on air.
 *
 * <p>A claim against an on-air streamer comes back 409 ({@link #parseBlocked}): {@code too_soon} inside
 * their protected first minutes — the wearer may ask the audience ({@link #request}) — or
 * {@code occupied} past them, when the wearer decides alone and claims again with {@code takeover}. A
 * vote is polled once a second ({@link #status}); approved hands the requester a one-use ticket their
 * next claim carries. Votes themselves are ordinary live-chat lines ({@code yes} / {@code no}) the relay
 * counts and keeps out of the room, so this class never sends one.</p>
 */
public final class LiveTakeoverClient {

    private LiveTakeoverClient() {}

    /**
     * Who is in the way of a claim, for the takeover prompt: {@code holder} has been on air
     * {@code tenureMs} (carried across their re-wears), protected until {@code takeoverMinMs}; this is
     * their {@code streamCount}th time streaming. {@code voteNeeded}: still inside the protected minutes.
     * {@code pollEndsMs}: another player's takeover vote closes then (0 when none is running).
     */
    public record Holder(String holder, long tenureMs, long takeoverMinMs, int streamCount, boolean voteNeeded,
                         long pollEndsMs) {
        public Holder(String holder, long tenureMs, long takeoverMinMs, int streamCount, boolean voteNeeded) {
            this(holder, tenureMs, takeoverMinMs, streamCount, voteNeeded, 0L);
        }

        /** Whether the vote is still needed {@code elapsedMs} after this was read (the prompt's live counter). */
        public boolean voteNeededAfter(long elapsedMs) {
            return tenureMs + elapsedMs < takeoverMinMs;
        }
    }

    /** A vote as the requester sees it. {@code ticket} only once approved. */
    public record Poll(String id, String state, long endsTs, int yes, int no, @Nullable String ticket) {
        public boolean open() { return "open".equals(state); }
        public boolean approved() { return "approved".equals(state); }
        public boolean rejected() { return "rejected".equals(state); }
    }

    /**
     * A takeover vote's opening or result line in the chat room. {@code holder} is the streamer being
     * voted on, {@code holderUuid} theirs (32-hex) so they can be told their vote's weight; {@code yes}/{@code no}
     * are −1 until the result.
     */
    public record PollLine(String id, String requester, String holder, @Nullable String holderUuid, long endsTs,
                           int holderWeight, String state, int yes, int no) {
        public boolean open() { return "open".equals(state); }
    }

    /** The 409 of a claim against an on-air streamer, or null for any other reply. Package-private for tests. */
    @Nullable
    public static Holder parseBlocked(Result r) {
        if (r.status() != 409 || r.body() == null) return null;
        String error = r.str("error");
        boolean tooSoon = "too_soon".equals(error);
        if (!tooSoon && !"occupied".equals(error)) return null;
        JsonObject b = r.body();
        String holder = LiveChatRules.clean(r.str("holder"), LiveChatRules.MAX_AUTHOR);
        return new Holder(holder.isBlank() ? "Someone" : holder, num(b, "tenureMs"), num(b, "takeoverMinMs"),
            (int) Math.max(1, num(b, "streamCount")), tooSoon, num(b, "pollEndsTs"));
    }

    /** Ask the audience to let this player take over. 200 → {@link #parsePoll}; 409 not_needed / busy / too_soon. */
    public static CompletableFuture<Result> request(UUID uuid, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        return LiveFeedClient.post("/live/takeover/request", body);
    }

    /** The vote {@code id} as {@code uuid} (the requester) sees it. */
    public static CompletableFuture<Result> status(String id, UUID uuid) {
        return LiveFeedClient.get("/live/takeover/status?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&uuid=" + uuid);
    }

    /** The requester took their Live Eye off: withdraw the vote. Fire-and-forget. */
    public static CompletableFuture<Result> cancel(String id, UUID uuid) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("uuid", uuid.toString());
        return LiveFeedClient.post("/live/takeover/cancel", body);
    }

    /** A request or status reply as a {@link Poll}; null when it is not one. An unknown vote reads {@code void}. */
    @Nullable
    public static Poll parsePoll(Result r) {
        if (!r.ok()) return null;
        String id = r.str("id");
        String state = r.str("state");
        if (id.isEmpty() || state.isEmpty()) return null;
        JsonObject b = r.body();
        String ticket = r.str("ticket");
        return new Poll(id, state, num(b, "endsTs"), (int) num(b, "yes"), (int) num(b, "no"), ticket.isEmpty() ? null : ticket);
    }

    /** The {@code poll} object of a chat-line object, or null. Package-private for tests. */
    @Nullable
    static PollLine parsePollLine(JsonObject m) {
        if (!m.has("poll") || !m.get("poll").isJsonObject()) return null;
        JsonObject p = m.getAsJsonObject("poll");
        String id = str(p, "id");
        String state = str(p, "state");
        if (id.isEmpty() || state.isEmpty()) return null;
        String requester = LiveChatRules.clean(str(p, "requester"), LiveChatRules.MAX_AUTHOR);
        String holder = LiveChatRules.clean(str(p, "holder"), LiveChatRules.MAX_AUTHOR);
        String holderUuid = str(p, "holderUuid");
        return new PollLine(id, requester, holder, holderUuid.isEmpty() ? null : holderUuid, num(p, "endsTs"),
            (int) Math.max(1, num(p, "holderWeight")), state,
            p.has("yes") ? (int) num(p, "yes") : -1, p.has("no") ? (int) num(p, "no") : -1);
    }

    private static long num(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
