package games.brennan.dungeontrain.client.live.audio;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ShortBuffer;
import java.util.List;

/**
 * The streamer's microphone, through OpenAL's capture API (its own device: the game's sound is not
 * touched). Mono 16-bit at 48 kHz, or 44.1 kHz brought to 48 kHz ({@link MicDsp.Resampler}) on devices
 * that refuse 48.
 *
 * <p>Not paced by itself: {@link LiveAudioPump}'s thread asks for exactly the samples the picture is
 * owed ({@link #read}) and this hands out what the device has captured, padding with silence when it is
 * short and dropping the oldest when more than {@link #MAX_BACKLOG_MS} has piled up, so the two clocks
 * drifting never adds delay to the voice.</p>
 */
public final class MicCapture implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int RATE = LiveAudioPump.RATE;
    private static final int[] DEVICE_RATES = {RATE, 44_100};
    /** The device's own ring: a second of sound, so a slow pump never loses any to the driver. */
    private static final int DEVICE_BUFFER_SECONDS = 1;
    /** More waiting than this and the oldest is dropped: the voice stays close to the picture. */
    static final int MAX_BACKLOG_MS = 150;
    static final int MAX_BACKLOG = RATE * MAX_BACKLOG_MS / 1000;

    private final long device;
    private final MicDsp.Resampler resampler;
    private final ShortBuffer grab;
    private final short[] raw;
    private final short[] resampled;
    /** Captured, resampled samples not yet handed out (a ring). */
    private final short[] fifo = new short[RATE];
    private int head;
    private int size;
    private boolean closed;

    private MicCapture(long device, int deviceRate) {
        this.device = device;
        this.resampler = new MicDsp.Resampler(deviceRate, RATE);
        int chunk = deviceRate / 10;
        this.grab = MemoryUtil.memAllocShort(chunk);
        this.raw = new short[chunk];
        this.resampled = new short[resampler.maxOut(chunk)];
    }

    /** The capture devices OpenAL lists, by name; empty when there are none or capture is unsupported. */
    public static List<String> devices() {
        try {
            List<String> names = ALUtil.getStringList(0L, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
            return names == null ? List.of() : List.copyOf(names);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] mic: could not list capture devices ({})", t.toString());
            return List.of();
        }
    }

    /**
     * Opens and starts {@code name} (empty or not listed = the system default), or null (logged) when
     * no capture device opens. On macOS the first open is what asks the player for microphone access.
     */
    @Nullable
    public static MicCapture open(String name) {
        String wanted = name != null && !name.isEmpty() && devices().contains(name) ? name : null;
        for (int rate : DEVICE_RATES) {
            try {
                long dev = ALC11.alcCaptureOpenDevice(wanted, rate, AL10.AL_FORMAT_MONO16, rate * DEVICE_BUFFER_SECONDS);
                if (dev == 0L) continue;
                ALC11.alcCaptureStart(dev);
                LOGGER.info("[DungeonTrain] mic: capturing from {} at {} Hz", wanted == null ? "the default device" : wanted, rate);
                return new MicCapture(dev, rate);
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] mic: capture at {} Hz failed ({})", rate, t.toString());
            }
        }
        LOGGER.warn("[DungeonTrain] mic: no capture device would open{}", wanted == null ? "" : " (" + wanted + ")");
        return null;
    }

    /**
     * Pump thread: fills {@code out[0..n)} with the next {@code n} mono samples at 48 kHz, after taking
     * in what the device captured since the last call. Returns how many were real (the rest is silence).
     */
    public synchronized int read(short[] out, int n) {
        if (!closed) drainDevice();
        int real = Math.min(n, size);
        for (int i = 0; i < real; i++) out[i] = fifo[(head + i) % fifo.length];
        head = (head + real) % fifo.length;
        size -= real;
        java.util.Arrays.fill(out, real, n, (short) 0);
        return real;
    }

    private void drainDevice() {
        int avail = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
        while (avail > 0) {
            int take = Math.min(avail, raw.length);
            grab.clear().limit(take);
            ALC11.alcCaptureSamples(device, grab, take);
            grab.get(0, raw, 0, take);
            push(resampled, resampler.process(raw, take, resampled));
            avail -= take;
        }
    }

    private void push(short[] s, int n) {
        for (int i = 0; i < n; i++) {
            if (size == fifo.length) { head = (head + 1) % fifo.length; size--; }
            fifo[(head + size) % fifo.length] = s[i];
            size++;
        }
        if (size > MAX_BACKLOG) {
            int drop = size - MAX_BACKLOG;
            head = (head + drop) % fifo.length;
            size -= drop;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            ALC11.alcCaptureStop(device);
            ALC11.alcCaptureCloseDevice(device);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] mic: close failed ({})", t.toString());
        }
        MemoryUtil.memFree(grab);
    }
}
