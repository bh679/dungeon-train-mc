package games.brennan.dungeontrain.client.live.audio;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.FfmpegSupport;
import games.brennan.dungeontrain.client.live.LivePerfStats;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.function.LongSupplier;

/**
 * Carries the stream's sound to the encoder. ffmpeg reads it as a second input,
 * {@code -f s16le -ar 48000 -ac 2 -i tcp://127.0.0.1:<port>}: this listens on a loopback-only port,
 * accepts that one connection, and feeds it the mirror's mix ({@link AudioMirror}) on its own thread.
 *
 * <p>Paced by the picture, not by its own clock: after the encoder has written {@code n} video frames
 * it has written exactly {@code n × 48000 / fps} sample frames ({@link #samplesDue}), so both inputs
 * sit on one timeline even when the encoder's writer falls behind and skips ticks. What the encoder
 * wrote before ffmpeg connected is matched with silence, as is {@link #DELAY_MS}.</p>
 */
public final class LiveAudioPump implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int RATE = MirrorDevice.RATE;
    /** 10 ms of sound per render: small enough to follow the picture closely, big enough to be cheap. */
    static final int CHUNK_FRAMES = 480;
    private static final long POLL_MS = 5;
    /** A socket write this slow means ffmpeg is not keeping up; counted on the perf line. */
    private static final long STALL_NS = 50_000_000L;
    /**
     * How much later the sound goes out than the picture it belongs with. A grabbed frame reaches the
     * encoder a frame or so after it was drawn (the read-back ring), so unshifted the sound led by
     * 30–110 ms, avg ~63 (measured 2026-10-13: title flash + anvil hit in one tick, 20 fps). Written
     * as leading silence, which moves every later sample by the same amount; ffmpeg's
     * {@code -itsoffset} did not survive the mpegts muxer's timestamp normalisation.
     */
    static final int DELAY_MS = 60;
    /**
     * IPv4 loopback exactly, as ffmpeg is told {@code tcp://127.0.0.1:<port>}. Not
     * {@link InetAddress#getLoopbackAddress()}: under the game's JVM flags that can be {@code ::1},
     * and ffmpeg's connect is then refused.
     */
    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

    private final ServerSocketChannel server;
    private final int port;
    @Nullable private volatile SocketChannel client;
    @Nullable private Thread thread;
    private volatile boolean running = true;

    private LiveAudioPump(ServerSocketChannel server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * Opens the mirror and the listening port for a stream with audio, or null (logged) when this
     * client cannot — no {@code aac} encoder in Vista's ffmpeg, no loopback OpenAL — and the stream
     * then goes out without sound, as before.
     */
    @Nullable
    public static LiveAudioPump open(FFmpeg ffmpeg) {
        if (!FfmpegSupport.hasAacEncoder(ffmpeg)) {
            LOGGER.warn("[DungeonTrain] stream audio off: this ffmpeg has no aac encoder");
            return null;
        }
        if (!AudioMirror.start()) return null;
        try {
            ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), 0), 1);
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            return new LiveAudioPump(server, port);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] stream audio off: no local port ({})", e.toString());
            AudioMirror.stop();
            return null;
        }
    }

    public int port() {
        return port;
    }

    /** Starts feeding once ffmpeg connects; {@code videoFrames} is the encoder's written-frame count. */
    public void start(LongSupplier videoFrames, int fps) {
        Thread t = new Thread(() -> run(videoFrames, Math.max(1, fps)), "dt-live-audio");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Sample frames that belong with {@code videoFrames} frames of picture at {@code fps}, {@link #DELAY_MS} included. */
    static long samplesDue(long videoFrames, int fps) {
        return videoFrames * RATE / fps + delaySamples();
    }

    static long delaySamples() {
        return (long) DELAY_MS * RATE / 1000;
    }

    static boolean isLoopback(@Nullable SocketAddress address) {
        return address instanceof InetSocketAddress inet && inet.getAddress() != null && inet.getAddress().isLoopbackAddress();
    }

    private void run(LongSupplier videoFrames, int fps) {
        try (SocketChannel c = server.accept()) {
            if (!isLoopback(c.getRemoteAddress())) {
                LOGGER.warn("[DungeonTrain] stream audio: refused a non-local connection");
                return;
            }
            client = c;
            c.socket().setTcpNoDelay(true);
            ByteBuffer mix = ByteBuffer.allocateDirect(CHUNK_FRAMES * MirrorDevice.BYTES_PER_FRAME).order(ByteOrder.nativeOrder());
            ByteBuffer silence = ByteBuffer.allocateDirect(mix.capacity());
            long written = 0;
            long backlog = samplesDue(videoFrames.getAsLong(), fps);
            while (written < backlog && running) {
                int n = (int) Math.min(backlog - written, CHUNK_FRAMES);
                send(c, silence, n);
                written += n;
            }
            while (running) {
                long due = samplesDue(videoFrames.getAsLong(), fps) - written;
                while (due > 0 && running) {
                    int n = (int) Math.min(due, CHUNK_FRAMES);
                    long t0 = System.nanoTime();
                    mix.clear();
                    boolean mixed = AudioMirror.render(mix, n);
                    LivePerfStats.audioRendered(System.nanoTime() - t0);
                    send(c, mixed ? mix : silence, n);
                    written += n;
                    due -= n;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (ClosedChannelException e) {
            // closed by close(): the stream is over
        } catch (IOException e) {
            if (running) LOGGER.warn("[DungeonTrain] stream audio pipe closed: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(SocketChannel c, ByteBuffer samples, int frames) throws IOException {
        ByteBuffer out = samples.duplicate();
        out.position(0).limit(frames * MirrorDevice.BYTES_PER_FRAME);
        long t0 = System.nanoTime();
        while (out.hasRemaining()) c.write(out);
        if (System.nanoTime() - t0 > STALL_NS) LivePerfStats.audioStall();
    }

    /** Ends the sound input (ffmpeg sees end-of-stream on it) and closes the mirror. */
    @Override
    public void close() {
        running = false;
        closeQuietly(client);
        closeQuietly(server);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AudioMirror.stop();
    }

    private static void closeQuietly(@Nullable java.nio.channels.Channel c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
