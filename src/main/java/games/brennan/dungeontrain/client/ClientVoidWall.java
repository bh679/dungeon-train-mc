package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.shader.VoidWallFadePass;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.VoidWallLayout;
import games.brennan.dungeontrain.worldgen.VoidWallPlane;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/**
 * The see-through wall in force this frame — the client half of {@link VoidWallLayout}, and the one
 * thing the culling hooks, the veil and the Distant Horizons half read.
 *
 * <p>Same shape as {@link ClientPortalSeal}, whose hooks it rides: <b>a place, not a state</b> —
 * recomputed from the camera every frame, nothing synced (the band layout is COMMON config, the track
 * height came with the join packet) — and <b>once a frame, not once a call</b>, so
 * {@code Frustum#isVisible} pays one volatile read and a comparison.</p>
 *
 * <p>Only a wall at full strength culls. While the camera is in the first stretch of a void its wall
 * is fading: nothing past it is culled, and {@code VoidWallFadePass} paints the sky back over whatever
 * lies past it, thinning as the wall fades, so the far side comes into view out of the real sky rather
 * than all at once. Should that pass's shader fail to load, the fading wall keeps culling until
 * it is half gone and then drops.</p>
 */
public final class ClientVoidWall {

    /** Wide enough for any configured carriage — the train's own corridor carries on past the wall. */
    private static final int CORRIDOR_WIDTH = CarriageDims.MAX_WIDTH;
    /** Strength a fading wall drops at when the sky fade cannot run. */
    private static final double HALF = 0.5;

    /** Volatile: written on the render thread, read from chunk-build threads through the frustum. */
    private static volatile VoidWallPlane plane = VoidWallPlane.NONE;
    private static volatile VoidWallLayout.Result result = VoidWallLayout.Result.NONE;

    private ClientVoidWall() {}

    /**
     * Re-evaluate the wall for this frame's camera. When the culled plane moves — a wall reaching full
     * strength, or starting to fade — the renderer's visible-section set is invalidated, since sections
     * it already accepted or rejected would otherwise keep their old answer until the camera next
     * crossed a section boundary.
     */
    public static void beginFrame(double cameraX) {
        VoidWallLayout.Result next = compute(cameraX);
        VoidWallPlane nextPlane = VoidWallPlane.at(cullX(next), cameraX, ClientUpsideDownBand.trainY(), CORRIDOR_WIDTH);
        VoidWallPlane before = plane;
        result = next;
        plane = nextPlane;
        // Only a wall appearing, going or moving re-derives the visible set — not the camera X the plane
        // also carries, which changes every frame.
        if (before.active() != nextPlane.active() || before.wallX() != nextPlane.wallX()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null) mc.levelRenderer.needsUpdate();
        }
    }

    /** The X culled this frame: the full-strength wall, or a fading one still over half strength with no fade pass. */
    private static double cullX(VoidWallLayout.Result r) {
        if (r.hasVeil() && r.veilStrength() >= HALF && !VoidWallFadePass.available()) return r.veilX();
        return r.cullX();
    }

    private static VoidWallLayout.Result compute(double cameraX) {
        if (!ClientDisplayConfig.isVoidWallEnabled() || !ClientVoidBand.startsWithTrain() || !inTheOverworld()) {
            return VoidWallLayout.Result.NONE;
        }
        return VoidWallLayout.wallAt(WorldGenCycle.fromConfig(), cameraX,
                ClientDisplayConfig.getVoidWallFadeFraction(),
                DungeonTrainCommonConfig.getDisintegrationSkyFadeOffsetBlocks(),
                true, ClientDisplayConfig.isVoidWallLegacyEras());
    }

    /**
     * The band layout — and so the wall — describes the overworld only; the Train Builder's dimension
     * and the real Nether and End have no voids along a track. Same reason as {@code ClientPortalSeal}.
     */
    private static boolean inTheOverworld() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(Level.OVERWORLD);
    }

    /** Forget the wall. Wired to logging out, so a wall never leaks into the next world. */
    public static void reset() {
        plane = VoidWallPlane.NONE;
        result = VoidWallLayout.Result.NONE;
    }

    /** Whether anything is culled at all — the early-out every hook opens with. */
    public static boolean active() {
        return plane.active();
    }

    /** Whether terrain spanning {@code [minX, maxX]} reaches past this frame's full-strength wall. */
    public static boolean hidesTerrain(double minX, double maxX) {
        return plane.hidesTerrain(minX, maxX);
    }

    /** Whether a body (sub-level, entity) is past the wall and off the track corridor. */
    public static boolean hidesBody(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return plane.hidesBody(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** The culling plane in force this frame. */
    public static VoidWallPlane plane() {
        return plane;
    }

    /** This frame's walls — the culled one and the fading veil. */
    public static VoidWallLayout.Result result() {
        return result;
    }
}
