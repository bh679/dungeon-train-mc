package games.brennan.dungeontrain.client.live;

/**
 * How well the streamer's own connection keeps up with the stream, measured on the segment PUTs to
 * storage only. Relay calls (presign) are never counted: a relay stall is not the streamer's upload,
 * and must never lower their quality.
 *
 * <p>Two moving averages, one sample per segment: <b>utilisation</b> (time the PUT took / the stream
 * time the segment holds; above 1 the upload falls further behind every segment) and <b>throughput</b>
 * (kbit/s the PUT achieved, which is the link's speed whatever the stream's bitrate). Lifetime totals
 * give the sustained speed remembered for the next stream. Thread-safe: the uploader thread records,
 * the render thread reads.</p>
 */
public final class LiveUploadHealth {

    /** Weight of the newest sample in the moving averages. */
    static final double ALPHA = 0.3;
    /** Segments a stream must upload before its speed is worth remembering. */
    static final int MIN_SAMPLES_TO_LEARN = 5;

    /** What the governor reads: samples since the last {@link #restart}, and the two averages. */
    public record Snapshot(int samples, double utilisation, int throughputKbps) {}

    private final long segmentMs;
    private int samples;
    private int speedSamples;
    private double utilisation;
    private double throughputKbps;
    private int lifetimeSamples;
    private long lifetimeBytes;
    private long lifetimePutMs;

    public LiveUploadHealth(int segmentSeconds) {
        this.segmentMs = Math.max(1, segmentSeconds) * 1000L;
    }

    /** A segment of {@code bytes} reached storage in {@code putMs}. */
    public synchronized void uploaded(long bytes, long putMs) {
        if (bytes <= 0) return;
        long ms = Math.max(1, putMs);
        // bytes * 8 / ms = kbit/s
        double kbps = bytes * 8.0 / ms;
        utilisation = average(utilisation, (double) ms / segmentMs, samples++);
        throughputKbps = average(throughputKbps, kbps, speedSamples++);
        lifetimeSamples++;
        lifetimeBytes += bytes;
        lifetimePutMs += ms;
    }

    /**
     * A segment PUT failed after {@code putMs}. Only a slow failure (longer than the segment) says
     * anything about the upload: a fast one is a dropped connection, retried at once.
     */
    public synchronized void failed(long putMs) {
        if (putMs <= segmentMs) return;
        utilisation = average(utilisation, (double) putMs / segmentMs, samples++);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(samples, utilisation, (int) Math.round(throughputKbps));
    }

    /** The bitrate changed: start the averages again so they describe the new stream. Lifetime totals stay. */
    public synchronized void restart() {
        samples = 0;
        speedSamples = 0;
        utilisation = 0;
        throughputKbps = 0;
    }

    /** The link speed over the whole stream, kbit/s; 0 when too few segments went up to tell. */
    public synchronized int sustainedKbps() {
        if (lifetimeSamples < MIN_SAMPLES_TO_LEARN || lifetimePutMs <= 0) return 0;
        return (int) Math.round(lifetimeBytes * 8.0 / lifetimePutMs);
    }

    /** {@code before}: samples already in {@code current}; the first one is taken as it is. */
    private static double average(double current, double sample, int before) {
        return before == 0 ? sample : current + ALPHA * (sample - current);
    }
}
