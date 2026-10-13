package games.brennan.dungeontrain.client.live.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.LivePerfStats;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Gives the mirror the sounds Minecraft decoded <em>before</em> the stream started. The engine drops a
 * sound's PCM once it is on the sound card and OpenAL cannot read it back, so each is decoded again
 * from its resource, one at a time on its own thread, and uploaded on the sound thread. Sounds a
 * playing channel is waiting on jump the queue ({@link #prioritize}).
 *
 * <p>One decode in flight at a time keeps the extra memory to a single sound's PCM; the cost shows on
 * the {@code [live]} perf line as {@code decodes}.</p>
 */
final class MirrorPredecoder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long UPLOAD_WAIT_S = 5;

    private final AudioMirror.Session session;
    private final ResourceProvider resources;
    private final SoundEngineExecutor soundThread;
    private final LinkedBlockingDeque<SoundBuffer> queue = new LinkedBlockingDeque<>();
    private final Set<SoundBuffer> seen = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Thread thread;
    private volatile boolean running = true;

    MirrorPredecoder(AudioMirror.Session session, ResourceProvider resources, SoundEngineExecutor soundThread) {
        this.session = session;
        this.resources = resources;
        this.soundThread = soundThread;
        this.thread = new Thread(this::loop, "dt-live-audio-decode");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MIN_PRIORITY);
    }

    void start() {
        thread.start();
    }

    void enqueue(Collection<SoundBuffer> buffers) {
        for (SoundBuffer b : buffers) {
            if (seen.add(b)) queue.addLast(b);
        }
    }

    /** A channel is playing this one now: decode it next. */
    void prioritize(SoundBuffer buffer) {
        queue.remove(buffer);
        seen.add(buffer);
        queue.addFirst(buffer);
    }

    void close() {
        running = false;
        thread.interrupt();
        queue.clear();
    }

    private void loop() {
        try {
            while (running) {
                SoundBuffer buffer = queue.take();
                MirrorTwin.Buffer duck = (MirrorTwin.Buffer) (Object) buffer;
                if (duck.dungeontrain$twinIn(session.gen) != 0) continue;
                ResourceLocation id = session.known.get(buffer);
                if (id != null) decode(buffer, id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void decode(SoundBuffer buffer, ResourceLocation id) throws InterruptedException {
        long t0 = System.nanoTime();
        ByteBuffer pcm;
        AudioFormat format;
        try (InputStream in = resources.open(id); JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            pcm = stream.readAll();
            format = stream.getFormat();
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] stream audio: could not re-decode {}: {}", id, e.toString());
            return;
        }
        LivePerfStats.audioDecoded(System.nanoTime() - t0);
        try {
            soundThread.submit(() -> AudioMirror.uploadDecoded(session, buffer, pcm, format))
                .get(UPLOAD_WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // The engine flushed its queue (reload) or is busy; this sound stays silent on the stream.
            LOGGER.debug("[DungeonTrain] stream audio: upload of {} skipped: {}", id, e.toString());
        }
    }
}
