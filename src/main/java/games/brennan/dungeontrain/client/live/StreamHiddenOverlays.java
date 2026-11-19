package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * Keeps the stream-muted music mods' lyrics off the Live Eye stream. The stream captures the finished
 * frame, HUD included ({@link LiveFrameGrabber}), so the only way to keep lyrics out of it is not to
 * draw them while this client is streaming. The player still hears the music (it plays through the
 * mod's own audio output, which the stream never copies) and gets the lyrics back when the stream ends.
 *
 * <p>What is hidden, per mod (see {@code ApprovedModList#streamMuted}):</p>
 * <ul>
 *   <li><b>Show the Lyrics</b>: its HUD widget layer, cancelled here, and its 3D lyrics in the world
 *       ({@code ShowTheLyricsHideMixin});</li>
 *   <li><b>Dango Music</b>: its HUD overlay and its 3D lyrics ({@code DangoMusicHideMixin});</li>
 *   <li><b>Concerto</b>: its in-game player HUD, lyrics included ({@code ConcertoHudHideMixin}).</li>
 * </ul>
 * <p>Their own screens need nothing: an open screen is never broadcast ({@link StreamableScreens}).
 * The mixins are gated by {@code MusicModsMixinPlugin}; a mod update that moves a hook logs a WARN at
 * boot and that mod's lyrics stay visible on the stream, as they were before it was approved.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamHiddenOverlays {

    /** Show the Lyrics' HUD widget ({@code RegisterGuiLayersEvent#registerAboveAll}). */
    static final ResourceLocation SHOW_THE_LYRICS_WIDGET =
        ResourceLocation.fromNamespaceAndPath("showthelyrics", "player_widget");

    /** Dev-only override: hide as if this client were streaming. Never set on a release build. */
    public static volatile boolean devForce;

    private StreamHiddenOverlays() {}

    /** True while this client is broadcasting: the music mods' lyrics are not drawn. */
    public static boolean hidden() {
        if (devForce) return true;
        try {
            LiveStreamController c = LiveStreamController.get();
            return c != null && c.streaming();
        } catch (Throwable t) {
            // Hide rather than leak if the controller can't be read mid-frame.
            return true;
        }
    }

    @SubscribeEvent
    public static void onRenderLayer(RenderGuiLayerEvent.Pre event) {
        if (SHOW_THE_LYRICS_WIDGET.equals(event.getName()) && hidden()) event.setCanceled(true);
    }
}
