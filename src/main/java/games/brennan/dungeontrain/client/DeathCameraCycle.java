package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.SnapshotCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Vector3d;

/**
 * The death moment's camera: three shots of the train, cycling on their own, until the player
 * presses Space on {@link DeathMomentScreen} (Left/Right pick a shot by hand).
 *
 * <p>Each shot is an offset from the body in carriage space (the train runs along +X), re-projected
 * through the carriage's live pose every frame, the same way {@link DeadRiderCarry} keeps the body
 * on the carriage, so the train stays framed while it rolls on. The camera always aims at the body.
 * A body that died off the train gets the same offsets in world space instead, and the train rolls
 * out of frame as it would for anyone standing there.</p>
 *
 * <p>When a shot starts, its lens is clipped toward open air once ({@link
 * SnapshotCamera#clipTowardOpenAir}) so a trackside camera inside a tunnel wall is pulled back to
 * the body's side of it. {@link games.brennan.dungeontrain.mixin.client.CameraCinematicMixin}
 * applies {@link #pose(float)} while {@link #isActive()}; the HUD and the first-person hand are
 * hidden for the duration. Client main thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathCameraCycle {

    /** How long each shot holds before the next one (4 s). */
    static final int ANGLE_TICKS = 80;
    /** Carriage-space offsets from the body: along the train, up, across. */
    static final Vec3[] OFFSETS = {
        new Vec3(7.0, 2.5, 8.0),    // trackside, a little ahead: the carriage rolls past
        new Vec3(-12.0, 10.0, -5.0), // high and behind: the train stretching ahead
        new Vec3(12.0, 1.5, -7.0),   // low, ahead, the far side of the track, looking back down the line
    };
    static final int ANGLE_COUNT = OFFSETS.length;
    /** Aim a little above the feet so the body sits in the lower middle of the frame. */
    private static final double LOOK_Y_OFFSET = 1.0;

    private static boolean active;
    private static int angle;
    private static int angleTicks;
    private static SubLevel carriage;
    /** Body position in carriage (plot) space, or world space when {@link #carriage} is null. */
    private static Vector3d bodyLocal;
    /** Resolved lens position per shot, in the same space as {@link #bodyLocal}; null until the shot starts. */
    private static final Vector3d[] lensLocal = new Vector3d[ANGLE_COUNT];
    private static boolean savedHideGui;
    /** Dead ticks seen before starting, so {@link DeadRiderCarry} has had a tick to pin the body. */
    private static int waitTicks;
    /** Give the carriage pin this many dead ticks to appear before starting in world space. */
    private static final int PIN_WAIT_TICKS = 3;

    private DeathCameraCycle() {}

    /**
     * Start once the player is actually dead on the client. The death screen opens from the
     * combat-kill packet, a tick or two before the health update that makes
     * {@code isDeadOrDying()} true, and {@link DeadRiderCarry} pins the body on its first dead
     * tick (after the screen's tick). So the screen calls this every tick and the cycle begins
     * when the body is dead and pinned, or after {@link #PIN_WAIT_TICKS} dead ticks off the train.
     */
    public static void ensureStarted(LocalPlayer player) {
        if (active || player == null || !player.isDeadOrDying()) return;
        waitTicks++;
        if (DeadRiderCarry.pinned() != null || waitTicks >= PIN_WAIT_TICKS) start(player);
    }

    public static boolean isActive() {
        return active;
    }

    /** Which of the three shots is showing (0-based). */
    public static int angle() {
        return angle;
    }

    /** Begin the cycle on the first shot. Safe to call again while active (no-op). */
    public static void start(LocalPlayer player) {
        if (active || player == null) return;
        Minecraft mc = Minecraft.getInstance();
        DeadRiderCarry.Pin pin = DeadRiderCarry.pinned();
        if (pin != null) {
            carriage = pin.carriage();
            bodyLocal = new Vector3d(pin.local());
        } else {
            carriage = null;
            bodyLocal = new Vector3d(player.getX(), player.getY(), player.getZ());
        }
        for (int i = 0; i < ANGLE_COUNT; i++) lensLocal[i] = null;
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;
        active = true;
        select(0);
    }

    /** End the cycle and give the HUD back. Safe to call when not active. */
    public static void stop() {
        if (!active) return;
        active = false;
        carriage = null;
        bodyLocal = null;
        waitTicks = 0;
        Minecraft.getInstance().options.hideGui = savedHideGui;
    }

    /** Once per client tick while the death moment screen is up. */
    public static void tick() {
        if (!active) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isDeadOrDying()) {
            stop();
            return;
        }
        if (carriage != null && carriage.isRemoved()) {
            // The carriage under the body is gone: fall back to where the body is now, in world space.
            Vector3d world = bodyWorld();
            carriage = null;
            bodyLocal = world;
            for (int i = 0; i < ANGLE_COUNT; i++) lensLocal[i] = null;
            select(angle);
        }
        angleTicks++;
        if (angleTicks >= ANGLE_TICKS) select(nextIndex(angle));
    }

    /** Right arrow. */
    public static void next() {
        if (active) select(nextIndex(angle));
    }

    /** Left arrow. */
    public static void previous() {
        if (active) select(previousIndex(angle));
    }

    static int nextIndex(int current) {
        return (current + 1) % ANGLE_COUNT;
    }

    static int previousIndex(int current) {
        return (current + ANGLE_COUNT - 1) % ANGLE_COUNT;
    }

    private static void select(int index) {
        angle = index;
        angleTicks = 0;
        if (lensLocal[index] == null) lensLocal[index] = resolveLens(index);
    }

    /**
     * The shot's lens in local space, clipped toward open air against the world once. Falls back
     * to the raw offset when there is no level to clip against.
     */
    private static Vector3d resolveLens(int index) {
        Vector3d want = lensFor(bodyLocal, OFFSETS[index]);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return want;
        Vector3d bodyW = toWorld(bodyLocal);
        Vector3d wantW = toWorld(want);
        Vec3 from = new Vec3(bodyW.x, bodyW.y + player.getBbHeight() * 0.6, bodyW.z);
        Vec3 clipped = SnapshotCamera.clipTowardOpenAir(level, player, from, new Vec3(wantW.x, wantW.y, wantW.z));
        return toLocal(new Vector3d(clipped.x, clipped.y, clipped.z));
    }

    /** Pure: the lens position for a body position and a carriage-space offset. */
    static Vector3d lensFor(Vector3d body, Vec3 offset) {
        return new Vector3d(body.x + offset.x, body.y + offset.y, body.z + offset.z);
    }

    /** The camera pose for this render frame, or null when not active. */
    public static CinematicCameraController.Pose pose(float partialTick) {
        if (!active || bodyLocal == null) return null;
        Vector3d lens = lensLocal[angle];
        if (lens == null) lens = lensFor(bodyLocal, OFFSETS[angle]);
        // Render time: project through the carriage's frame-interpolated pose, the same one the
        // carriage itself is drawn with, so the camera glides instead of stepping once per tick.
        Vector3d lensW = toWorldRender(lens, partialTick);
        Vector3d bodyW = toWorldRender(bodyLocal, partialTick);
        return poseFor(new Vec3(lensW.x, lensW.y, lensW.z), new Vec3(bodyW.x, bodyW.y + LOOK_Y_OFFSET, bodyW.z));
    }

    private static Vector3d toWorldRender(Vector3d local, float partialTick) {
        if (carriage instanceof ClientSubLevel client) {
            Pose3dc pose = client.renderPose(partialTick);
            if (pose != null) return pose.transformPosition(new Vector3d(local), new Vector3d());
        }
        return toWorld(local);
    }

    /** Pure: a pose at {@code lens} looking at {@code target}. */
    static CinematicCameraController.Pose poseFor(Vec3 lens, Vec3 target) {
        float[] yp = CinematicCameraController.lookAt(lens, target);
        return new CinematicCameraController.Pose(lens, yp[0], yp[1]);
    }

    private static Vector3d bodyWorld() {
        return toWorld(bodyLocal);
    }

    private static Vector3d toWorld(Vector3d local) {
        if (carriage == null) return new Vector3d(local);
        return carriage.logicalPose().transformPosition(new Vector3d(local));
    }

    private static Vector3d toLocal(Vector3d world) {
        if (carriage == null) return new Vector3d(world);
        return carriage.logicalPose().transformPositionInverse(new Vector3d(world));
    }

    /** The camera is detached: no first-person hand floating in the shot. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (active) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop();
    }
}
