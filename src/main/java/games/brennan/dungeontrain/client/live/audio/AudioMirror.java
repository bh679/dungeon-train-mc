package games.brennan.dungeontrain.client.live.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;
import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.ApprovedModList;
import games.brennan.dungeontrain.client.live.LivePerfStats;
import games.brennan.dungeontrain.mixin.client.audio.SoundBufferLibraryAccessor;
import games.brennan.dungeontrain.mixin.client.audio.SoundEngineAccessor;
import games.brennan.dungeontrain.mixin.client.audio.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntConsumer;

/**
 * The stream's ears: while a stream with audio is live, every OpenAL call Minecraft's sound engine
 * makes on a {@link Channel} (play, stop, gain, pitch, position, attenuation, buffers) and its
 * {@link Listener} is replayed onto a <em>twin</em> in a second, speaker-less context
 * ({@link MirrorDevice}), which {@link LiveAudioPump} renders into the broadcast. The speaker side is
 * never touched: the mixins call in here only after (or, for uploads and deletes, just before)
 * Minecraft's own call, every speaker call keeps its process-wide context, and nothing here ever
 * blocks the sound thread — when a stop is tearing the mirror down, a mirrored call is simply skipped.
 *
 * <p>With no stream live every entry point is one volatile read.</p>
 *
 * <p>Buffers belong to a device, so decoded sounds get a twin buffer too: uploaded alongside
 * Minecraft's upload while live, or — for sounds the engine uploaded before the stream started, whose
 * PCM it has already dropped — re-decoded in the background ({@link MirrorPredecoder}); a channel that
 * plays one before it is ready (a looping engine hum, say) catches up at the speaker's sample offset.</p>
 *
 * <p>Only sounds {@link StreamSoundPolicy} allows are mirrored — files from vanilla or an approved
 * mod's jar. A channel playing anything else (a resource pack's override, an unapproved mod's file, a
 * stream that never came through the sound cache such as a Vista TV's) is {@linkplain MirrorTwin#BLOCKED
 * blocked}: the streamer hears it, the stream does not.</p>
 */
public final class AudioMirror {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int AL_SOURCE_TYPE = 0x1027;
    private static final int AL_STATIC = 0x1028;
    private static final int AL_SAMPLE_OFFSET = 0x1025;
    private static final AtomicInteger GENERATIONS = new AtomicInteger();

    @Nullable private static volatile Session live;
    /** The sound file each open stream was read from (tagged by {@code SoundBufferLibraryMirrorMixin}). */
    private static final Map<AudioStream, ResourceLocation> STREAM_SOURCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean warned;

    /** What the {@code [live]} perf line reports about the mirror. */
    public record Stats(int sources, long twinBytes) {}

    /** One stream's mirror: its device, its twins, and what still waits for a re-decoded buffer. */
    static final class Session {
        final MirrorDevice device;
        final int gen;
        /** Mirrored calls take the read side with tryLock; {@link #stop} takes the write side. */
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        boolean closed;
        /** Sounds decoded before the stream began, by the resource they came from; fixed at start. */
        final Map<SoundBuffer, ResourceLocation> known;
        // ---- books, guarded by `this` ----
        final Set<MirrorTwin.Channel> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<MirrorTwin.Buffer> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<SoundBuffer, List<MirrorTwin.Channel>> waiting = new IdentityHashMap<>();
        long twinBytes;
        MirrorPredecoder decoder;
        /** Whether each sound file may reach the stream, decided once per session. */
        final Map<ResourceLocation, Boolean> verdicts = new ConcurrentHashMap<>();
        /** Unapproved mods appeared mid-stream: the pump writes silence until they are gone. */
        volatile boolean silenced;

        Session(MirrorDevice device, int gen, Map<SoundBuffer, ResourceLocation> known) {
            this.device = device;
            this.gen = gen;
            this.known = known;
        }
    }

    private AudioMirror() {}

    // ---- lifecycle (render thread / teardown thread) -----------------------------------------

