package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateWeightCodec;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.UnaryOperator;

/**
 * Per-carriage-variant map of {@link CarriagePartKind} → candidate part
 * names with per-entry pick weights. Stored as a sidecar
 * {@code <variant-id>.parts.json} next to the carriage NBT; when present
 * with at least one resolvable part, it takes precedence over the
 * monolithic NBT at spawn time and {@code CarriagePlacer.placeAt}
 * composes the carriage from parts.
 *
 * <p>Each slot is a <b>list</b> of {@link WeightedName} entries. At
 * spawn, one entry is picked deterministically from
 * {@code (worldSeed, carriageIndex, kind.ordinal())} so the same slot in
 * the same carriage on the same track renders the same visual across
 * reloads. Weights bias the pick (cumulative-weight scan). The picked
 * entry may be the sentinel {@link CarriagePartKind#NONE} — in which
 * case that kind's stamp is skipped entirely (the FLATBED case:
 * {@code walls=[{none, 1}]} leaves the sides open).</p>
 *
 * <p>Each entry also carries an optional per-entry spawn {@link TemplateGate} (Diff-Level band +
 * {@link TrainPhase dimension} set), mirroring the per-template gate on carriage / contents / track
 * variants. At spawn the picker drops out-of-band / out-of-dimension entries <b>before</b> the
 * weighted draw via {@link #applyGate}, falling back to the ungated pool if that empties the slot so
 * a part is never left unfillable. The gate is (de)serialised with the shared
 * {@link TemplateWeightCodec} so it shares the exact on-disk shape used by the weight stores; the
 * default gate emits nothing, so prior files round-trip byte-identically.</p>
 *
 * <p>An entry may instead <b>link live to a named Stage</b> (optional {@code stage} field): its
 * effective gate is then that Stage's gate, resolved via {@code StageStore.effectiveGate}, so editing
 * the Stage retunes every linked part entry. A null link means the inline gate is authoritative
 * (Custom), exactly as before Stages existed.</p>
 *
 * <p>JSON schema is forward and backwards tolerant:
 * <ul>
 *   <li><b>v4</b> (current) — v3 plus an optional {@code stage} link per entry, emitted only when set.</li>
 *   <li><b>v3</b> — v2 plus optional gate fields ({@code minLevel}, {@code maxLevel},
 *       {@code phases}) per entry, emitted only when the gate is non-default.</li>
 *   <li><b>v2</b> — array of {@code {"name": "...", "weight": N}} objects.</li>
 *   <li><b>v1</b> — array of bare strings; loaded with {@code weight=1}.</li>
 *   <li><b>v0 scalar</b> — a single bare string in a slot; normalised to one entry at weight 1.</li>
 * </ul></p>
 *
 * <pre>
 * { "schemaVersion": 3,
 *   "floor": [ { "name": "wood", "weight": 3 } ],
 *   "walls": [ { "name": "standard_walls", "weight": 1 },
 *              { "name": "nether", "weight": 2, "minLevel": 10, "phases": ["NETHER"] },
 *              { "name": "none", "weight": 1 } ],
 *   "roof":  [ { "name": "standard", "weight": 1 } ],
 *   "doors": [] }
 * </pre>
 */
