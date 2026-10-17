package games.brennan.dungeontrain.client.live.life;

/**
 * Which story the Live Eye death pages tell about a broadcast life, and which community reward the
 * crowd page pitches. Pure decisions over {@link LiveLifeStats} and {@link LiveRewards}, so every
 * variant is pinned by a test.
 */
public final class LiveDeathStory {

    /** What happened to the broadcast. */
    public enum Story {
        /** The Eye was live when the player died. */
        TO_THE_END,
        /** The player set the Eye down, then died off air. */
        ENDED_EARLY,
        /** Someone took the Eye from the player while they were alive. */
        TAKEN,
        /** Nobody ever watched. */
        NOBODY
    }

    /** The crowd page's pitch. */
    public enum Pitch {
        /** Quicker Streams is off: ask for it. */
        QUICKER_ASK,
        /** Quicker Streams is on: thank the buyer, the next month is open. */
        QUICKER_THANKS,
        /** The stream ended before the fall and Replays Kept is off: ask for that. */
        REPLAYS_ASK,
        /** The Eye was taken: Two Streams means nobody has to hand it over. */
        TWO_STREAMS_ASK,
        /** Nobody watched: no money ask, point at the Twitch link. */
        NOBODY
    }

    /** Website viewers' delay with Quicker Streams on (2 s segments, one-segment buffer). */
    public static final int QUICKER_DELAY_S = 5;
    private static final int DEFAULT_SEGMENT_S = 10;

    private LiveDeathStory() {}

    public static Story story(LiveLifeStats s) {
        if (!s.takenBy().isEmpty() && s.takenWhileAlive()) return Story.TAKEN;
        if (s.peakViewers() <= 0) return Story.NOBODY;
        return s.diedOnAir() ? Story.TO_THE_END : Story.ENDED_EARLY;
    }

    public static Pitch pitch(LiveLifeStats s, LiveRewards rewards, long now) {
        Story story = story(s);
        if (story == Story.TAKEN && !rewards.active(LiveRewards.TWO_STREAMS, now)) return Pitch.TWO_STREAMS_ASK;
        if (story == Story.NOBODY) return Pitch.NOBODY;
        if (rewards.active(LiveRewards.QUICKER_STREAMS, now)) return Pitch.QUICKER_THANKS;
        if (story == Story.ENDED_EARLY && !rewards.active(LiveRewards.REPLAYS_KEPT, now)) return Pitch.REPLAYS_ASK;
        return Pitch.QUICKER_ASK;
    }

    /**
     * How far behind the player the viewers watched: one segment being encoded plus a two-segment
     * player buffer, plus about a second of upload — 31 s at the default 10 s segments. Quicker
     * Streams sets it to {@link #QUICKER_DELAY_S}.
     */
    public static int delaySeconds(int segmentSeconds, boolean quickerOn) {
        if (quickerOn) return QUICKER_DELAY_S;
        int seg = segmentSeconds > 0 ? segmentSeconds : DEFAULT_SEGMENT_S;
        return 3 * seg + 1;
    }
}
