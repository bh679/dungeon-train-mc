package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Per-template spawn gate: the inclusive difficulty-<em>level</em> band <b>and</b> the set of
 * worldgen {@link TrainPhase phases} in which a weighted {@link Template} is allowed to spawn. The
 * generator drops out-of-band / out-of-phase templates from the candidate pool <b>before</b> the
 * weighted pick, exactly as
 * {@link games.brennan.dungeontrain.editor.VariantDifficulty} drops out-of-band mob eggs from a
 * cell's candidate pool — only one layer up (whole templates instead of one cell's candidates).
 *
 * <p>Mirrors {@link games.brennan.dungeontrain.editor.VariantDifficulty}'s conventions: a "level"
 * is the boarding-HUD <em>Diff-Level</em>
 * ({@link games.brennan.dungeontrain.difficulty.DifficultyProgression#tierForTravelled}).
 * {@link #minLevel} is a plain level (default 0 — "from the start"); {@link #maxLevel} is either a
 * level or the {@link #ALL} sentinel (default — "no upper bound"). {@link #phases} defaults to all
 * four phases. The {@link #DEFAULT} gate is eligible at every level and phase, so templates that
 * predate this field behave identically (full backward compatibility), and {@link #isDefault()}
 * drives JSON-emission skipping in the weight stores.</p>
 *
 * <p>The phase set is normalised to a non-empty unmodifiable {@link EnumSet}: a {@code null} or
 * empty set means "all phases" (you cannot express "eligible in zero phases" — a template that
 * should never spawn is removed or given weight 0, not gated to no phase).</p>
 */
public record TemplateGate(int minLevel, int maxLevel, Set<TrainPhase> phases) {

    /** Sentinel for {@link #maxLevel}: "no upper bound — eligible at every level ≥ minLevel". */
    public static final int ALL = -1;

    /**
     * Editor cap for a finite {@code minLevel}/{@code maxLevel}; matches
     * {@code VariantDifficulty.MAX_TIER} so the authoring range lines up with the rest of the
     * difficulty UI. Beyond it, authors use {@link #ALL}.
     */
    public static final int MAX_LEVEL = 100;

    /** Every phase — the default phase set. Unmodifiable. */
    public static final Set<TrainPhase> ALL_PHASES =
        Collections.unmodifiableSet(EnumSet.allOf(TrainPhase.class));

    /** Default gate: level {@code 0..ALL}, all phases — eligible everywhere. */
    public static final TemplateGate DEFAULT = new TemplateGate(0, ALL, ALL_PHASES);

    public TemplateGate {
        if (minLevel < 0) minLevel = 0;
        if (minLevel > MAX_LEVEL) minLevel = MAX_LEVEL;
        if (maxLevel < ALL) maxLevel = ALL;
        if (maxLevel > MAX_LEVEL) maxLevel = MAX_LEVEL;
        // Enforce min ≤ max (ALL is treated as +∞), mirroring VariantDifficulty.
        if (maxLevel != ALL && minLevel > maxLevel) minLevel = maxLevel;
        // Normalise the phase set: null / empty ⇒ all phases (default); else an unmodifiable copy.
        if (phases == null || phases.isEmpty()) {
            phases = ALL_PHASES;
        } else {
            phases = Collections.unmodifiableSet(EnumSet.copyOf(phases));
        }
    }

    /** Convenience: a level-only gate with all phases. */
    public static TemplateGate ofLevels(int min, int max) {
        return new TemplateGate(min, max, ALL_PHASES);
    }

    /** True when this is the no-op default (level 0..ALL, all phases) — drives JSON-emission skipping. */
    public boolean isDefault() {
        return minLevel == 0 && maxLevel == ALL && phases.size() == TrainPhase.values().length;
    }

    /** True when {@code level} falls inside the band; an {@link #ALL} max is unbounded above. */
    public boolean levelEligible(int level) {
        if (level < minLevel) return false;
        return maxLevel == ALL || level <= maxLevel;
    }

    /** True when both {@code level} and {@code phase} are eligible for this gate. */
    public boolean eligible(int level, TrainPhase phase) {
        return levelEligible(level) && phases.contains(phase);
    }

    /**
     * True when this gate and {@code other} share at least one eligible {@code (level, phase)} — i.e.
     * their difficulty-level bands intersect <b>and</b> their phase sets intersect ({@link #ALL} max is
     * treated as {@code +∞}). Powers the editor's per-stage carriage-preview fallback: a slot with
     * nothing explicitly linked to the selected stage still shows parts whose effective gate overlaps
     * the stage's gate (same level band + dimension) rather than airing out.
     */
    public boolean overlaps(TemplateGate other) {
        if (other == null) return false;
        int myMax = (maxLevel == ALL) ? Integer.MAX_VALUE : maxLevel;
        int otherMax = (other.maxLevel == ALL) ? Integer.MAX_VALUE : other.maxLevel;
        if (minLevel > otherMax || other.minLevel > myMax) return false;   // level bands disjoint
        for (TrainPhase p : phases) {
            if (other.phases.contains(p)) return true;                     // shared dimension
        }
        return false;
    }

    /** Copy with {@code minLevel} replaced (re-clamped by the canonical constructor). */
    public TemplateGate withMinLevel(int newMin) {
        return new TemplateGate(newMin, maxLevel, phases);
    }

    /** Copy with {@code maxLevel} replaced (re-clamped by the canonical constructor). */
    public TemplateGate withMaxLevel(int newMax) {
        return new TemplateGate(minLevel, newMax, phases);
    }

    /**
     * Cycle {@code maxLevel} up one step for a click-to-bump editor:
     * {@link #ALL} → 0 → 1 → … → {@link #MAX_LEVEL} → {@link #ALL} (mirrors the mob difficulty-band
     * editor's wrap). Shared by the template-type editor and the carriage-parts editor so both step
     * the {@code ALL}↔finite sentinel identically.
     */
    public TemplateGate incMaxLevel() {
        int next = (maxLevel == ALL) ? 0 : (maxLevel >= MAX_LEVEL ? ALL : maxLevel + 1);
        return withMaxLevel(next);
    }

    /** Cycle {@code maxLevel} down one step: {@link #ALL} → {@link #MAX_LEVEL} → … → 0 → {@link #ALL}. */
    public TemplateGate decMaxLevel() {
        int next = (maxLevel == ALL) ? MAX_LEVEL : (maxLevel <= 0 ? ALL : maxLevel - 1);
        return withMaxLevel(next);
    }

    /**
     * Copy with {@code phase} toggled on/off. Toggling the last remaining phase off normalises back
     * to all phases (the canonical constructor's "empty ⇒ all" rule), so the gate never becomes
     * "eligible in zero phases".
     */
    public TemplateGate withPhase(TrainPhase phase, boolean on) {
        EnumSet<TrainPhase> next = EnumSet.copyOf(phases);
        if (on) next.add(phase); else next.remove(phase);
        return new TemplateGate(minLevel, maxLevel, next);
    }

    /**
     * Toggle every dimension <em>except</em> {@code keep} (whose membership is preserved) — the
     * editor's shift-click on a dimension letter, "toggle all but that one". From the all-on default
     * this solos {@code keep}; applied again it restores the rest. An empty result normalises back to
     * all dimensions (the gate's "empty ⇒ all" invariant).
     */
    public TemplateGate toggleOtherPhases(TrainPhase keep) {
        EnumSet<TrainPhase> next = EnumSet.noneOf(TrainPhase.class);
        for (TrainPhase p : TrainPhase.values()) {
            boolean on = phases.contains(p);
            // keep: unchanged; others: flipped.
            if (p == keep ? on : !on) next.add(p);
        }
        return new TemplateGate(minLevel, maxLevel, next);
    }
}
