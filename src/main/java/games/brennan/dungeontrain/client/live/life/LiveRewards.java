package games.brennan.dungeontrain.client.live.life;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The community rewards the server is running, as the relay's donation summary payload carries them
 * ({@code rewards[]}: id, activeUntil, by, queued[]). Read defensively: the block may not exist yet
 * (an older relay, or before the rewards shelf ships), and an absent or malformed block simply means
 * no reward is on. The Live Eye death pages read it to thank a buyer or pitch the next month.
 *
 * <p>Ids are the shelf's: {@link #QUICKER_STREAMS}, {@link #TWO_STREAMS}, {@link #VOICE_ON_STREAM},
 * {@link #HD_STREAMS}, {@link #REPLAYS_KEPT}.</p>
 */
public record LiveRewards(List<Reward> rewards) {

    public static final String QUICKER_STREAMS = "quicker_streams";
    public static final String TWO_STREAMS = "two_streams";
    public static final String VOICE_ON_STREAM = "voice_on_stream";
    public static final String HD_STREAMS = "hd_streams";
    public static final String REPLAYS_KEPT = "replays_kept";

    public static final LiveRewards NONE = new LiveRewards(List.of());

    private static volatile LiveRewards latest = NONE;

    /** One reward: on until {@code activeUntilMs} (0 = off), bought by {@code by}, later months queued. */
    public record Reward(String id, long activeUntilMs, String by, List<String> queuedBy) {
        public Reward {
            by = by == null ? "" : by;
            queuedBy = List.copyOf(queuedBy == null ? List.of() : queuedBy);
        }
    }

    public LiveRewards {
        rewards = List.copyOf(rewards == null ? List.of() : rewards);
    }

    /** The latest state seen on a summary fetch; {@link #NONE} until one lands. */
    public static LiveRewards latest() {
        return latest;
    }

    /** Keep the rewards block of a summary body; a body without one clears it back to none. */
    public static void remember(String body) {
        latest = parseBody(body);
    }

    /** Tests and the dev preview: set the state directly. */
    public static void set(LiveRewards rewards) {
        latest = rewards == null ? NONE : rewards;
    }

    @Nullable
    public Reward find(String id) {
        for (Reward r : rewards) if (r.id().equals(id)) return r;
        return null;
    }

    /** Whether {@code id} is on at {@code now}. */
    public boolean active(String id, long now) {
        Reward r = find(id);
        return r != null && r.activeUntilMs() > now;
    }

    static LiveRewards parseBody(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            return root.isJsonObject() ? parse(root.getAsJsonObject()) : NONE;
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /** The {@code rewards} array of a summary payload; malformed entries are skipped. */
    static LiveRewards parse(JsonObject summary) {
        if (summary == null || !summary.has("rewards") || !summary.get("rewards").isJsonArray()) return NONE;
        List<Reward> out = new ArrayList<>();
        for (JsonElement e : summary.getAsJsonArray("rewards")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id");
            if (id.isEmpty()) continue;
            List<String> queued = new ArrayList<>();
            if (o.has("queued") && o.get("queued").isJsonArray()) {
                for (JsonElement q : o.getAsJsonArray("queued")) {
                    if (q.isJsonPrimitive()) queued.add(q.getAsString());
                    else if (q.isJsonObject()) queued.add(str(q.getAsJsonObject(), "by"));
                }
            }
            out.add(new Reward(id, timeOf(o.get("activeUntil")), str(o, "by"), queued));
        }
        return new LiveRewards(out);
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Epoch millis from a number (ms), an ISO instant or an ISO date (end of that day, UTC); 0 when none. */
    static long timeOf(@Nullable JsonElement v) {
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return 0L;
        try {
            if (v.getAsJsonPrimitive().isNumber()) return Math.max(0L, v.getAsLong());
            String s = v.getAsString().trim();
            if (s.isEmpty()) return 0L;
            if (s.length() == 10) return LocalDate.parse(s).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
            return Instant.parse(s).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
