package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client game-bus hooks for the void band's atmosphere:
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — darkens fog toward the End's look
 *       (~15% brightness) as the void intensity {@code t} rises, so the horizon
 *       blackens out under the End-sky overlay.</li>
 *   <li>{@link SelectMusicEvent} — swaps the soundtrack to the vanilla End music
 *       ({@code minecraft:music.end}) while the player is inside the band, so the
 *       music matches the End sky/fog already on screen. The band is layered onto the
 *       Overworld (not the real {@code the_end} dimension), so vanilla would otherwise
 *       keep playing Overworld/biome music across the void.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state
 *       so it never leaks into the next world.</li>
 * </ul>
 * The sky overlay itself is drawn by {@link VoidSkyRenderer} from a render mixin.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VoidSkyEvents {

    /**
     * Above this End-sky intensity, the soundtrack switches to the End music. Matches
     * {@code LevelRendererVoidSkyMixin}'s cloud-hide threshold so the music change lands
     * at the same point the clouds vanish and the End sky has mostly faded in.
     */
    private static final double END_MUSIC_THRESHOLD = 0.5;

    /**
     * The vanilla End music ({@code minecraft:music.end}), but with short min/max delays so
     * it actually plays across the band. Vanilla {@code Musics.END} carries a 6000-tick
     * (5-minute) min delay: when {@code MusicManager} swaps to a {@code replaceCurrentMusic}
     * track it stops the current song immediately, then waits that delay before the next —
     * which left the void silent for minutes. Short delays (1s start, ≤10s gaps) make the End
     * track come in promptly and recur for the whole crossing. {@code replaceCurrentMusic=true}
     * cuts the Overworld track the moment the world breaks apart.
     */
    private static final Music END_MUSIC = new Music(SoundEvents.MUSIC_END, 20, 200, true);

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
        if (t > END_MUSIC_THRESHOLD) {
            event.setMusic(END_MUSIC);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientVoidBand.reset();
    }
}
