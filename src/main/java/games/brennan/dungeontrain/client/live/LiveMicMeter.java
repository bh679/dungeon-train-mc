package games.brennan.dungeontrain.client.live;

/**
 * How loud the streamer's microphone is on a live feed, 0 to 1, for the worn TV's level bar
 * ({@link HeadViewerMicBar}).
 *
 * <p>A mic stream carries two sound tracks: the game with the mic mixed in (track 0, what the TVs
 * play) and the game alone (track 1). The two leave the same ffmpeg run, each to a reader of its own
 * ({@link LiveHlsSession}): the mix reader feeds {@link #mix}, the game reader {@link #game}. Voice and
 * game are uncorrelated, so their powers add: the mic's own loudness is the square root of the mix's
 * power less the game's. With no game track to compare against (an older stream, or one whose second
 * track ffmpeg could not map) the mix's loudness stands in, which is why the bar is only shown while
 * the relay says the mic is on.</p>
 *
 * <p>The level follows its target like a VU meter: it rises almost at once and falls back slowly, so
 * a word shows as a clear peak that sinks rather than a flicker. Each reader works on its own thread,
 * with nothing allocated per chunk; the render thread only reads {@link #level}.</p>
 */
public final class LiveMicMeter {

    /** Full bar at this loudness (RMS of the mic alone, 1 = full scale): loud, close speech. */
    static final float FULL_RMS = 0.25f;
    /** Share of the way to a louder target the level moves per chunk (about 85 ms of sound): near instant. */
    static final float ATTACK = 0.6f;
    /** Share of the way to a quieter target per chunk: about half a second to sink from full. */
    static final float RELEASE = 0.15f;
    /** The game track counts as present while its sound arrived this recently. */
    static final long GAME_FRESH_MS = 1_000;
    private static final float FULL_SCALE = 32_768f;

    private volatile float level;
    private volatile float gameRms;
    private volatile long gameHeardMs;

    /** The level now, 0 to 1. Any thread. */
    public float level() {
        return level;
    }

    /** Mix reader: the next chunk of track 0 (24 kHz mono s16le), {@code len} bytes from {@code off}. */
    void mix(byte[] pcm, int off, int len) {
        boolean gameFresh = System.currentTimeMillis() - gameHeardMs < GAME_FRESH_MS;
        level = follow(level, target(rms(pcm, off, len), gameRms, gameFresh));
    }

    /** Game reader: the next chunk of track 1. */
    void game(byte[] pcm, int off, int len) {
        gameRms = rms(pcm, off, len);
        gameHeardMs = System.currentTimeMillis();
    }

    /** Root mean square of {@code len} bytes of s16le samples from {@code off}, 0 to 1. Pure. */
    static float rms(byte[] pcm, int off, int len) {
        int samples = len / 2;
        if (samples <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            int at = off + 2 * i;
            int s = (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8));
            double v = s / FULL_SCALE;
            sum += v * v;
        }
        return (float) Math.sqrt(sum / samples);
    }

    /**
     * The level the meter heads for: the mic's own loudness over {@link #FULL_RMS} when the game track is
     * there to take away, else the mix's. 0 to 1. Pure.
     */
    static float target(float mixRms, float gameRms, boolean gameFresh) {
        float mic = gameFresh ? (float) Math.sqrt(Math.max(0f, mixRms * mixRms - gameRms * gameRms)) : mixRms;
        return clamp(mic / FULL_RMS);
    }

    /** {@code level} moved towards {@code target}: by {@link #ATTACK} of the way up, {@link #RELEASE} down. Pure. */
    static float follow(float level, float target) {
        float share = target > level ? ATTACK : RELEASE;
        return clamp(level + (target - level) * share);
    }

    static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
