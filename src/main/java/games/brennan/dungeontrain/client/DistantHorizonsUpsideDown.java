package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Stops <b>Distant Horizons</b> drawing while the camera is in — or close enough to see — the
 * upside-down band.
 *
 * <p><b>Why.</b> The band is not a world-data flip: the blocks stay the way they generated and DT
 * inverts them at mesh time, in both chunk-meshing pipelines ({@code ModelBlockRendererUpsideDownMixin}
 * for vanilla, {@code mixin.client.sodium.BlockRendererUpsideDownMixin} for Sodium), through
 * {@link UpsideDownRenderFlip}. DH renders neither: it draws its own LODs from its own database, so
 * in-band its horizon stands the right way up underneath an inverted sky and inverted near terrain —
 * two contradictory views of the same world, which is worse than no distant terrain at all.</p>
 *
 * <p><b>What this does not do.</b> Only DH's <em>rendering</em> of a frame is cancelled, per frame.
 * DH's config file is never written, its LOD generation and stored data are untouched, and nothing
 * persists — a crash or a disconnect mid-band cannot leave a player's DH switched off, which is
 * exactly the failure mode of flipping DH's own "enable rendering" setting instead.</p>
 *
 * <p><b>Loading.</b> DH is an optional companion, compiled against but never shipped or required, so
 * this class is the only one in DT that names DH types and it is reached solely behind the
 * {@code ModList} check in {@link DungeonTrainClient} — without DH installed it is never loaded and
 * its DH imports are never resolved. Same soft-dependency contract as the Iris/DH probes in
 * {@link GraphicsCapabilities}.</p>
 */
public final class DistantHorizonsUpsideDown {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DistantHorizonsUpsideDown() {}

    /**
     * Bind the suppression to DH's before-render event. Call once, on the client, only when DH is
     * loaded. Any failure (an older DH whose API differs, DH not initialised) degrades to "DH keeps
     * rendering in-band" and is logged once — never an exception into client setup.
     */
    public static void register() {
        try {
            DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new BandRenderSuppressor());
            LOGGER.info("[DungeonTrain] Distant Horizons will stop drawing in the upside-down band");
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons render hook; "
                    + "distant terrain may render upright in the upside-down band: {}", t.toString());
        }
    }

    /**
     * Whether DH should skip this frame: the player wants it hidden, and some part of the flipped
     * zone lies within the configured margin of the camera. Falls back to the config default before
     * the client config has loaded, and to "draw" when there is no camera yet.
     */
    private static boolean hideThisFrame() {
        if (!ClientDisplayConfig.isLoaded()) return false;
        if (!ClientDisplayConfig.UPSIDE_DOWN_HIDE_DISTANT_HORIZONS.get()) return false;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) return false;

        int cameraX = (int) Math.floor(camera.getPosition().x);
        return ClientUpsideDownBand.isFlipZoneWithin(
                cameraX, ClientDisplayConfig.UPSIDE_DOWN_DISTANT_HORIZONS_MARGIN.get());
    }

    /** DH's cancellable before-render event: cancelling it skips DH's LOD pass for that frame. */
    private static final class BandRenderSuppressor extends DhApiBeforeRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (hideThisFrame()) {
                event.cancelEvent();
            }
        }
    }
}
