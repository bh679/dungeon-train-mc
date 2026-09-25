package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.worldgen.LapBand;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Per-template spawn gate: the inclusive difficulty-<em>level</em> band <b>and</b> the set of
 * per-lap {@link LapBand band occurrences} in which a weighted {@link Template} is allowed to spawn. The
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
 * bands. The {@link #DEFAULT} gate is eligible at every level and phase, so templates that
 * predate this field behave identically (full backward compatibility), and {@link #isDefault()}
 * drives JSON-emission skipping in the weight stores.</p>
 *
 * <p>The phase set is normalised to a non-empty unmodifiable {@link EnumSet}: a {@code null} or
 * empty set means "all phases" (you cannot express "eligible in zero phases" — a template that
 * should never spawn is removed or given weight 0, not gated to no phase).</p>
 */
public record TemplateGate(int minLevel, int maxLevel, Set<LapBand> phases) {

    /** Sentinel for {@link #maxLevel}: "no upper bound — eligible at every level ≥ minLevel". */
    public static final int ALL = -1;

    /**
     * Editor cap for a finite {@code minLevel}/{@code maxLevel}; matches
     * {@code VariantDifficulty.MAX_TIER} so the authoring range lines up with the rest of the
     * difficulty UI. Beyond it, authors use {@link #ALL}.
     */
    public static final int MAX_LEVEL = 1000;

    /** Every phase — the default phase set. Unmodifiable. */
    public static final Set<LapBand> ALL_PHASES =
        Collections.unmodifiableSet(EnumSet.allOf(LapBand.class));

    /** Default gate: level {@code 0..ALL}, all phases — eligible everywhere. */
    public static final TemplateGate DEFAULT = new TemplateGate(0, ALL, ALL_PHASES);

    public TemplateGate {
        if (minLevel < 0) minLevel = 0;
        if (minLevel > MAX_LEVEL) minLevel = MAX_LEVEL;
        if (maxLevel < ALL) maxLevel = ALL;
        if (maxLevel > MAX_LEVEL) maxLevel = MAX_LEVEL;
        // Enforce min ≤ max (ALL is treated as +∞). Min is the anchor: a max that has fallen
        // under it rises to meet it, never the other way — the same direction every editor
        // step resolves the pair (see withMinLevel / withMaxLevel).
        if (maxLevel != ALL && minLevel > maxLevel) maxLevel = minLevel;
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
        return minLevel == 0 && maxLevel == ALL && phases.size() == LapBand.values().length;
    }

    /** True when {@code level} falls inside the band; an {@link #ALL} max is unbounded above. */
    public boolean levelEligible(int level) {
        if (level < minLevel) return false;
        return maxLevel == ALL || level <= maxLevel;
    }

    /** True when both {@code level} and {@code phase} are eligible for this gate. */
    public boolean eligible(int level, LapBand phase) {
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
        for (LapBand p : phases) {
            if (other.phases.contains(p)) return true;                     // shared dimension
        }
        return false;
    }

    /**
     * Copy with {@code minLevel} replaced. Raising min past a finite max drags max up with it;
     * lowering min leaves max where it is. Every level editor (type menus, sheet, Stages, parts,
     * slash commands) funnels through here, so the rule holds the same way in all of them.
     */
    public TemplateGate withMinLevel(int newMin) {
        int min = Math.max(0, Math.min(MAX_LEVEL, newMin));
        int max = (maxLevel != ALL && min > maxLevel) ? min : maxLevel;
        return new TemplateGate(min, max, phases);
    }

    /**
     * Copy with {@code maxLevel} replaced. Min is max's floor: a finite value below it lands on
     * min instead, and min itself is never moved by a max edit. {@link #ALL} passes through.
     */
    public TemplateGate withMaxLevel(int newMax) {
        int max = newMax == ALL ? ALL : Math.max(minLevel, Math.min(MAX_LEVEL, newMax));
        return new TemplateGate(minLevel, max, phases);
    }

    /**
     * Cycle {@code maxLevel} up one step for a click-to-bump editor:
     * {@link #ALL} → {@code minLevel} → … → {@link #MAX_LEVEL} → {@link #ALL}. The finite range
     * starts at min, not 0, so a step can never put max under min. Shared by the template-type
     * editor and the carriage-parts editor so both step the {@code ALL}↔finite sentinel identically.
     */
    public TemplateGate incMaxLevel() {
        int next = (maxLevel == ALL) ? minLevel : (maxLevel >= MAX_LEVEL ? ALL : maxLevel + 1);
        return withMaxLevel(next);
    }

    /**
     * Cycle {@code maxLevel} down one step: {@link #ALL} → {@link #MAX_LEVEL} → … →
     * {@code minLevel} → {@link #ALL}. Everything below min is skipped.
     */
    public TemplateGate decMaxLevel() {
        int next = (maxLevel == ALL) ? MAX_LEVEL : (maxLevel <= minLevel ? ALL : maxLevel - 1);
        return withMaxLevel(next);
    }

    /**
     * Copy with {@code phase} toggled on/off. Toggling the last remaining phase off normalises back
     * to all phases (the canonical constructor's "empty ⇒ all" rule), so the gate never becomes
     * "eligible in zero phases".
     */
    public TemplateGate withPhase(LapBand phase, boolean on) {
        return withPhases(EnumSet.of(phase), on);
    }

    /**
     * {@link #withPhase} for several bands at once — an old {@code TrainPhase} token such as
     * {@code nether} names both Nether occurrences ({@link LapBand#resolve}).
     */
    public TemplateGate withPhases(Set<LapBand> bands, boolean on) {
        EnumSet<LapBand> next = EnumSet.copyOf(phases);
        if (on) next.addAll(bands); else next.removeAll(bands);
        return new TemplateGate(minLevel, maxLevel, next);
    }

    /**
     * Replace the whole phase set with the bands in {@code mask} ({@link LapBand#bit()} per band)
     * — the editor's group toggles and band picker. Rejects an empty mask rather than letting the
     * "empty ⇒ all" normalisation silently turn "no bands" into "every band".
     */
    public TemplateGate withPhaseMask(int mask) {
        EnumSet<LapBand> next = LapBand.fromMask(mask & LapBand.ALL_MASK);
        if (next.isEmpty()) throw new IllegalArgumentException("phase mask selects no bands: " + mask);
        return new TemplateGate(minLevel, maxLevel, next);
    }

    /**
     * Toggle every dimension <em>except</em> {@code keep} (whose membership is preserved) — the
     * editor's shift-click on a dimension letter, "toggle all but that one". From the all-on default
     * this solos {@code keep}; applied again it restores the rest. An empty result normalises back to
     * all dimensions (the gate's "empty ⇒ all" invariant).
     */
    public TemplateGate toggleOtherPhases(LapBand keep) {
        return toggleOtherPhases(EnumSet.of(keep));
    }

    /** {@link #toggleOtherPhases(LapBand)} keeping several bands (an old token naming more than one). */
    public TemplateGate toggleOtherPhases(Set<LapBand> keep) {
        EnumSet<LapBand> next = EnumSet.noneOf(LapBand.class);
        for (LapBand p : LapBand.values()) {
            boolean on = phases.contains(p);
            // keep: unchanged; others: flipped.
            if (keep.contains(p) ? on : !on) next.add(p);
        }
        return new TemplateGate(minLevel, maxLevel, next);
    }
}
