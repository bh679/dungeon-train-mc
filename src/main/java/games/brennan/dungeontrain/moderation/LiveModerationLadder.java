package games.brennan.dungeontrain.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

/**
 * The relay's verdict on a strike or a status check, and the local guess made while waiting for it.
 *
 * <p>The relay ({@code livestrikes.js}) owns the count. It answers {@code POST /live/moderation/strike}
 * and {@code GET /live/moderation/status} with the same shape:
 * {@code {ok, strikes, removals, action: warn|remove|ban|none, banned, permanent, remainingSec}}.
 * {@link #parse} reads it; anything not well-formed is {@code null}, which callers treat as "no news"
 * — a relay outage never punishes anyone and never clears a ban already cached.</p>
 */
public final class LiveModerationLadder {

    public enum Action { NONE, WARN, REMOVE, BAN }

    /** What the relay said. {@code remainingSec} is only meaningful while {@code banned}. */
    public record Verdict(int strikes, int removals, Action action, boolean banned, boolean permanent, long remainingSec) {

        public static final Verdict CLEAN = new Verdict(0, 0, Action.NONE, false, false, 0);

        /** Whether a Live Eye may be worn: not banned, or a timed ban that has run out. */
        public boolean mayStream(long nowMs, long receivedAtMs) {
            if (!banned) return true;
            if (permanent) return false;
            return nowMs - receivedAtMs >= remainingSec * 1000L;
        }

        /** Seconds of ban left as of {@code nowMs}, measured from when the verdict arrived. */
        public long remainingAt(long nowMs, long receivedAtMs) {
            if (!banned) return 0;
            if (permanent) return Long.MAX_VALUE;
            return Math.max(0, remainingSec - (nowMs - receivedAtMs) / 1000L);
        }
    }

    private LiveModerationLadder() {}

    /** The relay body as a verdict, or null when it isn't a well-formed {@code ok} answer. */
    @Nullable
    public static Verdict parse(@Nullable String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            if (!o.has("ok") || !o.get("ok").isJsonPrimitive() || !o.get("ok").getAsBoolean()) return null;
            int strikes = intOr(o, "strikes", -1);
            if (strikes < 0) return null;
            int removals = Math.max(0, intOr(o, "removals", 0));
            boolean banned = boolOr(o, "banned", false);
            boolean permanent = boolOr(o, "permanent", false);
            long remaining = Math.max(0, longOr(o, "remainingSec", 0));
            if (!banned) { permanent = false; remaining = 0; }
            return new Verdict(strikes, removals, action(o), banned, permanent, remaining);
        } catch (Exception e) {
            return null;
        }
    }

    private static Action action(JsonObject o) {
        if (!o.has("action") || !o.get("action").isJsonPrimitive()) return Action.NONE;
        return switch (o.get("action").getAsString()) {
            case "warn" -> Action.WARN;
            case "remove" -> Action.REMOVE;
            case "ban" -> Action.BAN;
            default -> Action.NONE;
        };
    }

    /**
     * The warning a player hears the moment a word is masked, before the relay answers: the local count
     * stepped by one against the ladder. Only the words change; the relay's reply does the acting.
     */
    public static Action decideLocally(int strikesAfter, LiveWordList.Ladder ladder) {
        return strikesAfter >= ladder.removeAtStrike() ? Action.REMOVE : Action.WARN;
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longOr(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean boolOr(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
