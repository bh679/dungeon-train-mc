package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.CarriageOcclusion;
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
import java.util.UUID;

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
 * its shot starts, against world blocks ({@link ShotPath#terrainBetween}) and carriage
 * blocks ({@link CarriageOcclusion#insideBlock}). HUD, hand and death tilt are off while a camera
 * shot is up. Client main thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathCameraCycle {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Ticks of black on each side of a cut. */
    static final int FADE_TICKS = 8;
    /** The carriage shot zooms out for this long (3 s), then holds in the world and tracks the body. */
    static final int DOLLY_TICKS = 60;
    /** After the zoom, the lens blends from riding the carriage into its world-locked hold over this long. */
    static final int DOLLY_BLEND_TICKS = 20;
    /** Candidate angles tested around an orbit subject. */
    static final int ORBIT_SAMPLES = 24;
    /** The ragdoll's sub-level origin sits in its torso; look a little above it, where a lying body's chest is. */
    static final double BODY_RAGDOLL_LIFT = 0.3;
    /** An orbit needs at least this much clear arc (degrees) at a radius before it falls back to a tighter one. */
    static final double MIN_ARC_DEGREES = 60.0;
    /** An orbit never sweeps more than half a turn. */
    static final double MAX_SWEEP_DEGREES = 180.0;
    /** Radii an orbit tries, widest first. */
    static final double[] ORBIT_RADII = {6.0, 4.0, 2.5, 1.6, 1.0};
    /** Re-test a moving subject's view this often; the arc is recomputed only when the lens is blocked. */
    private static final int ORBIT_RECHECK_TICKS = 20;
    /** Aim a little above the feet so the body sits in the lower middle of the frame. */
    private static final double LOOK_Y_OFFSET = 1.0;
    private static final double CARRIAGE_SCAN_RADIUS = 40.0;
    private static final double CLIP_MARGIN = 0.6;

    /** The world-locked shots of the train, looped until Space. */
    static final List<DeathShot> TRAIN_SHOTS = List.of(
        DeathShot.worldArc(0.0, 24.0, 14.0, 50.0, 120, "W-5"),                                     // high overhead, slow arc
        DeathShot.worldDolly(new Vec3(-14.0, 3.0, 26.0), new Vec3(14.0, 3.0, 26.0), 120, "W-6"),   // low side, slide along
        DeathShot.worldDolly(new Vec3(-55.0, 18.0, -28.0), new Vec3(-42.0, 15.0, -22.0), 120, "W-7"), // far behind high, push in
        DeathShot.worldArc(45.0, 4.0, -24.0, -25.0, 120, "W-8"),                                   // far ahead low, slow arc
        DeathShot.worldArc(0.0, 36.0, 44.0, 35.0, 120, "W-9"),                                     // very high and wide, slow arc
        DeathShot.worldDolly(new Vec3(-26.0, 5.0, -20.0), new Vec3(-18.0, 4.0, -15.0), 120, "W-10") // behind low, push in
    );

    /** Played instead of the open-air train shots while none of those has a clear path (a tunnel). */
    static final List<DeathShot> TUNNEL_SHOTS = List.of(
        DeathShot.tunnel(DeathShot.Kind.TUNNEL_RISE, 120, "side-rise"),
        DeathShot.tunnel(DeathShot.Kind.TUNNEL_ABOVE_BACK, 120, "above-back"),
        DeathShot.tunnel(DeathShot.Kind.TUNNEL_ROOF, 100, "roof-skim"),
        DeathShot.tunnel(DeathShot.Kind.TUNNEL_WINDOW_OUT, 100, "window-out")
    );
    /** Plays only when a whole loop pass found nothing else: never with an empty screen. */
    static final DeathShot LAST_RESORT = DeathShot.tunnel(DeathShot.Kind.TUNNEL_EYES, 100, "eyes-forward");
    /** The window shot is a filler: it plays only when fewer than this many other loop shots did this pass. */
    static final int WINDOW_OUT_MAX_OTHERS = 5;
    /** Subject shots at the end of every loop pass, in the open and in a tunnel alike. */
    static final List<DeathShot> LOOP_TAIL = List.of(
        DeathShot.feature(80, "feature"),
        DeathShot.orbitLoop(DeathShot.Target.RANDOM_MOB, 3.0, 2.0, 100, "mob-arc")
    );
    /** Every shot is re-tested this often for a lens that has run into a block. */
    private static final int TRAIN_LOCKED_RECHECK_TICKS = 1;
    private static final double RANDOM_MOB_RANGE = 64.0;

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
    /** The ragdoll body left where they died (server {@code DeathBodyRagdoll}), by root sub-level id; null when none. */
    private static UUID bodyId;
    /** That ragdoll once this client has it; the body shots follow it instead of the pinned player. */
    private static ClientSubLevel bodySub;
    private static Entity killer;
    private static Entity contact;
    private static Entity randomMob;
    /** The feature shot's subject: a point on a sub-level, in that sub-level's plot space. */
    private static ClientSubLevel featureSub;
    private static Vector3d featureLocal;
    private static DeathScenePacket.Target.Kind featureKind;
    /** Tunnel shots: where the train-locked lens looks, carriage space. */
    private static Vector3d aimLocal;
    /** Tunnel dollies: the aim rides with the lens at this carriage-space offset. */
    private static Vector3d aimOffset;
    /** Open-air loop shots that played since the loop last started; tunnel shots play only when it is 0. */
    private static int playedInLoop;
    /** Loop shots of any kind that played since the loop last started (the window and last-resort gates). */
    private static int passCount;
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
        return buildSequence(voidDeath, hasKiller, hasContact, onTrain, false);
    }

    static List<DeathShot> buildSequence(boolean voidDeath, boolean hasKiller, boolean hasContact, boolean onTrain,
                                         boolean hasFeature) {
        List<DeathShot> shots = new ArrayList<>();
        shots.add(DeathShot.pov(40));
        // Where you died, riding with the carriage (the body stays on it): as the lead-in to who
        // killed you, or, off the train, in place of the carriage shot. Never for a void death.
        // A blocked arc is answered by a tighter one, never by dropping the shot.
        if (!voidDeath && (hasKiller || !onTrain)) shots.add(DeathShot.orbit(DeathShot.Target.BODY, 6.0, 3.0, 60, "spot"));
        if (hasKiller) shots.add(DeathShot.orbit(DeathShot.Target.KILLER, 5.0, 2.5, 60, "killer"));
        if (hasContact) shots.add(DeathShot.orbit(DeathShot.Target.CONTACT, 5.0, 2.5, 60, "mob"));
        // One shot: zoom out from beside the body for DOLLY_TICKS, then the lens stays where it
        // reached, in the world, and tracks the body as the carriage rolls away.
        if (onTrain) shots.add(DeathShot.dolly(new Vec3(3.0, 2.0, 5.0), new Vec3(-6.0, 8.0, 14.0), DOLLY_TICKS + 60, "carriage"));
        else shots.add(DeathShot.transition(-3.0, 2.5, 8.0, 60, "leaving"));
        shots.addAll(TRAIN_SHOTS);
        shots.addAll(TUNNEL_SHOTS);
        // The feature and the mob arc close every pass, tunnel or not (the feature only when the
        // server listed one); they are not "open-air shots" for the tunnel gate.
        for (DeathShot tail : LOOP_TAIL) {
            if (tail.kind() == DeathShot.Kind.FEATURE_TRACK && !hasFeature) continue;
            shots.add(tail);
        }
        shots.add(LAST_RESORT);
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
        bodyId = hints.bodyId();
        bodySub = null;
        resolveBody(mc);
        resolveFeature(mc, hints.targets());
        DeathSceneHints.clear();
        LOGGER.info("[DungeonTrain] Death scene starts: hints={} killer={} contact={} onTrain={} body={}", hints,
            killer == null ? "none" : killer.getType().toShortString(), contact == null ? "none" : contact.getType().toShortString(),
            carriage != null, bodySub != null ? "here" : bodyId != null ? "awaited" : "none");
        sequence = reviewSequence != null
            ? reviewSequence
            : buildSequence(hints.voidDeath(), killer != null, contact != null, carriage != null, featureSub != null);
        savedHideGui = mc.options.hideGui;
        active = true;
        playedInLoop = 0;
        passCount = 0;
        select(0);
    }

    /** The first server-listed feature whose sub-level is on this client (contents first, nearest first). */
    private static void resolveFeature(Minecraft mc, List<DeathScenePacket.Target> targets) {
        featureSub = null;
        featureLocal = null;
        featureKind = null;
        if (mc.level == null || targets == null) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        if (container == null) return;
        int known = 0;
        for (ClientSubLevel ignored : container.getAllSubLevels()) known++;
        LOGGER.info("[DungeonTrain] Death scene feature targets: {} listed, {} sub-levels on the client", targets.size(), known);
        for (DeathScenePacket.Target t : targets) {
            for (ClientSubLevel sub : container.getAllSubLevels()) {
                if (!sub.isRemoved() && sub.getUniqueId().equals(t.subLevelId())) {
                    featureSub = sub;
                    featureLocal = new Vector3d(t.x(), t.y(), t.z());
                    featureKind = t.kind();
                    return;
                }
            }
        }
    }

    /**
     * Find the ragdoll body on this client. Sable can sync a new sub-level a tick or two after the
     * death scene packet, so {@link #tick} keeps asking until it shows up; once it is gone it stays gone.
     */
    private static void resolveBody(Minecraft mc) {
        if (bodyId == null || mc.level == null) return;
        if (bodySub != null) {
            if (bodySub.isRemoved()) {
                bodySub = null;
                bodyId = null;
            }
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        if (container == null) return;
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            if (!sub.isRemoved() && sub.getUniqueId().equals(bodyId)) {
                bodySub = sub;
                LOGGER.info("[DungeonTrain] Death scene body {} is on the client", bodyId);
                return;
            }
        }
    }

    /** True while the ragdoll body is on this client, so the player's own model should not draw. */
    public static boolean bodyRagdollLive() {
        return active && bodySub != null && !bodySub.isRemoved();
    }

    /** The ragdoll body's world position this frame, or null when there is none to follow. */
    private static Vector3d bodyRagdollWorld(float partialTick) {
        if (bodySub == null || bodySub.isRemoved()) return null;
        Pose3dc pose = bodySub.renderPose(partialTick);
        if (pose == null) pose = bodySub.logicalPose();
        org.joml.Vector3dc p = pose.position();
        return new Vector3d(p.x(), p.y() + BODY_RAGDOLL_LIFT, p.z());
    }

    /** Where "the body" is this frame: the ragdoll when this client has it, else the pinned player. */
    private static Vector3d bodyRender(float partialTick) {
        Vector3d ragdoll = bodyRagdollWorld(partialTick);
        return ragdoll != null ? ragdoll : toWorldRender(bodyLocal, partialTick);
    }

    private static Vector3d featureWorld(float partialTick) {
        if (featureSub == null || featureSub.isRemoved() || featureLocal == null) return null;
        Pose3dc pose = featureSub.renderPose(partialTick);
        if (pose == null) pose = featureSub.logicalPose();
        return pose.transformPosition(new Vector3d(featureLocal), new Vector3d());
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
        bodyId = null;
        bodySub = null;
        killer = null;
        contact = null;
        randomMob = null;
        featureSub = null;
        featureLocal = null;
        aimLocal = null;
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
        resolveBody(Minecraft.getInstance());
        if (carriage != null && carriage.isRemoved()) {
            // The carriage under the body is gone: carry on from where the body is, in world space.
            Vector3d world = bodyWorld();
            carriage = null;
            bodyLocal = world;
            select(index);
        }
        shotTicks++;
        DeathShot shot = sequence.get(index);
        if ((shot.kind() == DeathShot.Kind.ORBIT || shot.kind() == DeathShot.Kind.ORBIT_LOOP) && shotTicks % ORBIT_RECHECK_TICKS == 0) recheckOrbit(shot);
        if (shotTicks % TRAIN_LOCKED_RECHECK_TICKS == 0 && lensRanIntoBlock(shot)) {
            select(nextIndex(sequence, index));
            return;
        }
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
            if (shots.get(i).kind().isLoop()) return i;
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

    /**
     * Show shot {@code i}, or the next one that can be shown: a shot whose subject is missing or
     * whose lens path is not clear is skipped. Tunnel shots play only in a loop pass in which no
     * open-air train shot could. {@code TUNNEL_EYES} always can, so this always lands somewhere.
     */
    private static void select(int i) {
        int loop = loopStart(sequence);
        for (int hops = 0; hops <= sequence.size() + TUNNEL_SHOTS.size(); hops++) {
            if (i == loop && hops > 0) {
                playedInLoop = 0;
                passCount = 0;
            }
            DeathShot shot = sequence.get(i);
            boolean gated = switch (shot.kind()) {
                case TUNNEL_WINDOW_OUT -> passCount >= WINDOW_OUT_MAX_OTHERS;
                case TUNNEL_EYES -> passCount > 0;
                case TUNNEL_RISE, TUNNEL_ABOVE_BACK, TUNNEL_ROOF, TUNNEL_COUPLING -> playedInLoop > 0;
                default -> false;
            };
            if (gated) {
                i = nextIndex(sequence, i);
                continue;
            }
            if (prepare(shot)) {
                if (shot.kind().isOpenAir()) playedInLoop++;
                if (shot.kind().isLoop()) passCount++;
                index = i;
                shotTicks = 0;
                Minecraft.getInstance().options.hideGui = shot.kind() != DeathShot.Kind.POV;
                return;
            }
            int next = nextIndex(sequence, i);
            if (next == i) break;
            i = next;
        }
        // Nothing at all could be shown (no carriage, no level): hold the player's own view.
        index = 0;
        shotTicks = 0;
    }

    /** Resolve a shot's lens, aim and path; false when it cannot be shown now. */
    private static boolean prepare(DeathShot shot) {
        lens = null;
        lensEnd = null;
        dollyHold = null;
        aimWorld = null;
        aimLocal = null;
        aimOffset = null;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return shot.kind() == DeathShot.Kind.POV;
        Vector3d bodyW = bodyWorld();
        Vec3 bodyVec = new Vec3(bodyW.x, bodyW.y, bodyW.z);
        List<CarriageOcclusion.Carriage> carriages = CarriageOcclusion.gatherNearby(level, bodyVec, CARRIAGE_SCAN_RADIUS, 1.0f);
        Vector3d aimBody = new Vector3d(bodyW.x, bodyW.y + LOOK_Y_OFFSET, bodyW.z);
        switch (shot.kind()) {
            case POV, TUNNEL_EYES -> {
                if (shot.kind() == DeathShot.Kind.TUNNEL_EYES) {
                    if (carriage == null) return false;
                    lens = lensFor(bodyLocal, new Vec3(0.0, 1.6, 0.0));
                    aimLocal = lensFor(lens, new Vec3(12.0, 0.0, 0.0));
                }
                return true;
            }
            case TRAIN_LOCKED -> {
                lens = resolveLens(shot.offset(), true, null);
                if (lens == null) return false;
                return ShotPath.clear(level, player, carriages, List.of(toWorld(lens)), aimBody);
            }
            case DOLLY -> {
                // Start just outside the carriage wall, looking in (through a window when there
                // is one), then pull out a little more into the hold.
                CarriageWindow window = windowForBody();
                Vec3 from = window != null ? toVec(window.start()) : shot.offset();
                Vec3 to = window != null ? toVec(window.end()) : shot.offsetEnd();
                lens = resolveLens(from, true, null);
                lensEnd = resolveLens(to, true, null);
                if (lens == null || lensEnd == null) return false;
                Vector3d a = lens;
                Vector3d b = lensEnd;
                return ShotPath.clear(level, player, carriages, ShotPath.samples(f -> toWorld(dollyAt(a, b, f))), aimBody);
            }
            case ORBIT, ORBIT_LOOP -> {
                return setUpOrbit(shot);
            }
            case TUNNEL_RISE -> {
                CarriageWindow window = windowForBody();
                Double roof = roofAboveBody();
                if (window == null || carriage == null) return false;
                double side = window.side();
                double across = side * (window.wallAcross() + 1.2);
                double top = roof != null ? roof + 1.0 : bodyLocal.y + 5.0;
                lens = new Vector3d(bodyLocal.x, bodyLocal.y + 0.4, bodyLocal.z + across);
                lensEnd = new Vector3d(bodyLocal.x, top, bodyLocal.z + across);
                aimOffset = new Vector3d(16.0, 0.0, -side * 2.5);
                Vector3d a = lens;
                Vector3d b = lensEnd;
                return ShotPath.clear(level, player, carriages, ShotPath.samples(f -> toWorld(new Vector3d(a).lerp(b, f))),
                    toWorld(lensFor(b, new Vec3(3.0, 0.0, -side * 0.6))));
            }
            case TUNNEL_ABOVE_BACK -> {
                Double roof = roofAboveBody();
                if (roof == null || carriage == null) return false;
                lens = new Vector3d(bodyLocal.x + 2.0, roof + 2.0, bodyLocal.z);
                lensEnd = new Vector3d(bodyLocal.x - 8.0, roof + 2.0, bodyLocal.z);
                aimOffset = new Vector3d(18.0, -1.5, 0.0);
                Vector3d a = lens;
                Vector3d b = lensEnd;
                return ShotPath.clear(level, player, carriages, ShotPath.samples(f -> toWorld(new Vector3d(a).lerp(b, f))),
                    toWorld(lensFor(b, new Vec3(3.0, -0.2, 0.0))));
            }
            case TRANSITION -> {
                lens = resolveLens(shot.offset(), false, null);
                if (lens == null) return false;
                return ShotPath.clear(level, player, carriages, List.of(lens), aimBody);
            }
            case FEATURE_TRACK -> {
                Vector3d feature = featureWorld(1.0f);
                if (feature == null) return false;
                double up = featureKind == DeathScenePacket.Target.Kind.GROUP ? 9.0 : 4.0;
                double across = featureKind == DeathScenePacket.Target.Kind.GROUP ? 26.0 : 14.0;
                aimWorld = new Vector3d(feature.x, feature.y + LOOK_Y_OFFSET, feature.z);
                for (double side : new double[] {1.0, -1.0}) {
                    Vector3d candidate = lensFor(feature, new Vec3(0.0, up, side * across));
                    if (ShotPath.clear(level, player, carriages, List.of(candidate), aimWorld, true)) {
                        lens = candidate;
                        return true;
                    }
                }
                LOGGER.info("[DungeonTrain] Death scene feature shot skipped: no clear lens beside {} at ({}, {}, {})",
                    featureKind, (int) feature.x, (int) feature.y, (int) feature.z);
                return false;
            }
            case WORLD -> {
                // Phase 2 is about the place: lens and aim hang off where the player died, in the
                // world, not off the body the carriage has since carried on.
                lens = resolveLens(shot.offset(), false, deathSpot);
                if (lens == null) return false;
                aimWorld = new Vector3d(deathSpot.x, deathSpot.y + LOOK_Y_OFFSET, deathSpot.z);
                return ShotPath.clear(level, player, carriages, List.of(lens), aimWorld, true);
            }
            case WORLD_DOLLY -> {
                lens = resolveLens(shot.offset(), false, deathSpot);
                lensEnd = resolveLens(shot.offsetEnd(), false, deathSpot);
                if (lens == null || lensEnd == null) return false;
                aimWorld = new Vector3d(deathSpot.x, deathSpot.y + LOOK_Y_OFFSET, deathSpot.z);
                Vector3d a = lens;
                Vector3d b = lensEnd;
                return ShotPath.clear(level, player, carriages, ShotPath.samples(f -> new Vector3d(a).lerp(b, f)), aimWorld, true);
            }
            case WORLD_ARC -> {
                lens = resolveLens(shot.offset(), false, deathSpot);
                if (lens == null) return false;
                aimWorld = new Vector3d(deathSpot.x, deathSpot.y + LOOK_Y_OFFSET, deathSpot.z);
                Vector3d a = lens;
                Vector3d c = deathSpot;
                return ShotPath.clear(level, player, carriages, ShotPath.samples(f -> arcAt(c, a, shot.sweepDegrees(), f)), aimWorld, true);
            }
            case TUNNEL_WINDOW_OUT -> {
                CarriageWindow window = windowForBody();
                if (window == null || carriage == null) return false;
                double side = window.side();
                lens = lensFor(bodyLocal, new Vec3(0.0, 1.2, side * Math.max(0.5, window.wallAcross() - 0.8)));
                aimLocal = lensFor(lens, new Vec3(0.0, 0.0, side * 12.0));
                return ShotPath.clear(level, player, carriages, List.of(toWorld(lens)), toWorld(lensFor(lens, new Vec3(0.0, 0.0, side * 1.5))));
            }
            case TUNNEL_ROOF -> {
                Double roof = roofAboveBody();
                if (roof == null || carriage == null) return false;
                lens = new Vector3d(bodyLocal.x, roof + 1.2, bodyLocal.z);
                aimLocal = lensFor(lens, new Vec3(14.0, -2.0, 0.0));
                return ShotPath.clear(level, player, carriages, List.of(toWorld(lens)), toWorld(lensFor(lens, new Vec3(3.0, -0.3, 0.0))));
            }
            case TUNNEL_COUPLING -> {
                if (carriage == null) return false;
                lens = lensFor(bodyLocal, new Vec3(-7.0, 1.5, 0.0));
                aimLocal = lensFor(lens, new Vec3(-14.0, -0.5, 0.0));
                return ShotPath.clear(level, player, carriages, List.of(toWorld(lens)), toWorld(lensFor(lens, new Vec3(-3.0, -0.1, 0.0))));
            }
        }
        return false;
    }

    /**
     * The lens has ended up inside a block: a train-locked lens carried into a hillside, a
     * world-locked lens a carriage rolled through, an orbit swept past a wall. Cut away from it.
     */
    private static boolean lensRanIntoBlock(DeathShot shot) {
        if (shot.kind() == DeathShot.Kind.POV) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        CinematicCameraController.Pose now = pose(1.0f);
        if (now == null) return false;
        Vec3 l = now.pos();
        List<CarriageOcclusion.Carriage> carriages = CarriageOcclusion.gatherNearby(mc.level, l, CARRIAGE_SCAN_RADIUS, 1.0f);
        return ShotPath.insideBlock(mc.level, carriages, new Vector3d(l.x, l.y, l.z));
    }

    /** The top of the carriage roof above the body, in carriage-space Y, or null with no roof within 10 blocks. */
    private static Double roofAboveBody() {
        CarriageOcclusion.Carriage own = ownCarriage();
        if (own == null) return null;
        Vector3d bodyW = bodyWorld();
        Vec3 local = own.pose().transformPositionInverse(new Vec3(bodyW.x, bodyW.y, bodyW.z));
        int bx = (int) Math.floor(local.x);
        int bz = (int) Math.floor(local.z);
        for (int dy = 2; dy <= 10; dy++) {
            int y = (int) Math.floor(local.y) + dy;
            if (CarriageOcclusion.solidAt(own, new net.minecraft.core.BlockPos(bx, y, bz))) return bodyLocal.y + dy + 1.0;
        }
        return null;
    }

    private static CarriageOcclusion.Carriage ownCarriage() {
        Minecraft mc = Minecraft.getInstance();
        if (carriage == null || bodyLocal == null || mc.level == null) return null;
        Vector3d bodyW = bodyWorld();
        Vec3 bodyVec = new Vec3(bodyW.x, bodyW.y, bodyW.z);
        return CarriageOcclusion.carriageAt(CarriageOcclusion.gatherNearby(mc.level, bodyVec, 16.0, 1.0f), bodyVec);
    }

    // ── orbits ───────────────────────────────────────────────────────────

    /** The orbit subject's world position now (null if it is gone). */
    private static Vector3d subjectWorld(DeathShot shot, float partialTick) {
        return switch (shot.target()) {
            case DEATH_SPOT -> deathSpot == null ? null : new Vector3d(deathSpot);
            case KILLER -> entityWorld(killer, partialTick);
            case CONTACT -> entityWorld(contact, partialTick);
            case RANDOM_MOB -> entityWorld(randomMob, partialTick);
            case FEATURE -> featureWorld(partialTick);
            case BODY -> bodyRender(partialTick);
        };
    }

    private static Vector3d entityWorld(Entity entity, float partialTick) {
        if (entity == null || !entity.isAlive()) return null;
        Vec3 p = entity.getPosition(partialTick);
        return new Vector3d(p.x, p.y, p.z);
    }

    /** Pick the orbit's clear arc at the widest radius that has one. False when no angle at all is clear. */
    private static boolean setUpOrbit(DeathShot shot) {
        if (shot.target() == DeathShot.Target.RANDOM_MOB) randomMob = pickRandomMob();
        Vector3d subject = subjectWorld(shot, 1.0f);
        if (subject == null) return false;
        double height = shot.offset().y;
        for (double radius : ORBIT_RADII) {
            for (double h : new double[] {height, height * 0.5, 0.8, height * 1.6, 0.3}) {
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
        if (shot.target() == DeathShot.Target.BODY) {
            // The body stands in air, so a lens just above its head looking down at it is always
            // clear: the shot of where you died is never dropped for want of an angle.
            orbitSweep = Math.toRadians(90.0);
            orbitStart = -orbitSweep / 2.0;
            orbitRadius = 0.6;
            orbitHeight = 1.9;
            return true;
        }
        return false;
    }

    /** Any living creature (not a player) riding a carriage within range of the death spot, at random. */
    private static Entity pickRandomMob() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || deathSpot == null) return null;
        List<Entity> riders = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || e == mc.player || !living.isAlive()) continue;
            if (e.distanceToSqr(deathSpot.x, deathSpot.y, deathSpot.z) > RANDOM_MOB_RANGE * RANDOM_MOB_RANGE) continue;
            if (Sable.HELPER.getTrackingSubLevel(e) == null) continue;
            riders.add(e);
        }
        if (riders.isEmpty()) return null;
        return riders.get(mc.level.random.nextInt(riders.size()));
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
        Minecraft mc = Minecraft.getInstance();
        List<CarriageOcclusion.Carriage> carriages = mc.level == null ? List.of()
            : CarriageOcclusion.gatherNearby(mc.level, new Vec3(subject.x, subject.y, subject.z), CARRIAGE_SCAN_RADIUS, 1.0f);
        for (int i = 0; i < ORBIT_SAMPLES; i++) {
            double angle = i * 2.0 * Math.PI / ORBIT_SAMPLES;
            Vector3d lensAt = orbitLens(subject, radius, height, angle);
            boolean open = mc.level == null || !ShotPath.insideBlock(mc.level, carriages, lensAt);
            clear[i] = open && clearView(subject, lensAt);
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
        List<CarriageOcclusion.Carriage> carriages = CarriageOcclusion.gatherNearby(level, from, CARRIAGE_SCAN_RADIUS, 1.0f);
        return ShotPath.clearView(level, player, carriages, subject, lensW);
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
     * A lens at {@code offset} from the body (or from {@code worldAnchor} when given), nudged out of
     * any carriage block it sits in; null when terrain stands between the subject and it (that shot
     * cannot be had). Carriage space when {@code carriageSpace} and a carriage is pinned, else world.
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
        Vec3 wantVec = new Vec3(wantW.x, wantW.y, wantW.z);
        // Terrain between the subject and the lens: the shot cannot be had from there. Never pull
        // the lens back to the subject's side of a hillside, that is how a camera ends up inside a
        // mountain; the caller skips the shot and the tunnel set takes over.
        if (ShotPath.terrainBetween(level, from, wantVec)) return null;
        Vec3 clipped = wantVec;
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

    /** The wall-and-window read for the body's own carriage, or null when it is not on one. */
    private static CarriageWindow windowForBody() {
        if (carriage == null || bodyLocal == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Vector3d bodyW = bodyWorld();
        Vec3 bodyVec = new Vec3(bodyW.x, bodyW.y, bodyW.z);
        List<CarriageOcclusion.Carriage> carriages = CarriageOcclusion.gatherNearby(mc.level, bodyVec, 16.0, 1.0f);
        CarriageOcclusion.Carriage own = CarriageOcclusion.carriageAt(carriages, bodyVec);
        if (own == null) return null;
        Vec3 local = own.pose().transformPositionInverse(bodyVec);
        return CarriageWindow.find(CarriageWindow.of(own), new Vector3d(local.x, local.y, local.z));
    }

    private static Vec3 toVec(Vector3d v) {
        return new Vec3(v.x, v.y, v.z);
    }

    /** Pure: the lens position for a body position and an offset in the same space. */
    static Vector3d lensFor(Vector3d body, Vec3 offset) {
        return new Vector3d(body.x + offset.x, body.y + offset.y, body.z + offset.z);
    }

    /** Pure: constant speed, easing out over the last part of the move. */
    static double easeOut(double f) {
        double c = Mth.clamp(f, 0.0, 1.0);
        return 1.0 - (1.0 - c) * (1.0 - c);
    }

    /** Pure: lens between two positions at {@code f} of the way, constant speed then slowing into the end. */
    static Vector3d dollyAt(Vector3d from, Vector3d to, double f) {
        return new Vector3d(from).lerp(to, easeOut(f));
    }

    /**
     * Pure: lens on an arc around {@code centre}, starting at {@code start} and turning
     * {@code sweepDegrees} over the move at constant speed. Height and radius are those of
     * {@code start}. (The train shots run at constant speed end to end; only the carriage zoom
     * eases into its end.)
     */
    static Vector3d arcAt(Vector3d centre, Vector3d start, double sweepDegrees, double f) {
        double dx = start.x - centre.x;
        double dz = start.z - centre.z;
        double radius = Math.sqrt(dx * dx + dz * dz);
        double angle = Math.atan2(dz, dx) + Math.toRadians(sweepDegrees) * Mth.clamp(f, 0.0, 1.0);
        return new Vector3d(centre.x + radius * Math.cos(angle), start.y, centre.z + radius * Math.sin(angle));
    }

    /** The camera pose for this render frame, or null when the player's own view should show. */
    public static CinematicCameraController.Pose pose(float partialTick) {
        DeathShot shot = current();
        if (shot == null) return null;
        if (shot.kind() == DeathShot.Kind.POV) return povPose(partialTick);
        double t = shotTicks + partialTick;
        Vector3d bodyW = bodyRender(partialTick);
        Vector3d aimBody = new Vector3d(bodyW.x, bodyW.y + LOOK_Y_OFFSET, bodyW.z);
        Vector3d lensW;
        Vector3d aim = aimBody;
        switch (shot.kind()) {
            case ORBIT, ORBIT_LOOP -> {
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
                    // The lens was riding the carriage; let it go gently: blend from where the
                    // ride would still carry it into the spot it holds in the world.
                    if (dollyHold == null) dollyHold = toWorldRender(lensEnd, partialTick);
                    Vector3d riding = toWorldRender(lensEnd, partialTick);
                    double f = Mth.clamp((t - DOLLY_TICKS) / DOLLY_BLEND_TICKS, 0.0, 1.0);
                    lensW = new Vector3d(riding).lerp(dollyHold, easeOut(f));
                }
            }
            case WORLD_DOLLY -> {
                if (lens == null || lensEnd == null) return null;
                lensW = new Vector3d(lens).lerp(lensEnd, Mth.clamp(t / Math.max(1.0, shot.holdTicks()), 0.0, 1.0));
                if (aimWorld != null) aim = aimWorld;
            }
            case WORLD_ARC -> {
                if (lens == null || deathSpot == null) return null;
                lensW = arcAt(deathSpot, lens, shot.sweepDegrees(), t / Math.max(1.0, shot.holdTicks()));
                if (aimWorld != null) aim = aimWorld;
            }
            case TRAIN_LOCKED -> {
                if (lens == null) return null;
                lensW = toWorldRender(lens, partialTick);
            }
            case FEATURE_TRACK -> {
                if (lens == null) return null;
                lensW = lens;
                Vector3d feature = featureWorld(partialTick);
                if (feature != null) aim = new Vector3d(feature.x, feature.y + LOOK_Y_OFFSET, feature.z);
                else if (aimWorld != null) aim = aimWorld;
            }
            case TUNNEL_WINDOW_OUT, TUNNEL_ROOF, TUNNEL_COUPLING, TUNNEL_EYES -> {
                if (lens == null || aimLocal == null) return null;
                lensW = toWorldRender(lens, partialTick);
                aim = toWorldRender(aimLocal, partialTick);
            }
            case TUNNEL_RISE, TUNNEL_ABOVE_BACK -> {
                if (lens == null || lensEnd == null || aimOffset == null) return null;
                Vector3d local = new Vector3d(lens).lerp(lensEnd, Mth.clamp(t / Math.max(1.0, shot.holdTicks()), 0.0, 1.0));
                lensW = toWorldRender(local, partialTick);
                aim = toWorldRender(new Vector3d(local).add(aimOffset), partialTick);
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

    /**
     * The player's own view, but placed through the carriage's frame-interpolated render pose.
     * The body itself is re-pinned to the carriage once per tick from the server-synced pose,
     * which lands in uneven bursts, so the vanilla eye camera jitters; this follows the carriage
     * the way it is drawn. Look angles stay the player's own, and the death roll still applies
     * (it is added after the camera is placed). Null off the train: vanilla does fine there.
     */
    private static CinematicCameraController.Pose povPose(float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || carriage == null || bodyLocal == null) return null;
        Vector3d eye = toWorldRender(bodyLocal, partialTick);
        return new CinematicCameraController.Pose(
            new Vec3(eye.x, eye.y + player.getEyeHeight(), eye.z),
            player.getViewYRot(partialTick), player.getViewXRot(partialTick));
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
