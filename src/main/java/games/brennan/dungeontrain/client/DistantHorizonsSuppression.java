package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Stops <b>Distant Horizons</b> drawing wherever its own copy of the world would contradict the one
 * Dungeon Train is showing. Two places do that, for the same underlying reason and independently
 * switchable.
 *
 * <p><b>The upside-down band.</b> The band is not a world-data flip: the blocks stay the way they
 * generated and DT inverts them at mesh time, in both chunk-meshing pipelines
 * ({@code ModelBlockRendererUpsideDownMixin} for vanilla, {@code mixin.client.sodium.BlockRendererUpsideDownMixin}
 * for Sodium), through {@link UpsideDownRenderFlip}. DH renders neither: it draws its own LODs from
 * its own database, so in-band its horizon stands the right way up underneath an inverted sky and
 * inverted near terrain — two contradictory views of the same world, which is worse than no distant
 * terrain at all.</p>
 *
 * <p><b>Dimensional carriages.</b> A portal room is not a dimension either. It is stamped into twin
 * space — the sealed basement under the world's bedrock, or the attic over the band's lid — in the
 * chunk columns of the carriage it stands in for, and everything that sells it as somewhere else is
 * a local trick: the fog ({@link ClientPortalRoomFog}), the room sky ({@link ClientPortalRoomSky}),
 * the skybox punch, the faked debug-screen Y ({@link ClientPortalRoomDepth}). DH sees through all of
 * it, because it draws the overworld's LODs at the real coordinates the room occupies — so the
 * room's sky arrives with the surface world's horizon behind it. A {@code CHUNK_DIMENSION} room is
 * the worst of it: the room <em>is</em> a sampled slice of terrain, and DH draws the unrelated
 * surface behind that slice.</p>
 *
 * <p><b>What this does not do.</b> Only DH's <em>rendering</em> of a frame is cancelled, per frame.
 * DH's config file is never written, its LOD generation and stored data are untouched, and nothing
 * persists — a crash or a disconnect inside a room or mid-band cannot leave a player's DH switched
 * off, which is exactly the failure mode of flipping DH's own "enable rendering" setting instead.</p>
 *
 * <p><b>Loading.</b> DH is an optional companion, compiled against but never shipped or required, so
 * this class is the only one in DT that names DH types and it is reached solely behind the
 * {@code ModList} check in {@link DungeonTrainClient} — without DH installed it is never loaded and
 * its DH imports are never resolved. Same soft-dependency contract as the Iris/DH probes in
 * {@link GraphicsCapabilities}.</p>
 */
public final class DistantHorizonsSuppression {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DistantHorizonsSuppression() {}

    /**
     * Bind the suppression to DH's before-render event. Call once, on the client, only when DH is
     * loaded. Any failure (an older DH whose API differs, DH not initialised) degrades to "DH keeps
     * rendering" and is logged once — never an exception into client setup.
     */
    public static void register() {
        try {
            DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new RenderSuppressor());
            LOGGER.info("[DungeonTrain] Distant Horizons will stop drawing in the upside-down band "
                    + "and inside dimensional carriages");
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons render hook; "
                    + "distant terrain may render through the upside-down band and portal rooms: {}",
                    t.toString());
        }
    }

    /**
     * Whether DH should skip this frame. Falls back to the config defaults before the client config
     * has loaded, and to "draw" when there is no camera yet.
     */
    private static boolean hideThisFrame() {
        if (!ClientDisplayConfig.isLoaded()) return false;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) return false;
        Vec3 pos = camera.getPosition();

        // The structure box is a real box, so this needs the camera's Y as well — unlike the band,
        // which is a slab of world X and asks about that alone.
        if (ClientDisplayConfig.PORTAL_ROOM_HIDE_DISTANT_HORIZONS.get()
                && ClientPortalRoomDepth.isInsideStructure(pos.x, pos.y, pos.z)) {
            return true;
        }

        return ClientDisplayConfig.UPSIDE_DOWN_HIDE_DISTANT_HORIZONS.get()
                && ClientUpsideDownBand.isFlipZoneWithin((int) Math.floor(pos.x),
                        ClientDisplayConfig.UPSIDE_DOWN_DISTANT_HORIZONS_MARGIN.get());
    }

    /** DH's cancellable before-render event: cancelling it skips DH's LOD pass for that frame. */
    private static final class RenderSuppressor extends DhApiBeforeRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            if (hideThisFrame()) {
                event.cancelEvent();
            }
        }
    }
}
