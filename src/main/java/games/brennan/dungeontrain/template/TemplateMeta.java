package games.brennan.dungeontrain.template;

/**
 * The full per-id metadata a weight store holds for one template: its pick {@code weight} plus its
 * spawn {@link TemplateGate gate} (min/max Diff-Level band + worldgen phase set). Replaces the bare
 * {@code int} that the weight stores used to map each id to, so weight and gate travel together
 * through one JSON value, one in-memory map, and one editor edit path.
 *
 * <p>An id with the {@link TemplateGate#DEFAULT default} gate serialises back to the legacy bare-int
 * form (see {@link TemplateWeightCodec}), so existing {@code weights.json} files are unaffected.</p>
 */
public record TemplateMeta(int weight, TemplateGate gate) {

    public TemplateMeta {
        if (gate == null) gate = TemplateGate.DEFAULT;
    }

    /** A weight-only entry with the default (eligible-everywhere) gate. */
    public static TemplateMeta of(int weight) {
        return new TemplateMeta(weight, TemplateGate.DEFAULT);
    }

    /** Copy with {@code weight} replaced, keeping the gate. */
    public TemplateMeta withWeight(int newWeight) {
        return new TemplateMeta(newWeight, gate);
    }

    /** Copy with {@code gate} replaced, keeping the weight. */
    public TemplateMeta withGate(TemplateGate newGate) {
        return new TemplateMeta(weight, newGate);
    }
}
