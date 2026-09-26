package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.VoidWallLayout;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The void wall in <b>Distant Horizons</b>: DH's own culling stops at the wall
 * ({@link DistantHorizonsTrackCulling}), and two box groups DH draws itself carry the rest.
 *
 * <ul>
 *   <li><b>The track past the wall</b> — a bed and two rails in DH's box style, from where the vanilla
 *       world ends to the edge of DH's view, while a wall stands. The LODs the real track is part of are
 *       culled with everything else past the wall; without these the rails would stop dead at it.</li>
 *   <li><b>The veil</b> — while a wall fades, a translucent sheet at its X in the fog colour, with a hole
 *       for the track. It is DH's box so DH depth-tests it against its own LODs, which the vanilla veil
 *       ({@link VoidWallVeilRenderer}) cannot see.</li>
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
    /** How far the ghost track or the veil must drift before its boxes are rebuilt. */
    private static final double REBUILD_STEP = 16.0;
    /** Veil opacity steps as it thins towards the top of the world — DH boxes are one colour each. */
    private static final float[] SKY_STEPS = {0.75f, 0.5f, 0.25f};

    /** The client overworld DH is drawing, and our groups in it. Client thread only. */
    private static IDhApiLevelWrapper level;
    private static IDhApiRenderableBoxGroup track;
    private static IDhApiRenderableBoxGroup veil;
    private static double trackFromBuilt = Double.NaN;
    private static double veilXBuilt = Double.NaN;
    private static float veilAlphaBuilt = -1f;
    private static double veilCamZBuilt = Double.NaN;

    private DistantHorizonsVoidWall() {}

    /** Bind the culling override, the level hooks and the per-tick update. Call once, only when DH is loaded. */
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
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> update());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not add the Distant Horizons void-wall track and veil; "
                    + "the track will stop at the wall in DH and a fading wall will pop: {}", t.toString());
            return;
        }
        LOGGER.info("[DungeonTrain] Distant Horizons will stop at the void wall, with the track drawn past it");
    }

    private static void attach(IDhApiLevelWrapper wrapper) {
        if (wrapper == null || wrapper.getLevelType() != EDhApiLevelType.CLIENT_LEVEL) return;
        if (!wrapper.getDimensionName().endsWith("overworld")) return;
        try {
            detach();
            track = newGroup("void_wall_track");
            veil = newGroup("void_wall_veil");
            wrapper.getRenderRegister().add(track);
            wrapper.getRenderRegister().add(veil);
            level = wrapper;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not register the Distant Horizons void-wall boxes: {}", t.toString());
            track = null;
            veil = null;
        }
    }

    private static IDhApiRenderableBoxGroup newGroup(String path) {
        List<DhApiRenderableBox> seed = new ArrayList<>();
        seed.add(new DhApiRenderableBox(new DhApiVec3d(0, 0, 0), new DhApiVec3d(1, 1, 1),
                new Color(0, 0, 0, 0), EDhApiBlockMaterial.AIR));
        IDhApiRenderableBoxGroup g = DhApi.Delayed.customRenderObjectFactory
                .createAbsolutePositionedGroup("dungeontrain:" + path, seed);
        g.setShading(DhApiRenderableBoxGroupShading.getUnshaded());
        g.setSsaoEnabled(false);
        g.setSkyLight(15);
        g.setActive(false);
        return g;
    }

    private static void detach() {
        try {
            if (level != null) {
                if (track != null) level.getRenderRegister().remove(track.getId());
                if (veil != null) level.getRenderRegister().remove(veil.getId());
            }
        } catch (Throwable ignored) {
            // The level is going away; DH drops its groups with it.
        }
        level = null;
        track = null;
        veil = null;
        trackFromBuilt = Double.NaN;
        veilXBuilt = Double.NaN;
        veilAlphaBuilt = -1f;
    }

    /** Per tick: rebuild the ghost track and the veil when the wall or the camera has moved enough. */
    private static void update() {
        if (track == null || veil == null) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        VoidWallLayout.Result wall = ClientVoidWall.result();
        boolean on = ClientDisplayConfig.isDistantHorizonsAdjustmentsEnabled() && camera != null && mc.level != null;
        try {
            if (!on) {
                track.setActive(false);
                veil.setActive(false);
                return;
            }
            Vec3 cam = camera.getPosition();
            double vanillaReach = mc.options.getEffectiveRenderDistance() * 16.0;
            double dhReach = dhReach(vanillaReach);
            updateTrack(wall, cam, vanillaReach, dhReach);
            updateVeil(wall, cam, dhReach, mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Distant Horizons void-wall update failed; hiding its boxes: {}", t.toString());
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

    /** The bed and two rails from the wall (or the end of the vanilla world, whichever is further) outwards. */
    private static void updateTrack(VoidWallLayout.Result wall, Vec3 cam, double vanillaReach, double dhReach) {
        double from = Math.max(wall.cullX(), Math.floor(cam.x + vanillaReach));
        double to = cam.x + dhReach;
        if (!wall.hasCull() || from >= to) {
            track.setActive(false);
            trackFromBuilt = Double.NaN;
            return;
        }
        if (Double.isNaN(trackFromBuilt) || Math.abs(from - trackFromBuilt) >= REBUILD_STEP) {
            int trainY = ClientUpsideDownBand.trainY();
            int w = CarriageDims.DEFAULT_WIDTH;
            double end = from + dhReach + REBUILD_STEP;
            List<DhApiRenderableBox> boxes = new ArrayList<>(3);
            boxes.add(box(from, trainY - 2, 0, end, trainY - 1, w, BED_COLOR, EDhApiBlockMaterial.STONE));
            boxes.add(box(from, trainY - 1, 1, end, trainY - 0.8, 2, RAIL_COLOR, EDhApiBlockMaterial.METAL));
            boxes.add(box(from, trainY - 1, w - 2, end, trainY - 0.8, w - 1, RAIL_COLOR, EDhApiBlockMaterial.METAL));
            replace(track, boxes);
            trackFromBuilt = from;
        }
        track.setActive(true);
    }

    /**
     * The fading sheet at the veil's X, split around the track, across DH's whole view. DH composites
     * its LODs over the vanilla frame after the vanilla veil ({@link VoidWallVeilRenderer}) is drawn, so
     * that one covers vanilla chunks and this one DH's LODs; both thin out low above the track so where
     * they overlap — open sky — the stacking stays faint.
     */
    private static void updateVeil(VoidWallLayout.Result wall, Vec3 cam, double dhReach, int minY, int maxY) {
        if (!wall.hasVeil()) {
            veil.setActive(false);
            veilXBuilt = Double.NaN;
            return;
        }
        float alpha = (float) wall.veilStrength();
        boolean rebuild = wall.veilX() != veilXBuilt
                || Math.abs(alpha - veilAlphaBuilt) >= 0.01f
                || Double.isNaN(veilCamZBuilt) || Math.abs(cam.z - veilCamZBuilt) >= REBUILD_STEP;
        if (rebuild) {
            float[] fog = RenderSystem.getShaderFogColor();
            double x0 = wall.veilX();
            double x1 = x0 + 1.0;
            double z0 = cam.z - dhReach;
            double z1 = cam.z + dhReach;
            int trainY = ClientUpsideDownBand.trainY();
            double holeLo = trainY - 2;
            double holeHi = trainY + 1;
            double fullTop = Math.min(maxY, holeHi + VoidWallVeilRenderer.FULL_HEIGHT_ABOVE_TRACK);
            double clearTop = Math.min(maxY, holeHi + VoidWallVeilRenderer.CLEAR_HEIGHT_ABOVE_TRACK);
            Color full = fogColor(fog, alpha);

            List<DhApiRenderableBox> boxes = new ArrayList<>();
            sheet(boxes, x0, x1, z0, z1, minY, holeLo, full);
            sheet(boxes, x0, x1, z0, 0, holeLo, holeHi, full);
            sheet(boxes, x0, x1, CarriageDims.DEFAULT_WIDTH, z1, holeLo, holeHi, full);
            sheet(boxes, x0, x1, z0, z1, holeHi, fullTop, full);
            double band = (clearTop - fullTop) / SKY_STEPS.length;
            for (int i = 0; i < SKY_STEPS.length; i++) {
                sheet(boxes, x0, x1, z0, z1, fullTop + i * band, fullTop + (i + 1) * band,
                        fogColor(fog, alpha * SKY_STEPS[i]));
            }
            replace(veil, boxes);
            veilXBuilt = wall.veilX();
            veilAlphaBuilt = alpha;
            veilCamZBuilt = cam.z;
        }
        veil.setActive(true);
    }

    private static void sheet(List<DhApiRenderableBox> out, double x0, double x1, double z0, double z1,
                              double y0, double y1, Color color) {
        if (y1 <= y0 || z1 <= z0) return;
        out.add(box(x0, y0, z0, x1, y1, z1, color, EDhApiBlockMaterial.UNKNOWN));
    }

    private static DhApiRenderableBox box(double x0, double y0, double z0, double x1, double y1, double z1,
                                          Color color, EDhApiBlockMaterial material) {
        return new DhApiRenderableBox(new DhApiVec3d(x0, y0, z0), new DhApiVec3d(x1, y1, z1), color, material);
    }

    private static Color fogColor(float[] fog, float alpha) {
        return new Color(clamp(fog[0]), clamp(fog[1]), clamp(fog[2]), clamp(alpha));
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static void replace(IDhApiRenderableBoxGroup group, List<DhApiRenderableBox> boxes) {
        group.clear();
        group.addAll(boxes);
        group.triggerBoxChange();
    }
}
