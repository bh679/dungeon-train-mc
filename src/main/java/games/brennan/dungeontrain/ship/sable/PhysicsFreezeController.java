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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issue #646 — each tick, keeps a carriage's Rapier body only while a client is watching it (or a
 * live entity is standing on it), and {@link PhysicsFreeze#freeze}s the rest. Physics cost then
 * scales with <em>watched</em> carriages, not total train length. Mirrors
 * {@link PhysicsSubstepTuner}'s "reconcile a live Sable structure each tick" shape.
 *
 * <p><b>Active predicate = tracked OR live-entity-aboard.</b> {@code getTrackingPlayers()} is the
 * self-scaling, correct signal — a carriage no client tracks is rendered by nobody, so removing its
 * body is invisible; anything a client renders or stands on is tracked and stays active. The
 * live-entity scan (run only for <em>untracked</em> candidates) keeps the body under a mob/item in
 * world space so it never drops through. Carriage-<em>contents</em> entities live at shipyard coords
 * and don't ride the body, so they don't block freezing.</p>
 *
 * <p><b>Hysteresis: eager unfreeze, lazy freeze.</b> A carriage becomes active → unfrozen the same
 * tick (ready before it can be seen); it must be continuously inactive for {@link #FREEZE_GRACE_TICKS}
 * before we freeze, so a brief tracking flicker never flaps the body.</p>
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
     *   <li>active + frozen → {@code UNFREEZE} (immediately);</li>
     *   <li>inactive + not frozen + inactive ≥ {@link #FREEZE_GRACE_TICKS} → {@code FREEZE};</li>
     *   <li>otherwise hold.</li>
     * </ul>
     */
    static Action decide(boolean activeNow, int ticksInactive, boolean currentlyFrozen) {
        if (activeNow) return currentlyFrozen ? Action.UNFREEZE : Action.NONE;
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
            for (Trains.Carriage c : train) {
                if (!(c.ship() instanceof SableManagedShip ship)) continue;
                ServerSubLevel sl = ship.subLevel();
                if (sl.isRemoved()) continue; // non-resident: stale wrapper, leave it alone
                resident++;

                boolean frozenNow = PhysicsFreeze.isFrozen(sl);

                if (!ENABLED) {
                    if (frozenNow) PhysicsFreeze.unfreeze(system, sl);
                    PhysicsFreeze.setInactiveTicks(sl, 0);
                    continue;
                }

                // Short-circuits: the (bounded) entity scan runs only for untracked candidates.
                boolean activeNow = !sl.getTrackingPlayers().isEmpty() || hasLiveEntityAboard(level, ship);
                if (activeNow) active++;

                int inactive = activeNow ? 0 : PhysicsFreeze.inactiveTicks(sl) + 1;
                PhysicsFreeze.setInactiveTicks(sl, inactive);

                switch (decide(activeNow, inactive, frozenNow)) {
                    case FREEZE -> PhysicsFreeze.freeze(system, sl);
                    case UNFREEZE -> PhysicsFreeze.unfreeze(system, sl);
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
