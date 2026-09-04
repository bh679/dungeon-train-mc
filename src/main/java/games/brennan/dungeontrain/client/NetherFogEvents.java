package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client game-bus hooks for the Nether transition band's atmosphere — the counterpart
 * to {@link VoidSkyEvents} for the End band:
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — blends fog toward the Nether's colour as the nether
 *       intensity {@code n} rises. In the real-Nether core it uses the <em>actual biome's</em> own fog
 *       colour (warped teal, crimson red, soul-sand blue, basalt grey, wastes red), so the fog matches
 *       the biome the player is in; the netherrack crossfade (a highland biome) keeps the fixed
 *       nether_wastes red.</li>
 *   <li>{@link SelectMusicEvent} + {@link ClientTickEvent.Post} — a position-driven crossfade to
 *       the vanilla nether_wastes music ({@code minecraft:music.nether.nether_wastes}) across the
 *       band, so the soundtrack matches the red fog/core on screen. The band is layered onto the
 *       Overworld (not the real {@code the_nether} dimension), so vanilla would otherwise keep
 *       playing Overworld music through the netherrack. {@code SelectMusicEvent} chooses which
 *       track plays either side of the crossover; the per-tick handler scales the {@code MUSIC}
 *       volume by {@link ClientNetherBand#musicVolumeFactor} so the outgoing track fades down to
 *       the handoff and the incoming track fades back up — Overworld→Nether in, Nether→Overworld
 *       out. The actual volume scaling is applied by {@code SoundEngineMusicFadeMixin} (shared
 *       with the End band). Mirrors {@link VoidSkyEvents}.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state.</li>
 * </ul>
 * Cloud suppression piggybacks on the shared {@code LevelRendererVoidSkyMixin}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NetherFogEvents {

    /** Target fog colour at full intensity — vanilla nether_wastes fog (0x330808). */
    private static final float NETHER_FOG_R = 0.2f;
    private static final float NETHER_FOG_G = 0.03f;
    private static final float NETHER_FOG_B = 0.03f;

    /**
     * The vanilla nether_wastes music ({@code minecraft:music.nether.nether_wastes}), but with
     * short min/max delays so it actually plays across the band — same treatment as
     * {@code VoidSkyEvents.END_MUSIC}. Vanilla biome {@code Musics} carry a multi-minute min
     * delay: when {@code MusicManager} swaps to a {@code replaceCurrentMusic} track it stops the
     * current song immediately, then waits that delay before the next — which would leave the
     * netherrack core silent for minutes. Short delays (1s start, ≤10s gaps) make the Nether track
     * come in promptly and recur for the whole crossing.
     */
    private static final Music NETHER_MUSIC = new Music(SoundEvents.MUSIC_BIOME_NETHER_WASTES, 20, 200, true);

    /** True while we are actively scaling the music volume, so we force one final restore tick on leaving. */
    private static boolean musicFadeActive = false;

    private NetherFogEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double n = ClientNetherBand.netherIntensityAt(event.getCamera().getPosition().x);
        if (n <= 0.0) return;
        float t = (float) Math.min(1.0, n);

        // Target colour: in the real-Nether core, the biome's own fog colour; elsewhere (the
        // crossfade's highland biome) the fixed nether_wastes red. Shared with NetherSkyRenderer
        // so the sky dome and the fog stay the same colour.
        int target = smoothedNetherColor(mc.level, event.getCamera().getBlockPosition(), true);
        float fr = ((target >> 16) & 0xFF) / 255.0f;
        float fg = ((target >> 8) & 0xFF) / 255.0f;
        float fb = (target & 0xFF) / 255.0f;

        int before = ShaderDiagnostics.recording() ? ShaderDiagnostics.packFog(event) : 0;
        event.setRed(lerp(event.getRed(), fr, t));
        event.setGreen(lerp(event.getGreen(), fg, t));
        event.setBlue(lerp(event.getBlue(), fb, t));
        if (ShaderDiagnostics.recording()) {
            ShaderDiagnostics.recordFogColor("nether", before, ShaderDiagnostics.packFog(event));
        }
    }

    /**
     * The Nether atmosphere's target colour (packed {@code 0xRRGGBB}) at {@code pos}: the biome's
     * own fog colour when the camera is in one of the five real-Nether biomes (so warped teal,
     * crimson red, soul-sand blue, basalt grey and wastes red each match their biome), otherwise
     * the fixed {@code nether_wastes} red {@code 0x330808} — also the fallback on any read error.
     *
     * <p>Used by both the fog blend above and {@link NetherSkyRenderer} so the sky dome the player
     * sees and the fog they look through are painted the same colour.</p>
     */
    /**
     * How fast the Nether colour crosses a biome boundary, as a fraction of the gap per frame.
     *
     * <p>The Nether biomes carry sharply different fog colours — crimson red against warped teal —
     * and sampling the biome under the camera hands back a step change the moment a foot crosses the
     * line. Vanilla hides that with its own biome blending; this path samples one block and so shows
     * the step outright, which under a shader pack drives the pack's whole atmosphere and reads as
     * the background snapping colour. Easing costs a few frames of lag entering a new biome and
     * removes the step.</p>
     */
    private static final float BIOME_COLOUR_EASE = 0.04f;

    /** The eased colour, as floats so a slow ease is not quantised to nothing. Render thread. */
    private static float smoothR = -1.0f;
    private static float smoothG;
    private static float smoothB;

    /**
     * The Nether colour to draw right now: {@link #netherTargetColor} eased across biome changes.
     *
     * <p>Advanced once per frame from the fog-colour handler, which is the one caller guaranteed to
     * run exactly once a frame. Everything else — the sky dome, the pack's {@code skyColor} uniform —
     * reads it, so they all agree within a frame and move together across a boundary.</p>
     */
    public static int smoothedNetherColor(Level level, net.minecraft.core.BlockPos pos, boolean advance) {
        int target = netherTargetColor(level, pos);
        float tr = ((target >> 16) & 0xFF) / 255.0f;
        float tg = ((target >> 8) & 0xFF) / 255.0f;
        float tb = (target & 0xFF) / 255.0f;

        if (smoothR < 0.0f) {
            smoothR = tr;
            smoothG = tg;
            smoothB = tb;
        } else if (advance) {
            smoothR += (tr - smoothR) * BIOME_COLOUR_EASE;
            smoothG += (tg - smoothG) * BIOME_COLOUR_EASE;
            smoothB += (tb - smoothB) * BIOME_COLOUR_EASE;
        }
        return (Math.round(smoothR * 255.0f) << 16)
            | (Math.round(smoothG * 255.0f) << 8)
            | Math.round(smoothB * 255.0f);
    }

    /** Drop the ease, so one world's biome colour never bleeds into the next. */
    public static void resetSmoothedColor() {
        smoothR = -1.0f;
    }

    public static int netherTargetColor(Level level, net.minecraft.core.BlockPos pos) {
        try {
            Holder<Biome> biome = level.getBiome(pos);
            if (isNetherBiome(biome)) {
                return biome.value().getFogColor() & 0xFFFFFF;
            }
        } catch (Throwable ignored) {
            // fall through to the fixed nether_wastes red
        }
        return ((int) (NETHER_FOG_R * 255.0f) << 16)
                | ((int) (NETHER_FOG_G * 255.0f) << 8)
                | (int) (NETHER_FOG_B * 255.0f);
    }

    /** True for the five vanilla Nether biomes (the only ones the core labels columns with). */
    private static boolean isNetherBiome(Holder<Biome> biome) {
        return biome.is(Biomes.NETHER_WASTES)
                || biome.is(Biomes.CRIMSON_FOREST)
                || biome.is(Biomes.WARPED_FOREST)
                || biome.is(Biomes.SOUL_SAND_VALLEY)
                || biome.is(Biomes.BASALT_DELTAS);
    }

    @SubscribeEvent
    public static void onSelectMusic(SelectMusicEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!mc.level.dimension().equals(Level.OVERWORLD)) return;
        // Respect screens that carry their own music (e.g. credits) — only override in-world.
        if (mc.screen != null && mc.screen.getBackgroundMusic() != null) return;

        double n = ClientNetherBand.netherIntensityAt(mc.player.getX());
        if (n <= 0.0) return; // outside any nether band → leave vanilla selection alone

        if (n > ClientNetherBand.MUSIC_CROSSOVER) {
            // Past the crossover the Nether track plays (the volume mixin fades it in/out by position).
            event.setMusic(NETHER_MUSIC);
        } else {
            // Overworld side of a fade zone: keep the Overworld track, but give it short delays so it
            // resumes promptly on the way out instead of waiting vanilla's multi-minute idle gap.
            Music original = event.getOriginalMusic();
            if (original != null) {
                event.setMusic(new Music(original.getEvent(), 20, 200, true));
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean inOverworld = mc.player != null && mc.level != null
                && mc.level.dimension().equals(Level.OVERWORLD);
        double factor = inOverworld ? ClientNetherBand.musicVolumeFactor(mc.player.getX()) : 1.0;
        boolean active = factor < 1.0;
        // While fading (and once more on the tick we stop) re-apply the MUSIC volume so the engine
        // recomputes the (mixin-scaled) gain for the currently playing, non-tickable music channel.
        if (active || musicFadeActive) {
            mc.getSoundManager().updateSourceVolume(SoundSource.MUSIC,
                    mc.options.getSoundSourceVolume(SoundSource.MUSIC));
            musicFadeActive = active;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNetherBand.reset();
        musicFadeActive = false;
        resetSmoothedColor();
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
