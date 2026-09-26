package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import games.brennan.dungeontrain.client.shader.VoidWallFadePass;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.VoidWallLayout;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The void wall in <b>Distant Horizons</b>:
 *
 * <ul>
 *   <li><b>The cull</b> — DH stops drawing at the wall ({@link DistantHorizonsTrackCulling}).</li>
 *   <li><b>The track past the wall</b> — a bed and two rails as DH boxes, from where the vanilla world
 *       ends to the edge of DH's view, while a wall stands. The LODs the real track is part of are
 *       culled with everything else past the wall; without these the rails would stop dead at it. Short
 *       of the vanilla edge the real blocks are drawn instead ({@link VoidWallTrackRenderer}).</li>
 *   <li><b>The sky pass</b> — DH's depth texture and projection are handed to {@link VoidWallFadePass}
 *       each frame, so the wall's sky — the fade, and the per-pixel cut past a standing wall — reaches
 *       DH's LODs as well as vanilla's terrain.</li>
 * </ul>
 *
 * <p>Like {@link DistantHorizonsSuppression}, this names DH types and is reached only behind the
 * {@code ModList} check in {@link DungeonTrainClient}. If DH refuses any part, DH simply draws as it
 * always did.</p>
 */
public final class DistantHorizonsVoidWall {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DistantHorizonsTrackCulling CULLING = new DistantHorizonsTrackCulling();

    private static final Color BED_COLOR = new Color(0x55, 0x55, 0x5A);
    private static final Color RAIL_COLOR = new Color(0x8C, 0x8C, 0x94);
    /** Widest LOD section DH culls whole — how far before the wall the real track's LODs can vanish. */
    private static final double MAX_LOD_WIDTH = 1024.0;
    /** Ghost track sits this far over the real track's LODs, so the two never z-fight. */
    private static final double LIFT = 0.05;
    /** How far the ghost track must drift before its boxes are rebuilt. */
    private static final double REBUILD_STEP = 16.0;

    /** The client overworld DH is drawing, and the track group in it. Client thread only. */
    private static IDhApiLevelWrapper level;
    private static IDhApiRenderableBoxGroup track;
    private static double trackFromBuilt = Double.NaN;

    private DistantHorizonsVoidWall() {}

