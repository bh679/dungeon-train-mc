package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.SnapshotCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Vector3d;

import java.util.List;

/**
 * The death moment's camera: the player's own view first (with the red wash), then a one-pass
 * run of shots riding the carriage, a world-locked shot that watches the carriage roll away, and
 * a looping set of world-locked shots of the train passing by, until Space on
 * {@link DeathMomentScreen}. Every cut fades through black; Left/Right step through the shots.
 *
 * <p>Train-locked lenses are offsets from the body in carriage space, re-projected through the
 * carriage's frame-interpolated render pose (the one the carriage is drawn with) so they glide.
 * World-locked lenses are fixed where their shot starts. A body that died off the train gets the
 * same offsets in world space. Each lens is clipped toward open air against world blocks once when
 * its shot starts ({@link SnapshotCamera#clipTowardOpenAir}); carriage walls are invisible to that
 * clip, so every offset stands clear of the train's 7-block width. HUD and first-person hand are
 * hidden while a camera shot is up. Client main thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathCameraCycle {

    /** Ticks of black on each side of a cut. */
    static final int FADE_TICKS = 8;
    /** Aim a little above the feet so the body sits in the lower middle of the frame. */
    private static final double LOOK_Y_OFFSET = 1.0;

    /** The shipped sequence: POV, three carriage shots, one transition, three passing shots (looped). */
    static final List<DeathShot> DEFAULT_SEQUENCE = List.of(
        DeathShot.pov(40),
        DeathShot.trainLocked(4.0, 1.8, 6.0, 60, "P1-1"),
        DeathShot.trainLocked(-8.0, 6.0, -4.0, 60, "P1-3"),
        DeathShot.trainLocked(9.0, 1.2, -5.5, 60, "P1-5"),
        DeathShot.transition(-3.0, 2.5, 8.0, 100, "T-1"),
        DeathShot.world(0.0, 3.0, 14.0, 120, "W-1"),
        DeathShot.world(-20.0, 4.0, 10.0, 120, "W-3"),
        DeathShot.world(0.0, 8.0, 18.0, 120, "W-2")
    );

    private static List<DeathShot> sequence = DEFAULT_SEQUENCE;
    private static boolean reviewLabels;

    private static boolean active;
    private static int index;
    private static int shotTicks;
    private static SubLevel carriage;
    /** Body position in carriage (plot) space, or world space when {@link #carriage} is null. */
    private static Vector3d bodyLocal;
    /** The current shot's lens: carriage space for train-locked shots, world space otherwise. */
    private static Vector3d lens;
    /** Fixed world aim for {@link DeathShot.Kind#WORLD} shots. */
    private static Vector3d aimWorld;
    private static boolean savedHideGui;
    private static int waitTicks;
    private static final int PIN_WAIT_TICKS = 3;

    private DeathCameraCycle() {}

    public static boolean isActive() {
        return active;
    }

    /** The shot now showing, or null when inactive. */
    public static DeathShot current() {
        return active ? sequence.get(index) : null;
    }

    /** True while the player's own view is up (the red wash belongs to it alone). */
    public static boolean isPov() {
        DeathShot shot = current();
        return shot != null && shot.kind() == DeathShot.Kind.POV;
    }

    /** The current shot's label when the review overlay is on, else null. */
    public static String reviewLabel() {
        DeathShot shot = current();
        return reviewLabels && shot != null ? shot.label() : null;
    }

    /** Ticks into the current shot. */
    public static int shotTicks() {
        return shotTicks;
    }

    /** Dev review: swap the sequence and show each shot's label. Takes effect at the next start. */
    static void review(List<DeathShot> shots, boolean labels) {
        sequence = shots == null || shots.isEmpty() ? DEFAULT_SEQUENCE : List.copyOf(shots);
        reviewLabels = labels;
    }

    /**
     * Start once the player is actually dead on the client: the death screen opens from the
     * combat-kill packet a tick before the health update, and {@link DeadRiderCarry} pins the body
     * on its first dead tick (after the screen's tick). So the screen calls this every tick.
     */
    public static void ensureStarted(LocalPlayer player) {
        if (active || player == null || !player.isDeadOrDying()) return;
        waitTicks++;
        if (DeadRiderCarry.pinned() != null || waitTicks >= PIN_WAIT_TICKS) start(player);
    }

    private static void start(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        DeadRiderCarry.Pin pin = DeadRiderCarry.pinned();
        if (pin != null) {
            carriage = pin.carriage();
            bodyLocal = new Vector3d(pin.local());
        } else {
            carriage = null;
            bodyLocal = new Vector3d(player.getX(), player.getY(), player.getZ());
        }
        savedHideGui = mc.options.hideGui;
        active = true;
        select(0);
    }

    /** End the sequence and give the HUD back. Safe to call when not active. */
    public static void stop() {
        if (!active) return;
        active = false;
        carriage = null;
        bodyLocal = null;
        lens = null;
        aimWorld = null;
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
            // The carriage under the body is gone: carry on from where the body is, in world space.
            Vector3d world = bodyWorld();
            carriage = null;
            bodyLocal = world;
            select(index);
        }
        shotTicks++;
        if (shotTicks >= sequence.get(index).holdTicks()) select(nextIndex(sequence, index));
    }

    /** Right arrow. */
    public static void next() {
        if (active) select(nextIndex(sequence, index));
    }

    /** Left arrow. */
    public static void previous() {
        if (active) select(previousIndex(sequence, index));
    }

    /**
     * Pure: the shot after {@code current}. Past the end, the looping world shots start over;
     * a sequence with no world shot holds its last shot.
     */
    static int nextIndex(List<DeathShot> shots, int current) {
        if (current + 1 < shots.size()) return current + 1;
        int loop = loopStart(shots);
        return loop >= 0 ? loop : current;
    }

    /** Pure: the shot before {@code current}; never back into the POV. */
    static int previousIndex(List<DeathShot> shots, int current) {
        int floor = shots.size() > 1 && shots.get(0).kind() == DeathShot.Kind.POV ? 1 : 0;
        if (current - 1 >= floor) return current - 1;
        int loop = loopStart(shots);
        return current == floor && loop >= 0 ? shots.size() - 1 : current;
    }

    /** Pure: index of the first world-locked shot, the start of the loop, or -1. */
    static int loopStart(List<DeathShot> shots) {
        for (int i = 0; i < shots.size(); i++) {
            if (shots.get(i).kind() == DeathShot.Kind.WORLD) return i;
        }
        return -1;
    }

    /**
     * Pure: black overlay alpha for a shot of {@code hold} ticks at {@code t} ticks in. Fades in
     * from black over the first {@link #FADE_TICKS} (not on the POV, which opens on the live world)
     * and out to black over the last.
     */
    static float blackAlpha(double t, int hold, boolean fadeIn) {
        float in = fadeIn ? 1.0f - (float) Mth.clamp(t / FADE_TICKS, 0.0, 1.0) : 0.0f;
        float out = (float) Mth.clamp((t - (hold - FADE_TICKS)) / FADE_TICKS, 0.0, 1.0);
        return Math.max(in, out);
    }

    /** Black overlay alpha for this frame, 0 when inactive. */
    public static float blackAlpha(float partialTick) {
        DeathShot shot = current();
        if (shot == null) return 0.0f;
        return blackAlpha(shotTicks + partialTick, shot.holdTicks(), shot.kind() != DeathShot.Kind.POV);
    }

    private static void select(int i) {
        index = i;
        shotTicks = 0;
        DeathShot shot = sequence.get(i);
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = shot.kind() != DeathShot.Kind.POV;
        lens = null;
        aimWorld = null;
        if (shot.kind() == DeathShot.Kind.POV) return;
        if (shot.kind() == DeathShot.Kind.TRAIN_LOCKED) {
            lens = resolveLens(shot, true);
            return;
        }
        lens = resolveLens(shot, false);
        if (shot.kind() == DeathShot.Kind.WORLD) {
            Vector3d body = bodyWorld();
            aimWorld = new Vector3d(body.x, body.y + LOOK_Y_OFFSET, body.z);
        }
    }

    /**
     * The shot's lens, clipped toward open air against world blocks once. Carriage space when
     * {@code carriageSpace} (train-locked) and a carriage is pinned, else world space.
     */
    private static Vector3d resolveLens(DeathShot shot, boolean carriageSpace) {
        boolean local = carriageSpace && carriage != null;
        Vector3d body = local ? bodyLocal : bodyWorld();
        Vector3d want = lensFor(body, shot.offset());
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return want;
        Vector3d bodyW = bodyWorld();
        Vector3d wantW = local ? toWorld(want) : want;
        Vec3 from = new Vec3(bodyW.x, bodyW.y + player.getBbHeight() * 0.6, bodyW.z);
        Vec3 clipped = SnapshotCamera.clipTowardOpenAir(level, player, from, new Vec3(wantW.x, wantW.y, wantW.z));
        Vector3d clippedW = new Vector3d(clipped.x, clipped.y, clipped.z);
        return local ? toLocal(clippedW) : clippedW;
    }

    /** Pure: the lens position for a body position and an offset in the same space. */
    static Vector3d lensFor(Vector3d body, Vec3 offset) {
        return new Vector3d(body.x + offset.x, body.y + offset.y, body.z + offset.z);
    }

    /** The camera pose for this render frame, or null when the player's own view should show. */
    public static CinematicCameraController.Pose pose(float partialTick) {
        DeathShot shot = current();
        if (shot == null || shot.kind() == DeathShot.Kind.POV || lens == null) return null;
        Vector3d bodyW = toWorldRender(bodyLocal, partialTick);
        Vector3d lensW = shot.kind() == DeathShot.Kind.TRAIN_LOCKED ? toWorldRender(lens, partialTick) : lens;
        Vector3d aim = shot.kind() == DeathShot.Kind.WORLD && aimWorld != null
            ? aimWorld
            : new Vector3d(bodyW.x, bodyW.y + LOOK_Y_OFFSET, bodyW.z);
        return poseFor(new Vec3(lensW.x, lensW.y, lensW.z), new Vec3(aim.x, aim.y, aim.z));
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

    /** Render time: through the carriage's frame-interpolated pose, the one it is drawn with. */
    private static Vector3d toWorldRender(Vector3d local, float partialTick) {
        if (carriage instanceof ClientSubLevel client) {
            Pose3dc pose = client.renderPose(partialTick);
            if (pose != null) return pose.transformPosition(new Vector3d(local), new Vector3d());
        }
        return toWorld(local);
    }

    /** The camera is detached: no first-person hand floating in the shot. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (active && !isPov()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop();
    }
}
