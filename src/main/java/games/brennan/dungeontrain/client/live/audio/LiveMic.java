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
 */
public final class LiveMic {

    /** 10 ms of a quiet room sits well under this RMS; speech well over it. */
    static final double GATE_THRESHOLD = 400;
    /** The gate stays open this long after the last loud block, so word endings are kept. */
    static final int GATE_HOLD_SAMPLES = MicCapture.RATE / 4;

    private static volatile boolean enabled;
    private static volatile boolean on;
    private static volatile boolean pttHeld;
    @Nullable private static volatile MicCapture capture;
    /** Pump thread only. */
    private static MicDsp.NoiseGate gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);

    private LiveMic() {}

    /** What a switch-on did. */
    public enum Toggle { ON, OFF, NO_DEVICE, NOT_ENABLED }

    /** Whether this client could stream a mic at all: support switched on and a capture device listed. */
    public static boolean available() {
        return LiveFeedClientConfig.micSupport() && !MicCapture.devices().isEmpty();
    }

    /** A stream starts: with a mic track or without one; the mic itself is always off. */
    public static void startStream(boolean withMic) {
        enabled = withMic;
        on = false;
        gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);
    }

    /** The stream is over: the mic goes off and the capture device is let go. */
    public static void endStream() {
        enabled = false;
        on = false;
        closeCapture();
    }

    /** True while the stream on air carries a mic track. */
    public static boolean enabled() {
        return enabled;
    }

    /** The mic switch for this stream (the heartbeat's {@code micOn}). */
    public static boolean on() {
        return enabled && on;
    }

    /** Whether the mic is heard right now: on, and push to talk held when that is set. */
    public static boolean transmitting() {
        return on() && (!LiveFeedClientConfig.micPushToTalk() || pttHeld);
    }

    public static void setPushToTalkHeld(boolean held) {
        pttHeld = held;
    }

    /** Render thread: flips the mic, opening the capture device the first time it goes on. */
    public static Toggle toggle() {
        if (!enabled) return Toggle.NOT_ENABLED;
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
            return;
        }
        c.read(out, n);
        if (!transmitting()) {
            java.util.Arrays.fill(out, 0, n, (short) 0);
            return;
        }
        MicDsp.applyGain(out, n, LiveFeedClientConfig.micGainPercent() / 100f);
        if (LiveFeedClientConfig.micNoiseGate()) gate.process(out, n);
    }

    private static void closeCapture() {
        MicCapture c = capture;
        capture = null;
        if (c != null) c.close();
    }
}
