package games.brennan.dungeontrain.template;

/**
 * The full per-id metadata a weight store holds for one template: its pick {@code weight}, its
 * inline spawn {@link TemplateGate gate} (min/max Diff-Level band + worldgen phase set), an
 * optional {@link #stageId() Stage link}, and an optional {@link #mode() mode} tag. Replaces the
 * bare {@code int} that the weight stores used to map each id to, so all of it travels together
 * through one JSON value, one in-memory map, and one editor edit path.
 *
 * <p>When {@link #stageId()} is non-null the template is <b>linked</b> to a named Stage and its
 * <em>effective</em> gate is the Stage's gate (resolved live via {@code StageStore.effectiveGate}),
 * with the inline {@link #gate()} kept only as the detach snapshot / dangling-link fallback. When
 * {@code stageId} is null the template is <b>Custom</b> — the inline {@link #gate()} is
 * authoritative, exactly as before Stages existed.</p>
 *
 * <p><b>{@link #mode()} is an opaque per-kind tag</b>, deliberately a raw string rather than an enum:
 * what a mode <i>means</i> is the owning kind's business, and this record is shared by every weight
 * store. Today only {@code TrackKind.PORTAL_ROOM} defines any — the portal layer resolves them with
 * {@code PortalRoomMode.parse}, and an unrecognised or absent tag falls back to that kind's default
 * rather than failing a load. Kinds with no modes never set it and never read it.</p>
 *
 * <p><b>{@link #flip()} is likewise a per-kind slot</b>, read today only by the carriage-contents
 * layer: which axes that template may be randomly flipped along when it is stamped. {@code null}
 * means {@link FlipOptions#DEFAULT} — the entry has no authored {@code flip} block — so a store
 * that never sets it behaves exactly as it did before the option existed.</p>
 *
 * <p><b>{@link #name()} is the editor's display label</b> — the one thing on this record that is
 * not a spawn rule. A template's id is its file basename and can never change without moving files
 * (and, for a bundled template, cannot change at all), so a rename edits this label instead, exactly
 * as a {@link Stage} keeps a stable {@code id} beside a free-text {@code name}. {@code null} means
 * "show the id". Nothing at spawn time reads it.</p>
 *
 * <p><b>{@link #builder()} is who originally built the template</b> — the second thing here that
 * is not a spawn rule. A {@link BuilderCredit} (uuid + cached display name) that the editor's data
 * sheet prints, the Credits page thanks, and the relay counts on the builder leaderboard.
 * {@code null} means nobody is credited. Nothing at spawn time reads it.</p>
 *
 * <p>An id with the {@link TemplateGate#DEFAULT default} gate, no stage link, no mode, no flip block,
 * no label <b>and</b> no builder serialises back to the legacy bare-int form (see
 * {@link TemplateWeightCodec}), so existing {@code weights.json} files are unaffected.</p>
 */