    /** Bind the culling override, the level hooks and the per-frame updates. Call once, only when DH is loaded. */
    public static void register() {
        try {
            DhApi.overrides.bind(IDhApiCullingFrustum.class, CULLING);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not bind the Distant Horizons void-wall culling; "
                    + "DH may draw past voids and beyond the next legacy era: {}", t.toString());
            return;
        }
        try {
            DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
                @Override
                public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> e) {
                    attach(e.value.levelWrapper);
                }
            });
            DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
                @Override
                public void onLevelUnload(DhApiEventParam<DhApiLevelUnloadEvent.EventParam> e) {
                    if (e.value.levelWrapper == level) detach();
                }
            });
            DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> e) {
                    publishFrame(e.value);
                }
            });
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> update());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not add the Distant Horizons void-wall track and fade; "
                    + "the track will stop at the wall in DH and a fading wall will not reach DH: {}", t.toString());
            return;
        }
        LOGGER.info("[DungeonTrain] Distant Horizons will stop at the void wall, with the track drawn past it");
    }

    /** Hand DH's depth texture, projection and reach to the sky pass whenever there is a wall ahead. */
    private static void publishFrame(DhApiRenderParam param) {
        if (param == null || ClientVoidWall.result().isNone()
                || !ClientDisplayConfig.isDistantHorizonsAdjustmentsEnabled()) {
            return;
        }
        try {
            IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
            DhApiResult<Integer> depth = proxy.getDhDepthTextureGlId();
            if (depth == null || !depth.success || depth.payload == null) return;
            VoidWallFadePass.setDistantHorizonsFrame(depth.payload, toJoml(param.dhProjectionMatrix).invert(),
                    proxy.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z,
                    proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE,
                    dhReach(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) + MAX_LOD_WIDTH);
        } catch (Throwable t) {
            // The sky pass then covers vanilla terrain only; DH's LODs past the wall rely on the cull.
        }
    }

    /**
     * DH's row-major {@code mRowCol} matrix as JOML's column-major one. A perspective projection has its
     * {@code -1} at row 3, column 2; if the fields turn out to be column-major instead, it lands at the
     * transpose, and the result is flipped back.
     */
    private static Matrix4f toJoml(DhApiMat4f m) {
        Matrix4f out = new Matrix4f(
                m.m00, m.m10, m.m20, m.m30,
                m.m01, m.m11, m.m21, m.m31,
                m.m02, m.m12, m.m22, m.m32,
                m.m03, m.m13, m.m23, m.m33);
        if (out.m23() != -1.0f && out.m32() == -1.0f) out.transpose();
        return out;
    }

    private static void attach(IDhApiLevelWrapper wrapper) {
        if (wrapper == null || wrapper.getLevelType() != EDhApiLevelType.CLIENT_LEVEL) return;
        if (!wrapper.getDimensionName().endsWith("overworld")) return;
        try {
            detach();
            track = newGroup("void_wall_track");
            wrapper.getRenderRegister().add(track);
            level = wrapper;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not register the Distant Horizons void-wall track: {}", t.toString());
            track = null;
        }
    }

    private static IDhApiRenderableBoxGroup newGroup(String path) {
        List<DhApiRenderableBox> seed = new ArrayList<>();
        seed.add(new DhApiRenderableBox(new DhApiVec3d(0, 0, 0), new DhApiVec3d(1, 1, 1),
                new Color(0, 0, 0, 0), EDhApiBlockMaterial.AIR));
        IDhApiRenderableBoxGroup g = DhApi.Delayed.customRenderObjectFactory
                .createAbsolutePositionedGroup("dungeontrain:" + path, seed);
        g.setShading(DhApiRenderableBoxGroupShading.getDefaultShaded());
        g.setSkyLight(15);
        g.setActive(false);
        return g;
    }

    private static void detach() {
        try {
            if (level != null && track != null) level.getRenderRegister().remove(track.getId());
        } catch (Throwable ignored) {
            // The level is going away; DH drops its groups with it.
        }
        level = null;
        track = null;
        trackFromBuilt = Double.NaN;
    }

    /** Per tick: rebuild the ghost track when the wall or the camera has moved enough. */
    private static void update() {
        if (track == null) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        try {
            if (!ClientDisplayConfig.isDistantHorizonsAdjustmentsEnabled() || camera == null || mc.level == null) {
                track.setActive(false);
                return;
            }
            double vanillaReach = mc.options.getEffectiveRenderDistance() * 16.0;
            updateTrack(ClientVoidWall.result(), camera.getPosition(), vanillaReach, dhReach(vanillaReach));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Distant Horizons void-wall update failed; hiding its track: {}", t.toString());
            detach();
        }
    }

    private static double dhReach(double vanillaReach) {
        try {
            Integer chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
            return chunks == null ? vanillaReach * 4 : chunks * 16.0;
        } catch (Throwable t) {
            return vanillaReach * 4;
        }
    }

    /**
     * The bed and two rails from the end of the vanilla world outwards, while a wall stands within DH's
     * view. It starts at the vanilla edge rather than at the wall because the culled LOD section that
     * straddles the wall can begin hundreds of blocks before it, taking the real track's LODs with it;
     * short of the wall the boxes simply lie over the real track, a hair above it.
     */
    private static void updateTrack(VoidWallLayout.Result wall, Vec3 cam, double vanillaReach, double dhReach) {
        double from = Math.floor(cam.x + vanillaReach);
        double to = cam.x + dhReach;
        if (!wall.hasCull() || from >= to || wall.cullX() > to + MAX_LOD_WIDTH) {
            track.setActive(false);
            trackFromBuilt = Double.NaN;
            return;
        }
        if (Double.isNaN(trackFromBuilt) || Math.abs(from - trackFromBuilt) >= REBUILD_STEP) {
            int trainY = ClientUpsideDownBand.trainY();
            int w = CarriageDims.DEFAULT_WIDTH;
            double end = from + dhReach + REBUILD_STEP;
            List<DhApiRenderableBox> boxes = new ArrayList<>(3);
            boxes.add(box(from, trainY - 2, 0, end, trainY - 1 + LIFT, w, BED_COLOR, EDhApiBlockMaterial.STONE));
            boxes.add(box(from, trainY - 1, 1, end, trainY - 0.8 + LIFT, 2, RAIL_COLOR, EDhApiBlockMaterial.METAL));
            boxes.add(box(from, trainY - 1, w - 2, end, trainY - 0.8 + LIFT, w - 1, RAIL_COLOR, EDhApiBlockMaterial.METAL));
            track.clear();
            track.addAll(boxes);
            track.triggerBoxChange();
            trackFromBuilt = from;
        }
        track.setActive(true);
    }

    private static DhApiRenderableBox box(double x0, double y0, double z0, double x1, double y1, double z1,
                                          Color color, EDhApiBlockMaterial material) {
        return new DhApiRenderableBox(new DhApiVec3d(x0, y0, z0), new DhApiVec3d(x1, y1, z1), color, material);
    }
}
