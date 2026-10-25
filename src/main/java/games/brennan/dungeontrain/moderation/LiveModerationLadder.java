package games.brennan.dungeontrain.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The relay's verdict on a strike or a status check, and the local guess made while waiting for it.
 *
 * <p>The relay ({@code livestrikes.js}) owns the count. It answers {@code POST /live/moderation/strike}
 * and {@code GET /live/moderation/status} with the same shape:
 * {@code {ok, strikes, removals, action: warn|mute|remove|ban|none, endStream, banned, permanent, remainingSec,
 * muted, mutePermanent, muteRemainingSec}}. A mute stops the player's chat; a ban stops the Live Eye.
 * An older relay sends no mute fields, which reads as "not muted".
 * {@link #parse} reads it; anything not well-formed is {@code null}, which callers treat as "no news"
 * — a relay outage never punishes anyone and never clears a ban already cached.</p>
 */
public final class LiveModerationLadder {

    public enum Action { NONE, WARN, MUTE, REMOVE, BAN }

    /** One logged line behind a ban: the relay's id (what an appeal names), the text as typed, the terms that matched. */
    public record Evidence(long id, String text, List<String> terms) {}

    /**
     * What the relay said. {@code remainingSec} is only meaningful while {@code banned},
     * {@code muteRemainingSec} while {@code muted}, {@code evidence} while either. {@code endStream}: this
     * verdict also ends the player's stream if they are on air.
     */
    public record Verdict(int strikes, int removals, Action action, boolean banned, boolean permanent, long remainingSec,
                          List<Evidence> evidence, boolean muted, boolean mutePermanent, long muteRemainingSec,
                          boolean endStream) {

        public static final Verdict CLEAN = new Verdict(0, 0, Action.NONE, false, false, 0, List.of(), false, false, 0, false);

        public Verdict {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        /** Whether a Live Eye may be worn: not banned, or a timed ban that has run out. */
        public boolean mayStream(long nowMs, long receivedAtMs) {
            if (!banned) return true;
            if (permanent) return false;
            return nowMs - receivedAtMs >= remainingSec * 1000L;
        }

        /** Seconds of ban left as of {@code nowMs}, measured from when the verdict arrived. */
        public long remainingAt(long nowMs, long receivedAtMs) {
            return left(banned, permanent, remainingSec, nowMs, receivedAtMs);
        }

        /** Whether the player may talk in chat: not muted, or a timed mute that has run out. */
        public boolean mayChat(long nowMs, long receivedAtMs) {
            return muteRemainingAt(nowMs, receivedAtMs) == 0;
        }

        /** Seconds of chat mute left as of {@code nowMs}; {@link Long#MAX_VALUE} when permanent. */
        public long muteRemainingAt(long nowMs, long receivedAtMs) {
            return left(muted, mutePermanent, muteRemainingSec, nowMs, receivedAtMs);
        }

        private static long left(boolean on, boolean forever, long sec, long nowMs, long receivedAtMs) {
            if (!on) return 0;
            if (forever) return Long.MAX_VALUE;
            return Math.max(0, sec - (nowMs - receivedAtMs) / 1000L);
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
            boolean muted = boolOr(o, "muted", false);
            boolean mutePermanent = boolOr(o, "mutePermanent", false);
            long muteRemaining = Math.max(0, longOr(o, "muteRemainingSec", 0));
            if (!muted) { mutePermanent = false; muteRemaining = 0; }
            return new Verdict(strikes, removals, action(o), banned, permanent, remaining,
                banned || muted ? evidence(o) : List.of(), muted, mutePermanent, muteRemaining, boolOr(o, "endStream", false));
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code evidence[]} of a ban verdict, newest first; malformed rows are skipped. */
    private static List<Evidence> evidence(JsonObject o) {
        if (!o.has("evidence") || !o.get("evidence").isJsonArray()) return List.of();
        List<Evidence> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("evidence")) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            try {
                long id = r.get("id").getAsLong();
                String text = r.has("text") && !r.get("text").isJsonNull() ? r.get("text").getAsString() : "";
                List<String> terms = new ArrayList<>();
                if (r.has("terms") && r.get("terms").isJsonArray()) {
                    for (JsonElement t : r.getAsJsonArray("terms")) if (t.isJsonPrimitive()) terms.add(t.getAsString());
                }
                out.add(new Evidence(id, text, List.copyOf(terms)));
            } catch (Exception ignored) {
                // one bad row never hides the others
            }
            if (out.size() >= 50) break;
        }
        return List.copyOf(out);
    }

    private static Action action(JsonObject o) {
        if (!o.has("action") || !o.get("action").isJsonPrimitive()) return Action.NONE;
        return switch (o.get("action").getAsString()) {
            case "warn" -> Action.WARN;
            case "mute" -> Action.MUTE;
            case "remove" -> Action.REMOVE;
            case "ban" -> Action.BAN;
            default -> Action.NONE;
        };
    }

    /**
     * The warning a player hears the moment a word is masked, before the relay answers: the local count
     * stepped by one against the ladder. Only the words change; the relay's reply does the acting.
     * {@code REMOVE} here just means "no warnings left" (the relay's reply says what that costs).
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
