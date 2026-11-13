package games.brennan.dungeontrain.client.live;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/**
 * One speaker's read of a {@link LiveAudioRing}: what Minecraft's sound engine streams a
 * {@link LiveSpeakerSound} from.
 *
 * <p>Minecraft asks for a second at a time and keeps four buffers queued, which for a live feed would
 * put the sound four seconds behind the picture. A buffer here is at most {@link #CHUNK_BYTES} (60 ms),
 * so the queue holds about 240 ms: with the ring's 500 ms starting cushion the sound plays about
 * three quarters of a second after the picture it left ffmpeg with, more once the ring has settled
 * behind a jittery stream. The queue is refilled once a client tick, so a client hitch
 * longer than the queue runs it dry, which ends the sound; {@code LiveTvSpeakers} starts it again
 * (40 ms buffers, a 160 ms queue, ran dry a few times a minute on a loaded machine).</p>
 */
final class LiveAudioStream implements AudioStream {

    static final AudioFormat FORMAT = new AudioFormat(24_000f, 16, 1, true, false);
    /** 60 ms of sound. */
    static final int CHUNK_BYTES = LiveAudioRing.BYTES_PER_SECOND * 60 / 1000;

    private final LiveAudioRing ring;
    private long cursor = -1;

    LiveAudioStream(LiveAudioRing ring) {
        this.ring = ring;
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public ByteBuffer read(int size) {
        int len = Math.max(2, Math.min(size, CHUNK_BYTES)) & ~1;
        ByteBuffer out = BufferUtils.createByteBuffer(len);
        cursor = ring.read(cursor, out, len);
        return out;
    }

    /** How far behind the feed this speaker plays, in milliseconds (for the {@code [live]} perf line). */
    long lagMs() {
        return cursor < 0 ? 0 : LiveAudioRing.lagMs(cursor, ring.head());
    }

    @Override
    public void close() {
        // nothing held: the ring belongs to the session
    }
}
