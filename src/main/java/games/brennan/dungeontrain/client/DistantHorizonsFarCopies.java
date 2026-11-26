package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import games.brennan.dungeontrain.client.farcopies.FarBox;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The Distant Horizons half of {@link ClientPortalRoomFarCopies}: hands the far copies of an endless
 * room to DH as box groups, and keeps DH's own terrain out of the frame while it does.
 *
 * <h2>Why a frustum, not a cancelled frame</h2>
 * <p>{@link DistantHorizonsSuppression} hides DH in a room by cancelling its whole render pass. DH
 * draws API box groups inside that same pass ("Custom Objects", after the opaque terrain), so a
 * cancelled frame takes the copies with it. What DH asks before drawing each terrain section, and
 * never asks about a box group, is the culling frustum — so while the copies are showing, an
 * overriding frustum that rejects every section leaves DH drawing our boxes and nothing of the
 * surface world. Everywhere else it hands each question to DH's own frustum unchanged.</p>
 *
 * <p>DH-type confinement as {@link DistantHorizonsSuppression}: reached only behind the
 * {@code ModList} check in {@link DungeonTrainClient}; any failure to bind leaves the feature off
 * and those rooms exactly as they were.</p>
 */
public final class DistantHorizonsFarCopies {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "dungeontrain:endless_room_copies";
    /** Light the copies are drawn at: lit as by the room's own lamps, not the open sky. */
    private static final int SKY_LIGHT = 0;
    private static final int BLOCK_LIGHT = 13;

    // Render-thread state.
    private static IDhApiCustomRenderRegister register = null;
    private static final List<IDhApiRenderableBoxGroup> groups = new ArrayList<>();
    private static long appliedVersion = -1;
    private static boolean warned = false;

    /**
     * How long DH gets, in a new room, to show that it draws box groups at all. Until it has, the
     * room keeps its fog: nothing is drawn past it yet.
     */
    private static final long PROBE_MILLIS = 3000;
    /** How recently DH must have drawn the copies for the fog to stay lifted. */
    private static final long DRAWN_FRESH_MILLIS = 1000;
    /** How recently DH must have asked the frustum for terrain to count as culled by it. */
    private static final long CULL_FRESH_MILLIS = 250;

    private static volatile long lastCullUpdateMillis = 0;
    private static volatile long lastDrawnMillis = 0;
    private static volatile long probeStartMillis = 0;
    private static long keepingSinceMillis = 0;
    private static Object probeRoom = null;
    /**
     * Set once DH has been given a room's copies and not drawn them within {@link #PROBE_MILLIS}.
     * DH 3.3.3 draws no box groups at all on macOS, and none anywhere with its generic rendering
     * switched off; from then on rooms are left exactly as they were before this class, until the
     * player next joins a world.
     */
    private static volatile boolean unsupported = false;

    private DistantHorizonsFarCopies() {}

