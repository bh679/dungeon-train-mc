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
 * put the sound four seconds behind the picture. A buffer here is at most {@link #CHUNK_BYTES} (40 ms),
 * so the queue holds about 160 ms: with the ring's 40 ms cushion the sound plays about 220 ms after
 * the picture it left ffmpeg with. A client hitch longer than the queue runs it dry, which ends the
 * sound; {@code LiveTvSpeakers} starts it again.</p>
 */
final class LiveAudioStream implements AudioStream {

    static final AudioFormat FORMAT = new AudioFormat(24_000f, 16, 1, true, false);
    /** 40 ms of sound. */
    static final int CHUNK_BYTES = LiveAudioRing.BYTES_PER_SECOND / 25;

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
