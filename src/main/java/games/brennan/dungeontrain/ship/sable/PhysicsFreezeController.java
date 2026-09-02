package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issue #646 (soft-freeze) — each tick, keeps a carriage's physics active only while a client is
 * watching it (or a live entity is standing on it), and {@link PhysicsFreeze#freeze}s (parks) the
 * rest. Physics cost then scales with <em>watched</em> carriages, not total train length. Mirrors
 * {@link PhysicsSubstepTuner}'s "reconcile a live Sable structure each tick" shape.
 *
 * <p><b>Active predicate = tracked OR live-entity-aboard.</b> {@code getTrackingPlayers()} is the
 * self-scaling, correct signal — a carriage no client tracks is rendered by nobody, so parking its
 * body is invisible; anything a client renders or stands on is tracked and stays active. The
 * live-entity scan (run only for <em>untracked</em> candidates) keeps the body under a mob/item in
 * world space so it never drops through. Carriage-<em>contents</em> entities live at shipyard coords
 * and don't ride the body, so they don't block freezing.</p>
 *
 * <p><b>Hysteresis: eager unfreeze, lazy freeze.</b> A carriage becomes active → unfrozen the same
 * tick (ready before it can be seen); it must be continuously inactive for {@link #FREEZE_GRACE_TICKS}
 * before we freeze, so a brief tracking flicker never flaps the body.</p>
 *
 * <p><b>Only the body parks; the pose keeps following the train.</b> {@link PhysicsFreeze#followParked}
 * writes the driver's output into the frozen sub-level's Java {@code logicalPose()} every tick (no
 * native call), so Sable's tracking, its chunk tickets, serialization and DT's {@code worldAABB()}
 * readers all see a parked carriage where the train actually has it. Tracking is the one that
 * matters: it measures the player's distance to that pose, so a stale parked pose let a group's true
 * slot come into view without the group ever being re-tracked — a group-sized hole in the train.</p>
 *
 * <p><b>Unsettled carriages are never frozen.</b> A carriage that hasn't reached
 * {@code placedSuccessfully} is still being nudged into its seam by
 * {@link games.brennan.dungeontrain.train.TrainCarriageAppender#runPlacementCollisionTracker}, which
 * reads back the body's {@code worldAABB()} every tick to decide the next nudge. Before the pose
 * followed the train, freezing skipped the per-tick teleport, so the AABB stopped moving, the tracker
 * never saw a clean tick, and it kept shifting {@code spawnWorldPos} blind until the 200-tick safety
 * valve fired — leaving up to 25 blocks of accumulated offset that materialised as a wide seam the
 * moment the body unfroze, and collapsing the appender's per-lane spawn rate. A group appended behind
 * a backward-riding player is untracked by construction, so this was the backward-generation stall.
 * The exemption is kept: a settling group still wants its real body in play, and at most one
 * unsettled group per lane is ever in flight (the appender's placement gate enforces it), so it
 * costs nothing measurable.</p>
 *
 * <p><b>...and neither are their neighbours.</b> A seam is a relationship between two carriages, so
 * exempting only the unsettled one just breaks the assumption from the other side: it keeps
 * advancing with the train while an already-settled neighbour is parked, the pair drift at train
 * speed, and every nudge is undone before the next reading. See {@link #settlingAnchors}.</p>
 */
public final class PhysicsFreezeController {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /**
     * Master switch. Flip via {@code /dungeontrain physicsfreeze <on|off>} for the Gate 2
     * matched-toggle A/B measurement, and as a safety valve. When off, the next reconcile unfreezes
     * every currently-frozen carriage.
     */
    public static volatile boolean ENABLED = true;

    /** Consecutive inactive ticks required before freezing (lazy freeze; unfreeze is immediate). */
    static final int FREEZE_GRACE_TICKS = 40;

    /** Period (ticks) for the steady-state {@code [freeze]} log line — matches the {@code [mspt]} cadence. */
    private static final int LOG_PERIOD_TICKS = 40;

    // Last reconcile snapshot, for the debug command + log line.
    private static volatile int lastResident;
    private static volatile int lastActive;
    private static volatile int lastFrozen;

    private PhysicsFreezeController() {}

    /** Actions the pure decision core can pick. */
    enum Action { FREEZE, UNFREEZE, NONE }

    /**
     * Pure decision core (no Minecraft/Sable types, unit-testable — mirrors
     * {@link PhysicsSubstepTuner#decideSubsteps}). Eager unfreeze, lazy freeze:
     * <ul>
     *   <li>active OR settling + frozen → {@code UNFREEZE} (immediately);</li>
     *   <li>inactive + not frozen + inactive ≥ {@link #FREEZE_GRACE_TICKS} → {@code FREEZE};</li>
     *   <li>otherwise hold.</li>
     * </ul>
     *
     * <p>{@code settling} is a hard exemption, not a tie-breaker: an unsettled carriage — and its
     * immediate neighbours, which are the other end of the seam it is settling against — must keep
     * their per-tick teleport so the placement tracker can see its nudges land, and so the pair
     * never drift apart at train speed (see the class javadoc and {@link #settlingAnchors}). It
     * behaves exactly like {@code activeNow}, including immediate unfreeze.</p>
     */
    static Action decide(boolean activeNow, boolean settling, int ticksInactive, boolean currentlyFrozen) {
        if (activeNow || settling) return currentlyFrozen ? Action.UNFREEZE : Action.NONE;
        if (currentlyFrozen) return Action.NONE;
        return ticksInactive >= FREEZE_GRACE_TICKS ? Action.FREEZE : Action.NONE;
    }

    /**
     * Reconcile every resident carriage's physics-active state. Called each tick from
     * {@link games.brennan.dungeontrain.event.TrainTickEvents} while a train is present. Runs in
     * {@code LevelTickEvent.Post} (after the physics tick, single-threaded), so the frozen flags it
     * writes are stable for the next physics tick's readers.
     */
    public static void reconcile(ServerLevel level, Map<UUID, List<Trains.Carriage>> trainsById) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) return;

        int resident = 0, active = 0, frozen = 0;
        for (List<Trains.Carriage> train : trainsById.values()) {
            Set<Integer> settlingAnchors = settlingAnchors(train);
            for (Trains.Carriage c : train) {
                if (!(c.ship() instanceof SableManagedShip ship)) continue;
                ServerSubLevel sl = ship.subLevel();
                if (sl.isRemoved()) continue; // non-resident: stale wrapper, leave it alone
                resident++;

                boolean frozenNow = PhysicsFreeze.isFrozen(sl);

                if (!ENABLED) {
                    if (frozenNow) PhysicsFreeze.unfreeze(sl);
                    PhysicsFreeze.setInactiveTicks(sl, 0);
                    continue;
                }

                // Short-circuits: the set lookup is cheap, and the (bounded) entity scan runs only
                // for untracked candidates.
                boolean settling = settlingAnchors.contains(c.provider().getPIdx());
                boolean activeNow = settling
                    || !sl.getTrackingPlayers().isEmpty()
                    || hasLiveEntityAboard(level, ship);
                if (activeNow) active++;

                // An unsettled carriage also holds its inactive counter at zero, so it doesn't
                // freeze the instant the tracker marks it placed — it gets a fresh grace window.
                int inactive = activeNow ? 0 : PhysicsFreeze.inactiveTicks(sl) + 1;
                PhysicsFreeze.setInactiveTicks(sl, inactive);

                switch (decide(activeNow, settling, inactive, frozenNow)) {
                    case FREEZE -> PhysicsFreeze.freeze(sl, level.getGameTime());
                    case UNFREEZE -> {
                        // The pose has followed the train the whole time it was parked (see
                        // PhysicsFreeze.followParked); only the native body is behind, by roughly
                        // train speed × parked ticks, and applyTickOutput's next teleport closes
                        // exactly this distance. Logged so the resume can be checked in a ride log:
                        // bodyLagBlocks ≈ 0.1 × parkedTicks at the default 2 blocks/s.
                        LOGGER.debug("[freeze.unpark] pIdx={} trainId={} parkedTicks={} bodyLagBlocks={}",
                            c.provider().getPIdx(), c.provider().getTrainId(),
                            PhysicsFreeze.parkedTicks(sl, level.getGameTime()),
                            String.format("%.2f", PhysicsFreeze.bodyLagBlocks(sl)));
                        PhysicsFreeze.unfreeze(sl);
                    }
                    case NONE -> { }
                }
                if (PhysicsFreeze.isFrozen(sl)) frozen++;
            }
        }

        lastResident = resident;
        lastActive = active;
        lastFrozen = frozen;

        if (frozen > 0 && level.getGameTime() % LOG_PERIOD_TICKS == 0) {
            LOGGER.debug("[freeze] dim={} resident={} active={} frozen={}",
                level.dimension().location(), resident, active, frozen);
        }
    }

    /**
     * Anchors that must keep ticking: every group of a train that has any unsettled group (see
     * {@link #anchorsToKeepTicking}). The original rule was "the unsettled group plus its
     * immediate neighbours", which the paragraph below explains; the remote-player catch-up
     * showed a parked group two or more strides away can sit inside the spawn zone too.
     *
     * <p><b>Why the neighbours.</b> The placement tracker settles a group by measuring the seam
     * against its train-facing neighbour and nudging. That model assumes the two share a motion
     * frame. Exempting only the unsettled group breaks the assumption in the other direction: the
     * unsettled group keeps advancing with the train while a neighbour that has already reached
     * {@code placedSuccessfully} can be parked, so the pair drift together or apart at train speed
     * and every nudge is undone before the next reading. Observed live as a group oscillating
     * {@code colliding ↔ too-close} against its settled neighbour for the full settle budget. A seam
     * is a relationship between two carriages, so both ends of it have to be live.</p>
     *
     * <p>Bounded by construction: at most one group per lane is unsettled at a time, so this exempts
     * a handful of anchors, not the train.</p>
     */
    private static Set<Integer> settlingAnchors(List<Trains.Carriage> train) {
        Set<Integer> unsettled = new HashSet<>();
        Set<Integer> all = new HashSet<>();
        for (Trains.Carriage c : train) {
            int anchor = c.provider().getPIdx();
            all.add(anchor);
            if (!c.provider().isPlacedSuccessfully()) unsettled.add(anchor);
        }
        return anchorsToKeepTicking(unsettled, all);
    }

    /**
     * Pure core of {@link #settlingAnchors}: while any group of a train is unsettled, EVERY group
     * of that train keeps ticking; once none is, nothing is exempt on this account.
     *
     * <p><b>Why the whole train, not just the neighbours.</b> A parked body physically stays where
     * it was while its canonical position keeps advancing, so after a minute it sits ~100 blocks
     * behind the train's frame. A remote player's catch-up run spawns groups into exactly that
     * zone (the lane places against a live reference, but the collision check sees the parked
     * body's real box): the burst leader read "colliding" against a group two strides above,
     * nudged −0.5, and the train's own +0.5 of travel undid it — for the whole settle budget, on
     * sixteen groups in a row. Waking the whole train while a lane is spawning is bounded by the
     * force-load window (only resident groups can be parked) and lasts 40 ticks past the last
     * spawn, after which parking resumes.</p>
     */
    static Set<Integer> anchorsToKeepTicking(Set<Integer> unsettled, Set<Integer> all) {
        if (unsettled.isEmpty()) return Set.of();
        Set<Integer> out = new HashSet<>(all);
        out.addAll(unsettled);
        return out;
    }

    /**
     * True if a world-space living or item entity is standing on this carriage — it relies on the
     * carriage's Rapier collision, so freezing would drop it through. Excludes carriage-contents
     * entities (shipyard-coord, don't ride the body). Called only for untracked carriages.
     */
    private static boolean hasLiveEntityAboard(ServerLevel level, SableManagedShip ship) {
        AABBdc b = ship.worldAABB();
        AABB box = new AABB(b.minX() - 1, b.minY() - 1, b.minZ() - 1,
            b.maxX() + 1, b.maxY() + 2, b.maxZ() + 1);
        return !level.getEntitiesOfClass(Entity.class, box, PhysicsFreezeController::blocksFreeze).isEmpty();
    }

    private static boolean blocksFreeze(Entity e) {
        if (!e.isAlive()) return false;
        if (!(e instanceof LivingEntity || e instanceof ItemEntity)) return false;
        for (String tag : e.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return false;
        }
        return true;
    }

    /** Snapshot from the last {@link #reconcile} — for {@code /dungeontrain physicsfreeze status}. */
    public static int lastResident() { return lastResident; }
    public static int lastActive() { return lastActive; }
    public static int lastFrozen() { return lastFrozen; }
}