    /** Opens the mirror for a new stream. False (logged) when this OpenAL cannot; nothing changes then. */
    public static boolean start() {
        stop();
        MirrorDevice device = MirrorDevice.open();
        if (device == null) return false;
        SoundEngineAccessor engine = (SoundEngineAccessor)
            ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).dungeontrain$soundEngine();
        SoundBufferLibraryAccessor library = (SoundBufferLibraryAccessor) engine.dungeontrain$soundBuffers();
        Map<SoundBuffer, ResourceLocation> known = new IdentityHashMap<>();
        for (Map.Entry<ResourceLocation, CompletableFuture<SoundBuffer>> e : library.dungeontrain$cache().entrySet()) {
            SoundBuffer buffer = e.getValue().isDone() && !e.getValue().isCompletedExceptionally() ? e.getValue().join() : null;
            if (buffer != null) {
                known.put(buffer, e.getKey());
                MirrorTwin.Buffer duck = (MirrorTwin.Buffer) (Object) buffer;
                if (duck.dungeontrain$source() == null) duck.dungeontrain$setSource(e.getKey());
            }
        }
        Session s = new Session(device, GENERATIONS.incrementAndGet(), Collections.unmodifiableMap(known));
        s.decoder = new MirrorPredecoder(s, library.dungeontrain$resourceManager(), engine.dungeontrain$executor());
        live = s;
        warned = false;
        engine.dungeontrain$executor().execute(() -> seed(s, engine));
        LOGGER.info("[DungeonTrain] stream audio mirror open ({} decoded sounds to mirror)", known.size());
        return true;
    }

    /** Sound thread: copy the listener, adopt every channel already playing, start re-decoding. */
    private static void seed(Session s, SoundEngineAccessor engine) {
        if (live != s) return;
        Listener listener = engine.dungeontrain$listener();
        listenerTransform(listener.getTransform()); // gain stays 1: the stream ignores the master slider
        engine.dungeontrain$channelAccess().executeOnChannels(channels -> {
            channels.forEach(ch -> adopt(s, (MirrorTwin.Channel) ch));
            List<SoundBuffer> rest = new ArrayList<>();
            for (SoundBuffer b : s.known.keySet()) {
                if (((MirrorTwin.Buffer) (Object) b).dungeontrain$hasAlBuffer()) rest.add(b);
            }
            s.decoder.enqueue(rest);
            s.decoder.start();
        });
    }

    /** A channel playing at go-live: give it a twin and, if it plays a static sound, wait for that buffer. */
    private static void adopt(Session s, MirrorTwin.Channel ch) {
        withLock(s, () -> {
            if (twinSource(s, ch) == 0) return;
            int src = ch.dungeontrain$speakerSource();
            if (AL10.alGetSourcei(src, AL_SOURCE_TYPE) != AL_STATIC) return; // streams join at their next chunk
            int speakerBuffer = AL10.alGetSourcei(src, AL10.AL_BUFFER);
            for (SoundBuffer b : s.known.keySet()) {
                MirrorTwin.Buffer duck = (MirrorTwin.Buffer) (Object) b;
                if (duck.dungeontrain$hasAlBuffer() && duck.dungeontrain$alBuffer() == speakerBuffer) {
                    if (bufferAllowed(s, b)) await(s, b, ch); else block(s, ch);
                    return;
                }
            }
        });
    }

    /** Closes the mirror: every twin and the device go. Safe from any thread; a no-op when off. */
    public static void stop() {
        Session s = live;
        if (s == null) return;
        live = null;
        s.decoder.close();
        s.lock.writeLock().lock();
        try {
            s.closed = true;
            synchronized (s) {
                s.device.run(() -> {
                    for (MirrorTwin.Channel ch : s.sources) deleteSource(ch.dungeontrain$twinIn(s.gen));
                    for (MirrorTwin.Buffer b : s.buffers) {
                        int id = b.dungeontrain$twinIn(s.gen);
                        if (id != 0) AL10.alDeleteBuffers(id);
                    }
                    AL10.alGetError();
                });
                s.sources.clear();
                s.buffers.clear();
                s.waiting.clear();
            }
            s.device.close();
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] stream audio mirror did not close cleanly: {}", e.toString());
        } finally {
            s.lock.writeLock().unlock();
        }
        LOGGER.info("[DungeonTrain] stream audio mirror closed");
    }

    /** The mirror's twins now, or null when no stream audio is live. */
    @Nullable
    public static Stats stats() {
        Session s = live;
        if (s == null) return null;
        synchronized (s) {
            return new Stats(s.sources.size(), s.twinBytes);
        }
    }

    /** Pump thread: mix the next {@code frames} into {@code out}. False when no mirror is live (write silence). */
    static boolean render(ByteBuffer out, int frames) {
        Session s = live;
        if (s == null || !s.lock.readLock().tryLock()) return false;
        try {
            if (s.closed || s.silenced) return false;
            s.device.render(out, frames);
            return true;
        } finally {
            s.lock.readLock().unlock();
        }
    }

    // ---- channel calls (sound thread, except destroy) ----------------------------------------

    /** Whether a stream with sound is live — the engine's zero-volume skips stand aside while it is. */
    public static boolean live() {
        return live != null;
    }

    /** The speaker source's gain changed: the twin gets it with the streamer's category slider undone. */
    public static void volume(MirrorTwin.Channel ch, float speakerGain) {
        if (live == null) return;
        float gain = streamGain(ch, speakerGain);
        onTwin(ch, true, id -> AL10.alSourcef(id, AL10.AL_GAIN, gain));
    }

    /**
     * NeoForge's play events (sound thread, right after attach + play): the channel learns what it
     * plays, and its twin's gain is set again now that the category is known.
     */
    public static void played(MirrorTwin.Channel ch, SoundInstance instance) {
        ch.dungeontrain$setOrigin(instance.getSource(), instance);
        if (live == null) return;
        float gain = streamGain(ch, AL10.alGetSourcef(ch.dungeontrain$speakerSource(), AL10.AL_GAIN));
        onTwin(ch, false, id -> AL10.alSourcef(id, AL10.AL_GAIN, gain));
    }

    private static float streamGain(MirrorTwin.Channel ch, float speakerGain) {
        SoundSource category = ch.dungeontrain$category();
        SoundInstance instance = ch.dungeontrain$instance();
        if (category == null || instance == null) return speakerGain;
        // The engine's own getVolume(): MASTER-category sounds are not scaled by a slider at all.
        float slider = category == SoundSource.MASTER ? 1.0F : Minecraft.getInstance().options.getSoundSourceVolume(category);
        return StreamVolume.twinGain(speakerGain, slider, instance.getVolume());
    }

    public static void sourcef(MirrorTwin.Channel ch, int param, float value) {
        onTwin(ch, true, id -> AL10.alSourcef(id, param, value));
    }

    public static void sourcei(MirrorTwin.Channel ch, int param, int value) {
        onTwin(ch, true, id -> AL10.alSourcei(id, param, value));
    }

    public static void position(MirrorTwin.Channel ch, Vec3 pos) {
        onTwin(ch, true, id -> AL10.alSource3f(id, AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z));
    }

    public static void play(MirrorTwin.Channel ch) {
        onTwin(ch, true, AL10::alSourcePlay);
    }

    public static void pause(MirrorTwin.Channel ch) {
        onTwin(ch, false, id -> { if (state(id) == AL10.AL_PLAYING) AL10.alSourcePause(id); });
    }

    public static void unpause(MirrorTwin.Channel ch) {
        onTwin(ch, false, id -> { if (state(id) == AL10.AL_PAUSED) AL10.alSourcePlay(id); });
    }

    public static void stop(MirrorTwin.Channel ch) {
        onTwin(ch, false, AL10::alSourceStop);
    }

    /** After the speaker source got {@code buffer}: give the twin its twin buffer, or wait for one. */
    public static void attachStatic(MirrorTwin.Channel ch, SoundBuffer buffer) {
        Session s = live;
        if (s == null) return;
        withLock(s, () -> {
            if (!bufferAllowed(s, buffer)) {
                block(s, ch);
                return;
            }
            int src = twinSource(s, ch);
            if (src == 0) return;
            int twin = ((MirrorTwin.Buffer) (Object) buffer).dungeontrain$twinIn(s.gen);
            if (twin != 0) {
                s.device.run(() -> AL10.alSourcei(src, AL10.AL_BUFFER, twin));
                LivePerfStats.audioMirrored();
            } else if (s.known.containsKey(buffer)) {
                await(s, buffer, ch);
                s.decoder.prioritize(buffer);
            }
        });
    }

    /**
     * A streamed chunk about to be uploaded to the speakers (its PCM still in hand): upload a twin and
     * queue it on the channel's twin, restarting the twin if it ran dry while the speaker plays on.
     */
    public static void streamChunk(MirrorTwin.Channel ch, @Nullable AudioStream stream, MirrorTwin.Buffer chunk) {
        Session s = live;
        if (s == null) return;
        ByteBuffer data = chunk.dungeontrain$data();
        AudioFormat format = chunk.dungeontrain$format();
        if (data == null) return;
        withLock(s, () -> {
            if (!allowed(s, stream == null ? null : STREAM_SOURCES.get(stream))) {
                block(s, ch);
                return;
            }
            int src = twinSource(s, ch);
            if (src == 0) return;
            boolean speakerPlaying = state(ch.dungeontrain$speakerSource()) == AL10.AL_PLAYING;
            int id = upload(s, data, format);
            if (id == 0) return;
            chunk.dungeontrain$setTwin(s.gen, id, 0); // owned by the twin queue, not the buffer books
            s.device.run(() -> {
                AL10.alSourceQueueBuffers(src, id);
                if (speakerPlaying && state(src) != AL10.AL_PLAYING) AL10.alSourcePlay(src);
            });
            LivePerfStats.audioMirrored();
        });
    }

    /** After the speaker refilled its stream: drop the twin's played chunks, restart it if it ran dry. */
    public static void updateStream(MirrorTwin.Channel ch) {
        Session s = live;
        if (s == null) return;
        boolean speakerPlaying = state(ch.dungeontrain$speakerSource()) == AL10.AL_PLAYING;
        onTwin(ch, false, id -> {
            unqueueProcessed(id);
            if (speakerPlaying && state(id) == AL10.AL_STOPPED && AL10.alGetSourcei(id, AL10.AL_BUFFERS_QUEUED) > 0) {
                AL10.alSourcePlay(id);
            }
        });
    }

    /** Before the speaker source is deleted (sound or client thread). */
    public static void destroy(MirrorTwin.Channel ch) {
        Session s = live;
        if (s == null) return;
        withLock(s, () -> {
            int id = ch.dungeontrain$twinIn(s.gen);
            if (id == 0) return;
            if (id > 0) s.device.run(() -> deleteSource(id));
            synchronized (s) {
                s.sources.remove(ch);
                s.waiting.values().forEach(list -> list.remove(ch));
            }
            ch.dungeontrain$clearTwin();
        });
    }

    // ---- buffers ------------------------------------------------------------------------------

    /** A static sound about to be uploaded to the speakers, its PCM still in hand: upload the twin. */
    public static void uploading(MirrorTwin.Buffer buffer) {
        Session s = live;
        if (s == null) return;
        ByteBuffer data = buffer.dungeontrain$data();
        AudioFormat format = buffer.dungeontrain$format();
        if (data == null) return;
        withLock(s, () -> {
            if (buffer.dungeontrain$twinIn(s.gen) != 0) return;
            if (!allowed(s, buffer.dungeontrain$source())) return; // never mirrored; its channel is blocked on attach
            adoptBuffer(s, buffer, data, format);
        });
    }

    /** Sound thread: a re-decoded pre-stream sound, ready for its twin. */
    static void uploadDecoded(Session s, SoundBuffer buffer, ByteBuffer data, AudioFormat format) {
        if (live != s) return;
        MirrorTwin.Buffer duck = (MirrorTwin.Buffer) (Object) buffer;
        withLock(s, () -> {
            if (!duck.dungeontrain$hasAlBuffer() || duck.dungeontrain$twinIn(s.gen) != 0) return;
            adoptBuffer(s, duck, data, format);
        });
    }

    /** Before the speaker buffer is deleted: delete its twin. */
    public static void discarded(MirrorTwin.Buffer buffer) {
        Session s = live;
        if (s == null) return;
        withLock(s, () -> {
            int id = buffer.dungeontrain$twinIn(s.gen);
            if (id == 0) return;
            s.device.run(() -> { AL10.alDeleteBuffers(id); AL10.alGetError(); });
            synchronized (s) {
                if (s.buffers.remove(buffer)) s.twinBytes -= buffer.dungeontrain$twinBytes();
            }
            buffer.dungeontrain$clearTwin();
        });
    }

    // ---- listener -----------------------------------------------------------------------------

    public static void listenerTransform(ListenerTransform t) {
        Session s = live;
        if (s == null) return;
        withLock(s, () -> s.device.run(() -> {
            Vec3 p = t.position();
            Vec3 f = t.forward();
            Vec3 u = t.up();
            AL10.alListener3f(AL10.AL_POSITION, (float) p.x, (float) p.y, (float) p.z);
            AL10.alListenerfv(AL10.AL_ORIENTATION,
                new float[]{(float) f.x, (float) f.y, (float) f.z, (float) u.x, (float) u.y, (float) u.z});
        }));
    }


    // ---- what may reach the stream ----------------------------------------------------------

    /** {@code SoundBufferLibraryMirrorMixin}: remember the file a stream was opened from. */
    public static void tagStream(AudioStream stream, ResourceLocation source) {
        STREAM_SOURCES.put(stream, source);
    }

    /** The unapproved-mod gate: while silenced the stream carries silence; the mirror itself runs on. */
    public static void setSilenced(boolean silenced) {
        Session s = live;
        if (s != null && s.silenced != silenced) {
            s.silenced = silenced;
            LOGGER.info("[DungeonTrain] stream audio {}", silenced ? "silenced: unapproved mods loaded" : "back on");
        }
    }

    /** Whether a decoded sound may reach the stream: by its tagged file, or the cache it was found in. */
    static boolean bufferAllowed(Session s, SoundBuffer buffer) {
        ResourceLocation source = ((MirrorTwin.Buffer) (Object) buffer).dungeontrain$source();
        return allowed(s, source != null ? source : s.known.get(buffer));
    }

    /** {@link StreamSoundPolicy} for one file, by the pack that serves it now (top-most wins). */
    private static boolean allowed(Session s, @Nullable ResourceLocation source) {
        if (source == null) return false;
        return s.verdicts.computeIfAbsent(source, id -> {
            String pack = Minecraft.getInstance().getResourceManager().getResource(id).map(Resource::sourcePackId).orElse(null);
            boolean ok = StreamSoundPolicy.allowed(pack, ApprovedModList::isApproved);
            if (!ok) LOGGER.debug("[DungeonTrain] stream audio: {} from pack {} stays off the stream", id, pack);
            return ok;
        });
    }

    /** Keep a channel off the stream for good: its twin (if any) goes, and nothing re-creates it. */
    private static void block(Session s, MirrorTwin.Channel ch) {
        int id = ch.dungeontrain$twinIn(s.gen);
        if (id == MirrorTwin.BLOCKED) return;
        if (id > 0) {
            s.device.run(() -> deleteSource(id));
            synchronized (s) {
                s.sources.remove(ch);
            }
        }
        ch.dungeontrain$setTwin(s.gen, MirrorTwin.BLOCKED, 0);
        LivePerfStats.audioBlocked();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void adoptBuffer(Session s, MirrorTwin.Buffer buffer, ByteBuffer data, AudioFormat format) {
        int bytes = data.remaining();
        int id = upload(s, data, format);
        if (id == 0) return;
        buffer.dungeontrain$setTwin(s.gen, id, bytes);
        List<MirrorTwin.Channel> waiters;
        synchronized (s) {
            s.buffers.add(buffer);
            s.twinBytes += bytes;
            waiters = s.waiting.remove((SoundBuffer) (Object) buffer);
        }
        if (waiters != null) waiters.forEach(ch -> catchUp(s, ch, id));
    }

    /** A channel that started before its twin buffer existed: attach it and jump to the speaker's place. */
    private static void catchUp(Session s, MirrorTwin.Channel ch, int bufferId) {
        int src = ch.dungeontrain$twinIn(s.gen);
        if (src <= 0) return;
        int speaker = ch.dungeontrain$speakerSource();
        int speakerState = state(speaker);
        int offset = AL10.alGetSourcei(speaker, AL_SAMPLE_OFFSET);
        s.device.run(() -> {
            AL10.alSourcei(src, AL10.AL_BUFFER, bufferId);
            AL10.alSourcei(src, AL_SAMPLE_OFFSET, offset);
            if (speakerState == AL10.AL_PLAYING) AL10.alSourcePlay(src);
            AL10.alGetError();
        });
    }

    private static void await(Session s, SoundBuffer buffer, MirrorTwin.Channel ch) {
        synchronized (s) {
            s.waiting.computeIfAbsent(buffer, b -> new ArrayList<>()).add(ch);
        }
    }

    /** A twin buffer holding {@code data}, or 0. {@code data}'s position is left as it was. */
    private static int upload(Session s, ByteBuffer data, AudioFormat format) {
        int alFormat = alFormat(format);
        if (alFormat == 0) return 0;
        int[] id = new int[1];
        s.device.run(() -> {
            id[0] = AL10.alGenBuffers();
            AL10.alBufferData(id[0], alFormat, data, (int) format.getSampleRate());
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteBuffers(id[0]);
                AL10.alGetError();
                id[0] = 0;
            }
        });
        return id[0];
    }

    /** The twin of a speaker source, made on first use as a copy of the speaker source's settings. */
    private static int twinSource(Session s, MirrorTwin.Channel ch) {
        int id = ch.dungeontrain$twinIn(s.gen);
        if (id == MirrorTwin.BLOCKED) return 0;
        if (id != 0) return id;
        int src = ch.dungeontrain$speakerSource();
        // Read on this thread's own (speaker) context, before switching to the mirror.
        float gain = streamGain(ch, AL10.alGetSourcef(src, AL10.AL_GAIN));
        float pitch = AL10.alGetSourcef(src, AL10.AL_PITCH);
        int looping = AL10.alGetSourcei(src, AL10.AL_LOOPING);
        int relative = AL10.alGetSourcei(src, AL10.AL_SOURCE_RELATIVE);
        int model = AL10.alGetSourcei(src, AL10.AL_DISTANCE_MODEL);
        float maxDistance = AL10.alGetSourcef(src, AL10.AL_MAX_DISTANCE);
        float rolloff = AL10.alGetSourcef(src, AL10.AL_ROLLOFF_FACTOR);
        float reference = AL10.alGetSourcef(src, AL10.AL_REFERENCE_DISTANCE);
        float[] x = new float[1], y = new float[1], z = new float[1];
        AL10.alGetSource3f(src, AL10.AL_POSITION, x, y, z);
        int[] made = new int[1];
        s.device.run(() -> {
            int twin = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) return;
            AL10.alSourcef(twin, AL10.AL_GAIN, gain);
            AL10.alSourcef(twin, AL10.AL_PITCH, pitch);
            AL10.alSourcei(twin, AL10.AL_LOOPING, looping);
            AL10.alSourcei(twin, AL10.AL_SOURCE_RELATIVE, relative);
            AL10.alSourcei(twin, AL10.AL_DISTANCE_MODEL, model);
            AL10.alSourcef(twin, AL10.AL_MAX_DISTANCE, maxDistance);
            AL10.alSourcef(twin, AL10.AL_ROLLOFF_FACTOR, rolloff);
            AL10.alSourcef(twin, AL10.AL_REFERENCE_DISTANCE, reference);
            AL10.alSource3f(twin, AL10.AL_POSITION, x[0], y[0], z[0]);
            AL10.alGetError();
            made[0] = twin;
        });
        if (made[0] == 0) return 0;
        ch.dungeontrain$setTwin(s.gen, made[0], 0);
        synchronized (s) {
            s.sources.add(ch);
        }
        return made[0];
    }

    /** Runs {@code op} on the channel's twin in the mirror context; {@code create} makes the twin if missing. */
    private static void onTwin(MirrorTwin.Channel ch, boolean create, IntConsumer op) {
        Session s = live;
        if (s == null) return;
        withLock(s, () -> {
            int id = create ? twinSource(s, ch) : ch.dungeontrain$twinIn(s.gen);
            if (id <= 0) return;
            s.device.run(() -> { op.accept(id); AL10.alGetError(); });
            LivePerfStats.audioMirrored();
        });
    }

    /** Never waits: while {@link #stop} holds the write side, a mirrored call is skipped. */
    private static void withLock(Session s, Runnable body) {
        if (!s.lock.readLock().tryLock()) return;
        try {
            if (!s.closed) body.run();
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[DungeonTrain] stream audio mirror call failed (further failures not logged)", e);
            }
        } finally {
            s.lock.readLock().unlock();
        }
    }

    private static int state(int source) {
        return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
    }

    /** Mirror context current: drop every chunk the twin has played. */
    private static void unqueueProcessed(int source) {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        if (processed <= 0) return;
        int[] ids = new int[processed];
        AL10.alSourceUnqueueBuffers(source, ids);
        AL10.alDeleteBuffers(ids);
    }

    /** Mirror context current: stop, free its queued chunks, delete. */
    private static void deleteSource(int source) {
        if (source <= 0) return;
        AL10.alSourceStop(source);
        unqueueProcessed(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        AL10.alDeleteSources(source);
        AL10.alGetError();
    }

    /** OpenAL's PCM format for {@code f}, or 0 (Minecraft only makes 8/16-bit mono/stereo). */
    static int alFormat(AudioFormat f) {
        boolean pcm = f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED || f.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
        if (!pcm) return 0;
        int bits = f.getSampleSizeInBits();
        return switch (f.getChannels()) {
            case 1 -> bits == 8 ? AL10.AL_FORMAT_MONO8 : bits == 16 ? AL10.AL_FORMAT_MONO16 : 0;
            case 2 -> bits == 8 ? AL10.AL_FORMAT_STEREO8 : bits == 16 ? AL10.AL_FORMAT_STEREO16 : 0;
            default -> 0;
        };
    }
}
