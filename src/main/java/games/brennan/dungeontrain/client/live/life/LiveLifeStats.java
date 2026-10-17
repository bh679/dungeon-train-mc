package games.brennan.dungeontrain.client.live.life;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one life put on the Live Eye, for the death screen's "the broadcast" and "the crowd" pages.
 * Immutable: every event returns a new record ({@link LiveLifeTracker} swaps it in).
 *
 * <p>Counted across every stint of the life — each time the player picked the Eye up, relogged with
 * it, or was handed it back — so a stream that ended an hour before the death still has its page.
 * {@code stintStartMs} is non-zero while a stint is running; {@link #onAirMs(long)} adds it.</p>
 *
 * @param lifeId        the life ({@code LiveLife}); "" on a server that never said
 * @param closedOnAirMs on-air time of the stints that have ended
 * @param stintStartMs  when the running stint went live, 0 when none is running
 * @param lastBeatMs    the running stint's latest heartbeat, to close it after a crash
 * @param stints        how many times the Eye went live this life
 * @param moments       moments shared into the stream's chat (photos included)
 * @param photos        of those, photos
 * @param peakViewers   the most watching at once, from the presign heartbeat
 * @param chatLines     lines other people said in the stream's chat while it ran
 * @param perAuthor     chat lines per author name, for MOST SAID
 * @param recentChat    the latest few chat lines, oldest first
 * @param reactions     reactions on the streamer's own lines, latest count per line seq
 * @param votesWon      takeover votes on this streamer's Eye the room rejected (they kept it)
 * @param votesClosed   takeover votes on this streamer's Eye that closed either way
 * @param lastStopMs    when the latest stint ended, 0 while none has
 * @param lastStopReason the relay stop reason of that stint ({@code died}, {@code left}, …), "" when unknown
 * @param diedOnAir     the player died while the Eye was live
 * @param takenBy       who took the Eye from this streamer, "" when nobody did
 * @param takenWhileAlive the Eye was taken before the death, not after it
 * @param segmentSeconds the segment length the latest stint ran at
 * @param crowd         the relay's longest watchers of this life, longest first
 */
public record LiveLifeStats(String lifeId, long closedOnAirMs, long stintStartMs, long lastBeatMs, int stints,
                            int moments, int photos, int peakViewers, int chatLines,
                            Map<String, Integer> perAuthor, List<ChatMoment> recentChat,
                            Map<Long, Integer> reactions, int votesWon, int votesClosed, List<String> votesSeen,
                            long lastStopMs, String lastStopReason, boolean diedOnAir,
                            String takenBy, boolean takenWhileAlive, int segmentSeconds, List<Watcher> crowd) {

    /** How many recent chat lines are kept; the page shows two from the final minute. */
    public static final int RECENT_CHAT = 6;
    /** Bound on distinct authors and reacted lines kept, so a long life cannot grow without end. */
    public static final int MAX_KEYS = 200;

    /** One chat line, as the crowd page quotes it. */
    public record ChatMoment(long ts, String author, String text) {}

    /** One of the longest watchers: {@code source} is {@code game} or {@code web}; name/uuid may be null. */
    public record Watcher(@Nullable String name, @Nullable String uuid, String source, long seconds) {}

    public LiveLifeStats {
        lifeId = lifeId == null ? "" : lifeId;
        perAuthor = Map.copyOf(perAuthor == null ? Map.of() : perAuthor);
        recentChat = List.copyOf(recentChat == null ? List.of() : recentChat);
        reactions = Map.copyOf(reactions == null ? Map.of() : reactions);
        votesSeen = List.copyOf(votesSeen == null ? List.of() : votesSeen);
        lastStopReason = lastStopReason == null ? "" : lastStopReason;
        takenBy = takenBy == null ? "" : takenBy;
        crowd = List.copyOf(crowd == null ? List.of() : crowd);
    }

    public static LiveLifeStats empty(String lifeId) {
        return new LiveLifeStats(lifeId, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), Map.of(), 0, 0, List.of(),
            0, "", false, "", false, 0, List.of());
    }

    /** Whether this life was broadcast at all — the gate for both death pages. */
    public boolean broadcast() {
        return stints > 0;
    }

    public boolean live() {
        return stintStartMs > 0;
    }

    /** On-air time so far, the running stint included up to {@code now}. */
    public long onAirMs(long now) {
        return closedOnAirMs + (stintStartMs > 0 ? Math.max(0, now - stintStartMs) : 0);
    }

    public int reactionTotal() {
        int sum = 0;
        for (int n : reactions.values()) sum += n;
        return sum;
    }

    /** The author with the most chat lines, or "" when nobody spoke. Ties go to the first counted. */
    public String mostSaid() {
        String best = "";
        int most = 0;
        for (Map.Entry<String, Integer> e : perAuthor.entrySet()) {
            if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** Up to {@code n} chat lines from the minute before {@code endMs}, oldest first. */
    public List<ChatMoment> finalMinute(long endMs, int n) {
        List<ChatMoment> out = new ArrayList<>();
        for (ChatMoment c : recentChat) if (c.ts() <= endMs && endMs - c.ts() <= 60_000L) out.add(c);
        return out.size() <= n ? out : out.subList(out.size() - n, out.size());
    }

    // ---- events (each returns a new record) -------------------------------------------------------

    public LiveLifeStats stintStarted(long now, int segS) {
        if (stintStartMs > 0) return this;
        return new LiveLifeStats(lifeId, closedOnAirMs, now, now, stints + 1, moments, photos, peakViewers, chatLines,
            perAuthor, recentChat, reactions, votesWon, votesClosed, votesSeen, lastStopMs, lastStopReason,
            diedOnAir, takenBy, takenWhileAlive, segS, crowd);
    }

    public LiveLifeStats stintEnded(long now, @Nullable String reason) {
        if (stintStartMs <= 0) return this;
        return new LiveLifeStats(lifeId, closedOnAirMs + Math.max(0, now - stintStartMs), 0, now, stints, moments, photos,
            peakViewers, chatLines, perAuthor, recentChat, reactions, votesWon, votesClosed, votesSeen, now,
            reason == null ? "" : reason, diedOnAir, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    /** A stint left running by a crash or a killed game: close it at its last heartbeat. */
    public LiveLifeStats closeStale() {
        return stintStartMs > 0 ? stintEnded(Math.max(stintStartMs, lastBeatMs), "") : this;
    }

    public LiveLifeStats heartbeat(long now, int viewers) {
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, stintStartMs > 0 ? now : lastBeatMs, stints,
            moments, photos, Math.max(peakViewers, viewers), chatLines, perAuthor, recentChat, reactions, votesWon,
            votesClosed, votesSeen, lastStopMs, lastStopReason, diedOnAir, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    public LiveLifeStats moment(boolean photo) {
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments + 1,
            photos + (photo ? 1 : 0), peakViewers, chatLines, perAuthor, recentChat, reactions, votesWon, votesClosed,
            votesSeen, lastStopMs, lastStopReason, diedOnAir, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    public LiveLifeStats chat(long ts, String author, String text) {
        Map<String, Integer> authors = new LinkedHashMap<>(perAuthor);
        if (authors.containsKey(author) || authors.size() < MAX_KEYS) authors.merge(author, 1, Integer::sum);
        List<ChatMoment> recent = new ArrayList<>(recentChat);
        recent.add(new ChatMoment(ts, author, text));
        if (recent.size() > RECENT_CHAT) recent = recent.subList(recent.size() - RECENT_CHAT, recent.size());
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments, photos, peakViewers,
            chatLines + 1, authors, recent, reactions, votesWon, votesClosed, votesSeen, lastStopMs, lastStopReason,
            diedOnAir, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    /** The latest reaction count on one of the streamer's own lines (the relay sends the whole tally). */
    public LiveLifeStats reacted(long seq, int count) {
        if (!reactions.containsKey(seq) && reactions.size() >= MAX_KEYS) return this;
        Map<Long, Integer> next = new LinkedHashMap<>(reactions);
        next.put(seq, Math.max(0, count));
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments, photos, peakViewers,
            chatLines, perAuthor, recentChat, next, votesWon, votesClosed, votesSeen, lastStopMs, lastStopReason,
            diedOnAir, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    /** A takeover vote on this streamer's Eye closed; {@code kept} when the room said no. Once per id. */
    public LiveLifeStats voteClosed(String id, boolean kept) {
        if (votesSeen.contains(id)) return this;
        List<String> seen = new ArrayList<>(votesSeen);
        seen.add(id);
        if (seen.size() > MAX_KEYS) seen = seen.subList(seen.size() - MAX_KEYS, seen.size());
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments, photos, peakViewers,
            chatLines, perAuthor, recentChat, reactions, votesWon + (kept ? 1 : 0), votesClosed + 1, seen, lastStopMs,
            lastStopReason, diedOnAir, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    public LiveLifeStats died() {
        if (stintStartMs <= 0) return this;
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments, photos, peakViewers,
            chatLines, perAuthor, recentChat, reactions, votesWon, votesClosed, votesSeen, lastStopMs, lastStopReason,
            true, takenBy, takenWhileAlive, segmentSeconds, crowd);
    }

    /** {@code by} took the Eye; {@code alive}: the streamer had not died yet. */
    public LiveLifeStats taken(String by, boolean alive) {
        if (by == null || by.isBlank()) return this;
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments, photos, peakViewers,
            chatLines, perAuthor, recentChat, reactions, votesWon, votesClosed, votesSeen, lastStopMs, lastStopReason,
            diedOnAir, by, alive, segmentSeconds, crowd);
    }

    public LiveLifeStats crowd(List<Watcher> top) {
        if (top == null || top.isEmpty()) return this;
        return new LiveLifeStats(lifeId, closedOnAirMs, stintStartMs, lastBeatMs, stints, moments, photos, peakViewers,
            chatLines, perAuthor, recentChat, reactions, votesWon, votesClosed, votesSeen, lastStopMs, lastStopReason,
            diedOnAir, takenBy, takenWhileAlive, segmentSeconds, top);
    }
}
