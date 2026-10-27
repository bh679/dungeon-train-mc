package games.brennan.dungeontrain.client.live;

import net.mehvahdjukaar.vista.VistaMod;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * A live feed playing out of one TV ({@code at} its screen, fading over Vista's 32 blocks) or out of the
 * TV on the viewer's own head or in their hand ({@code at} null: no position, no fade). Vista's own
 * speaker sound event (a streamed sound, so Minecraft asks this instance for its audio) and category,
 * so Vista's TV volume and the Blocks slider set how loud it is; the audio is {@link LiveAudioStream}.
 */
final class LiveSpeakerSound extends AbstractTickableSoundInstance {

    private final LiveAudioRing ring;
    @Nullable private volatile LiveAudioStream stream;

    LiveSpeakerSound(LiveAudioRing ring, @Nullable Vec3 at, float volume) {
        super((SoundEvent) VistaMod.TV_SPEAKER_SOUND.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.ring = ring;
        this.volume = volume;
        if (at == null) {
            this.relative = true;
            this.attenuation = Attenuation.NONE;
        } else {
            moveTo(at);
        }
    }

    void moveTo(Vec3 at) {
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;
    }

    void setVolume(float volume) {
        this.volume = volume;
    }

    /** Stop playing; the sound engine lets it go on its next tick. */
    void end() {
        stop();
    }

    /** How far behind the feed this speaker plays, ms; 0 before it started. */
    long lagMs() {
        LiveAudioStream s = stream;
        return s == null ? 0 : s.lagMs();
    }

    @Override
    public void tick() {
        // moved and re-levelled by LiveTvSpeakers
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        LiveAudioStream s = new LiveAudioStream(ring);
        stream = s;
        return CompletableFuture.completedFuture(s);
    }
}
