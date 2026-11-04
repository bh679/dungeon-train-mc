package games.brennan.dungeontrain.client.live.audio;

import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import org.jetbrains.annotations.Nullable;

/**
 * The microphone for the stream on air. A stream either carries a mic track or it does not, decided
 * when it starts ({@link #startStream}): mic support on, a capture device listed, and the relay not
 * barring this player. A stream that carries one sends two audio tracks for its whole length (the game
 * with the mic over it, and the game alone), so turning the mic on and off never restarts the encoder:
 * while it is off the mic's half is silence.
 *
 * <p>Off at the start of every stream; the icon by LIVE ({@code LiveRecOverlay}) or the Toggle Mic key
 * turns it on. The capture device opens on the first switch-on, so a streamer who never uses the mic
 * never meets the system's microphone permission prompt. With push to talk set, the mic is only heard
 * while that key is held as well.</p>
 *
 * <p>The relay has the last word: every heartbeat reply says whether this streamer may use a mic
 * ({@link #relayAllows}). Barred mid-stream, the voice stops at once (the mic track carries silence,
 * the icon shows it off) and the switch is kept, so it is heard again on the heartbeat that allows it.</p>
 */
public final class LiveMic {

    /** 10 ms of a quiet room sits well under this RMS; speech well over it. */
    static final double GATE_THRESHOLD = 400;
    /** The gate stays open this long after the last loud block, so word endings are kept. */
    static final int GATE_HOLD_SAMPLES = MicCapture.RATE / 4;

    private static volatile boolean enabled;
    private static volatile boolean on;
    private static volatile boolean pttHeld;
    /** The relay barred the mic mid-stream (uploader thread writes, render and pump threads read). */
    private static volatile boolean barred;
    /** A ban landed and the render thread has not yet said so in chat ({@link #takeBarredNotice}). */
    private static volatile boolean barredNotice;
    @Nullable private static volatile MicCapture capture;
    /** Pump thread only. */
    private static MicDsp.NoiseGate gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);
    /** Pump thread only; its reading goes out through {@link #level}. */
    private static final MicDsp.LevelMeter meter = new MicDsp.LevelMeter(MicCapture.RATE);
    /** The voice's loudness, 0..1, written by the pump thread and read by the render thread. */
    private static volatile float level;
    /** Dev screenshots only: a fixed level in place of the meter's, or NaN for the real one. */
    private static volatile float levelOverride = Float.NaN;

    private LiveMic() {}

    /** What a switch-on did. {@code BARRED}: the relay has the mic turned off for this streamer. */
    public enum Toggle { ON, OFF, NO_DEVICE, NOT_ENABLED, BARRED }

    /** Whether this client could stream a mic at all: support switched on and a capture device listed. */
    public static boolean available() {
        return LiveFeedClientConfig.micSupport() && !MicCapture.devices().isEmpty();
    }

    /** A stream starts: with a mic track or without one; the mic itself is always off. */
    public static void startStream(boolean withMic) {
        enabled = withMic;
        on = false;
        barred = false;
        barredNotice = false;
        gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);
    }

    /** The stream is over: the mic goes off and the capture device is let go. */
    public static void endStream() {
        enabled = false;
        on = false;
        barred = false;
        barredNotice = false;
        closeCapture();
    }

    /** True while the stream on air carries a mic track. */
    public static boolean enabled() {
        return enabled;
    }

    /** The mic switch for this stream, as far as the relay lets it count: off while barred. */
    public static boolean on() {
        return enabled && on && !barred;
    }

    /** Whether the mic is heard right now: on, allowed, and push to talk held when that is set. */
    public static boolean transmitting() {
        return heard(on(), LiveFeedClientConfig.micPushToTalk(), pttHeld);
    }

    /** The one rule for "voice is going out now": the switch on, and the key held when push to talk is set. */
    static boolean heard(boolean on, boolean pushToTalk, boolean held) {
        return on && (!pushToTalk || held);
    }

    /** The heartbeat's {@code micOn}: what viewers hear right now, not the switch. */
    public static boolean heartbeatMicOn() {
        return transmitting();
    }

    public static void setPushToTalkHeld(boolean held) {
        pttHeld = held;
    }

    /** The relay has the mic turned off for this streamer while the stream carries on. */
    public static boolean barred() {
        return barred;
    }

    /**
     * Any thread: the relay's word from a heartbeat reply. A ban silences the voice from the next pump
     * block and leaves the switch alone; a later {@code true} lets it through again. True when this
     * reply turned the ban on.
     */
    public static boolean relayAllows(boolean allowed) {
        if (!enabled) return false;
        boolean newlyBarred = !allowed && !barred;
        barred = !allowed;
        if (newlyBarred) barredNotice = true;
        return newlyBarred;
    }

    /** Render thread: true once per ban, for the chat line that says the mic is off for this streamer. */
    public static boolean takeBarredNotice() {
        if (!barredNotice) return false;
        barredNotice = false;
        return true;
    }

    /** Render thread: flips the mic, opening the capture device the first time it goes on. */
    public static Toggle toggle() {
        if (!enabled) return Toggle.NOT_ENABLED;
        if (barred) return Toggle.BARRED;
        if (on) {
            on = false;
            return Toggle.OFF;
        }
        if (capture == null) {
            MicCapture c = MicCapture.open(LiveFeedClientConfig.micDevice());
            if (c == null) return Toggle.NO_DEVICE;
            capture = c;
        }
        on = true;
        return Toggle.ON;
    }

    /**
     * Pump thread: the next {@code n} mic samples at 48 kHz into {@code out}, gain and gate applied, or
     * silence while the mic is off. The device is read either way, so switching on never plays back
     * sound captured while it was off.
     */
    static void fill(short[] out, int n) {
        MicCapture c = capture;
        if (c == null) {
            java.util.Arrays.fill(out, 0, n, (short) 0);
            silenceMeter();
            return;
        }
        c.read(out, n);
        if (!transmitting()) {
            java.util.Arrays.fill(out, 0, n, (short) 0);
            silenceMeter();
            return;
        }
        if (LiveFeedClientConfig.micNoiseGate()) gate.process(out, n);
        // Metered after the gate (what the room noise leaves) and before the balance, so the icon
        // shows the voice itself, whatever the game/mic slider is set to.
        level = meter.update(out, n);
        MicDsp.applyGain(out, n, MicDsp.micGain(LiveFeedClientConfig.micMixPercent()));
    }

    private static void silenceMeter() {
        meter.reset();
        level = 0;
    }

    /** Render thread: the voice's loudness for the icon by LIVE, 0 silent to 1 loud; 0 while not heard. */
    public static float level() {
        float o = levelOverride;
        if (!Float.isNaN(o)) return o;
        return transmitting() ? level : 0f;
    }

    /** Dev builds only, for screenshots of the meter: pins {@link #level()} to {@code value}; NaN lets go. */
    public static void setLevelOverride(float value) {
        if (!games.brennan.dungeontrain.DungeonTrain.isDevBuild()) return;
        levelOverride = Float.isNaN(value) ? Float.NaN : Math.max(0f, Math.min(1f, value));
    }

    /**
     * Pump thread: the game's level in the mix track. The balance only applies while the mic is
     * switched on, so a stream with the mic off sounds exactly like one without a mic.
     */
    static float mixGameGain() {
        return on() ? MicDsp.gameGain(LiveFeedClientConfig.micMixPercent()) : 1f;
    }

    private static void closeCapture() {
        MicCapture c = capture;
        capture = null;
        if (c != null) c.close();
    }
}
