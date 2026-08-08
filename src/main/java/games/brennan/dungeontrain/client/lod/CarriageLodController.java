package games.brennan.dungeontrain.client.lod;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

/**
 * Decides which carriage sub-levels render as full Sable chunk sections (NEAR) and which render as
 * a single baked mesh (FAR), reconciling once per client tick.
 *
 * <p>Client-side mirror of {@link games.brennan.dungeontrain.ship.sable.PhysicsFreezeController}:
 * same "pure decision core + per-tick reconcile over a live Sable structure" shape, same
 * hysteresis policy, same matched-toggle debug switch. Where the freeze controller flattens
 * <em>physics</em> cost to watched carriages, this flattens <em>render</em> cost — Sable runs a
 * separate cull pass, section-compile queue and {@code renderSectionLayer} call per sub-level per
 * RenderType, so client render cost is O(sub-levels × render types) and a long train pays it in
 * full even though the far carriages are a handful of pixels.</p>
 *
 * <p><b>Distance, not tracking.</b> The freeze controller can use {@code getTrackingPlayers()}
 * because a carriage nobody tracks is rendered by nobody. That signal is useless here: every
 * carriage we want to demote <em>is</em> tracked and <em>is</em> being rendered — that is the
 * whole problem. Sable networks sub-levels out to {@code sub_level_tracking_range} (default 320
 * blocks), comfortably past the default carriage window, so the threshold has to be our own.</p>
 *
 * <p><b>Hysteresis: eager promote, lazy demote.</b> A carriage that comes inside the threshold is
 * promoted the same tick (full detail ready before the player can inspect it); it must be
 * continuously outside for {@link #DEMOTE_GRACE_TICKS} before demoting, so a player pacing the
 * boundary — or a train oscillating around it — can't flap the tier. Deliberately the same
 * direction as the freeze controller's eager-unfreeze/lazy-freeze: the cheap state is the one you
 * make the system work to enter.</p>
 */
public final class CarriageLodController {

    /**
     * Master switch. Flip via {@code /dungeontrain debug lod <on|off>} for the Gate 2
     * matched-toggle A/B measurement, and as a safety valve. When off, the next reconcile promotes
     * every currently-far carriage back to the full chunk render.
     */
    public static volatile boolean ENABLED = true;

    /**
     * Distance (blocks, world space, camera → carriage centre) beyond which a carriage demotes to
     * the baked-mesh tier. Runtime-tunable via {@code /dungeontrain debug lod distance <n>} so the
     * Gate 2 sweep can find where baked and chunk-rendered stop being tellable apart.
     *
     * <p>96 is the shipped starting point, not a measured optimum: comfortably past the 48-block
     * contents-entity spawn radius (so a carriage is never baked while its mobs are popping in),
     * and ~2.5 carriage groups back, which puts the nearest demoted carriage far enough that baked
     * AO/tint differences sit below a couple of pixels at default FOV.</p>
     */
    public static final double DEFAULT_FAR_DISTANCE_BLOCKS = 96.0;
    public static final double MIN_FAR_DISTANCE_BLOCKS = 16.0;
    public static final double MAX_FAR_DISTANCE_BLOCKS = 512.0;

    public static volatile double farDistanceBlocks = DEFAULT_FAR_DISTANCE_BLOCKS;

    /** Consecutive far ticks required before demoting (lazy demote; promotion is immediate). */
    static final int DEMOTE_GRACE_TICKS = 40;

    // Last reconcile snapshot, for the debug command + log line.
    private static volatile int lastTracked;
    private static volatile int lastNear;
    private static volatile int lastFar;

    private CarriageLodController() {}

    /** Actions the pure decision core can pick. */
    public enum Action { DEMOTE, PROMOTE, NONE }

    /**
     * Pure decision core (no Minecraft/Sable types, unit-testable — mirrors
     * {@link games.brennan.dungeontrain.ship.sable.PhysicsFreezeController#decide}). Eager promote,
     * lazy demote:
     * <ul>
     *   <li>near + far-tier → {@code PROMOTE} (immediately);</li>
     *   <li>far + near-tier + farTicks ≥ {@link #DEMOTE_GRACE_TICKS} → {@code DEMOTE};</li>
     *   <li>otherwise hold.</li>
     * </ul>
     */
    public static Action decide(boolean farNow, int ticksFar, boolean currentlyFar) {
        if (!farNow) return currentlyFar ? Action.PROMOTE : Action.NONE;
        if (currentlyFar) return Action.NONE;
        return ticksFar >= DEMOTE_GRACE_TICKS ? Action.DEMOTE : Action.NONE;
    }

