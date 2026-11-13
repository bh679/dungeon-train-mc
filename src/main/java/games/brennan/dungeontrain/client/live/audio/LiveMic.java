package games.brennan.dungeontrain.client.live.audio;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The microphone for the stream on air. There is ONE mic switch: the Microphone option
 * ({@link LiveFeedClientConfig#micSupport()}). The mic button by LIVE in the inventory, the Toggle Mic
 * key and the Stream tab's option all flip that same saved setting ({@link #set}), and the icon by LIVE
 * shows it.
 *
 * <p>Whether a stream carries a mic track is decided when it starts ({@link #startStream}): the option
 * on, a capture device listed, and the relay not barring this player. A stream that carries one sends
 * two audio tracks for its whole length (the game with the mic over it, and the game alone), so
 * switching the mic off and on never restarts the encoder: while it is off the mic's half is silence.
 * The mic starts the stream in whatever state the option is in. Switched on during a stream that
 * started without a track, the stream gains one ({@link Toggle#JOINING}): the controller swaps its
 * encoder for one with a mic track ({@link #setMidStreamJoin}), and the voice is held silent
 * ({@link #held}) until the relay's heartbeat reply says it counts the track ({@link #relayTrack}), so
 * viewers in Kid mode have already moved to the game-only track. Where that cannot happen (no stream
 * sound, a relay too old to say) the voice joins from the next stream ({@link Toggle#NEXT_STREAM}).</p>
 *
 * <p>The capture device opens when the mic goes on and closes when it goes off, so a streamer who
 * never switches it on never meets the system's microphone permission prompt. With push to talk set,
 * the mic is only heard while that key is held as well.</p>
 *
 * <p>The relay has the last word: every heartbeat reply says whether this streamer may use a mic
 * ({@link #relayAllows}). Barred mid-stream, the voice stops at once (the mic track carries silence,
 * the icon shows it off) and the option is left alone, so it is heard again on the heartbeat that
 * allows it.</p>
 */
public final class LiveMic {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 10 ms of a quiet room sits well under this RMS; speech well over it. */
    static final double GATE_THRESHOLD = 400;
    /** The gate stays open this long after the last loud block, so word endings are kept. */
    static final int GATE_HOLD_SAMPLES = MicCapture.RATE / 4;

    /** The one switch, and how it is saved: the Microphone option (tests stand in for it). */
    private static volatile BooleanSupplier option = LiveFeedClientConfig::micSupport;
    private static volatile Consumer<Boolean> saveOption = LiveFeedClientConfig::setMicSupport;
    /** How a capture device is opened (tests hand out none, so no real device is ever opened). */
    private static volatile Supplier<@Nullable MicCapture> opener = () -> MicCapture.open(LiveFeedClientConfig.micDevice());
    /** The stream on air carries a mic track. */
    private static volatile boolean enabled;
    /** A mic track added mid-stream, silent until the relay counts it ({@link #relayTrack}). */
    private static volatile boolean held;
    /** A relay too old to count a mid-stream track answered: say once that the voice joins next stream. */
    private static volatile boolean heldNotice;
    /** Render thread: adds a mic track to the stream on air, or says why not ({@code LiveStreamController}). */
    private static volatile Supplier<Toggle> midStreamJoin = () -> Toggle.NEXT_STREAM;
    private static volatile boolean pttHeld;
    /** The relay barred the mic mid-stream (uploader thread writes, render and pump threads read). */
    private static volatile boolean barred;
    /** A ban landed and the render thread has not yet said so in chat ({@link #takeBarredNotice}). */
    private static volatile boolean barredNotice;
    @Nullable private static volatile MicCapture capture;
    /** Pump thread only. */
    private static MicDsp.NoiseGate gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);
    /** Pump thread only: the automatic gain on the voice, fresh for every stream. */
    private static MicDsp.Agc agc = new MicDsp.Agc(MicCapture.RATE);
    /** Pump thread only; its reading goes out through {@link #level}. */
    private static final MicDsp.LevelMeter meter = new MicDsp.LevelMeter(MicCapture.RATE);
    /** The voice's loudness, 0..1, written by the pump thread and read by the render thread. */
    private static volatile float level;
    /** Dev screenshots only: a fixed level in place of the meter's, or NaN for the real one. */
    private static volatile float levelOverride = Float.NaN;

    private LiveMic() {}

    /**
     * What a switch did. {@code JOINING}: the option went on during a stream without a mic track, and
     * the stream is gaining one; viewers hear the voice once the relay counts it. {@code NEXT_STREAM}:
     * the option went on, but this stream cannot gain a track, so the voice joins from the next one.
     * {@code BARRED}: the relay has the mic turned off for this streamer. {@code NO_DEVICE}: the option
     * went on but no capture device would open.
     */
    public enum Toggle { ON, OFF, JOINING, NEXT_STREAM, NO_DEVICE, BARRED }

    /** What a heartbeat reply says of the mic track: counted, not counted, or not said (an older relay). */
    public enum TrackWord { YES, NO, UNSAID }

    /** Where a switch came from, for the log. */
    public enum Source { KEY, INVENTORY, OPTIONS }

    /** Whether this client could stream a mic at all: the option on and a capture device listed. */
    public static boolean available() {
        return option.getAsBoolean() && !MicCapture.devices().isEmpty();
    }

    /** A stream starts: with a mic track or without one. With one, the mic is in whatever state the option is. */
    public static void startStream(boolean withMic) {
        enabled = withMic;
        held = false;
        heldNotice = false;
        barred = false;
        barredNotice = false;
        gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);
        agc = new MicDsp.Agc(MicCapture.RATE);
        if (withMic && option.getAsBoolean()) openCapture();
    }

    /** The stream is over (or carries no mic track after all): the capture device is let go. The option stays. */
    public static void endStream() {
        enabled = false;
        held = false;
        heldNotice = false;
        barred = false;
        barredNotice = false;
        closeCapture();
    }

    /**
     * Render thread: the encoder now writes a mic track, added mid-stream. The voice stays silent until
     * the relay counts the track ({@link #relayTrack}); the device opens now if the option is on.
     */
    public static void joinMidStream() {
        enabled = true;
        held = true;
        heldNotice = false;
        gate = new MicDsp.NoiseGate(GATE_THRESHOLD, GATE_HOLD_SAMPLES);
        agc = new MicDsp.Agc(MicCapture.RATE);
        if (option.getAsBoolean()) openCapture();
        LOGGER.info("[DungeonTrain] live mic: track added mid-stream, voice held until the relay counts it");
    }

    /** Who adds a mic track mid-stream; the controller sets it once. Tests stand in for it. */
    public static void setMidStreamJoin(Supplier<Toggle> join) {
        midStreamJoin = join;
    }

    /**
     * Any thread: a heartbeat reply's word on the track. A held voice is let through once the relay
     * counts the track; an older relay that never says leaves it held and asks for the "next stream"
     * line once ({@link #takeHeldNotice}). True when this reply let the voice through.
     */
    public static boolean relayTrack(TrackWord word) {
        if (!enabled || !held) return false;
        if (word == TrackWord.YES) {
            held = false;
            LOGGER.info("[DungeonTrain] live mic: the relay counts the mid-stream track; voice on");
            return true;
        }
        if (word == TrackWord.UNSAID && !heldNotice) {
            heldNotice = true;
            LOGGER.info("[DungeonTrain] live mic: the relay does not count a mid-stream track; voice joins next stream");
        }
        return false;
    }

    /** Render thread: true once, when an older relay left a mid-stream track's voice held. */
    public static boolean takeHeldNotice() {
        if (!heldNotice) return false;
        heldNotice = false;
        return true;
    }

    /** The heartbeat's {@code mic}: this stream carries a mic track (from the start or added since). */
    public static boolean heartbeatMicTrack() {
        return enabled;
    }

    /** A mid-stream track still waiting on the relay. */
    public static boolean held() {
        return held;
    }

    /** True while the stream on air carries a mic track. */
    public static boolean enabled() {
        return enabled;
    }

    /** The mic on this stream, as far as the relay lets it count: the option on, a mic track, not barred. */
    public static boolean on() {
        return enabled && option.getAsBoolean() && !barred && !held;
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

    /** Render thread: flips the one switch. */
    public static Toggle toggle(Source source) {
        return set(!option.getAsBoolean(), source);
    }

    /**
     * Render thread: the one switch, from the button by LIVE, the key or the option. Saves the option,
     * then on a stream with a mic track opens or closes the capture device at once; on a stream without
     * one (or no stream) the saved option waits for the next stream. Logged, whatever it did.
     */
    public static Toggle set(boolean on, Source source) {
        Toggle result = apply(on);
        LOGGER.info("[DungeonTrain] live mic: switched {} from the {} -> {} (mic track {}, option {})",
            on ? "on" : "off", source.name().toLowerCase(java.util.Locale.ROOT), result.name().toLowerCase(java.util.Locale.ROOT),
            enabled ? "yes" : "no", option.getAsBoolean() ? "on" : "off");
        return result;
    }

    private static Toggle apply(boolean on) {
        if (!on) {
            saveOption.accept(false);
            if (enabled) closeCapture();
            return Toggle.OFF;
        }
        if (enabled && barred) return Toggle.BARRED;
        saveOption.accept(true);
        if (!enabled) return midStreamJoin.get();
        return openCapture() ? Toggle.ON : Toggle.NO_DEVICE;
    }

    /** Opens the capture device if it is not open; false when none would. */
    private static boolean openCapture() {
        if (capture != null) return true;
        MicCapture c = opener.get();
        if (c == null) return false;
        capture = c;
        return true;
    }

    /**
     * Pump thread: the next {@code n} mic samples at 48 kHz into {@code out}, gate, automatic gain and
     * balance applied, or silence while the mic is off. The device is read either way, so switching on
     * never plays back sound captured while it was off.
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
        boolean open = !LiveFeedClientConfig.micNoiseGate() || gate.process(out, n);
        // Between words the gate is shut: the gain holds, so room noise is never pumped up.
        agc.process(out, n, open);
        // Metered after the gate and the gain (the voice as viewers get it) and before the balance,
        // so the icon shows the voice itself, whatever the game/mic slider is set to.
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

    /**
     * Tests: stand in for the saved Microphone option, and open no real capture device (a test box may
     * well have one); {@code null} both puts the real ones back.
     */
    static void setOptionForTests(@Nullable BooleanSupplier read, @Nullable Consumer<Boolean> write) {
        option = read == null ? LiveFeedClientConfig::micSupport : read;
        saveOption = write == null ? LiveFeedClientConfig::setMicSupport : write;
        opener = read == null ? () -> MicCapture.open(LiveFeedClientConfig.micDevice()) : () -> null;
    }
}
