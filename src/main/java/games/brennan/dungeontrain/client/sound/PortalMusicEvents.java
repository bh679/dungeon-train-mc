package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

/**
 * Fades the soundtrack out along a portal corridor and keeps the dimensional carriage silent — the client
 * side of {@link ClientPortalMusic}, and the audio counterpart to
 * {@link games.brennan.dungeontrain.client.DimensionalCarriageFogEvents}.
 *
 * <p><b>Two mechanisms, in order.</b> The fade is a gain scale: {@code ClientPortalMusic}'s factor
 * reaches the volume mixin, and this class re-pushes the {@code MUSIC} source volume each tick so
 * the playing channel picks the new gain up — music in 1.21.1 is a fixed-volume, non-tickable
 * {@code SimpleSoundInstance}, so nothing recomputes it otherwise. Once the factor is {@code 0} the
 * track is stopped outright, by handing {@code null} back from {@link SelectMusicEvent}. Stopping
 * without the fade would be a cut; fading without the stop would leave a silent track playing on,
 * to come blaring back mid-phrase the moment the player stepped out.</p>
 *
 * <p><b>Why the restart is timed here rather than left to vanilla.</b> {@code MusicManager.tick}
 * pins {@code nextSongDelay} to {@code 0} for every tick the selected music is null, so the frame
 * after a player leaves a dimensional carriage a fresh track would start immediately — the one thing that is
 * definitely wrong, since it announces the exit. So leaving the silent zone arms a deadline and the
 * silence continues until it passes: at least {@link #MIN_RESTART_TICKS}, at most the situational
 * track's own idle gap, which is the delay vanilla would have used between two tracks anyway.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalMusicEvents {

    /**
     * Shortest silence after leaving a portal, in ticks (20 s).
     *
     * <p>A floor, not the figure — the deadline is rolled between this and the track's own maximum
     * gap. Without it "the normal restart timer" and "the instant you step out" are the same thing,
     * because the roll's other end is a minimum of zero.</p>
     */
    private static final int MIN_RESTART_TICKS = 400;

    private static final RandomSource RANDOM = RandomSource.create();

    /** Client ticks elapsed, so the deadline needs no wall clock and resets with the world. */
    private static long clientTicks = 0L;

    /** Ticks until music may start again, or {@code 0} for "no suppression running". */
    private static long suppressUntilTick = 0L;

    /** Set while the player is in the silent zone; read once, on the tick they leave it. */
    private static boolean wasSilenced = false;

    /** True while the mixin is scaling the gain, so it gets one final tick to restore it. */
    private static boolean musicFadeActive = false;

    private PortalMusicEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTicks++;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        float target = player == null || mc.level == null
            ? 1.0f
            : ClientPortalMusic.targetFactorAt(player.getX(), player.getY(), player.getZ());
        float factor = ClientPortalMusic.tick(target);

        boolean active = factor < 1.0f;
        // While fading (and once more on the tick we stop) re-apply the MUSIC volume so the engine
        // recomputes the mixin-scaled gain for the currently playing music channel.
        if (active || musicFadeActive) {
            mc.getSoundManager().updateSourceVolume(SoundSource.MUSIC,
                mc.options.getSoundSourceVolume(SoundSource.MUSIC));
            musicFadeActive = active;
        }
    }

    /**
     * Runs last so it wins: a portal structure sits under the Overworld and can share its X range
     * with a void or Nether band, whose handlers pick a track of their own.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSelectMusic(SelectMusicEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        // Screens that carry their own music (credits, and the like) stay in charge, as they do for
        // the band handlers.
        if (mc.screen != null && mc.screen.getBackgroundMusic() != null) return;

        boolean silent = ClientPortalMusic.targetFactorAt(
            player.getX(), player.getY(), player.getZ()) <= 0.0f;

        if (silent) {
            // The deadline is rolled on the way out instead, from wherever they leave.
            wasSilenced = true;
            suppressUntilTick = 0L;
            event.overrideMusic(null);
            return;
        }

        // Only a player who actually reached silence gets a restart delay. Walking part way down a
        // corridor and turning back is a fade down and a fade up, not a stop — arming the deadline
        // on any faded tick would have that cut off the track they were already listening to.
        if (wasSilenced) {
            wasSilenced = false;
            Music music = event.getMusic() != null ? event.getMusic() : event.getOriginalMusic();
            int max = music == null ? MIN_RESTART_TICKS : Math.max(MIN_RESTART_TICKS, music.getMaxDelay());
            suppressUntilTick = clientTicks + Mth.nextInt(RANDOM, MIN_RESTART_TICKS, max);
        }

        if (suppressUntilTick > clientTicks) {
            event.overrideMusic(null);
        } else {
            suppressUntilTick = 0L;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPortalMusic.reset();
        suppressUntilTick = 0L;
        wasSilenced = false;
        musicFadeActive = false;
        clientTicks = 0L;
    }
}
