package games.brennan.dungeontrain.template;

/**
 * The full per-id metadata a weight store holds for one template: its pick {@code weight}, its
 * inline spawn {@link TemplateGate gate} (min/max Diff-Level band + worldgen phase set), and an
 * optional {@link #stageId() Stage link}. Replaces the bare {@code int} that the weight stores used
 * to map each id to, so weight, gate, and stage link travel together through one JSON value, one
 * in-memory map, and one editor edit path.
 *
 * <p>When {@link #stageId()} is non-null the template is <b>linked</b> to a named Stage and its
 * <em>effective</em> gate is the Stage's gate (resolved live via {@code StageStore.effectiveGate}),
 * with the inline {@link #gate()} kept only as the detach snapshot / dangling-link fallback. When
 * {@code stageId} is null the template is <b>Custom</b> — the inline {@link #gate()} is
 * authoritative, exactly as before Stages existed.</p>
 *
 * <p>An id with the {@link TemplateGate#DEFAULT default} gate <b>and</b> no stage link serialises
 * back to the legacy bare-int form (see {@link TemplateWeightCodec}), so existing {@code weights.json}
 * files are unaffected.</p>
 */
public record TemplateMeta(int weight, TemplateGate gate, String stageId) {

    public TemplateMeta {
        if (gate == null) gate = TemplateGate.DEFAULT;
        // Normalise a blank stage link to "Custom" (null) so the two never diverge on disk.
        if (stageId != null && stageId.isBlank()) stageId = null;
    }

    /** Back-compat 2-arg form — an unlinked (Custom) entry with the given inline gate. */
    public TemplateMeta(int weight, TemplateGate gate) {
        this(weight, gate, null);
    }

    /** A weight-only, unlinked entry with the default (eligible-everywhere) gate. */
    public static TemplateMeta of(int weight) {
        return new TemplateMeta(weight, TemplateGate.DEFAULT, null);
    }

    /** Copy with {@code weight} replaced, keeping the inline gate and stage link. */
    public TemplateMeta withWeight(int newWeight) {
        return new TemplateMeta(newWeight, gate, stageId);
    }

    /** Copy with the inline {@code gate} replaced, keeping the weight and stage link. */
    public TemplateMeta withGate(TemplateGate newGate) {
        return new TemplateMeta(weight, newGate, stageId);
    }

    /**
     * Copy with the {@code stageId} link replaced (null = Custom), keeping weight and inline gate.
     * Callers that detach to Custom should first {@link #withGate} the snapshot so the inline gate
     * reflects what the row was showing.
     */
    public TemplateMeta withStage(String newStageId) {
        return new TemplateMeta(weight, gate, newStageId);
    }

    /** True when this entry is linked live to a named Stage (vs. an inline Custom gate). */
    public boolean isLinked() {
        return stageId != null;
    }
}