public record CarriagePartAssignment(List<WeightedName> floor, List<WeightedName> walls,
                                     List<WeightedName> roof, List<WeightedName> doors) {

    public static final String SCHEMA_KEY = "schemaVersion";
    public static final int SCHEMA_VERSION = 4;

    /** Inclusive weight bounds. Authors clamp to this range; the JSON loader also clamps. */
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 100;

    /** Mixer constant — same as {@code CarriagePlacer.seededPick}. Adjacent indices don't correlate. */
    private static final long MIX = 0x9E3779B97F4A7C15L;

    /**
     * How a wall/door variant is allowed to render across the kind's two
     * placements. Floors and roofs only have one placement and ignore
     * this field.
     *
     * <ul>
     *   <li>{@link #BOTH} — when picked, both wall/door sides render this variant (mirrored). UI label: {@code (2)}.</li>
     *   <li>{@link #ONE} — when picked, only one side renders this; the other side picks a separate variant. UI label: {@code (1)}.</li>
     *   <li>{@link #EITHER} — picker can use it either way; a seeded coin-flip decides. UI label: {@code (1|2)}.</li>
     * </ul>
     */
    public enum SideMode {
        BOTH("(2)"),
        ONE("(1)"),
        EITHER("(1|2)");

        private final String label;
        SideMode(String label) { this.label = label; }
        public String label() { return label; }

        /** Cycle order used by the menu's click-to-cycle: BOTH → ONE → EITHER → BOTH. */
        public SideMode next() {
            return switch (this) {
                case BOTH -> ONE;
                case ONE -> EITHER;
                case EITHER -> BOTH;
            };
        }

        public static SideMode fromId(String s) {
            if (s == null) return BOTH;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "both", "(2)", "2" -> BOTH;
                case "one", "(1)", "1" -> ONE;
                case "either", "(1|2)", "12" -> EITHER;
                default -> BOTH;
            };
        }
    }

    /**
     * Where along a carriage group a door variant is allowed to render.
     * Only meaningful for {@link CarriagePartKind#DOORS} — other kinds
     * accept the field but ignore it at pick time.
     *
     * <ul>
     *   <li>{@link #BOTH} — no position constraint; eligible for both placements.</li>
     *   <li>{@link #END} — eligible only on a placement that faces a flatbed (sub-level boundary / half-flatbed pad).</li>
     *   <li>{@link #MID} — eligible only on a placement that faces another enclosed carriage.</li>
     * </ul>
     *
     * <p>In generation modes without guaranteed flatbed neighbours
     * (LOOPING / RANDOM), the placer passes {@code flatbedAtBack=false,
     * flatbedAtFront=false}; an {@code END} entry therefore matches
     * nothing and a {@code MID} entry matches everything. The picker
     * falls back to the unfiltered pool when filtering empties a
     * placement, so authors can't accidentally produce an empty door
     * stamp.</p>
     */
    public enum EndMode {
        BOTH("end+mid"),
        END("end"),
        MID("mid");

        private final String label;
        EndMode(String label) { this.label = label; }
        public String label() { return label; }

        /** Cycle order used by the menu's click-to-cycle: BOTH → END → MID → BOTH. */
        public EndMode next() {
            return switch (this) {
                case BOTH -> END;
                case END -> MID;
                case MID -> BOTH;
            };
        }

        public static EndMode fromId(String s) {
            if (s == null) return BOTH;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "both", "end+mid", "all" -> BOTH;
                case "end" -> END;
                case "mid" -> MID;
                default -> BOTH;
            };
        }
    }

    /**
     * A candidate name with its pick weight, side-mode constraint,
     * end-mode constraint, and per-entry spawn {@link TemplateGate}.
     * Weight is clamped to {@code [MIN_WEIGHT, MAX_WEIGHT]}; sideMode
     * defaults to {@link SideMode#BOTH}, endMode to {@link EndMode#BOTH},
     * and gate to {@link TemplateGate#DEFAULT} (eligible at every
     * Diff-Level and dimension) so legacy entries (and floor / roof / wall
     * entries that don't care about position-along-the-group) match the
     * original behaviour. The convenience constructors below keep every
     * pre-gate call site compiling unchanged.
     */
    public record WeightedName(String name, int weight, SideMode sideMode, EndMode endMode,
                               TemplateGate gate, String stageId) {
        public WeightedName {
            name = (name == null || name.isBlank())
                ? CarriagePartKind.NONE
                : name.toLowerCase(Locale.ROOT);
            weight = clampWeight(weight);
            if (sideMode == null) sideMode = SideMode.BOTH;
            if (endMode == null) endMode = EndMode.BOTH;
            if (gate == null) gate = TemplateGate.DEFAULT;
            if (stageId != null && stageId.isBlank()) stageId = null;
        }

        /** Default-weight, BOTH-side, BOTH-end, default-gate, unlinked entry. */
        public static WeightedName of(String name) {
            return new WeightedName(name, MIN_WEIGHT, SideMode.BOTH, EndMode.BOTH, TemplateGate.DEFAULT, null);
        }

        /** Default-weight entry with explicit side mode (endMode BOTH, default gate, unlinked). */
        public static WeightedName of(String name, SideMode sideMode) {
            return new WeightedName(name, MIN_WEIGHT, sideMode, EndMode.BOTH, TemplateGate.DEFAULT, null);
        }

        /** 2-arg back-compat constructor — defaults sideMode/endMode BOTH, gate DEFAULT, unlinked. */
        public WeightedName(String name, int weight) {
            this(name, weight, SideMode.BOTH, EndMode.BOTH, TemplateGate.DEFAULT, null);
        }

        /** 3-arg back-compat constructor — defaults endMode BOTH, gate DEFAULT, unlinked. */
        public WeightedName(String name, int weight, SideMode sideMode) {
            this(name, weight, sideMode, EndMode.BOTH, TemplateGate.DEFAULT, null);
        }

        /** 4-arg back-compat constructor — defaults gate DEFAULT, unlinked. */
        public WeightedName(String name, int weight, SideMode sideMode, EndMode endMode) {
            this(name, weight, sideMode, endMode, TemplateGate.DEFAULT, null);
        }

        /** 5-arg back-compat constructor — unlinked (Custom inline gate). */
        public WeightedName(String name, int weight, SideMode sideMode, EndMode endMode, TemplateGate gate) {
            this(name, weight, sideMode, endMode, gate, null);
        }

        /**
         * The effective gate this entry spawns under: the linked Stage's gate when {@link #stageId}
         * names an existing Stage, else the inline {@link #gate}. O(1)
         * {@link games.brennan.dungeontrain.editor.StageStore} lookup.
         */
        public TemplateGate effectiveGate() {
            return games.brennan.dungeontrain.editor.StageStore.effectiveGate(gate, stageId);
        }
    }

    private static int clampWeight(int w) {
        if (w < MIN_WEIGHT) return MIN_WEIGHT;
        if (w > MAX_WEIGHT) return MAX_WEIGHT;
        return w;
    }

    /** Empty assignment — every kind set to {@link CarriagePartKind#NONE}. Caller short-circuits to monolithic. */
    public static final CarriagePartAssignment EMPTY = new CarriagePartAssignment(
        List.of(WeightedName.of(CarriagePartKind.NONE)),
        List.of(WeightedName.of(CarriagePartKind.NONE)),
        List.of(WeightedName.of(CarriagePartKind.NONE)),
        List.of(WeightedName.of(CarriagePartKind.NONE))
    );

    public CarriagePartAssignment {
        floor = normalize(floor);
        walls = normalize(walls);
        roof  = normalize(roof);
        doors = normalize(doors);
    }

    /**
     * Null / empty list → {@code [{"none", 1}]}; otherwise return an
     * immutable copy. The {@link WeightedName} canonical constructor
     * already lowercases names and clamps weights, so this method just
     * guards against a null / empty caller list.
     */
    private static List<WeightedName> normalize(List<WeightedName> list) {
        if (list == null || list.isEmpty()) return List.of(WeightedName.of(CarriagePartKind.NONE));
        return List.copyOf(list);
    }

    /** Wrap a list of bare names as weighted entries with default weight. */
    private static List<WeightedName> wrap(List<String> names) {
        if (names == null || names.isEmpty()) return List.of(WeightedName.of(CarriagePartKind.NONE));
        List<WeightedName> out = new ArrayList<>(names.size());
        for (String n : names) out.add(WeightedName.of(n));
        return List.copyOf(out);
    }

    /** Candidate weighted entries for {@code kind} — always non-empty (at minimum {@code [{"none", 1}]}). */
    public List<WeightedName> entries(CarriagePartKind kind) {
        return switch (kind) {
            case FLOOR -> floor;
            case WALLS -> walls;
            case ROOF  -> roof;
            case DOORS -> doors;
        };
    }

    /** Bare names for {@code kind} — back-compat for the action-bar joiner and slash-command output. */
    public List<String> names(CarriagePartKind kind) {
        List<WeightedName> es = entries(kind);
        List<String> out = new ArrayList<>(es.size());
        for (WeightedName e : es) out.add(e.name());
        return out;
    }

    /**
     * Pick one candidate name for {@code kind} at carriage index
     * {@code carriageIndex}. Deterministic in {@code (seed, carriageIndex,
     * kind.ordinal())} — the same triple always yields the same pick, so
     * carriages re-rendered by the rolling-window manager match what was
     * placed on the first pass.
     *
     * <p>Single-element lists short-circuit to return that element directly.
     * Multi-element lists do a weighted draw: pick {@code r ∈ [0, totalWeight)}
     * then linear-scan cumulative weights for the entry whose cumulative
     * upper bound exceeds {@code r}. Identical {@code (seed, idx, kind)}
     * with the same list+weights always returns the same name.</p>
     */
    public String pick(CarriagePartKind kind, long seed, int carriageIndex) {
        return pick(kind, seed, carriageIndex, null);
    }

    /**
     * Gate-aware {@link #pick(CarriagePartKind, long, int)}: when {@code gateCtx} is non-null,
     * {@link #applyGate} drops entries whose {@link TemplateGate} excludes the carriage's Diff-Level
     * / dimension before the weighted draw (ungated fallback if that empties the slot). A
     * {@code null} context — editor preview, slash command, template load — skips gating entirely.
     */
    public String pick(CarriagePartKind kind, long seed, int carriageIndex, GateContext gateCtx) {
        return pickFrom(kind, seed, carriageIndex, applyGate(entries(kind), gateCtx));
    }

    /**
     * Weighted draw of a single name from an explicit candidate {@code list} (already gate- or
     * stage-filtered by the caller). An empty list yields {@link CarriagePartKind#NONE} — defensive:
     * the gate path always passes a non-empty list, and the stage path checks emptiness before calling.
     */
    private String pickFrom(CarriagePartKind kind, long seed, int carriageIndex, List<WeightedName> list) {
        if (list.isEmpty()) return CarriagePartKind.NONE;
        if (list.size() == 1) return list.get(0).name();
        long mixed = seed ^ ((long) carriageIndex * MIX) ^ ((long) kind.ordinal() * 0xC6BC279692B5C323L);
        return weightedPick(list, mixed);
    }

    /**
     * Pick one name <i>per placement</i> for {@code kind}, honouring each
     * entry's {@link SideMode}. For one-placement kinds (FLOOR / ROOF) this
     * is a single-element list equal to {@link #pick}.
     *
     * <p>Back-compat overload that assumes no flatbed neighbours — same
     * behaviour as the original {@code pickPerPlacement}. Used by editor
     * preview, slash commands, and any caller that doesn't know the
     * group context. {@link EndMode#END}-tagged entries become ineligible
     * and {@link EndMode#MID}-tagged become unconstrained, exactly the
     * fallback chosen for LOOPING / RANDOM modes.</p>
     */
    public List<String> pickPerPlacement(CarriagePartKind kind, long seed, int carriageIndex) {
        return pickPerPlacement(kind, seed, carriageIndex, false, false, null);
    }

    /**
     * Gate-aware no-flatbed overload — see
     * {@link #pickPerPlacement(CarriagePartKind, long, int, boolean, boolean, GateContext)}.
     */
    public List<String> pickPerPlacement(CarriagePartKind kind, long seed, int carriageIndex,
                                         GateContext gateCtx) {
        return pickPerPlacement(kind, seed, carriageIndex, false, false, gateCtx);
    }

    /**
     * Pick one name <i>per placement</i> for {@code kind}, honouring each
     * entry's {@link SideMode} and {@link EndMode}. For DOORS, the
     * {@code flatbedAtBack} / {@code flatbedAtFront} flags filter the
     * candidate pool per placement before the SideMode logic runs:
     *
     * <ul>
     *   <li>Placement 0 (BACK end of the carriage) keeps entries whose
     *       {@link EndMode} is {@link EndMode#BOTH}, plus
     *       {@link EndMode#END} iff {@code flatbedAtBack}, plus
     *       {@link EndMode#MID} iff {@code !flatbedAtBack}.</li>
     *   <li>Placement 1 (FRONT end) uses the same predicate with
     *       {@code flatbedAtFront}.</li>
     * </ul>
     *
     * If a placement's filtered pool ends up empty (e.g. all entries are
     * {@code MID} but the placement faces a flatbed), the picker falls
     * back to the unfiltered list rather than producing an empty stamp.
     *
     * <p>For non-DOORS kinds the flags are ignored — only doors use the
     * per-end constraint today.</p>
     *
     * <p>Determinism: every random draw is keyed on
     * {@code (seed, carriageIndex, kind.ordinal())} with distinct mixers
     * for the two placements, so identical inputs always yield identical
     * picks. The end-mode filter is a pure function of {@code (list,
     * flatbedAtBack, flatbedAtFront)} with no random component.</p>
     */
    public List<String> pickPerPlacement(CarriagePartKind kind, long seed, int carriageIndex,
                                         boolean flatbedAtBack, boolean flatbedAtFront) {
        return pickPerPlacement(kind, seed, carriageIndex, flatbedAtBack, flatbedAtFront, null);
    }

    /**
     * Gate-aware {@link #pickPerPlacement(CarriagePartKind, long, int, boolean, boolean)}: when
     * {@code gateCtx} is non-null, {@link #applyGate} drops out-of-band / out-of-dimension entries
     * from the candidate pool before the per-placement SideMode/EndMode logic runs (ungated fallback
     * if that empties the kind's pool). A {@code null} context skips gating, so editor preview and
     * slash-command callers see every entry regardless of where the carriage sits.
     */
    public List<String> pickPerPlacement(CarriagePartKind kind, long seed, int carriageIndex,
                                         boolean flatbedAtBack, boolean flatbedAtFront,
                                         GateContext gateCtx) {
        return pickPerPlacementFrom(kind, seed, carriageIndex, flatbedAtBack, flatbedAtFront,
            applyGate(entries(kind), gateCtx));
    }

    /**
     * Stage-filtered per-placement pick for the editor's per-stage carriage preview, in two tiers:
     * <ol>
     *   <li>entries whose {@code stageId} matches {@code stageId} (case-insensitive) — the explicit link;</li>
     *   <li>if none, entries whose effective gate {@linkplain TemplateGate#overlaps overlaps}
     *       {@code stageGate} (the selected stage's own gate — same diff-level band + dimension), so a
     *       slot still shows what would actually appear at this stage rather than airing out.</li>
     * </ol>
     * Returns an <b>empty list</b> only when neither tier matches — the signal for the caller
     * ({@code CarriagePlacer.stampPartsOverlayForStage}) to air out that swappable slot. When a tier
     * has candidates, the shared {@link #pickPerPlacementFrom} core runs the same SideMode/EndMode/
     * weighted logic as the gated path, so a stamped part honours its mirror / end tags exactly as at
     * spawn. Neither tier falls back to the ungated full list.
     */
    public List<String> pickPerPlacementForStage(CarriagePartKind kind, long seed, int carriageIndex,
                                                 boolean flatbedAtBack, boolean flatbedAtFront,
                                                 String stageId, TemplateGate stageGate) {
        List<WeightedName> candidates = filterByStage(entries(kind), stageId);
        if (candidates.isEmpty()) {
            candidates = filterByGateOverlap(entries(kind), stageGate);
        }
        if (candidates.isEmpty()) return List.of();
        return pickPerPlacementFrom(kind, seed, carriageIndex, flatbedAtBack, flatbedAtFront, candidates);
    }

    /**
     * Shared per-placement core for {@link #pickPerPlacement} (gate-filtered) and
     * {@link #pickPerPlacementForStage} (stage-filtered): pick one name per placement from an explicit,
     * pre-filtered candidate {@code list}, honouring each entry's {@link SideMode} and (for DOORS)
     * {@link EndMode}. For one-placement kinds (FLOOR / ROOF) this is a single-element list.
     */
    private List<String> pickPerPlacementFrom(CarriagePartKind kind, long seed, int carriageIndex,
                                              boolean flatbedAtBack, boolean flatbedAtFront,
                                              List<WeightedName> list) {
        boolean twoPlacements = kind == CarriagePartKind.WALLS || kind == CarriagePartKind.DOORS;
        if (!twoPlacements) {
            return List.of(pickFrom(kind, seed, carriageIndex, list));
        }

        // End-mode filter applies to DOORS only; WALLS keep their full list.
        boolean applyEndFilter = kind == CarriagePartKind.DOORS;
        List<WeightedName> poolBack  = applyEndFilter ? filterByEndMode(list, flatbedAtBack)  : list;
        List<WeightedName> poolFront = applyEndFilter ? filterByEndMode(list, flatbedAtFront) : list;

        long mixed0 = seed ^ ((long) carriageIndex * MIX) ^ ((long) kind.ordinal() * 0xC6BC279692B5C323L);
        String first = poolBack.size() == 1 ? poolBack.get(0).name() : weightedPick(poolBack, mixed0);
        if (CarriagePartKind.NONE.equals(first)) {
            return List.of(first, first);
        }

        // Look up the first pick's SideMode from the full list (an entry's
        // sideMode is independent of which placement it ends up at).
        SideMode firstMode = SideMode.BOTH;
        for (WeightedName e : list) {
            if (e.name().equals(first)) { firstMode = e.sideMode(); break; }
        }

        // End-mode can also force a separate pick: if {@code first} survived
        // the back filter but not the front filter, we cannot mirror it
        // even when SideMode=BOTH would normally want to.
        boolean firstEligibleForFront = false;
        for (WeightedName e : poolFront) {
            if (e.name().equals(first)) { firstEligibleForFront = true; break; }
        }

        boolean separate;
        if (!firstEligibleForFront) {
            // End-mode disallows mirroring — override SideMode and pick a
            // distinct variant for the front placement.
            separate = true;
        } else if (firstMode == SideMode.BOTH) {
            separate = false;
        } else if (firstMode == SideMode.ONE) {
            separate = true;
        } else {
            // EITHER — deterministic coin flip.
            long coinSeed = seed ^ ((long) carriageIndex * 0xCAFEBABE12345678L) ^ ((long) kind.ordinal() * 0x9E3779B97F4A7C15L);
            separate = (new Random(coinSeed).nextInt() & 1) == 0;
        }

        if (!separate) return List.of(first, first);

        // Eligible pool for the front pick when separating:
        //   - If SideMode forced the separation (firstMode != BOTH but
        //     firstEligibleForFront): exclude BOTH-only entries so the
        //     second pick doesn't itself demand mirroring back to the first
        //     placement.
        //   - If EndMode forced the separation (!firstEligibleForFront):
        //     any entry in poolFront is fine — including BOTH-sideMode
        //     entries, which simply render once at the front and accept
        //     that the back has a different variant.
        List<WeightedName> eligible = new ArrayList<>();
        if (!firstEligibleForFront) {
            eligible.addAll(poolFront);
        } else {
            for (WeightedName e : poolFront) {
                if (e.sideMode() != SideMode.BOTH) eligible.add(e);
            }
        }
        if (eligible.isEmpty()) {
            // No eligible separate variant — degrade to the same pick on
            // both sides. Defensive fallback for pathological lists.
            return List.of(first, first);
        }

        long secondSeed = seed ^ ((long) carriageIndex * 0xDEADBEEF12345678L) ^ ((long) kind.ordinal() * 0xC6BC279692B5C323L);
        String second = weightedPick(eligible, secondSeed);
        return List.of(first, second);
    }

    /**
     * Filter {@code list} to entries whose {@link EndMode} matches the
     * placement's flatbed-neighbour status. Empty result falls back to
     * the unfiltered list so the picker always has something to draw
     * from (an author who tags every entry {@code MID} on a single-slot
     * group still gets a door stamp).
     */
    private static List<WeightedName> filterByEndMode(List<WeightedName> list, boolean flatbedNeighbour) {
        List<WeightedName> out = new ArrayList<>(list.size());
        for (WeightedName e : list) {
            EndMode m = e.endMode();
            if (m == EndMode.BOTH
                || (m == EndMode.END && flatbedNeighbour)
                || (m == EndMode.MID && !flatbedNeighbour)) {
                out.add(e);
            }
        }
        return out.isEmpty() ? list : out;
    }

    /**
     * Drop entries whose {@link TemplateGate} excludes {@code gateCtx} (the carriage's Diff-Level +
     * dimension), mirroring the per-template gate filter on the carriage / contents / track pools
     * ({@code CarriageContentsRegistry.pick}, {@code CarriagePlacer.gateFilter}). A {@code null}
     * context returns the list unchanged (no gating). If the phase gate empties the list it relaxes to
     * a <em>level-only</em> list (same Diff-Level tier, any phase) so the part stays themed to the
     * correct stage instead of collapsing to all stages for a phase no stage covers; only if that is
     * also empty does it fall back to the full list (a slot is never left unfillable). The
     * {@link CarriagePartKind#NONE} sentinel carries the default gate, so the FLATBED
     * {@code walls=[{none,1}]} case always survives the filter.
     */
    private static List<WeightedName> applyGate(List<WeightedName> list, GateContext gateCtx) {
        if (gateCtx == null) return list;
        List<WeightedName> gated = new ArrayList<>(list.size());
        for (WeightedName e : list) {
            if (gateCtx.allows(e.effectiveGate())) gated.add(e);
        }
        if (!gated.isEmpty()) return gated;
        List<WeightedName> levelOnly = new ArrayList<>(list.size());
        for (WeightedName e : list) {
            if (gateCtx.levelAllows(e.effectiveGate())) levelOnly.add(e);
        }
        return levelOnly.isEmpty() ? list : levelOnly;
    }

    /**
     * Keep only entries explicitly linked to Stage {@code stageId} (case-insensitive) — the candidate
     * pool for the editor's per-stage carriage preview. Unlike {@link #applyGate}, there is <b>no</b>
     * fallback to the full list: an empty result is meaningful ("nothing assigned to this stage") and
     * tells the caller to air out the slot. {@link CarriagePartKind#NONE} sentinels carry no stage
     * link, so a {@code [none]}-only kind correctly filters to empty.
     */
    private static List<WeightedName> filterByStage(List<WeightedName> list, String stageId) {
        if (stageId == null || stageId.isBlank()) return List.of();
        String key = stageId.toLowerCase(Locale.ROOT);
        List<WeightedName> out = new ArrayList<>(list.size());
        for (WeightedName e : list) {
            if (e.stageId() != null && e.stageId().toLowerCase(Locale.ROOT).equals(key)) out.add(e);
        }
        return out;
    }

    /**
     * Tier-2 candidate pool for the per-stage preview when {@link #filterByStage} comes up empty: real
     * parts (never the {@link CarriagePartKind#NONE} sentinel) whose effective gate
     * {@linkplain TemplateGate#overlaps overlaps} {@code stageGate} — the selected stage's own gate
     * (same diff-level band + dimension). No fallback to the full list (empty ⇒ the slot airs out).
     * NONE is skipped so its everything-overlapping DEFAULT gate can't turn "no overlapping part" into
     * an air pick — a flatbed's {@code walls=[none]} still airs out exactly as before.
     */
    private static List<WeightedName> filterByGateOverlap(List<WeightedName> list, TemplateGate stageGate) {
        if (stageGate == null) return List.of();
        List<WeightedName> out = new ArrayList<>(list.size());
        for (WeightedName e : list) {
            if (CarriagePartKind.NONE.equals(e.name())) continue;
            if (stageGate.overlaps(e.effectiveGate())) out.add(e);
        }
        return out;
    }

    /** Weighted-cumulative pick from a non-empty list. Returns the first entry's name when total weight is 0. */
    private static String weightedPick(List<WeightedName> list, long seed) {
        int total = 0;
        for (WeightedName e : list) total += e.weight();
        if (total <= 0) return list.get(0).name();
        int r = Math.floorMod(new Random(seed).nextInt(), total);
        int cum = 0;
        for (WeightedName e : list) {
            cum += e.weight();
            if (r < cum) return e.name();
        }
        return list.get(list.size() - 1).name();
    }

    /** Replace the list for {@code kind} entirely with {@link WeightedName} entries. Normalised. */
    public CarriagePartAssignment with(CarriagePartKind kind, List<WeightedName> newList) {
        List<WeightedName> n = normalize(newList);
        return switch (kind) {
            case FLOOR -> new CarriagePartAssignment(n, walls, roof, doors);
            case WALLS -> new CarriagePartAssignment(floor, n, roof, doors);
            case ROOF  -> new CarriagePartAssignment(floor, walls, n, doors);
            case DOORS -> new CarriagePartAssignment(floor, walls, roof, n);
        };
    }

    /** Replace the list for {@code kind} with bare names at default weight. */
    public CarriagePartAssignment withNames(CarriagePartKind kind, List<String> newList) {
        return with(kind, wrap(newList));
    }

    /**
     * Append {@code name} to {@code kind}'s list with default weight. When
     * the existing list is just {@code [{"none", *}]} (the default
     * placeholder), the append replaces it rather than producing
     * {@code [{"none", *}, {name, 1}]} — the author just set the first
     * real option, they almost certainly didn't also mean "and with equal
     * probability stamp nothing".
     */
    public CarriagePartAssignment withAppended(CarriagePartKind kind, String name) {
        return withAppended(kind, name, MIN_WEIGHT);
    }

    /** As {@link #withAppended(CarriagePartKind, String)} but with an explicit weight. */
    public CarriagePartAssignment withAppended(CarriagePartKind kind, String name, int weight) {
        List<WeightedName> existing = entries(kind);
        WeightedName entry = new WeightedName(name, weight);
        List<WeightedName> updated;
        if (existing.size() == 1
            && CarriagePartKind.NONE.equals(existing.get(0).name())
            && !CarriagePartKind.NONE.equals(entry.name())) {
            updated = List.of(entry);
        } else {
            updated = new ArrayList<>(existing);
            updated.add(entry);
        }
        return with(kind, updated);
    }

    /**
     * Remove the first occurrence of {@code name} (case-insensitive) from
     * {@code kind}'s list. If that empties the list, {@link #normalize}
     * restores {@code [{"none", 1}]}.
     */
    public CarriagePartAssignment withRemoved(CarriagePartKind kind, String name) {
        List<WeightedName> existing = entries(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<WeightedName> updated = new ArrayList<>(existing);
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).name().equals(norm)) {
                updated.remove(i);
                break;
            }
        }
        return with(kind, updated);
    }

    /**
     * Adjust the weight of {@code name} in {@code kind}'s list by
     * {@code delta}, clamped to {@code [MIN_WEIGHT, MAX_WEIGHT]}. If
     * {@code name} is not in the list, returns {@code this} unchanged.
     */
    public CarriagePartAssignment withWeight(CarriagePartKind kind, String name, int delta) {
        List<WeightedName> existing = entries(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<WeightedName> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (WeightedName e : existing) {
            if (!changed && e.name().equals(norm)) {
                updated.add(new WeightedName(e.name(), clampWeight(e.weight() + delta), e.sideMode(), e.endMode(), e.gate(), e.stageId()));
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) return this;
        return with(kind, updated);
    }

    /**
     * Cycle the side-mode of {@code name} in {@code kind}'s list to its
     * {@link SideMode#next} value. Returns {@code this} unchanged if the
     * name isn't in the list. Floors/roofs accept the call but the field
     * is functionally ignored at runtime.
     */
    public CarriagePartAssignment cycleSideMode(CarriagePartKind kind, String name) {
        List<WeightedName> existing = entries(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<WeightedName> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (WeightedName e : existing) {
            if (!changed && e.name().equals(norm)) {
                updated.add(new WeightedName(e.name(), e.weight(), e.sideMode().next(), e.endMode(), e.gate(), e.stageId()));
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) return this;
        return with(kind, updated);
    }

    /**
     * Cycle the end-mode of {@code name} in {@code kind}'s list to its
     * {@link EndMode#next} value. Returns {@code this} unchanged if the
     * name isn't in the list. Floor/roof/wall entries accept the call
     * but the field is functionally ignored at runtime — only DOORS
     * applies end-mode filtering in {@link #pickPerPlacement}.
     */
    public CarriagePartAssignment cycleEndMode(CarriagePartKind kind, String name) {
        List<WeightedName> existing = entries(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<WeightedName> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (WeightedName e : existing) {
            if (!changed && e.name().equals(norm)) {
                updated.add(new WeightedName(e.name(), e.weight(), e.sideMode(), e.endMode().next(), e.gate(), e.stageId()));
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) return this;
        return with(kind, updated);
    }

    /**
     * Step the {@code minLevel} of {@code name}'s gate in {@code kind}'s list by {@code delta}
     * (typically ±1; re-clamped to {@code [0, MAX_LEVEL]} by the gate constructor). Mirrors the
     * template-type editor's {@code minlevel inc|dec}. Returns {@code this} unchanged if the name
     * isn't in the list.
     */
    public CarriagePartAssignment withMinLevel(CarriagePartKind kind, String name, int delta) {
        return mutateGate(kind, name, g -> g.withMinLevel(g.minLevel() + delta));
    }

    /**
     * Step the {@code maxLevel} of {@code name}'s gate one notch in {@code kind}'s list — up for
     * {@code delta >= 0}, down otherwise — cycling the {@link TemplateGate#ALL}↔finite sentinel
     * exactly as the template-type editor's {@code maxlevel inc|dec} does. Returns {@code this}
     * unchanged if the name isn't in the list.
     */
    public CarriagePartAssignment withMaxLevel(CarriagePartKind kind, String name, int delta) {
        return mutateGate(kind, name, g -> delta >= 0 ? g.incMaxLevel() : g.decMaxLevel());
    }

    /**
     * Toggle {@code phase} on/off in the gate of {@code name} in {@code kind}'s list. Toggling the
     * last remaining dimension off normalises back to all dimensions (the gate's "empty ⇒ all"
     * rule), so a gate never becomes "eligible in zero dimensions". Returns {@code this} unchanged
     * if the name isn't in the list.
     */
    public CarriagePartAssignment togglePhase(CarriagePartKind kind, String name, TrainPhase phase) {
        return mutateGate(kind, name, g -> g.withPhase(phase, !g.phases().contains(phase)));
    }

    /**
     * Toggle every dimension <em>except</em> {@code phase} in the gate of {@code name} — the editor's
     * shift-click on a dimension letter ("toggle all but that one"). Returns {@code this} unchanged
     * if the name isn't in the list.
     */
    public CarriagePartAssignment toggleOtherPhases(CarriagePartKind kind, String name, TrainPhase phase) {
        return mutateGate(kind, name, g -> g.toggleOtherPhases(phase));
    }

    /**
     * Shared rewrite for the gate mutators: replace the first entry matching {@code name} with a copy
     * whose gate is {@code op}-transformed, preserving name/weight/sideMode/endMode. No match ⇒
     * {@code this} unchanged.
     */
    private CarriagePartAssignment mutateGate(CarriagePartKind kind, String name,
                                              UnaryOperator<TemplateGate> op) {
        List<WeightedName> existing = entries(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<WeightedName> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (WeightedName e : existing) {
            if (!changed && e.name().equals(norm)) {
                updated.add(new WeightedName(e.name(), e.weight(), e.sideMode(), e.endMode(),
                    op.apply(e.gate()), e.stageId()));
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) return this;
        return with(kind, updated);
    }

    /**
     * Link the entry {@code name} in {@code kind}'s list to the named Stage ({@code stageId}), or
     * detach to Custom when {@code stageId} is null. On detach the previously-effective gate (the
     * Stage's gate, if it was linked) is snapshotted into the inline gate so the entry's manual cells
     * reappear pre-filled. Returns {@code this} unchanged if the name isn't in the list. Mirrors the
     * weight stores' {@code setStage}.
     */
    public CarriagePartAssignment withStage(CarriagePartKind kind, String name, String stageId) {
        String link = (stageId == null || stageId.isBlank()) ? null : stageId.toLowerCase(Locale.ROOT);
        List<WeightedName> existing = entries(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<WeightedName> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (WeightedName e : existing) {
            if (!changed && e.name().equals(norm)) {
                TemplateGate inline = e.gate();
                if (link == null && e.stageId() != null) {
                    inline = games.brennan.dungeontrain.editor.StageStore.effectiveGate(inline, e.stageId());
                }
                updated.add(new WeightedName(e.name(), e.weight(), e.sideMode(), e.endMode(), inline, link));
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) return this;
        return with(kind, updated);
    }

    /**
     * True iff every slot's list is composed entirely of the
     * {@link CarriagePartKind#NONE} sentinel — caller treats this as
     * "no parts declared" and falls back to the monolithic template path.
     */
    public boolean allNone() {
        return isAllNone(floor) && isAllNone(walls) && isAllNone(roof) && isAllNone(doors);
    }

    private static boolean isAllNone(List<WeightedName> list) {
        for (WeightedName e : list) if (!CarriagePartKind.NONE.equals(e.name())) return false;
        return true;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(SCHEMA_KEY, SCHEMA_VERSION);
        o.add("floor", toArray(floor));
        o.add("walls", toArray(walls));
        o.add("roof",  toArray(roof));
        o.add("doors", toArray(doors));
        return o;
    }

    private static JsonArray toArray(List<WeightedName> list) {
        JsonArray arr = new JsonArray();
        for (WeightedName e : list) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", e.name());
            obj.addProperty("weight", e.weight());
            // Only emit sideMode / endMode when they differ from the
            // default — keeps generated parts.json files compact for
            // floor/roof entries and pre-existing two-side variants.
            if (e.sideMode() != SideMode.BOTH) {
                obj.addProperty("sideMode", e.sideMode().name().toLowerCase(Locale.ROOT));
            }
            if (e.endMode() != EndMode.BOTH) {
                obj.addProperty("endMode", e.endMode().name().toLowerCase(Locale.ROOT));
            }
            // Gate fields (minLevel / maxLevel / phases) — emitted only when non-default, in the
            // shared TemplateWeightCodec shape so parts sidecars match the weight stores on disk.
            TemplateWeightCodec.writeGateFields(obj, e.gate());
            // Optional Stage link — when set, this entry's effective gate is the Stage's gate.
            if (e.stageId() != null) obj.addProperty(TemplateWeightCodec.K_STAGE, e.stageId());
            arr.add(obj);
        }
        return arr;
    }

    public static CarriagePartAssignment fromJson(JsonObject o) {
        return new CarriagePartAssignment(
            readList(o, "floor"),
            readList(o, "walls"),
            readList(o, "roof"),
            readList(o, "doors")
        );
    }

    /**
     * Tolerant slot reader. Accepts:
     * <ul>
     *   <li>missing key → {@code [{"none", 1}]}</li>
     *   <li>scalar string (legacy v0) → {@code [{name, 1}]}</li>
     *   <li>array of strings (v1) → each → {@code {name, 1}}</li>
     *   <li>array of {@code {name, weight}} objects (v2) → as-given, weight clamped</li>
     * </ul>
     */
    private static List<WeightedName> readList(JsonObject o, String key) {
        if (!o.has(key)) return List.of(WeightedName.of(CarriagePartKind.NONE));
        JsonElement el = o.get(key);
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) return List.of(WeightedName.of(p.getAsString()));
            return List.of(WeightedName.of(CarriagePartKind.NONE));
        }
        if (!el.isJsonArray()) return List.of(WeightedName.of(CarriagePartKind.NONE));
        List<WeightedName> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                JsonPrimitive p = item.getAsJsonPrimitive();
                if (p.isString()) out.add(WeightedName.of(p.getAsString()));
                continue;
            }
            if (!item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            String name = obj.has("name") && obj.get("name").isJsonPrimitive()
                ? obj.get("name").getAsString()
                : CarriagePartKind.NONE;
            int weight = obj.has("weight") && obj.get("weight").isJsonPrimitive()
                && obj.get("weight").getAsJsonPrimitive().isNumber()
                ? obj.get("weight").getAsInt()
                : MIN_WEIGHT;
            SideMode mode = obj.has("sideMode") && obj.get("sideMode").isJsonPrimitive()
                ? SideMode.fromId(obj.get("sideMode").getAsString())
                : SideMode.BOTH;
            EndMode endMode = obj.has("endMode") && obj.get("endMode").isJsonPrimitive()
                ? EndMode.fromId(obj.get("endMode").getAsString())
                : EndMode.BOTH;
            // Optional gate fields — absent ⇒ TemplateGate.DEFAULT (eligible everywhere), so v1/v2
            // entries load unchanged.
            TemplateGate gate = TemplateWeightCodec.parseGate(obj);
            // Optional Stage link (v4) — absent ⇒ null (Custom), so v1–v3 entries load unchanged.
            String stageId = TemplateWeightCodec.parseStage(obj);
            out.add(new WeightedName(name, weight, mode, endMode, gate, stageId));
        }
        if (out.isEmpty()) return List.of(WeightedName.of(CarriagePartKind.NONE));
        return out;
    }
}
