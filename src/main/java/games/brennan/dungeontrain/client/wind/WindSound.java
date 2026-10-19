package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The sound of the train wind, from vanilla sounds (Windy ships none), scaled by the Wind volume slider
 * ({@link WindVolumeOption}) and played on the Ambient category:
 * <ul>
 *   <li><b>Breeze</b> — the elytra's rushing-air loop, louder and higher as the wind rises.</li>
 *   <li><b>Gust</b> — the same wind doubled: a second copy of the loop at the same pitch that swells in with
 *       each gust, while the breeze itself also gets louder.</li>
 * </ul>
 * It follows the wind at the player <em>now</em> (what they feel; the streaks lead it). Inside a carriage or
 * under cover, and in a tunnel, it is silent; in a lee it is quieter.
 */
final class WindSound {

    /** Push at which the breeze is at full volume. */
    private static final double FULL_PUSH = 0.03;
    private static final float BREEZE_MAX_VOLUME = 0.6f;
    /** How much louder the breeze itself gets at the height of a gust. */
    private static final float BREEZE_GUST_BOOST = 0.35f;
    private static final float GUST_MAX_VOLUME = 1.0f;
    /** Per-tick easing toward the target volume/pitch (loops never jump). */
    private static final float EASE = 0.12f;
    /** Far below the world, so Sable's per-sub-level sound remapping never touches a listener-relative loop. */
    private static final double SABLE_BYPASS_Z = -1.0e7;

    private static Loop breeze;
    private static Loop gust;

    private WindSound() {}

    /** Called every client tick while the wind is active in the Overworld. */
    static void update(LocalPlayer player, WindSample now, TrainExposure.Result exposure, boolean underTerrain) {
        double muffle = muffle(exposure, underTerrain);
        double level = Math.min(1.0, now.strength() / FULL_PUSH);
        double gustShare = now.gust01();

        float pitch = (float) ((0.55 + 0.6 * level) * (muffle < 1.0 ? 0.8 : 1.0));
        breeze = ensure(breeze, pitch);
        gust = ensure(gust, pitch);
        double loud = Math.sqrt(level) * muffle
                * games.brennan.dungeontrain.config.ClientDisplayConfig.getWindVolume();
        breeze.target((float) (loud * (BREEZE_MAX_VOLUME + BREEZE_GUST_BOOST * gustShare)), pitch);
        gust.target((float) (loud * GUST_MAX_VOLUME * gustShare), pitch);
    }

    /** Fade everything out (wind off, left the Overworld, logged out). */
    static void silence() {
        if (breeze != null) breeze.target(0.0f, breeze.pitchTarget);
        if (gust != null) gust.target(0.0f, gust.pitchTarget);
    }

    private static double muffle(TrainExposure.Result exposure, boolean underTerrain) {
        if (underTerrain) return 0.0;
        return switch (exposure) {
            case UNDER_COVER, IN_TUNNEL -> 0.0;
            case IN_LEE -> 0.7;
            default -> 1.0;
        };
    }

    private static Loop ensure(Loop loop, float basePitch) {
        Minecraft mc = Minecraft.getInstance();
        if (loop != null && !loop.isStopped() && mc.getSoundManager().isActive(loop)) return loop;
        Loop fresh = new Loop(SoundEvents.ELYTRA_FLYING, basePitch);
        mc.getSoundManager().play(fresh);
        return fresh;
    }

    /** A listener-relative looping wind layer that eases toward a target volume and pitch. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private float volumeTarget = 0.0f;
        private float pitchTarget;

        Loop(SoundEvent event, float pitch) {
            super(event, SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.attenuation = Attenuation.NONE;
            this.relative = true;
            this.volume = 0.001f;
            this.pitch = pitch;
            this.pitchTarget = pitch;
            this.x = 0.0;
            this.y = 0.0;
            this.z = SABLE_BYPASS_Z;
        }

        void target(float volume, float pitch) {
            this.volumeTarget = Math.max(0.0f, volume);
            this.pitchTarget = Math.max(0.5f, Math.min(2.0f, pitch));
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
            // Keep a whisper of volume so the instance stays alive between gusts.
            this.volume = Math.max(0.001f, this.volume + (volumeTarget - this.volume) * EASE);
            this.pitch = this.pitch + (pitchTarget - this.pitch) * EASE;
        }
    }
}