    /** Bind to DH. Call once, on the client, only when DH is loaded. */
    public static void register() {
        try {
            DhApi.overrides.bind(IDhApiCullingFrustum.class, new TerrainCull(IDhApiCullingFrustum.class));
            DhApi.overrides.bind(IDhApiShadowCullingFrustum.class, new ShadowTerrainCull());
            DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new GroupSync());
            DhApiEventRegister.on(DhApiBeforeGenericObjectRenderEvent.class, new DrawProbe());
            ClientPortalRoomFarCopies.bindDistantHorizons(DistantHorizonsFarCopies::horizonBlocks,
                DistantHorizonsFarCopies::drawnRecently, DistantHorizonsFarCopies::reset);
            LOGGER.info("[DungeonTrain] Distant Horizons will carry endless dimensional rooms on to its horizon");
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons far-copies hooks; "
                + "endless rooms stay as they are without DH: {}", t.toString());
        }
    }

    /** DH's render distance in blocks, or 0 when it cannot be read. */
    private static int horizonBlocks() {
        try {
            if (DhApi.Delayed.configs == null) return 0;
            Integer chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
            return chunks == null ? 0 : chunks * 16;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Whether DH should be left drawing this frame inside a dimensional room — and so not cancelled
     * by {@link DistantHorizonsSuppression}. Only while there are copies to draw, DH is known (or,
     * for a moment after entering a room, still being tested) to draw them, and DH is consulting
     * the frustum that keeps its terrain out. Anything else falls back to the cancelled frame.
     */
    public static boolean keepsDhDrawing() {
        if (unsupported || !ClientPortalRoomFarCopies.drawingAtCamera()) {
            keepingSinceMillis = 0;
            return false;
        }
        long now = System.currentTimeMillis();
        if (keepingSinceMillis == 0) keepingSinceMillis = now;
        // DH skips the frustum entirely when its own frustum culling is switched off, and drawing
        // then would show the surface world in the room. Only a frame DH was let draw can ask it, so
        // this is judged once DH has had a moment of uncancelled frames.
        if (now - keepingSinceMillis > CULL_FRESH_MILLIS && now - lastCullUpdateMillis > CULL_FRESH_MILLIS) {
            return giveUp("its frustum culling is off");
        }
        if (now - lastDrawnMillis <= DRAWN_FRESH_MILLIS) return true;
        if (probeStartMillis == 0 || now - probeStartMillis <= PROBE_MILLIS) return true;
        return giveUp("it draws no custom objects on this system, or its generic rendering is off");
    }

    private static boolean giveUp(String why) {
        unsupported = true;
        keepingSinceMillis = 0;
        LOGGER.info("[DungeonTrain] Distant Horizons cannot carry endless room copies here ({}); those rooms stay as they are", why);
        return false;
    }

    /** Whether DH has drawn the copies recently enough that the room's fog may stand back. */
    public static boolean drawnRecently() {
        return !unsupported && System.currentTimeMillis() - lastDrawnMillis <= DRAWN_FRESH_MILLIS;
    }

    /** Forget a failed probe: a new world is a fresh chance (DH's settings may have changed). */
    public static void reset() {
        unsupported = false;
        keepingSinceMillis = 0;
        probeRoom = null;
        probeStartMillis = 0;
        lastDrawnMillis = 0;
    }

    private static void startProbe(ClientPortalRoomFarCopies.Snapshot snapshot) {
        Object room = java.util.List.of(snapshot.roomX(), snapshot.roomY(), snapshot.roomZ());
        if (room.equals(probeRoom)) return;
        probeRoom = room;
        probeStartMillis = System.currentTimeMillis();
    }

    /** Notes each frame DH actually draws one of our groups — the evidence everything else waits on. */
    private static final class DrawProbe extends DhApiBeforeGenericObjectRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
            EventParam p = event.value;
            if (p != null && RESOURCE.equals(p.resourceLocationNamespace + ":" + p.resourceLocationPath)) {
                lastDrawnMillis = System.currentTimeMillis();
            }
        }
    }

    // ---------- box groups ----------

    /** Bring DH's box groups in line with the current layout, on the render thread, before DH draws. */
    private static void sync(DhApiRenderParam param) {
        ClientPortalRoomFarCopies.Snapshot snapshot = ClientPortalRoomFarCopies.drawingAtCamera()
            ? ClientPortalRoomFarCopies.snapshot() : null;
        IDhApiLevelWrapper level = param == null ? null : param.clientLevelWrapper;
        IDhApiCustomRenderRegister target = level == null ? null : level.getRenderRegister();

        if (unsupported || snapshot == null || target == null) {
            clearGroups();
            return;
        }
        if (target == register && snapshot.version() == appliedVersion) return;

        clearGroups();
        if (DhApi.Delayed.customRenderObjectFactory == null) return;
        register = target;
        appliedVersion = snapshot.version();
        DhApiVec3d origin = new DhApiVec3d(snapshot.roomX(), snapshot.roomY(), snapshot.roomZ());
        addGroup(origin, snapshot.layout().near());
        addGroup(origin, snapshot.layout().outer());
        startProbe(snapshot);
    }

    private static void addGroup(DhApiVec3d origin, List<FarBox> boxes) {
        if (boxes.isEmpty()) return;
        List<DhApiRenderableBox> dhBoxes = new ArrayList<>(boxes.size());
        for (FarBox b : boxes) {
            dhBoxes.add(new DhApiRenderableBox(
                new DhApiVec3d(b.minX(), b.minY(), b.minZ()),
                new DhApiVec3d(b.maxX(), b.maxY(), b.maxZ()),
                new Color(b.rgb()), EDhApiBlockMaterial.STONE));
        }
        IDhApiRenderableBoxGroup group = DhApi.Delayed.customRenderObjectFactory
            .createRelativePositionedGroup(RESOURCE, origin, dhBoxes);
        group.setSkyLight(SKY_LIGHT);
        group.setBlockLight(BLOCK_LIGHT);
        group.setSsaoEnabled(false);
        register.add(group);
        groups.add(group);
    }

    private static void clearGroups() {
        if (register != null) {
            for (IDhApiRenderableBoxGroup group : groups) {
                try {
                    register.remove(group.getId());
                } catch (RuntimeException e) {
                    // The level it was added to may already be gone; nothing left to remove from.
                }
            }
        }
        groups.clear();
        register = null;
        appliedVersion = -1;
    }

    private static final class GroupSync extends DhApiBeforeRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            try {
                sync(event.value);
            } catch (RuntimeException e) {
                if (!warned) {
                    warned = true;
                    LOGGER.warn("[DungeonTrain] Endless room far copies could not be handed to Distant Horizons: {}",
                        e.toString());
                }
                clearGroups();
            }
        }
    }

    // ---------- terrain culling ----------

    /**
     * Rejects every terrain section while far copies are showing; otherwise asks DH's own frustum
     * (bound at core priority) exactly what DH would have asked it.
     */
    private static class TerrainCull implements IDhApiCullingFrustum {
        private final Class<? extends IDhApiCullingFrustum> slot;
        private boolean hideTerrain;

        TerrainCull(Class<? extends IDhApiCullingFrustum> slot) {
            this.slot = slot;
        }

        private IDhApiCullingFrustum core() {
            IDhApiCullingFrustum core = (IDhApiCullingFrustum) DhApi.overrides.get(slot, IOverrideInjector.CORE_PRIORITY);
            return core == this ? null : core;
        }

        @Override
        public void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f worldViewProjection) {
            lastCullUpdateMillis = System.currentTimeMillis();
            hideTerrain = ClientPortalRoomFarCopies.drawingAtCamera();
            IDhApiCullingFrustum core = core();
            if (core != null) core.update(worldMinBlockY, worldMaxBlockY, worldViewProjection);
        }

        @Override
        public boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel) {
            if (hideTerrain) return false;
            IDhApiCullingFrustum core = core();
            return core == null || core.intersects(lodBlockPosMinX, lodBlockPosMinZ, lodBlockWidth, lodDetailLevel);
        }
    }

    /** The same for the shader-pack shadow pass, which DH culls through its own slot. */
    private static final class ShadowTerrainCull extends TerrainCull implements IDhApiShadowCullingFrustum {
        ShadowTerrainCull() {
            super(IDhApiShadowCullingFrustum.class);
        }
    }
}
