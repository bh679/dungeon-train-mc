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
    /** The dev preview's made-up state; while set, summary fetches do not replace what it shows. */
    @Nullable private static volatile LiveRewards preview;

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
        LiveRewards p = preview;
        return p != null ? p : latest;
    }

    /** Keep the rewards block of a summary body; a body without one clears it back to none. */
    public static void remember(String body) {
        latest = parseBody(body);
        StreamDelay d = StreamDelay.parseBody(body);
        if (d != null) streamDelay = d;
        openIds = parseOpen(body);
    }

    private static volatile java.util.Set<String> openIds = java.util.Set.of();

    /**
     * Whether reward {@code id} can be given today: the relay lists only rewards whose effect is built
     * (summary {@code rewardsOpen}). A relay that sends no list opens none.
     */
    public static boolean open(String id) {
        return openIds.contains(id);
    }

    /** The {@code rewardsOpen} ids of a summary body; empty when absent or malformed. */
    static java.util.Set<String> parseOpen(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return java.util.Set.of();
            JsonObject o = root.getAsJsonObject();
            if (!o.has("rewardsOpen") || !o.get("rewardsOpen").isJsonArray()) return java.util.Set.of();
            java.util.Set<String> out = new java.util.HashSet<>();
            for (JsonElement e : o.getAsJsonArray("rewardsOpen")) {
                if (e.isJsonPrimitive()) out.add(e.getAsString());
            }
            return java.util.Set.copyOf(out);
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    /**
     * Seconds viewers sit behind the streamer, without and with Quicker Streams, as the relay states
     * them (summary {@code streamDelay}: it owns the segment length, so the numbers follow it). The
     * rewards screens print these. {@link #DEFAULT_DELAY} until a relay that sends them answers.
     */
    public record StreamDelay(int normal, int quick) {

        /** Two segments of buffer at the relay's default 3 s Quicker Streams segments; ~30 s otherwise. */
        public static final StreamDelay DEFAULT_DELAY = new StreamDelay(30, 6);

        /** The {@code streamDelay} block of a summary body, or null when absent or unusable. */
        @Nullable
        static StreamDelay parseBody(String body) {
            try {
                JsonElement root = JsonParser.parseString(body);
                if (!root.isJsonObject()) return null;
                JsonObject o = root.getAsJsonObject();
                if (!o.has("streamDelay") || !o.get("streamDelay").isJsonObject()) return null;
                JsonObject d = o.getAsJsonObject("streamDelay");
                int normal = d.has("normal") && d.get("normal").isJsonPrimitive() ? d.get("normal").getAsInt() : 0;
                int quick = d.has("quick") && d.get("quick").isJsonPrimitive() ? d.get("quick").getAsInt() : 0;
                if (normal <= 0 || quick <= 0 || quick > normal || normal > 600) return null;
                return new StreamDelay(normal, quick);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static volatile StreamDelay streamDelay = StreamDelay.DEFAULT_DELAY;

    /** The stream delay the relay last stated, or the default before it has. */
    public static StreamDelay streamDelay() {
        return streamDelay;
    }

    /** Dev preview only: show {@code rewards} until cleared with null, whatever the relay says. */
    public static void preview(@Nullable LiveRewards rewards) {
        preview = rewards;
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
