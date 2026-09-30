package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-carriage-variant (or per-portal-room) allow-list that controls which contents variants this
 * shell may spawn with. Stored as a sidecar {@code <id>.contents-allow.json} next to the template.
 *
 * <p>Two explicit decisions are stored — ids switched <b>off</b> ({@code excluded}) and ids switched
 * <b>on</b> ({@code included}); everything else falls to the contents template's own default:</p>
 * <ul>
 *   <li><b>Opt-out</b> (every template that existed before opt-in did): allowed unless excluded. A
 *       file with no {@code included} array — every schema-1 file — behaves exactly as it always has.</li>
 *   <li><b>Opt-in</b> ({@link CarriageContentsWeights#optInFor}, set on every new top-level contents):
 *       excluded unless included. A new contents template therefore starts off everywhere instead of
 *       turning up in every carriage the moment it is saved.</li>
 * </ul>
 *
 * <p>JSON schema:</p>
 * <pre>
 * { "schemaVersion": 2, "excluded": ["lava_pool"], "included": ["new_room"] }
 * </pre>
 */
public record CarriageContentsAllowList(Set<String> excluded, Set<String> included) {

    public static final String SCHEMA_KEY = "schemaVersion";
    public static final int SCHEMA_VERSION = 2;

    /** No explicit decisions — every template at its own default. */
    public static final CarriageContentsAllowList EMPTY =
        new CarriageContentsAllowList(Collections.emptySet(), Collections.emptySet());

    public CarriageContentsAllowList {
        excluded = normalise(excluded);
        included = normalise(included);
        // One id, one decision: an id in both sets would make isAllowed order-dependent. Excluded wins
        // (the conservative reading of a hand-edited file).
        if (!included.isEmpty() && !Collections.disjoint(excluded, included)) {
            TreeSet<String> onlyIncluded = new TreeSet<>(included);
            onlyIncluded.removeAll(excluded);
            included = Collections.unmodifiableSet(onlyIncluded);
        }
    }

    /** Back-compat form — excluded ids only (schema 1). */
    public CarriageContentsAllowList(Set<String> excluded) {
        this(excluded, Collections.emptySet());
    }

    /** Lowercase + dedupe + immutable. TreeSet for stable JSON output ordering. */
    private static Set<String> normalise(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptySet();
        TreeSet<String> norm = new TreeSet<>();
        for (String s : ids) {
            if (s == null || s.isBlank()) continue;
            norm.add(s.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(norm);
    }

    /**
     * Whether {@code id} may spawn here, reading the template's opt-in mark from the live
     * {@link CarriageContentsWeights}. See {@link #isAllowed(String, boolean)}.
     */
    public boolean isAllowed(String id) {
        if (id == null) return true;
        return isAllowed(id, CarriageContentsWeights.current().optInFor(id));
    }

    /** Excluded → no; included → yes; otherwise the template's own default (opt-in = no). */
    public boolean isAllowed(String id, boolean optIn) {
        if (id == null) return true;
        String norm = id.toLowerCase(Locale.ROOT);
        if (excluded.contains(norm)) return false;
        if (included.contains(norm)) return true;
        return !optIn;
    }

    /** Record an explicit ON for {@code id}. Idempotent. */
    public CarriageContentsAllowList withAllowed(String id) {
        if (id == null) return this;
        String norm = id.toLowerCase(Locale.ROOT);
        if (included.contains(norm) && !excluded.contains(norm)) return this;
        Set<String> nextExcluded = new LinkedHashSet<>(excluded);
        nextExcluded.remove(norm);
        Set<String> nextIncluded = new LinkedHashSet<>(included);
        nextIncluded.add(norm);
        return new CarriageContentsAllowList(nextExcluded, nextIncluded);
    }

    /** Record an explicit OFF for {@code id}. Idempotent. */
    public CarriageContentsAllowList withExcluded(String id) {
        if (id == null) return this;
        String norm = id.toLowerCase(Locale.ROOT);
        if (excluded.contains(norm) && !included.contains(norm)) return this;
        Set<String> nextExcluded = new LinkedHashSet<>(excluded);
        nextExcluded.add(norm);
        Set<String> nextIncluded = new LinkedHashSet<>(included);
        nextIncluded.remove(norm);
        return new CarriageContentsAllowList(nextExcluded, nextIncluded);
    }

    /** Flip the effective state of {@code id}. */
    public CarriageContentsAllowList toggle(String id) {
        if (id == null) return this;
        return isAllowed(id) ? withExcluded(id) : withAllowed(id);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(SCHEMA_KEY, SCHEMA_VERSION);
        o.add("excluded", toArray(excluded));
        if (!included.isEmpty()) o.add("included", toArray(included));
        return o;
    }

    private static JsonArray toArray(Set<String> ids) {
        JsonArray arr = new JsonArray();
        for (String s : ids) arr.add(s);
        return arr;
    }

    /**
     * Tolerant reader. Missing or non-array {@code excluded} / {@code included} → empty set.
     * Non-string entries are skipped. Schema version is read but ignored; the format is
     * forward-compatible by being additive only.
     */
    public static CarriageContentsAllowList fromJson(JsonObject o) {
        if (o == null) return EMPTY;
        return new CarriageContentsAllowList(readIds(o.get("excluded")), readIds(o.get("included")));
    }

    private static Set<String> readIds(JsonElement el) {
        if (el == null || !el.isJsonArray()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) continue;
            JsonPrimitive p = item.getAsJsonPrimitive();
            if (!p.isString()) continue;
            String s = p.getAsString();
            if (s.isBlank()) continue;
            out.add(s);
        }
        return out;
    }
}
