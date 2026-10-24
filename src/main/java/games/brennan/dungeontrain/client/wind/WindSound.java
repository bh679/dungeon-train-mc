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
 * It follows the wind arriving ({@code ahead}, so you hear a gust coming). While the wind is actually pushing
 * the player ({@link WindFeel}) two more things happen:
 * <ul>
 *   <li><b>Louder when pushed</b> — the breeze holds at least {@link #PUSHED_FLOOR}, rising with the push, the
 *       gust copy also follows the wind <em>now</em> so it overlaps the shove instead of fading before it, and
 *       rising volume eases in faster.</li>
 *   <li><b>Ear buffet</b> ({@link WindFeelMode#buffet}) — a low, fluttering copy of the loop that only plays
 *       while you are being moved.</li>
 * </ul>
 * Inside a carriage or under cover, and in a tunnel, it is silent; in a lee it is quieter.
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
    /** Per-tick easing while the volume rises and the wind is pushing the player: a shove swells in quickly. */
    private static final float EASE_PUSHED = 0.35f;
    /** Least breeze volume while the wind is moving the player, and how much more a full push adds. */
    private static final float PUSHED_FLOOR = 0.35f;
    private static final float PUSHED_SPAN = 0.45f;
    /** The ear buffet: volume at a full push, pitch, and its flutter. */
    private static final float BUFFET_MAX_VOLUME = 0.7f;
    private static final float BUFFET_PITCH = 0.5f;
    private static final float BUFFET_TREMOLO_HZ = 4.0f;
    private static final float BUFFET_TREMOLO_DEPTH = 0.6f;
    /** Far below the world, so Sable's per-sub-level sound remapping never touches a listener-relative loop. */
    private static final double SABLE_BYPASS_Z = -1.0e7;

    private static Loop breeze;
    private static Loop gust;
    private static Loop buffet;

    private WindSound() {}

    /**
     * Called every client tick while the wind is active in the Overworld. {@code ahead} is the wind arriving (what
     * the streaks show), {@code now} the wind pushing the player this tick.
     */
    static void update(LocalPlayer player, WindSample now, WindSample ahead, TrainExposure.Result exposure,
                       boolean underTerrain) {
        double muffle = muffle(exposure, underTerrain);
        double level = Math.min(1.0, ahead.strength() / FULL_PUSH);
        double gustShare = ahead.gust01();
        double volume = games.brennan.dungeontrain.config.ClientDisplayConfig.getWindVolume();

        float pitch = (float) ((0.55 + 0.6 * level) * (muffle < 1.0 ? 0.8 : 1.0));
        breeze = ensure(breeze, pitch);
        gust = ensure(gust, pitch);
        double loud = Math.sqrt(level) * muffle * volume;
        double breezeVol = loud * (BREEZE_MAX_VOLUME + BREEZE_GUST_BOOST * gustShare);
        double gustVol = loud * GUST_MAX_VOLUME * gustShare;

        double felt = WindFeel.target();
        boolean pushed = felt > 0.0;
        if (pushed) {
            breezeVol = Math.max(breezeVol, (PUSHED_FLOOR + PUSHED_SPAN * Math.sqrt(felt)) * muffle * volume);
            double loudNow = Math.sqrt(Math.min(1.0, now.strength() / FULL_PUSH)) * muffle * volume;
            gustVol = Math.max(gustVol, loudNow * GUST_MAX_VOLUME * now.gust01());
        }
        float rise = pushed ? EASE_PUSHED : EASE;
        breeze.target((float) breezeVol, pitch, rise);
        gust.target((float) gustVol, pitch, rise);

        boolean buffetOn = WindFeelMode.current().buffet && pushed;
        if (buffetOn || buffet != null) {
            if (buffetOn) buffet = ensure(buffet, BUFFET_PITCH);
            if (buffet != null) {
                buffet.tremolo(BUFFET_TREMOLO_HZ, BUFFET_TREMOLO_DEPTH);
                buffet.target(buffetOn ? (float) (BUFFET_MAX_VOLUME * Math.sqrt(felt) * muffle * volume) : 0.0f,
                        BUFFET_PITCH, EASE_PUSHED);
            }
        }
    }

    /** Fade everything out (wind off, left the Overworld, logged out). */
    static void silence() {
        if (breeze != null) breeze.target(0.0f, breeze.pitchTarget);
        if (gust != null) gust.target(0.0f, gust.pitchTarget);
        if (buffet != null) buffet.target(0.0f, buffet.pitchTarget);
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

    /**
     * A listener-relative looping wind layer that eases toward a target volume and pitch, optionally with a
     * tremolo (its volume fluttering at a set rate).
     */
    private static final class Loop extends AbstractTickableSoundInstance {
        private float volumeTarget = 0.0f;
        private float pitchTarget;
        private float rise = EASE;
        private float level = 0.001f;
        private float tremoloHz = 0.0f;
        private float tremoloDepth = 0.0f;
        private float phase = 0.0f;

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
            target(volume, pitch, EASE);
        }

        /** As {@link #target(float, float)}, easing up at {@code rise} per tick (down always at {@link #EASE}). */
        void target(float volume, float pitch, float rise) {
            this.volumeTarget = Math.max(0.0f, volume);
            this.pitchTarget = Math.max(0.5f, Math.min(2.0f, pitch));
            this.rise = rise;
        }

        void tremolo(float hz, float depth) {
            this.tremoloHz = hz;
            this.tremoloDepth = depth;
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
            float ease = volumeTarget > level ? rise : EASE;
            this.level = Math.max(0.001f, level + (volumeTarget - level) * ease);
            float flutter = 1.0f;
            if (tremoloHz > 0.0f) {
                phase = (phase + tremoloHz / 20.0f) % 1.0f;
                flutter = 1.0f - tremoloDepth * (0.5f + 0.5f * (float) Math.sin(phase * 2.0 * Math.PI));
            }
            this.volume = Math.max(0.001f, level * flutter);
            this.pitch = this.pitch + (pitchTarget - this.pitch) * EASE;
        }
    }
}
