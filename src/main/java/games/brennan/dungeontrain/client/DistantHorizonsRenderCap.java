package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiMutableFogRenderParam;
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

import java.util.OptionalLong;

/**
 * Keeps <b>Distant Horizons</b> from showing what lies ahead: past the far side of a void, or more than
 * one legacy era away. {@link DhHorizon} decides how far is allowed; this class makes DH respect it.
 *
 * <p><b>Fog wall (the normal path).</b> DH re-reads its far fog every frame, and API 7's
 * {@link DhApiBeforeFogRenderEvent} lets a mod reshape it. The fog is pulled in so it turns fully opaque
 * exactly at the allowed distance ({@link DhFogWall}); DH keeps the player's own render distance, so its
 * LOD tree is never rebuilt and the boundary slides smoothly as the train moves.</p>
 *
 * <p><b>Render-distance tiers (the fallback).</b> When DH's fog is not being drawn — the player turned it
 * off, or a shader pack draws its own — a fog wall hides nothing, so DH's render distance is lowered
 * through its API override instead ({@link DhCapPolicy}: coarse tiers, lowered at once, raised with
 * headroom, never above the player's setting, never saved), and DH skips its frame entirely below its
 * 32-chunk minimum ({@link #belowFloor()}).</p>
 *
 * <p><b>Loading.</b> Like {@link DistantHorizonsSuppression}, this names DH types and is reached only
 * behind the {@code ModList} check in {@link DungeonTrainClient}. Any failure disables the cap and
 * leaves DH on the player's settings.</p>
 */
public final class DistantHorizonsRenderCap {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Shown in DH's config screen beside the render distance while DT overrides it. */
    private static final String API_USER = "Dungeon Train";

    /** Allowed distance in blocks at the camera, or {@code -1} when nothing narrows the view. */
    private static volatile long capBlocks = -1L;
    /** DH has fired its fog event at least once — proof the fog wall is bound and DH draws fog. */
    private static volatile boolean fogEventSeen = false;

    /** The render-distance override applied, or {@link DhCapPolicy#RELEASED} for the player's setting. */
    private static int applied = DhCapPolicy.RELEASED;
    private static boolean failed = false;
    /** Fallback path only: the cap is below DH's minimum render distance, so DH must not draw at all. */
    private static volatile boolean belowFloor = false;

    private DistantHorizonsRenderCap() {}

    /**
     * Whether DH must skip its frame: on the render-distance fallback, when the allowed radius is shorter
     * than DH's minimum render distance (32 chunks). Read by {@link DistantHorizonsSuppression}.
     */
    static boolean belowFloor() {
        return belowFloor;
    }

    /** Bind the fog wall, the per-tick update and the logout reset. Call once, only when DH is loaded. */
    public static void register() {
        try {
            DhApiEventRegister.on(DhApiBeforeFogRenderEvent.class, new FogWallEvent());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons fog wall; "
                    + "falling back to lowering DH's render distance: {}", t.toString());
        }
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> release());
        LOGGER.info("[DungeonTrain] Distant Horizons will not draw past voids or beyond the next legacy era");
    }

    private static void tick() {
        if (failed) return;
        try {
            OptionalLong cap = capBlocksHere();
            capBlocks = cap.orElse(-1L);
            IDhApiConfigValue<Integer> distance = renderDistance();
            if (distance == null) return;

            if (cap.isEmpty() || fogWallWorking()) {
                belowFloor = false;
                release(distance);
                return;
            }
            Integer minValue = distance.getMinValue();
            int min = minValue == null ? 1 : minValue;
            long capChunks = cap.getAsLong() / 16L;
            belowFloor = capChunks < min;
            int target = DhCapPolicy.next(applied, capChunks, distance.getTrueValue(), min);
            if (target == DhCapPolicy.RELEASED) {
                release(distance);
            } else if (target != applied && distance.setValue(target, API_USER)) {
                applied = target;
            }
        } catch (Throwable t) {
            failed = true;
            capBlocks = -1L;
            belowFloor = false;
            LOGGER.warn("[DungeonTrain] Distant Horizons render cap disabled after an error; "
                    + "DH stays on your own settings: {}", t.toString());
            release();
        }
    }

    /**
     * Whether the fog wall can hide what the cap hides: DH's fog has fired at all, is switched on in
     * DH's settings, and no shader pack is drawing its own fog in its place. Decided from settings rather
     * than from recent fog events, because DH fires none while its frame is suppressed (upside-down band,
     * portal rooms) — a freshness test would flip to the render-distance fallback exactly then and cost a
     * reload on the way back out.
     */
    private static boolean fogWallWorking() {
        if (!fogEventSeen || GraphicsCapabilities.shaderPackActive()) return false;
        IDhApiConfig configs = DhApi.Delayed.configs;
        return configs != null && Boolean.TRUE.equals(configs.graphics().fog().enableDhFog().getValue());
    }

    /** The allowed radius at the camera, or empty when this world or position has nothing to hide. */
    private static OptionalLong capBlocksHere() {
        if (!ClientDisplayConfig.isLoaded() || !ClientVoidBand.startsWithTrain()) return OptionalLong.empty();
        boolean voids = ClientDisplayConfig.DISTANT_HORIZONS_LIMIT_PAST_VOIDS.get();
        boolean legacy = ClientDisplayConfig.DISTANT_HORIZONS_LIMIT_LEGACY_ERAS.get();
        if (!voids && !legacy) return OptionalLong.empty();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return OptionalLong.empty();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return OptionalLong.empty();
        return DhHorizon.capBlocks(WorldGenCycle.fromConfig(), camera.getPosition().x, voids, legacy);
    }

    private static IDhApiConfigValue<Integer> renderDistance() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        return configs == null ? null : configs.graphics().chunkRenderDistance();
    }

    /** Drop the render-distance override so DH is back on the player's own setting. */
    private static void release() {
        try {
            IDhApiConfigValue<Integer> distance = renderDistance();
            if (distance != null) release(distance);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not clear the Distant Horizons render cap: {}", t.toString());
        }
    }

    private static void release(IDhApiConfigValue<Integer> distance) {
        if (applied == DhCapPolicy.RELEASED) return;
        distance.clearValue();
        applied = DhCapPolicy.RELEASED;
    }

    /** Reshape DH's far fog into a wall at the allowed distance, every frame. */
    private static final class FogWallEvent extends DhApiBeforeFogRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam> event) {
            fogEventSeen = true;
            long cap = capBlocks;
            if (cap < 0L || failed) return;
            try {
                IDhApiConfigValue<Integer> distance = renderDistance();
                if (distance == null) return;
                DhApiFogRenderParam user = event.value.getOriginalFogRenderParam();
                DhFogWall.Fog fog = DhFogWall.wall(cap, distance.getValue() * 16.0,
                        user.getFarFogStartPercent(), user.getFarFogEndPercent(), user.getFarFogMaxThickness());
                if (fog == null) return;
                DhApiMutableFogRenderParam out = event.value.getFogRenderParam();
                out.setFarFogStartPercent(fog.startPercent());
                out.setFarFogEndPercent(fog.endPercent());
                out.setFarFogMaxThickness(1.0f);
                out.setFarFogFalloff(EDhApiFogFalloff.LINEAR);
            } catch (Throwable t) {
                failed = true;
                LOGGER.warn("[DungeonTrain] Distant Horizons fog wall disabled after an error: {}", t.toString());
            }
        }
    }
}
