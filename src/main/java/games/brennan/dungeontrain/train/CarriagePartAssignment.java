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
 * names. Stored as a sidecar {@code <variant-id>.parts.json} next to the
 * carriage NBT; when present with at least one resolvable part, it takes
 * precedence over the monolithic NBT at spawn time and
 * {@code CarriageTemplate.placeAt} composes the carriage from parts.
 *
 * <p>Each slot is a <b>list</b> of part names. At spawn, one entry is picked
 * deterministically from {@code (worldSeed, carriageIndex, kind.ordinal())}
 * so the same slot in the same carriage on the same track renders the same
 * visual across reloads. The picked entry may be the sentinel
 * {@link CarriagePartKind#NONE} — in which case that kind's stamp is skipped
 * entirely (the FLATBED case: {@code walls=["none"]} leaves the sides open).</p>
 *
 * <p>JSON schema is scalar-or-array tolerant: a bare string in a slot is
 * normalised to a single-element list on load, so older sidecars (the
 * original scalar schema) still parse.</p>
 *
 * <pre>
 * { "schemaVersion": 1,
 *   "floor": "wood",                    // scalar, single-option
 *   "walls": ["standard_walls", "none"], // 50/50 walls-or-flatbed
 *   "roof":  ["solid_roof"],
 *   "doors": [] }                        // empty == ["none"]
 * </pre>
 */
public record CarriagePartAssignment(List<String> floor, List<String> walls,
                                     List<String> roof, List<String> doors) {

    public static final String SCHEMA_KEY = "schemaVersion";
    public static final int SCHEMA_VERSION = 1;

    /** Mixer constant — same as {@code CarriageTemplate.seededPick}. Adjacent indices don't correlate. */
    private static final long MIX = 0x9E3779B97F4A7C15L;

    /** Empty assignment — every kind set to {@link CarriagePartKind#NONE}. Caller short-circuits to monolithic. */
    public static final CarriagePartAssignment EMPTY = new CarriagePartAssignment(
        List.of(CarriagePartKind.NONE),
        List.of(CarriagePartKind.NONE),
        List.of(CarriagePartKind.NONE),
        List.of(CarriagePartKind.NONE)
    );

    public CarriagePartAssignment {
        floor = normalize(floor);
        walls = normalize(walls);
        roof  = normalize(roof);
        doors = normalize(doors);
    }

    /**
     * Lowercase each entry; null / blank → {@link CarriagePartKind#NONE};
     * null / empty list → {@code ["none"]}. Returns an immutable copy so the
     * record's fields can't be mutated via a caller's retained reference.
     */
    private static List<String> normalize(List<String> list) {
        if (list == null || list.isEmpty()) return List.of(CarriagePartKind.NONE);
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            if (s == null || s.isBlank()) out.add(CarriagePartKind.NONE);
            else out.add(s.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    /** Candidate names for {@code kind} — always non-empty (at minimum {@code ["none"]}). */
    public List<String> names(CarriagePartKind kind) {
        return switch (kind) {
            case FLOOR -> floor;
            case WALLS -> walls;
            case ROOF  -> roof;
            case DOORS -> doors;
        };
    }

    /**
     * Pick one candidate name for {@code kind} at carriage index
     * {@code carriageIndex}. Deterministic in {@code (seed, carriageIndex,
     * kind.ordinal())} — the same triple always yields the same pick, so
     * carriages re-rendered by the rolling-window manager match what was
     * placed on the first pass.
     *
     * <p>Single-element lists short-circuit to return that element directly
     * (no RNG cost for the common 1-option case).</p>
     */
    public String pick(CarriagePartKind kind, long seed, int carriageIndex) {
        List<String> list = names(kind);
        if (list.size() == 1) return list.get(0);
        long mixed = seed ^ ((long) carriageIndex * MIX) ^ ((long) kind.ordinal() * 0xC6BC279692B5C323L);
        int idx = Math.floorMod(new Random(mixed).nextInt(), list.size());
        return list.get(idx);
    }

    /** Replace the list for {@code kind} entirely. Normalised like the canonical constructor. */
    public CarriagePartAssignment with(CarriagePartKind kind, List<String> newList) {
        List<String> n = normalize(newList);
        return switch (kind) {
            case FLOOR -> new CarriagePartAssignment(n, walls, roof, doors);
            case WALLS -> new CarriagePartAssignment(floor, n, roof, doors);
            case ROOF  -> new CarriagePartAssignment(floor, walls, n, doors);
            case DOORS -> new CarriagePartAssignment(floor, walls, roof, n);
        };
    }

    /**
     * Append {@code name} to {@code kind}'s list. When the existing list is
     * just {@code ["none"]} (the default placeholder), the append replaces
     * it rather than producing {@code ["none", name]} — the author just set
     * the first real option, they almost certainly didn't also mean "and
     * with equal probability stamp nothing".
     */
    public CarriagePartAssignment withAppended(CarriagePartKind kind, String name) {
        List<String> existing = names(kind);
        String norm = (name == null || name.isBlank()) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<String> updated;
        if (existing.size() == 1 && CarriagePartKind.NONE.equals(existing.get(0))
            && !CarriagePartKind.NONE.equals(norm)) {
            updated = List.of(norm);
        } else {
            updated = new ArrayList<>(existing);
            updated.add(norm);
        }
        return with(kind, updated);
    }

    /**
     * Remove the first occurrence of {@code name} from {@code kind}'s list.
     * If that empties the list, {@link #normalize} restores {@code ["none"]}.
     */
    public CarriagePartAssignment withRemoved(CarriagePartKind kind, String name) {
        List<String> existing = names(kind);
        String norm = (name == null) ? CarriagePartKind.NONE : name.toLowerCase(Locale.ROOT);
        List<String> updated = new ArrayList<>(existing);
        updated.remove(norm);
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

    private static boolean isAllNone(List<String> list) {
        for (String s : list) if (!CarriagePartKind.NONE.equals(s)) return false;
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

    private static JsonArray toArray(List<String> list) {
        JsonArray arr = new JsonArray();
        for (String s : list) arr.add(s);
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

    private static List<String> readList(JsonObject o, String key) {
        if (!o.has(key)) return List.of(CarriagePartKind.NONE);
        JsonElement el = o.get(key);
        // Backwards-compat: accept a scalar string (original schema) as a one-element list.
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) return List.of(p.getAsString());
            return List.of(CarriagePartKind.NONE);
        }
        if (!el.isJsonArray()) return List.of(CarriagePartKind.NONE);
        List<String> out = new ArrayList<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) continue;
            JsonPrimitive p = item.getAsJsonPrimitive();
            if (!p.isString()) continue;
            out.add(p.getAsString());
        }
        return out;
    }
}
