package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.worldgen.SpheresComets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The roar of one incoming comet: three vanilla loops placed at the comet that swell together as it closes on the
 * listener ({@link SpheresComets#roarBuild}). Their volume is set here rather than by Minecraft's distance falloff,
 * which would leave a comet silent until it was close and then cut in; this way it is a faint rush from the moment
 * it appears and builds steadily all the way in. Comets come in from space, so there is no launch sound.
 * <ul>
 *   <li><b>Rush</b> — the elytra air loop, rising in pitch as it nears.</li>
 *   <li><b>Rumble</b> — the same loop held at its lowest pitch, for weight.</li>
 *   <li><b>Fire</b> — the fire loop, only coming up in the last stretch.</li>
 * </ul>
 */
final class CometSound {

    private static final float RUSH_FLOOR = 0.12F;
    private static final float RUMBLE_SHARE = 0.5F;
    private static final float FIRE_MAX = 0.9F;
    /** Per-tick easing toward the target volume and pitch, so the loops never step. */
    private static final float EASE = 0.25F;

    private final Loop rush = new Loop(SoundEvents.ELYTRA_FLYING);
    private final Loop rumble = new Loop(SoundEvents.ELYTRA_FLYING);
    private final Loop fire = new Loop(SoundEvents.FIRE_AMBIENT);

    private CometSound() {}

    static CometSound start(Vec3 at) {
        CometSound sound = new CometSound();
        for (Loop loop : sound.loops()) {
            loop.moveTo(at);
            Minecraft.getInstance().getSoundManager().play(loop);
        }
        return sound;
    }

    /** The comet is at {@code at}, {@code distance} blocks from the listener. */
    void update(Vec3 at, double distance) {
        float build = (float) SpheresComets.roarBuild(distance);
        float pitch = SpheresComets.roarPitch(distance);
        float rushVolume = RUSH_FLOOR + (1.0F - RUSH_FLOOR) * build;
        rush.target(at, rushVolume, pitch);
        rumble.target(at, rushVolume * RUMBLE_SHARE, 0.5F);
        fire.target(at, FIRE_MAX * build * build, 0.7F);
    }

    /** Fade out over a few ticks and end; the comet landed or burnt out. */
    void stop() {
        for (Loop loop : loops()) loop.fadeOut();
    }

    private Loop[] loops() {
        return new Loop[] {rush, rumble, fire};
    }

    /** One looping layer at the comet, easing toward its target volume and pitch each tick. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private float volumeTarget;
        private float pitchTarget = 0.5F;
        private boolean fading;

        Loop(SoundEvent event) {
            super(event, SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.attenuation = Attenuation.NONE;
            this.relative = false;
            this.volume = 0.001F;
            this.pitch = 0.5F;
        }

        void moveTo(Vec3 at) {
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
        }

        void target(Vec3 at, float volume, float pitch) {
            moveTo(at);
            this.volumeTarget = Math.max(0.0F, Math.min(1.0F, volume));
            this.pitchTarget = pitch;
        }

        void fadeOut() {
            this.fading = true;
            this.volumeTarget = 0.0F;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (Minecraft.getInstance().level == null) {
                stop();
                return;
            }
            this.volume = Math.max(0.001F, volume + (volumeTarget - volume) * EASE);
            this.pitch = pitch + (pitchTarget - pitch) * EASE;
            if (fading && volume < 0.01F) stop();
        }
    }
}
