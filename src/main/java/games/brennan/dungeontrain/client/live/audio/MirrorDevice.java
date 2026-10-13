package games.brennan.dungeontrain.client.live.audio;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * The stream's own OpenAL device: an OpenAL Soft <em>loopback</em> device, which has no speakers —
 * it mixes only when asked ({@link #render}) and hands the samples back to us. Its context is made
 * current per thread ({@code ALC_EXT_thread_local_context}) around each mirrored call and never
 * process-wide, so Minecraft's own context — the one the streamer hears — is never switched away.
 *
 * <p>Mix format: {@value #RATE} Hz, stereo, signed 16-bit, native byte order — what ffmpeg reads as
 * {@code -f s16le -ar 48000 -ac 2}. HRTF off (viewers are not wearing the streamer's headphones), the
 * output limiter on as Minecraft's own context has it.</p>
 */
final class MirrorDevice {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int RATE = 48_000;
    static final int CHANNELS = 2;
    static final int BYTES_PER_FRAME = CHANNELS * Short.BYTES;
    /** {@code AL_SOURCE_DISTANCE_MODEL} (AL_EXT_source_distance_model): per-source models, as {@code Library.init} enables. */
    private static final int AL_SOURCE_DISTANCE_MODEL = 0x200;
    /** Room for Minecraft's whole static pool (≤ 255) plus its streaming pool (≤ 8). */
    private static final int MONO_SOURCES = 256;
    private static final int STEREO_SOURCES = 16;

    private final long device;
    private final long context;

    private MirrorDevice(long device, long context) {
        this.device = device;
        this.context = context;
    }

    /** Opens the loopback device, or null (logged once) when this OpenAL cannot do it. */
    @Nullable
    static MirrorDevice open() {
        try {
            if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")
                || !ALC10.alcIsExtensionPresent(0L, "ALC_EXT_thread_local_context")) {
                LOGGER.warn("[DungeonTrain] stream audio off: this OpenAL has no loopback or thread-local context support");
                return null;
            }
            long dev = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (dev == 0L) {
                LOGGER.warn("[DungeonTrain] stream audio off: the loopback device did not open");
                return null;
            }
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(dev, RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                LOGGER.warn("[DungeonTrain] stream audio off: the loopback device cannot render 48 kHz stereo s16");
                ALC10.alcCloseDevice(dev);
                return null;
            }
            long ctx = ALC10.alcCreateContext(dev, new int[]{
                ALC10.ALC_FREQUENCY, RATE,
                SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
                SOFTHRTF.ALC_HRTF_SOFT, ALC10.ALC_FALSE,
                SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT, ALC10.ALC_TRUE,
                ALC11.ALC_MONO_SOURCES, MONO_SOURCES,
                ALC11.ALC_STEREO_SOURCES, STEREO_SOURCES,
                0});
            if (ctx == 0L) {
                LOGGER.warn("[DungeonTrain] stream audio off: the loopback context did not open");
                ALC10.alcCloseDevice(dev);
                return null;
            }
            MirrorDevice mirror = new MirrorDevice(dev, ctx);
            mirror.run(() -> AL10.alEnable(AL_SOURCE_DISTANCE_MODEL));
            return mirror;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[DungeonTrain] stream audio off: {}", e.toString());
            return null;
        }
    }

    /**
     * Runs {@code calls} with the mirror context current on this thread only, then puts back whatever
     * this thread had (normally nothing, i.e. the process-wide speaker context).
     */
    void run(Runnable calls) {
        long previous = EXTThreadLocalContext.alcGetThreadContext();
        EXTThreadLocalContext.alcSetThreadContext(context);
        try {
            calls.run();
        } finally {
            EXTThreadLocalContext.alcSetThreadContext(previous);
        }
    }

    /** Mixes the next {@code frames} sample frames into {@code out} (from its position; needs room). */
    void render(ByteBuffer out, int frames) {
        SOFTLoopback.alcRenderSamplesSOFT(device, out, frames);
    }

    void close() {
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
    }
}
