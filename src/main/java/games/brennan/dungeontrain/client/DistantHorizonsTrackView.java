package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.worldgen.DhHorizon;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Keeps <b>Distant Horizons</b> from showing what lies ahead along the track: past the far side of a
 * void, or more than one legacy era away. {@link DhHorizon} decides which stretch of world X may be
 * seen; {@link DistantHorizonsTrackCulling} makes DH skip every LOD section outside it.
 *
 * <p>Only the track axis (X) is limited — the view out to either side of the train is never cut — and
 * DH's render distance and settings are never touched, so its LODs are never rebuilt and nothing can
 * persist: the window moves every tick and the edge slides along with the train.</p>
 *
 * <p><b>Loading.</b> Like {@link DistantHorizonsSuppression}, this names DH types and is reached only
 * behind the {@code ModList} check in {@link DungeonTrainClient}. If DH refuses the override, DH simply
 * draws as it always did.</p>
 */
public final class DistantHorizonsTrackView {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DistantHorizonsTrackCulling CULLING = new DistantHorizonsTrackCulling();

    private DistantHorizonsTrackView() {}

    /** Bind the culling override and the per-tick window update. Call once, only when DH is loaded. */
    public static void register() {
        try {
            DhApi.overrides.bind(IDhApiCullingFrustum.class, CULLING);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons track culling; "
                    + "DH may draw past voids and beyond the next legacy era: {}", t.toString());
            return;
        }
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> CULLING.setWindow(windowHere()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> CULLING.setWindow(DhHorizon.XWindow.OPEN));
        LOGGER.info("[DungeonTrain] Distant Horizons will not draw past voids or beyond the next legacy era");
    }

    /** The stretch of X the camera may see, or {@link DhHorizon.XWindow#OPEN} when nothing is hidden here. */
    private static DhHorizon.XWindow windowHere() {
        if (!ClientDisplayConfig.isLoaded() || !ClientVoidBand.startsWithTrain()) return DhHorizon.XWindow.OPEN;
        boolean voids = ClientDisplayConfig.DISTANT_HORIZONS_LIMIT_PAST_VOIDS.get();
        boolean legacy = ClientDisplayConfig.DISTANT_HORIZONS_LIMIT_LEGACY_ERAS.get();
        if (!voids && !legacy) return DhHorizon.XWindow.OPEN;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return DhHorizon.XWindow.OPEN;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return DhHorizon.XWindow.OPEN;
        return DhHorizon.window(WorldGenCycle.fromConfig(), camera.getPosition().x, voids, legacy);
    }
}
