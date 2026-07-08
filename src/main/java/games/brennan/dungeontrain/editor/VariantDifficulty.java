package games.brennan.dungeontrain.editor;

/**
 * Per-{@link VariantState} difficulty band for mob (spawn-egg) entries: the
 * inclusive range of difficulty <em>tiers</em> (the 0,1,2… progression the
 * player experiences, post-delay — see
 * {@link games.brennan.dungeontrain.difficulty.DifficultyProgression#tierForTravelled})
 * in which the egg is allowed to spawn.
 *
 * <p>When a carriage's contents variant is rolled, mob entries whose band does
 * not contain the carriage's tier are dropped from the cell's candidate pool
 * <b>before</b> the weighted pick, so the cell re-rolls to an in-range
 * candidate (another egg or a block). Out-of-band eggs therefore never spawn;
 * a cell whose only candidates are out-of-band mobs is cleared to air.</p>
 *
 * <p>Mirrors the {@code fillMin}/{@code fillMax} convention used by
 * {@link ContainerContentsPool}: {@code min} is a plain tier (default 0,
 * "from the start"); {@code max} is either a tier or the {@link #ALL} sentinel
 * (default — "no upper bound"). The default band {@code (0, ALL)} is eligible
 * at every tier, so block-variant sidecars that predate this field behave
 * identically (full backward compatibility).</p>
 *
 * <p>Only mob entries consult or serialise a difficulty band; block entries
 * always carry {@link #NONE} and never emit it to JSON.</p>
 */
public record VariantDifficulty(int min, int max) {

    /** Sentinel for {@code max}: "no upper bound — eligible at every tier ≥ min". */
    public static final int ALL = -1;

    /**
     * Editor cap for a finite {@code min}/{@code max}, aligned with
     * {@code TemplateGate.MAX_LEVEL} so the mob-band authoring range lines up
     * with the stage / template gate UI; beyond it, authors use {@link #ALL}.
     */
    public static final int MAX_TIER = 1000;

    /** Default band: {@code min} 0, {@code max} {@link #ALL} — eligible at every tier. */
    public static final VariantDifficulty NONE = new VariantDifficulty(0, ALL);

    public VariantDifficulty {
        if (min < 0) min = 0;
        if (min > MAX_TIER) min = MAX_TIER;
        if (max < ALL) max = ALL;
        if (max > MAX_TIER) max = MAX_TIER;
        // Enforce min ≤ max (ALL is treated as +∞), mirroring ContainerContentsPool.
        if (max != ALL && min > max) min = max;
    }

    /** True when this is the no-op default (min 0, max ALL) — drives JSON-emission skipping. */
    public boolean isDefault() {
        return min == 0 && max == ALL;
    }

    /** True when {@code tier} falls inside the band; an {@link #ALL} max is unbounded above. */
    public boolean eligible(int tier) {
        if (tier < min) return false;
        return max == ALL || tier <= max;
    }

    /** Copy with {@code min} replaced (re-clamped by the canonical constructor). */
    public VariantDifficulty withMin(int newMin) {
        return new VariantDifficulty(newMin, max);
    }

    /** Copy with {@code max} replaced (re-clamped by the canonical constructor). */
    public VariantDifficulty withMax(int newMax) {
        return new VariantDifficulty(min, newMax);
    }
}
