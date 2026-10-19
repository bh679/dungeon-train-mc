package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The sound of the train wind, from vanilla sounds (Windy ships none), on the Ambient volume slider:
 * <ul>
 *   <li><b>Breeze</b> — the elytra's rushing-air loop, louder and higher as the steady wind rises.</li>
 *   <li><b>Gust</b> — a second, higher copy of that loop that swells with each gust.</li>
 *   <li><b>Gust whoosh</b> — the Breeze mob's air sound, once as each gust starts, from the upwind side.</li>
 * </ul>
 * It follows the wind at the player <em>now</em> (what they feel; the streaks lead it), and is muffled — quieter
 * and lower — when they shelter: inside a carriage or under cover, in a lee, or in a tunnel. The wind is still
 * blowing outside, so it never goes silent there.
 */
final class WindSound {

    /** Push at which the breeze is at full volume. */
    private static final double FULL_PUSH = 0.03;
    private static final float BREEZE_MAX_VOLUME = 0.55f;
    private static final float GUST_MAX_VOLUME = 0.7f;
    /** Per-tick easing toward the target volume/pitch (loops never jump). */
    private static final float EASE = 0.12f;
    /** Gust share at which a new gust "starts" (whoosh), and must fall below to re-arm. */
    private static final double GUST_ON = 0.35;
    private static final double GUST_OFF = 0.15;
    private static final double WHOOSH_DISTANCE = 6.0;
    /** Far below the world, so Sable's per-sub-level sound remapping never touches a listener-relative loop. */
    private static final double SABLE_BYPASS_Z = -1.0e7;

    private static Loop breeze;
    private static Loop gust;
    private static boolean gustArmed = true;

    private WindSound() {}

    /** Called every client tick while the wind is active in the Overworld. */
    static void update(LocalPlayer player, WindSample now, TrainExposure.Result exposure, boolean underTerrain) {
        double muffle = muffle(exposure, underTerrain);
        double level = Math.min(1.0, now.strength() / FULL_PUSH);
        double gustShare = now.gust01();

        breeze = ensure(breeze, 0.8f);
        gust = ensure(gust, 1.25f);
        breeze.target((float) (Math.sqrt(level) * BREEZE_MAX_VOLUME * muffle),
                (float) ((0.55 + 0.6 * level) * (muffle < 1.0 ? 0.8 : 1.0)));
        gust.target((float) (gustShare * Math.sqrt(level) * GUST_MAX_VOLUME * muffle),
                (float) ((0.9 + 0.5 * gustShare) * (muffle < 1.0 ? 0.8 : 1.0)));

        if (gustArmed && gustShare > GUST_ON) {
            gustArmed = false;
            whoosh(player, now, (float) (0.25 + 0.5 * level) * (float) muffle);
        } else if (gustShare < GUST_OFF) {
            gustArmed = true;
        }
    }

    /** Fade everything out (wind off, left the Overworld, logged out). */
    static void silence() {
        if (breeze != null) breeze.target(0.0f, breeze.pitchTarget);
        if (gust != null) gust.target(0.0f, gust.pitchTarget);
        gustArmed = true;
    }

    private static double muffle(TrainExposure.Result exposure, boolean underTerrain) {
        if (underTerrain) return 0.3;
        return switch (exposure) {
            case UNDER_COVER, IN_TUNNEL -> 0.35;
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

    private static void whoosh(LocalPlayer player, WindSample w, float volume) {
        if (volume <= 0.02f) return;
        // From upwind: the gust arrives from where the wind comes from.
        double x = player.getX() - w.dirX() * WHOOSH_DISTANCE;
        double z = player.getZ() - w.dirZ() * WHOOSH_DISTANCE;
        RandomSource r = player.getRandom();
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(SoundEvents.BREEZE_IDLE_AIR,
                SoundSource.AMBIENT, volume, 0.8f + r.nextFloat() * 0.3f, r, x, player.getEyeY(), z));
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