    /**
     * Reconcile every tracked sub-level's LOD tier. Called once per client tick from
     * {@link CarriageLodClientEvents}. Runs on the client thread, before the frame that will read
     * the flags, so a tier flip is stable for the whole of the next frame's render passes.
     */
    public static void reconcile(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            lastTracked = lastNear = lastFar = 0;
            return;
        }

        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            lastTracked = lastNear = lastFar = 0;
            return;
        }

        // Camera position, not player position: in a cinematic or third-person the camera is what
        // decides how big a carriage looks on screen, and it is what the render passes cull against.
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double thresholdSq = farDistanceBlocks * farDistanceBlocks;

        int tracked = 0, near = 0, far = 0;
        List<ClientSubLevel> subLevels = container.getAllSubLevels();
        for (ClientSubLevel sl : subLevels) {
            if (!(sl instanceof DtLodRenderable flag)) continue;
            tracked++;

            boolean currentlyFar = flag.dt$isLodFar();

            if (!ENABLED) {
                if (currentlyFar) CarriageLod.promote(sl);
                flag.dt$setFarTicks(0);
                near++;
                continue;
            }

            boolean farNow = distanceSqToCentre(sl, eye) > thresholdSq;
            int ticksFar = farNow ? flag.dt$farTicks() + 1 : 0;
            flag.dt$setFarTicks(ticksFar);

            switch (decide(farNow, ticksFar, currentlyFar)) {
                case DEMOTE -> CarriageLod.demote(sl);
                case PROMOTE -> CarriageLod.promote(sl);
                case NONE -> { }
            }

            if (flag.dt$isLodFar()) far++; else near++;
        }

        lastTracked = tracked;
        lastNear = near;
        lastFar = far;

        CarriageLodLog.sample(tracked, near, far);
    }

    /**
     * Squared world-space distance from {@code eye} to the sub-level's centre.
     *
     * <p>{@link ClientSubLevel#boundingBox()} returns Sable's {@code sweptBounds}, which is set
     * from {@code globalBounds} — <b>already world space</b>. It must NOT be pushed through
     * {@code renderPose}: that would apply the carriage's transform twice and put far carriages at
     * roughly double their true distance. Bounds update on the sub-level tick rather than per
     * frame, which is fine here — the reconcile is per tick and carries
     * {@link #DEMOTE_GRACE_TICKS} of hysteresis on top.</p>
     *
     * <p>Centre rather than origin: a group sub-level spans ~37 blocks, so using the origin would
     * bias the decision by up to half a group.</p>
     */
    private static double distanceSqToCentre(ClientSubLevel sl, Vec3 eye) {
        Vector3d centre = sl.boundingBox().center(new Vector3d());
        return centre.distanceSquared(eye.x, eye.y, eye.z);
    }

    /**
     * Promote every currently-far sub-level back to the full chunk render. Called on level unload
     * and when the master switch goes off, so no sub-level is left holding a stale far flag across
     * a world change.
     */
    public static void promoteAll(ClientLevel level) {
        if (level == null) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (ClientSubLevel sl : container.getAllSubLevels()) {
            if (sl instanceof DtLodRenderable flag && flag.dt$isLodFar()) {
                CarriageLod.promote(sl);
            }
            if (sl instanceof DtLodRenderable flag) flag.dt$setFarTicks(0);
        }
        lastTracked = lastNear = lastFar = 0;
    }

    /** Clamp + apply a new threshold. Returns the value actually applied. */
    public static double setFarDistance(double blocks) {
        double clamped = Math.max(MIN_FAR_DISTANCE_BLOCKS, Math.min(MAX_FAR_DISTANCE_BLOCKS, blocks));
        farDistanceBlocks = clamped;
        return clamped;
    }

    /** Snapshot from the last {@link #reconcile} — for {@code /dungeontrain debug lod status}. */
    public static int lastTracked() { return lastTracked; }
    public static int lastNear() { return lastNear; }
    public static int lastFar() { return lastFar; }
}
