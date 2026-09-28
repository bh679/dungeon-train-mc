package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.physics.config.PhysicsConfigData;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adaptively lowers Sable's per-level physics {@code substepsPerTick} on long
 * trains to keep server-tick cost under budget.
 *
 * <p><b>Why (issue #642, memory {@code project_sable_physics_shared_scene_resident_scaling}).</b>
 * Sable runs one Rapier scene per {@link ServerLevel} and steps every resident
 * carriage sub-level together; measured live, {@code MSPT ≈ 26.4ms + 0.65ms ×
 * resident-sub-levels}. {@code SubLevelPhysicsSystem.tickPipelinePhysics} reads
 * {@code config.substepsPerTick} live as its loop bound and runs the <em>entire</em>
 * per-substep block — the four {@code getAllSubLevels()} pre-physics loops plus
 * the native {@code physicsTick()} ({@code Rapier3D.step}) — that many times.
 * Dropping 2→1 therefore halves the whole physics cost, native and Java alike.</p>
 *
 * <p><b>Why adaptive.</b> Substeps only matter to Rapier's solver, and DT's scene has
 * nothing for it to solve: every carriage is a kinematic body teleported once per tick
 * from {@code SableManagedShip.applyTickOutput}, with no constraints, ropes, boxes or
 * dynamic bodies, and riders/mobs/loot meet the deck through Sable's own entity
 * collision, which reads the pose rather than solver output. A single substep is
 * therefore free of gameplay cost for a real train; the tuner still keeps Sable's
 * baseline for a tiny train (a handful of sub-levels costs nothing either way) and a
 * hysteresis band avoids flapping at the boundary.</p>
 *
 * <p><b>Why the band sits at 12/8.</b> The original 24/18 was set for dedicated servers
 * and never engaged in single-player, whose integrated-server view distance caps the
 * resident train at ~18–22 sub-levels — exactly the regime where a survival rider
 * mines and fights. Measured there (2026-09-18): ~70% of the server thread inside
 * {@code Rapier3D.step}, MSPT 40–105 ms, and every survival dig landing seconds late
 * because {@code ServerPlayerGameMode} counts break time in <em>server</em> ticks.
 * Twelve engages for any real train while leaving the 3–8 sub-level spawn-up phase
 * alone.</p>
 *
 * <p><b>Why this is safe (and not the #642 freeze that crashes).</b> This only
 * mutates a per-level config field via public Sable API — it never removes a body
 * from the physics scene, never unloads a sub-level, and never touches residency,
 * so the cull→reload jitter/vanish fixes (#628/#630/#623) are untouched. The field
 * is read live each tick and {@link SubLevelPhysicsSystem#getConfig()} returns the
 * live object, so mutating it takes effect next tick with no {@code updateConfigFrom}
 * (that only pushes solver params to native).</p>
 *
 * <p>The durable fix — dropping the ~35/38 needlessly-simulated bodies entirely —
 * is a separate DT-side mixin follow-up; substeps only halves the per-body cost, it
 * does not stop the O(bodies) climb.</p>
 */
public final class PhysicsSubstepTuner {

    private static final Logger JITTER_LOGGER =
        LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /**
     * Drop to {@link #LOW_SUBSTEPS} at or above this resident sub-level count, and
     * restore the captured baseline at or below {@link #LOW_WATER}; the gap between
     * them is hysteresis. Read the live {@code [mspt]} line's {@code physMs=} field to
     * re-tune: it is the per-tick cost of the substep block this class halves.
     * Package-private so the pure-logic test tracks them.
     */
    static final int HIGH_WATER = 12;
    static final int LOW_WATER = 8;
    /** The reduced substep count applied on long trains. */
    static final int LOW_SUBSTEPS = 1;

    /**
     * Master switch. {@code /dungeontrain debug substep-tuner <on|off>} drives the Gate 2
     * matched-toggle A/B (same ride, compare {@code [mspt] physMs=} at equal {@code carriages=})
     * and doubles as a safety valve. When off, the next reconcile restores the captured baseline.
     * Command thread writes, server thread reads — hence {@code volatile}, as with
     * {@link PhysicsFreezeController#ENABLED}.
     */
    public static volatile boolean ENABLED = true;

    // Last reconcile snapshot, for the status command.
    private static volatile int lastResidents;
    private static volatile int lastSubsteps;

    /**
     * The level's original {@code substepsPerTick}, captured once (before we ever
     * lower it) so we restore Sable's genuine default rather than assuming 2.
     */
    private static final Map<ResourceKey<Level>, Integer> BASELINE_BY_LEVEL = new HashMap<>();
    /** Levels currently held below baseline — the set we may need to restore. */
    private static final Set<ResourceKey<Level>> LOWERED_LEVELS = new HashSet<>();

    private PhysicsSubstepTuner() {}

    /**
     * Pure decision core (no Minecraft/Sable types, so unit-testable — mirrors the
     * {@code TrainTickEvents.isTrainPassengerId} pattern). Returns the
     * {@code substepsPerTick} to run given the resident sub-level count, the level's
     * captured {@code baseline}, and whether the level is currently held below it:
     * <ul>
     *   <li>{@code count >= HIGH_WATER} → {@link #LOW_SUBSTEPS};</li>
     *   <li>{@code count <= LOW_WATER} → {@code baseline};</li>
     *   <li>inside the band → hold the current mode (hysteresis).</li>
     * </ul>
     * If {@code baseline <= LOW_SUBSTEPS} there is nothing to reduce.
     */
    static int decideSubsteps(int residentCount, int baseline, boolean currentlyLowered) {
        if (baseline <= LOW_SUBSTEPS) return baseline;
        if (residentCount >= HIGH_WATER) return LOW_SUBSTEPS;
        if (residentCount <= LOW_WATER) return baseline;
        return currentlyLowered ? LOW_SUBSTEPS : baseline;
    }

    /**
     * Reconcile {@code level}'s Sable {@code substepsPerTick} against its current
     * resident sub-level count. Idempotent and self-healing (re-applies if Sable
     * resets the field); a no-op when the level has no Sable physics system yet.
     * Called on a modest cadence from {@code TrainTickEvents} only for levels that
     * currently hold trains, so it never touches unrelated dimensions.
     */
    public static void reconcile(ServerLevel level, int residentCount) {
        lastResidents = residentCount;
        if (!ENABLED) {
            restoreIfTuned(level);
            lastSubsteps = currentSubsteps(level);
            return;
        }
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) return;
        PhysicsConfigData config = system.getConfig();
        if (config == null) return;
        lastSubsteps = config.substepsPerTick;

        ResourceKey<Level> key = level.dimension();
        Integer baseline = BASELINE_BY_LEVEL.get(key);
        if (baseline == null) {
            // Capture only a sane baseline (Sable's default is 2). A not-yet-
            // initialised or already-1 field gives us nothing to reduce; skip and
            // retry next cadence rather than record a garbage baseline.
            if (config.substepsPerTick <= LOW_SUBSTEPS) return;
            baseline = config.substepsPerTick;
            BASELINE_BY_LEVEL.put(key, baseline);
        }

        int current = config.substepsPerTick;
        int desired = decideSubsteps(residentCount, baseline, LOWERED_LEVELS.contains(key));
        if (desired == current) return;

        config.substepsPerTick = desired; // read live by tickPipelinePhysics; effective next tick
        lastSubsteps = desired;
        if (desired < baseline) {
            LOWERED_LEVELS.add(key);
        } else {
            LOWERED_LEVELS.remove(key);
        }
        JITTER_LOGGER.debug("[substep-tuner] dim={} residents={} baseline={} {}->{}",
            key.location(), residentCount, baseline, current, desired);
    }

    /**
     * Restore {@code level}'s captured baseline substeps if we previously lowered
     * it — for the no-trains path, so a level we tuned returns to full fidelity when
     * its train despawns. A no-op for levels we never lowered.
     */
    public static void restoreIfTuned(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        if (!LOWERED_LEVELS.remove(key)) return;
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) return;
        PhysicsConfigData config = system.getConfig();
        Integer baseline = BASELINE_BY_LEVEL.get(key);
        if (config == null || baseline == null || config.substepsPerTick == baseline) return;
        int prev = config.substepsPerTick;
        config.substepsPerTick = baseline;
        JITTER_LOGGER.debug("[substep-tuner] dim={} train gone — restore {}->{}",
            key.location(), prev, baseline);
    }

    /**
     * The level's live Sable {@code substepsPerTick} — what {@code tickPipelinePhysics} will run
     * next tick — or {@code -1} when the level has no physics system / config yet. Read by the
     * {@code [mspt]} line so the A/B can be checked from the log.
     */
    public static int currentSubsteps(ServerLevel level) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) return -1;
        PhysicsConfigData config = system.getConfig();
        return config == null ? -1 : config.substepsPerTick;
    }

    /** Snapshot from the last {@link #reconcile} — for {@code /dungeontrain debug substep-tuner status}. */
    public static int lastResidents() { return lastResidents; }
    public static int lastSubsteps() { return lastSubsteps; }
}
