package games.brennan.dungeontrain.client.live.audio;

/**
 * Duck interface mixed into {@code com.mojang.blaze3d.audio.Channel} and {@code SoundBuffer}: the id
 * of this object's twin in the stream's mirror OpenAL context ({@link AudioMirror}), tagged with the
 * mirror session it belongs to. A twin from an older session is never used — its context is gone.
 */
public interface MirrorTwin {

    /** The mirror session the twin was made in; 0 when there is none. */
    int dungeontrain$twinGen();

    /** The twin's OpenAL source or buffer id in that session's context. */
    int dungeontrain$twinId();

    /** Bytes of PCM the twin holds (buffers only), for the perf line. */
    int dungeontrain$twinBytes();

    void dungeontrain$setTwin(int gen, int id, int bytes);

    /** A twin from {@code gen}, or 0. */
    default int dungeontrain$twinIn(int gen) {
        return dungeontrain$twinGen() == gen ? dungeontrain$twinId() : 0;
    }

    default void dungeontrain$clearTwin() {
        dungeontrain$setTwin(0, 0, 0);
    }

    /** A {@code Channel}: also exposes the speaker source id the twin mirrors. */
    interface Channel extends MirrorTwin {
        int dungeontrain$speakerSource();
    }

    /** A {@code SoundBuffer}: also exposes whether and where it is uploaded to the speakers. */
    interface Buffer extends MirrorTwin {
        boolean dungeontrain$hasAlBuffer();

        int dungeontrain$alBuffer();

        /** The PCM not yet handed to OpenAL, or null once it has been (vanilla drops it on upload). */
        @org.jetbrains.annotations.Nullable
        java.nio.ByteBuffer dungeontrain$data();

        javax.sound.sampled.AudioFormat dungeontrain$format();
    }
}
