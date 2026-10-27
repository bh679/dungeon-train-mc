package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.CarriageOcclusion;
import games.brennan.dungeontrain.client.snapshot.SnapshotCamera;
import games.brennan.dungeontrain.net.DeathScenePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The death moment's camera. In order: the player's own view (red wash, death tilt), an orbit of
 * where they died, an orbit of who killed them, an orbit of the other mob they fought, a dolly out
 * from the carriage they died on, a world-locked shot of the carriage leaving, then world-locked
 * shots of the train passing, looping until Space on {@link DeathMomentScreen}. Shots whose
 * subject does not exist are skipped ({@link #buildSequence}). Every cut fades through black;
 * Left/Right step through the shots.
 *
 * <p>Train-locked lenses are offsets from the body in carriage space, re-projected through the
 * carriage's frame-interpolated render pose so they glide. World-locked lenses are fixed where
 * their shot starts. Orbits sweep the longest arc with a clear view of the subject, tested against
 * world blocks and carriage walls ({@link #clearArc}). Every lens is pulled to open air once when
 * its shot starts, against world blocks ({@link SnapshotCamera#clipTowardOpenAir}) and carriage
 * blocks ({@link CarriageOcclusion#firstHit}). HUD, hand and death tilt are off while a camera
 * shot is up. Client main thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathCameraCycle {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Ticks of black on each side of a cut. */
    static final int FADE_TICKS = 8;
    /** The carriage shot zooms out for this long (3 s), then holds in the world and tracks the body. */
    static final int DOLLY_TICKS = 60;
    /** Candidate angles tested around an orbit subject. */
    static final int ORBIT_SAMPLES = 24;
    /** An orbit needs at least this much clear arc (degrees) at a radius before it falls back to a tighter one. */
    static final double MIN_ARC_DEGREES = 60.0;
    /** An orbit never sweeps more than half a turn. */
    static final double MAX_SWEEP_DEGREES = 180.0;
    /** Radii an orbit tries, widest first. */
    static final double[] ORBIT_RADII = {6.0, 4.0, 2.5, 1.6};
    /** Re-test a moving subject's view this often; the arc is recomputed only when the lens is blocked. */
    private static final int ORBIT_RECHECK_TICKS = 20;
    /** Aim a little above the feet so the body sits in the lower middle of the frame. */
    private static final double LOOK_Y_OFFSET = 1.0;
    private static final double CARRIAGE_SCAN_RADIUS = 40.0;
    private static final double CLIP_MARGIN = 0.6;

    /** The world-locked shots of the train, looped until Space. */
    static final List<DeathShot> TRAIN_SHOTS = List.of(
        DeathShot.world(0.0, 24.0, 14.0, 120, "W-5"),    // high overhead, a little to the side
        DeathShot.world(0.0, 3.0, 26.0, 120, "W-6"),     // low and wide from the side
        DeathShot.world(-55.0, 18.0, -28.0, 120, "W-7"), // far behind, high, far side
        DeathShot.world(45.0, 4.0, -24.0, 120, "W-8"),   // far ahead, low, far side
        DeathShot.world(0.0, 36.0, 44.0, 120, "W-9"),    // very high and wide
        DeathShot.world(-26.0, 5.0, -20.0, 120, "W-10")  // behind, low, far side
    );

    private static List<DeathShot> sequence = List.of();
    private static List<DeathShot> reviewSequence;
    private static boolean reviewLabels = DungeonTrain.isDevBuild();

    private static boolean active;
    private static int index;
    private static int shotTicks;
    private static SubLevel carriage;
    /** Body position in carriage (plot) space, or world space when {@link #carriage} is null. */
    private static Vector3d bodyLocal;
    /** Where the body was when the scene started, in world space. */
    private static Vector3d deathSpot;
    private static Entity killer;
    private static Entity contact;
    /** The current shot's lens: carriage space for train-locked shots, world space otherwise. */
    private static Vector3d lens;
    /** Dolly end, carriage space. */
    private static Vector3d lensEnd;
    /** Where the dolly ended, in the world, once it has: the lens holds there and tracks the body. */
    private static Vector3d dollyHold;
    /** Fixed world aim for {@link DeathShot.Kind#WORLD} shots. */
    private static Vector3d aimWorld;
    /** Orbit: start angle (radians), sweep (radians), radius and height in use. */
    private static double orbitStart;
    private static double orbitSweep;
    private static double orbitRadius;
    private static double orbitHeight;
    private static boolean savedHideGui;
    private static int waitTicks;
    private static final int PIN_WAIT_TICKS = 3;

    private DeathCameraCycle() {}

    public static boolean isActive() {
        return active;
    }

    /** The shot now showing, or null when inactive. */
    public static DeathShot current() {
        return active && index < sequence.size() ? sequence.get(index) : null;
    }

    /** True while the player's own view is up (the red wash and the death tilt belong to it alone). */
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

    /** Dev review: play this list instead of the built one (null = built), and show each shot's label. */
    static void review(List<DeathShot> shots, boolean labels) {
        reviewSequence = shots == null || shots.isEmpty() ? null : List.copyOf(shots);
        reviewLabels = labels;
    }

    /**
     * Pure: the sequence for this death. Shots whose subject is missing are left out; the
     * train shots are always there, so the loop always has something to show.
     */
    static List<DeathShot> buildSequence(boolean voidDeath, boolean hasKiller, boolean hasContact, boolean onTrain) {
        List<DeathShot> shots = new ArrayList<>();
        shots.add(DeathShot.pov(40));
        if (!voidDeath) shots.add(DeathShot.orbit(DeathShot.Target.DEATH_SPOT, 6.0, 3.0, 60, "spot"));
        if (hasKiller) shots.add(DeathShot.orbit(DeathShot.Target.KILLER, 5.0, 2.5, 60, "killer"));
        if (hasContact) shots.add(DeathShot.orbit(DeathShot.Target.CONTACT, 5.0, 2.5, 60, "mob"));
        // One shot: zoom out from beside the body for DOLLY_TICKS, then the lens stays where it
        // reached, in the world, and tracks the body as the carriage rolls away.
        if (onTrain) shots.add(DeathShot.dolly(new Vec3(3.0, 2.0, 5.0), new Vec3(-6.0, 8.0, 14.0), DOLLY_TICKS + 60, "carriage"));
        else shots.add(DeathShot.transition(-3.0, 2.5, 8.0, 60, "leaving"));
        shots.addAll(TRAIN_SHOTS);
        return List.copyOf(shots);
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
        deathSpot = bodyWorld();
        DeathScenePacket hints = DeathSceneHints.latest();
        killer = livingOnClient(mc, hints.killerId());
        contact = livingOnClient(mc, hints.contactId());
        DeathSceneHints.clear();
        LOGGER.info("[DungeonTrain] Death scene starts: hints={} killer={} contact={} onTrain={}", hints,
            killer == null ? "none" : killer.getType().toShortString(), contact == null ? "none" : contact.getType().toShortString(),
            carriage != null);
        sequence = reviewSequence != null
            ? reviewSequence
            : buildSequence(hints.voidDeath(), killer != null, contact != null, carriage != null);
        savedHideGui = mc.options.hideGui;
        active = true;
        select(0);
    }

    private static Entity livingOnClient(Minecraft mc, int id) {
        if (id == DeathScenePacket.NONE || mc.level == null) return null;
        Entity entity = mc.level.getEntity(id);
        return entity instanceof LivingEntity living && living.isAlive() && living != mc.player ? entity : null;
    }

    /** End the sequence and give the HUD back. Safe to call when not active. */
    public static void stop() {
        if (!active) return;
        active = false;
        carriage = null;
        bodyLocal = null;
        deathSpot = null;
        killer = null;
        contact = null;
        lens = null;
        lensEnd = null;
        dollyHold = null;
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
        DeathShot shot = sequence.get(index);
        if (shot.kind() == DeathShot.Kind.ORBIT && shotTicks % ORBIT_RECHECK_TICKS == 0) recheckOrbit(shot);
        if (shotTicks >= shot.holdTicks()) select(nextIndex(sequence, index));
    }

    /** Right arrow. */
    public static void next() {
        if (active) select(nextIndex(sequence, index));
    }

    /** Left arrow. */
    public static void previous() {
        if (active) select(previousIndex(sequence, index));
    }

    /** Pure: the shot after {@code current}; past the end, the looping train shots start over. */
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

    /** Pure: black overlay alpha for a shot of {@code hold} ticks at {@code t} ticks in. */
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
        lensEnd = null;
        dollyHold = null;
        aimWorld = null;
        switch (shot.kind()) {
            case POV -> { }
            case TRAIN_LOCKED -> lens = resolveLens(shot.offset(), true, null);
            case DOLLY -> {
                lens = resolveLens(shot.offset(), true, null);
                lensEnd = resolveLens(shot.offsetEnd(), true, null);
            }
            case ORBIT -> {
                if (!setUpOrbit(shot)) {
                    // Nothing can see the subject from anywhere: skip this shot.
                    int next = nextIndex(sequence, i);
                    if (next != i) select(next);
                }
            }
            case TRANSITION -> lens = resolveLens(shot.offset(), false, null);
            case WORLD -> {
                // Phase 2 is about the place: lens and aim hang off where the player died, in the
                // world, not off the body the carriage has since carried on.
                lens = resolveLens(shot.offset(), false, deathSpot);
                aimWorld = new Vector3d(deathSpot.x, deathSpot.y + LOOK_Y_OFFSET, deathSpot.z);
            }
        }
    }

    // ── orbits ───────────────────────────────────────────────────────────

    /** The orbit subject's world position now (null if it is gone). */
    private static Vector3d subjectWorld(DeathShot shot, float partialTick) {
        return switch (shot.target()) {
            case DEATH_SPOT -> deathSpot == null ? null : new Vector3d(deathSpot);
            case KILLER -> entityWorld(killer, partialTick);
            case CONTACT -> entityWorld(contact, partialTick);
            case BODY -> toWorldRender(bodyLocal, partialTick);
        };
    }

    private static Vector3d entityWorld(Entity entity, float partialTick) {
        if (entity == null || !entity.isAlive()) return null;
        Vec3 p = entity.getPosition(partialTick);
        return new Vector3d(p.x, p.y, p.z);
    }

    /** Pick the orbit's clear arc at the widest radius that has one. False when no angle at all is clear. */
    private static boolean setUpOrbit(DeathShot shot) {
        Vector3d subject = subjectWorld(shot, 1.0f);
        if (subject == null) return false;
        Entity e = shot.target() == DeathShot.Target.KILLER ? killer : shot.target() == DeathShot.Target.CONTACT ? contact : null;
        if (e != null) LOGGER.info("[DungeonTrain] Death scene {} subject {} at {} removed={} invisible={} bb={}", shot.label(),
            e.getType().toShortString(), e.position(), e.isRemoved(), e.isInvisible(), e.getBoundingBox());
        double height = shot.offset().y;
        for (double radius : ORBIT_RADII) {
            for (double h : new double[] {height, height * 0.5, 0.8}) {
                boolean[] clear = clearAngles(subject, radius, h);
                int[] run = longestClearRun(clear);
                LOGGER.debug("[DungeonTrain] Death scene orbit {}: radius={} height={} clearRun={} of {} subject=({}, {}, {})",
                    shot.label(), radius, h, run[1], ORBIT_SAMPLES, (int) subject.x, (int) subject.y, (int) subject.z);
                if (run[1] == 0) continue;
                double arc = run[1] * 360.0 / ORBIT_SAMPLES;
                if (arc < MIN_ARC_DEGREES && radius != ORBIT_RADII[ORBIT_RADII.length - 1]) continue;
                double sweep = Math.min(arc, MAX_SWEEP_DEGREES);
                double step = 2.0 * Math.PI / ORBIT_SAMPLES;
                double centre = run[0] * step + (run[1] - 1) * step / 2.0;
                orbitSweep = Math.toRadians(sweep);
                orbitStart = centre - orbitSweep / 2.0;
                orbitRadius = radius;
                orbitHeight = h;
                return true;
            }
        }
        return false;
    }

    /** A moving subject may have walked behind something: re-pick the arc only when the lens is blocked now. */
    private static void recheckOrbit(DeathShot shot) {
        if (shot.target() == DeathShot.Target.DEATH_SPOT) return;
        Vector3d subject = subjectWorld(shot, 1.0f);
        if (subject == null) return;
        double angle = orbitAngle(shotTicks, shot.holdTicks());
        Vector3d lensNow = orbitLens(subject, orbitRadius, orbitHeight, angle);
        if (!clearView(subject, lensNow)) setUpOrbit(shot);
    }

    private static double orbitAngle(double t, int hold) {
        double f = Mth.clamp(t / Math.max(1.0, hold - FADE_TICKS), 0.0, 1.0);
        return orbitStart + orbitSweep * f;
    }

    /** Pure: lens position on the orbit circle. */
    static Vector3d orbitLens(Vector3d subject, double radius, double height, double angle) {
        return new Vector3d(subject.x + radius * Math.cos(angle), subject.y + height, subject.z + radius * Math.sin(angle));
    }

    private static boolean[] clearAngles(Vector3d subject, double radius, double height) {
        boolean[] clear = new boolean[ORBIT_SAMPLES];
        for (int i = 0; i < ORBIT_SAMPLES; i++) {
            double angle = i * 2.0 * Math.PI / ORBIT_SAMPLES;
            clear[i] = clearView(subject, orbitLens(subject, radius, height, angle));
        }
        return clear;
    }

    /** Can a lens at {@code lensW} see the subject's chest, past world blocks and carriage walls? */
    private static boolean clearView(Vector3d subject, Vector3d lensW) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return true;
        Vec3 from = new Vec3(subject.x, subject.y + 1.2, subject.z);
        Vec3 to = new Vec3(lensW.x, lensW.y, lensW.z);
        Vec3 clipped = SnapshotCamera.clipTowardOpenAir(level, player, from, to);
        if (clipped.distanceToSqr(to) > 0.25) return false;
        List<CarriageOcclusion.Carriage> carriages = CarriageOcclusion.gatherNearby(level, from, CARRIAGE_SCAN_RADIUS, 1.0f);
        return CarriageOcclusion.firstHit(carriages, from, to) == null;
    }

    /**
     * Pure: {start index, length} of the longest run of true values, wrapping around the end.
     * Length 0 when nothing is clear; length {@code clear.length} when everything is.
     */
    static int[] longestClearRun(boolean[] clear) {
        int n = clear.length;
        int bestStart = 0;
        int bestLen = 0;
        int start = -1;
        for (int i = 0; i < 2 * n; i++) {
            if (clear[i % n]) {
                if (start < 0) start = i;
                int len = Math.min(i - start + 1, n);
                if (len > bestLen) {
                    bestLen = len;
                    bestStart = start % n;
                }
            } else {
                start = -1;
            }
        }
        return new int[] {bestStart, bestLen};
    }

    // ── lenses ───────────────────────────────────────────────────────────

    /**
     * A lens at {@code offset} from the body (or from {@code worldAnchor} when given), pulled to open
     * air once against world blocks and carriage blocks. Carriage space when {@code carriageSpace} and
     * a carriage is pinned, else world.
     */
    private static Vector3d resolveLens(Vec3 offset, boolean carriageSpace, Vector3d worldAnchor) {
        boolean local = carriageSpace && carriage != null;
        Vector3d body = local ? bodyLocal : (worldAnchor != null ? new Vector3d(worldAnchor) : bodyWorld());
        Vector3d want = lensFor(body, offset);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return want;
        Vector3d bodyW = local || worldAnchor == null ? bodyWorld() : new Vector3d(worldAnchor);
        Vector3d wantW = local ? toWorld(want) : want;
        Vec3 from = new Vec3(bodyW.x, bodyW.y + player.getBbHeight() * 0.6, bodyW.z);
        Vec3 clipped = SnapshotCamera.clipTowardOpenAir(level, player, from, new Vec3(wantW.x, wantW.y, wantW.z));
        // A lens that lands inside a carriage block (the body is usually inside one, so a ray
        // from the body always crosses its own wall first) steps back toward the body until it
        // is in open air. The body's own wall is never a reason to pull the lens inside.
        List<CarriageOcclusion.Carriage> carriages = CarriageOcclusion.gatherNearby(level, from, CARRIAGE_SCAN_RADIUS, 1.0f);
        Vec3 dir = from.subtract(clipped);
        double len = dir.length();
        for (int i = 0; i < 40 && len > 1.0e-6 && CarriageOcclusion.insideBlock(carriages, clipped); i++) {
            clipped = clipped.add(dir.scale(CLIP_MARGIN / len));
            dir = from.subtract(clipped);
            len = dir.length();
        }
        Vector3d clippedW = new Vector3d(clipped.x, clipped.y, clipped.z);
        return local ? toLocal(clippedW) : clippedW;
    }

    /** Pure: the lens position for a body position and an offset in the same space. */
    static Vector3d lensFor(Vector3d body, Vec3 offset) {
        return new Vector3d(body.x + offset.x, body.y + offset.y, body.z + offset.z);
    }

    /** Pure: smooth ease between two lens positions. */
    static Vector3d dollyAt(Vector3d from, Vector3d to, double f) {
        double c = Mth.clamp(f, 0.0, 1.0);
        double e = c * c * (3.0 - 2.0 * c);
        return new Vector3d(from).lerp(to, e);
    }

    /** The camera pose for this render frame, or null when the player's own view should show. */
    public static CinematicCameraController.Pose pose(float partialTick) {
        DeathShot shot = current();
        if (shot == null || shot.kind() == DeathShot.Kind.POV) return null;
        double t = shotTicks + partialTick;
        Vector3d bodyW = toWorldRender(bodyLocal, partialTick);
        Vector3d aimBody = new Vector3d(bodyW.x, bodyW.y + LOOK_Y_OFFSET, bodyW.z);
        Vector3d lensW;
        Vector3d aim = aimBody;
        switch (shot.kind()) {
            case ORBIT -> {
                Vector3d subject = subjectWorld(shot, partialTick);
                if (subject == null) return null;
                lensW = orbitLens(subject, orbitRadius, orbitHeight, orbitAngle(t, shot.holdTicks()));
                aim = new Vector3d(subject.x, subject.y + LOOK_Y_OFFSET, subject.z);
            }
            case DOLLY -> {
                if (lens == null || lensEnd == null) return null;
                if (t < DOLLY_TICKS) {
                    lensW = toWorldRender(dollyAt(lens, lensEnd, t / DOLLY_TICKS), partialTick);
                } else {
                    if (dollyHold == null) dollyHold = toWorldRender(lensEnd, partialTick);
                    lensW = dollyHold;
                }
            }
            case TRAIN_LOCKED -> {
                if (lens == null) return null;
                lensW = toWorldRender(lens, partialTick);
            }
            case WORLD -> {
                if (lens == null) return null;
                lensW = lens;
                if (aimWorld != null) aim = aimWorld;
            }
            default -> {
                if (lens == null) return null;
                lensW = lens;
            }
        }
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
