package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client game-bus hooks for the void band's atmosphere:
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — darkens fog toward the End's look
 *       (~15% brightness) as the void intensity {@code t} rises, so the horizon
 *       blackens out under the End-sky overlay.</li>
 *   <li>{@link SelectMusicEvent} + {@link ClientTickEvent.Post} — a position-driven
 *       crossfade to the vanilla End music ({@code minecraft:music.end}) across the band, so
 *       the soundtrack matches the End sky/fog on screen. The band is layered onto the
 *       Overworld (not the real {@code the_end} dimension), so vanilla would otherwise keep
 *       playing Overworld music across the void. {@code SelectMusicEvent} chooses which track
 *       plays either side of the crossover; the per-tick handler scales the {@code MUSIC}
 *       volume by {@link ClientVoidBand#musicVolumeFactor} so the outgoing track fades down to
 *       the handoff and the incoming track fades back up — Overworld→End in, End→Overworld out.
 *       The actual volume scaling is applied by {@code SoundEngineMusicFadeMixin}.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state
 *       so it never leaks into the next world.</li>
 * </ul>
 * The sky overlay itself is drawn by {@link VoidSkyRenderer} from a render mixin.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VoidSkyEvents {

    /**
     * The vanilla End music ({@code minecraft:music.end}), but with short min/max delays so
     * it actually plays across the band. Vanilla {@code Musics.END} carries a 6000-tick
     * (5-minute) min delay: when {@code MusicManager} swaps to a {@code replaceCurrentMusic}
     * track it stops the current song immediately, then waits that delay before the next —
     * which left the void silent for minutes. Short delays (1s start, ≤10s gaps) make the End
     * track come in promptly and recur for the whole crossing.
     */
    private static final Music END_MUSIC = new Music(SoundEvents.MUSIC_END, 20, 200, true);

    /** True while we are actively scaling the music volume, so we force one final restore tick on leaving. */
    private static boolean musicFadeActive = false;

    private VoidSkyEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientVoidBand.endSkyIntensityAt(event.getCamera().getPosition().x);
        if (t <= 0.0) return;
        float f = (float) (1.0 - 0.85 * Math.min(1.0, t)); // → ~0.15× at full void (End fog)
        event.setRed(event.getRed() * f);
        event.setGreen(event.getGreen() * f);
        event.setBlue(event.getBlue() * f);
    }

    @SubscribeEvent
    public static void onSelectMusic(SelectMusicEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!mc.level.dimension().equals(Level.OVERWORLD)) return;
        // Respect screens that carry their own music (e.g. credits) — only override in-world.
        if (mc.screen != null && mc.screen.getBackgroundMusic() != null) return;

        double t = ClientVoidBand.endSkyIntensityAt(mc.player.getX());
        if (t <= 0.0) return; // outside any band → leave vanilla selection alone

        if (t > ClientVoidBand.MUSIC_CROSSOVER) {
            // Past the crossover the End track plays (the volume mixin fades it in/out by position).
            event.setMusic(END_MUSIC);
        } else {
            // Overworld side of a fade zone: keep the Overworld track, but give it short delays so it
            // resumes promptly on the way out instead of waiting vanilla's multi-minute idle gap.
            Music original = event.getOriginalMusic();
            if (original != null) {
                event.setMusic(new Music(original.getEvent(), 20, 200, true));
            }
        }
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        boolean inOverworld = mc.player != null && mc.level != null
                && mc.level.dimension().equals(Level.OVERWORLD);
        double factor = inOverworld ? ClientVoidBand.musicVolumeFactor(mc.player.getX()) : 1.0;
        boolean active = factor < 1.0;
        // While fading (and once more on the tick we stop) re-apply the MUSIC volume so the engine
        // recomputes the (mixin-scaled) gain for the currently playing, non-tickable music channel.
        if (active || musicFadeActive) {
            mc.getSoundManager().updateSourceVolume(SoundSource.MUSIC,
                    mc.options.getSoundSourceVolume(SoundSource.MUSIC));
            musicFadeActive = active;
        }
    }

    public static void onLoggingOut() {
        ClientVoidBand.reset();
        musicFadeActive = false;
    }
}