public record TemplateMeta(int weight, TemplateGate gate, String stageId, String mode, FlipOptions flip,
                           String name, BuilderCredit builder) {

    /** Longest label the editor accepts — matches the wire field it travels in. */
    public static final int NAME_MAX = 32;

    public TemplateMeta {
        if (gate == null) gate = TemplateGate.DEFAULT;
        // Normalise a blank stage link to "Custom" (null) so the two never diverge on disk.
        if (stageId != null && stageId.isBlank()) stageId = null;
        // Likewise a blank mode: absent and "" both mean "this kind's default".
        if (mode != null && mode.isBlank()) mode = null;
        // An explicit default flip block is the same thing as no flip block; normalise so the two
        // never diverge on disk (and a default-flip entry keeps its legacy bare-int form).
        if (FlipOptions.DEFAULT.equals(flip)) flip = null;
        // A blank label is "no label"; whitespace around one is never meaningful.
        name = normaliseName(name);
        // A credit naming nobody is no credit, so "cleared" has one spelling on disk and in memory.
        if (builder != null && !builder.known()) builder = null;
    }

    /** Back-compat 6-arg form — no builder credit. */
    public TemplateMeta(int weight, TemplateGate gate, String stageId, String mode, FlipOptions flip,
                        String name) {
        this(weight, gate, stageId, mode, flip, name, null);
    }

    /** Back-compat 5-arg form — no display label. */
    public TemplateMeta(int weight, TemplateGate gate, String stageId, String mode, FlipOptions flip) {
        this(weight, gate, stageId, mode, flip, null);
    }

    /** Back-compat 4-arg form — no flip block ({@link FlipOptions#DEFAULT}). */
    public TemplateMeta(int weight, TemplateGate gate, String stageId, String mode) {
        this(weight, gate, stageId, mode, null, null);
    }

    /** Back-compat 3-arg form — no mode tag. */
    public TemplateMeta(int weight, TemplateGate gate, String stageId) {
        this(weight, gate, stageId, null, null, null);
    }

    /** Back-compat 2-arg form — an unlinked (Custom) entry with the given inline gate. */
    public TemplateMeta(int weight, TemplateGate gate) {
        this(weight, gate, null, null, null, null);
    }

    /** A weight-only, unlinked entry with the default (eligible-everywhere) gate. */
    public static TemplateMeta of(int weight) {
        return new TemplateMeta(weight, TemplateGate.DEFAULT, null, null, null, null);
    }

    /** This entry's flip options, resolving an absent block to {@link FlipOptions#DEFAULT}. */
    public FlipOptions effectiveFlip() {
        return flip == null ? FlipOptions.DEFAULT : flip;
    }

    /** True when this entry carries a display label distinct from its id. */
    public boolean hasName() {
        return name != null;
    }

    /**
     * {@code raw} as a stored label: trimmed, {@code null} when blank, cut to {@link #NAME_MAX}.
     * Shared by the constructor and the command layer so "what counts as no label" has one answer.
     */
    public static String normaliseName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        return s.length() > NAME_MAX ? s.substring(0, NAME_MAX) : s;
    }

    /** Copy with {@code weight} replaced, keeping the inline gate, stage link, mode, flip and label. */
    public TemplateMeta withWeight(int newWeight) {
        return new TemplateMeta(newWeight, gate, stageId, mode, flip, name, builder);
    }

    /** Copy with the inline {@code gate} replaced, keeping the weight, stage link, mode, flip and label. */
    public TemplateMeta withGate(TemplateGate newGate) {
        return new TemplateMeta(weight, newGate, stageId, mode, flip, name, builder);
    }

    /** Copy with the {@code flip} block replaced ({@code null} / default = no block), keeping everything else. */
    public TemplateMeta withFlip(FlipOptions newFlip) {
        return new TemplateMeta(weight, gate, stageId, mode, newFlip, name, builder);
    }

    /**
     * Copy with the {@code stageId} link replaced (null = Custom), keeping weight, inline gate and
     * mode. Callers that detach to Custom should first {@link #withGate} the snapshot so the inline
     * gate reflects what the row was showing.
     */
    public TemplateMeta withStage(String newStageId) {
        return new TemplateMeta(weight, gate, newStageId, mode, flip, name, builder);
    }

    /** Copy with the {@code mode} tag replaced (null = this kind's default), keeping everything else. */
    public TemplateMeta withMode(String newMode) {
        return new TemplateMeta(weight, gate, stageId, newMode, flip, name, builder);
    }

    /** Copy with the display label replaced ({@code null} / blank = show the id), keeping everything else. */
    public TemplateMeta withName(String newName) {
        return new TemplateMeta(weight, gate, stageId, mode, flip, newName, builder);
    }

    /**
     * The entry a weight store should store when a label edit lands on {@code prev} ({@code null} =
     * no existing entry, created unlinked at {@code defaultWeight} with the default gate). Preserves
     * every spawn rule — a rename must never change how often a template comes up.
     */
    public static TemplateMeta mergeName(TemplateMeta prev, String name, int defaultWeight) {
        return prev == null
            ? new TemplateMeta(defaultWeight, TemplateGate.DEFAULT, null, null, null, name)
            : prev.withName(name);
    }

    /**
     * The entry a <b>duplicate</b> of this template inherits: weight, gate, Stage link, mode, flip
     * and builder credit — everything but the display label. Two templates answering to one label
     * are indistinguishable in every menu, so the copy is labelled by its own id until its author
     * names it. The mode tag travels whole; for a portal room that is its sky, walls, copies and
     * door settings, and a copy without it is a bare box.
     */
    public TemplateMeta asCopy() {
        return withName(null);
    }

    /** True when somebody is credited as this template's original builder. */
    public boolean hasBuilder() {
        return builder != null;
    }

    /** Copy with the builder credit replaced ({@code null} = nobody credited), keeping everything else. */
    public TemplateMeta withBuilder(BuilderCredit newBuilder) {
        return new TemplateMeta(weight, gate, stageId, mode, flip, name, newBuilder);
    }

    /**
     * The entry a weight store should store when a builder-credit edit lands on {@code prev}
     * ({@code null} = no existing entry, created unlinked at {@code defaultWeight} with the default
     * gate). Preserves every spawn rule and the label — crediting a builder must never change how
     * often, or under what name, a template comes up.
     */
    public static TemplateMeta mergeBuilder(TemplateMeta prev, BuilderCredit builder, int defaultWeight) {
        return prev == null
            ? new TemplateMeta(defaultWeight, TemplateGate.DEFAULT, null, null, null, null, builder)
            : prev.withBuilder(builder);
    }

    /** True when this entry is linked live to a named Stage (vs. an inline Custom gate). */
    public boolean isLinked() {
        return stageId != null;
    }

    /**
     * The entry a weight store should store when a weight edit lands on {@code prev} ({@code null}
     * = no existing entry, created unlinked with the default gate).
     *
     * <p>Shared by all three stores' {@code set(...)} so the "keep everything but the weight"
     * decision lives in one place. Rebuilding the record from parts here instead would silently
     * drop the {@link #stageId() Stage link} — the reason this helper exists rather than each store
     * calling a constructor.</p>
     */
    public static TemplateMeta mergeWeight(TemplateMeta prev, int weight) {
        return prev == null ? new TemplateMeta(weight, TemplateGate.DEFAULT, null) : prev.withWeight(weight);
    }

    /**
     * The entry a weight store should store when an inline-gate edit lands on {@code prev}
     * ({@code null} = no existing entry, created unlinked at {@code defaultWeight}). Counterpart to
     * {@link #mergeWeight}: preserves the weight and the Stage link.
     *
     * <p>Note this writes the <em>inline</em> gate, which is inert while the entry is Stage-linked —
     * the effective gate then comes from the Stage. Callers that surface the edit to a user should
     * say so; see {@code EditorCommand.gateSuccess}.</p>
     */
    public static TemplateMeta mergeGate(TemplateMeta prev, TemplateGate gate, int defaultWeight) {
        return prev == null ? new TemplateMeta(defaultWeight, gate, null) : prev.withGate(gate);
    }

    /**
     * The entry a weight store should store when a flip-option edit lands on {@code prev}
     * ({@code null} = no existing entry, created unlinked at {@code defaultWeight} with the default
     * gate). Counterpart to {@link #mergeWeight} / {@link #mergeGate}: preserves the weight, the
     * inline gate, the Stage link and the mode.
     */
    public static TemplateMeta mergeFlip(TemplateMeta prev, FlipOptions flip, int defaultWeight) {
        return prev == null
            ? new TemplateMeta(defaultWeight, TemplateGate.DEFAULT, null, null, flip, null)
            : prev.withFlip(flip);
    }
}
