package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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
 * <p>JSON schema is forward and backwards tolerant:
 * <ul>
 *   <li><b>v2</b> (current) — array of {@code {"name": "...", "weight": N}} objects.</li>
 *   <li><b>v1</b> — array of bare strings; loaded with {@code weight=1}.</li>
 *   <li><b>v0 scalar</b> — a single bare string in a slot; normalised to one entry at weight 1.</li>
 * </ul></p>
 *
 * <pre>
 * { "schemaVersion": 2,
 *   "floor": [ { "name": "wood", "weight": 3 } ],
 *   "walls": [ { "name": "standard_walls", "weight": 1 },
 *              { "name": "none", "weight": 1 } ],
 *   "roof":  [ { "name": "standard", "weight": 1 } ],
 *   "doors": [] }
 * </pre>
 */
public record CarriagePartAssignment(List<WeightedName> floor, List<WeightedName> walls,
                                     List<WeightedName> roof, List<WeightedName> doors) {

    public static final String SCHEMA_KEY = "schemaVersion";
    public static final int SCHEMA_VERSION = 2;

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
     * A candidate name with its pick weight, side-mode constraint, and
     * end-mode constraint. Weight is clamped to {@code [MIN_WEIGHT,
     * MAX_WEIGHT]}; sideMode defaults to {@link SideMode#BOTH} and
     * endMode defaults to {@link EndMode#BOTH} so legacy entries (and
     * floor / roof / wall entries that don't care about position-along-
     * the-group) match the original mirroring behaviour.
     */
    public record WeightedName(String name, int weight, SideMode sideMode, EndMode endMode) {
        public WeightedName {
            name = (name == null || name.isBlank())
                ? CarriagePartKind.NONE
                : name.toLowerCase(Locale.ROOT);
            weight = clampWeight(weight);
            if (sideMode == null) sideMode = SideMode.BOTH;
            if (endMode == null) endMode = EndMode.BOTH;
        }

        /** Default-weight, BOTH-side, BOTH-end entry. */
        public static WeightedName of(String name) {
            return new WeightedName(name, MIN_WEIGHT, SideMode.BOTH, EndMode.BOTH);
        }

        /** Default-weight entry with explicit side mode (endMode defaults to BOTH). */
        public static WeightedName of(String name, SideMode sideMode) {
            return new WeightedName(name, MIN_WEIGHT, sideMode, EndMode.BOTH);
        }

        /** 2-arg back-compat constructor — defaults sideMode and endMode to BOTH. */
        public WeightedName(String name, int weight) {
            this(name, weight, SideMode.BOTH, EndMode.BOTH);
        }

        /** 3-arg back-compat constructor — defaults endMode to BOTH. */
        public WeightedName(String name, int weight, SideMode sideMode) {
            this(name, weight, sideMode, EndMode.BOTH);
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
        List<WeightedName> list = entries(kind);
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
        return pickPerPlacement(kind, seed, carriageIndex, false, false);
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
        boolean twoPlacements = kind == CarriagePartKind.WALLS || kind == CarriagePartKind.DOORS;
        if (!twoPlacements) {
            return List.of(pick(kind, seed, carriageIndex));
        }

        List<WeightedName> list = entries(kind);
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
                updated.add(new WeightedName(e.name(), clampWeight(e.weight() + delta), e.sideMode(), e.endMode()));
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
                updated.add(new WeightedName(e.name(), e.weight(), e.sideMode().next(), e.endMode()));
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
                updated.add(new WeightedName(e.name(), e.weight(), e.sideMode(), e.endMode().next()));
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
            out.add(new WeightedName(name, weight, mode, endMode));
        }
        if (out.isEmpty()) return List.of(WeightedName.of(CarriagePartKind.NONE));
        return out;
    }
}
