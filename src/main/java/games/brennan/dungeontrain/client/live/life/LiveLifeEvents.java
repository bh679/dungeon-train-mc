package games.brennan.dungeontrain.client.live.life;

import games.brennan.dungeontrain.client.live.LiveLife;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The one-line hooks the Live Eye code calls to count this life's broadcast ({@link LiveLifeTracker}).
 * Safe from any thread.
 *
 * <p>Everything a running stint counts goes to the life it STARTED in: a stream carries on through
 * the death screens, and the respawn's new life id can land a moment before the stream stops, which
 * must not hand the stint's end (or its last heartbeats) to the next life.</p>
 */
public final class LiveLifeEvents {

    /** The life the running stint started in, or null between stints. */
    @Nullable private static volatile String stintLife;

    private LiveLifeEvents() {}

    private static String life() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) LiveLifeTracker.initOnce(mc.gameDirectory.toPath());
        String s = stintLife;
        return s != null ? s : LiveLife.current();
    }

    /** The life the death screen tells: the running stint's, or the one the server last named. */
    public static String lifeForScreen() {
        return life();
    }

    /** {@code lifeId}'s counters (loading the save file first, when this is the first read). */
    public static LiveLifeStats stats(String lifeId) {
        life();
        return LiveLifeTracker.get(lifeId);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** The stream went live. */
    public static void stintStarted(int segmentSeconds) {
        long t = now();
        life(); // resolves the save file before the first update
        stintLife = LiveLife.current();
        LiveLifeTracker.update(stintLife, s -> s.stintStarted(t, segmentSeconds), true, t);
    }

    /** The stream ended; {@code reason} is the relay stop reason when known. */
    public static void stintEnded(@Nullable String reason) {
        long t = now();
        LiveLifeTracker.update(life(), s -> s.stintEnded(t, reason), true, t);
        stintLife = null;
    }

    /** A presign heartbeat answered with {@code viewers} watching (−1: the relay sent no count). */
    public static void heartbeat(int viewers) {
        long t = now();
        LiveLifeTracker.update(life(), s -> s.heartbeat(t, viewers), false, t);
    }

    public static void moment(boolean photo) {
        LiveLifeTracker.update(life(), s -> s.moment(photo), false, now());
    }

    /** Someone else said {@code text} in the stream's chat. */
    public static void chat(long ts, String author, String text) {
        LiveLifeTracker.update(life(), s -> s.chat(ts, author, text), false, now());
    }

    /** One of this streamer's own lines now has {@code count} reactions in all. */
    public static void reacted(long seq, int count) {
        LiveLifeTracker.update(life(), s -> s.reacted(seq, count), false, now());
    }

    /** A takeover vote on this streamer's Eye closed; {@code kept} when the room rejected it. */
    public static void voteClosed(String id, boolean kept) {
        LiveLifeTracker.update(life(), s -> s.voteClosed(id, kept), false, now());
    }

    /** The player died with the stream live. */
    public static void died() {
        LiveLifeTracker.update(life(), LiveLifeStats::died, true, now());
    }

    /** {@code by} took the Eye; {@code alive}: before the death. */
    public static void taken(String by, boolean alive) {
        LiveLifeTracker.update(life(), s -> s.taken(by, alive), true, now());
    }

    /** The relay's longest watchers of {@code lifeId}, when the caller pinned the life itself. */
    public static void crowdFor(String lifeId, List<LiveLifeStats.Watcher> top) {
        if (top == null || top.isEmpty()) return;
        life();
        LiveLifeTracker.update(lifeId, s -> s.crowd(top), true, now());
    }

    /** The relay's longest watchers of this life so far. */
    public static void crowd(List<LiveLifeStats.Watcher> top) {
        if (top == null || top.isEmpty()) return;
        LiveLifeTracker.update(life(), s -> s.crowd(top), false, now());
    }
}
