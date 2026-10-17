package games.brennan.dungeontrain.client.live.life;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who the crowd page shows: the viewer most present for this life, by watch time AND chat. Each chat
 * line is worth {@link #MESSAGE_WEIGHT_S} seconds of watching, so someone who talked the whole way
 * through can outrank a quiet viewer who watched a little longer.
 *
 * <p>Candidates are the relay's longest watchers plus everyone who spoke in the stream's chat this
 * life, Discord and Twitch chatters included (they never have watch time). An in-game viewer is one
 * person whether seen watching or chatting (matched by uuid); everyone else is matched by name — a
 * website viewer the relay named by their chat name, or a chat author. Unnamed website watchers stand
 * alone. The streamer's own lines never reach the chat counts, so the streamer is never ranked.</p>
 */
public final class CrowdRanking {

    /** One chat line counts as this many seconds of watching. */
    public static final int MESSAGE_WEIGHT_S = 60;
    /** How many the crowd page shows: the one most present viewer. */
    public static final int PODIUM = 1;

    /** A podium place: who, how they watched or spoke, and the totals behind the score. */
    public record Ranked(@Nullable String name, @Nullable String uuid, String source, long seconds, int messages) {
        public long score() {
            return seconds + (long) MESSAGE_WEIGHT_S * messages;
        }
    }

    private CrowdRanking() {}

    public static List<Ranked> podium(LiveLifeStats s) {
        return rank(s.crowd(), s.perAuthor(), s.authorInfo(), PODIUM);
    }

    static List<Ranked> rank(List<LiveLifeStats.Watcher> watchers, Map<String, Integer> messages,
                             Map<String, LiveLifeStats.Author> authors, int n) {
        Map<String, Ranked> byKey = new LinkedHashMap<>();
        Map<String, String> byName = new LinkedHashMap<>(); // lower-case name -> key, to match a chatter
        int anon = 0;
        for (LiveLifeStats.Watcher w : watchers) {
            String key = keyOf(w.uuid(), w.name());
            if (key == null) key = "anon:" + (anon++);
            if (w.name() != null && !w.name().isBlank()) byName.putIfAbsent(w.name().toLowerCase(Locale.ROOT), key);
            Ranked prev = byKey.get(key);
            long secs = Math.max(w.seconds(), prev == null ? 0 : prev.seconds());
            byKey.put(key, new Ranked(w.name(), w.uuid(), w.source(), secs, prev == null ? 0 : prev.messages()));
        }
        for (Map.Entry<String, Integer> e : messages.entrySet()) {
            String name = e.getKey();
            LiveLifeStats.Author a = authors.get(name);
            String uuid = a == null ? null : a.uuid();
            String key = keyOf(uuid, name);
            if (key == null) continue;
            // Matched by uuid when both sides have one; otherwise the same name is the same person.
            if (!byKey.containsKey(key)) {
                String named = byName.get(name.toLowerCase(Locale.ROOT));
                if (named != null) key = named;
            }
            Ranked prev = byKey.get(key);
            if (prev == null) {
                byKey.put(key, new Ranked(name, uuid, a == null ? "other" : a.source(), 0, e.getValue()));
            } else {
                byKey.put(key, new Ranked(prev.name() != null ? prev.name() : name,
                    prev.uuid() != null ? prev.uuid() : uuid, prev.source(), prev.seconds(), prev.messages() + e.getValue()));
            }
        }
        List<Ranked> out = new ArrayList<>(byKey.values());
        out.removeIf(r -> r.score() <= 0);
        out.sort(Comparator.comparingLong(Ranked::score).reversed().thenComparing(Comparator.comparingLong(Ranked::seconds).reversed()));
        return List.copyOf(out.subList(0, Math.min(n, out.size())));
    }

    @Nullable
    private static String keyOf(@Nullable String uuid, @Nullable String name) {
        if (uuid != null && !uuid.isBlank()) return "uuid:" + uuid.replace("-", "").toLowerCase(Locale.ROOT);
        if (name != null && !name.isBlank()) return "name:" + name.toLowerCase(Locale.ROOT);
        return null;
    }
}
