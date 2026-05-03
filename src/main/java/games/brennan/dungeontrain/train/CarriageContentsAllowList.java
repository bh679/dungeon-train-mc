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
 * Per-carriage-variant allow-list that controls which contents variants this
 * shell may spawn with. Stored as a sidecar {@code <variant-id>.contents-allow.json}
 * next to the carriage NBT. The wire model is "store only the excluded ids" —
 * default = empty set = every registered contents is allowed, and a content
 * added to the registry later is automatically allowed for every existing
 * carriage without rewriting their sidecar files.
 *
 * <p>JSON schema:</p>
 * <pre>
 * { "schemaVersion": 1, "excluded": ["lava_pool", "spawner"] }
 * </pre>
 */
public record CarriageContentsAllowList(Set<String> excluded) {

    public static final String SCHEMA_KEY = "schemaVersion";
    public static final int SCHEMA_VERSION = 1;

    /** All-allowed default. */
    public static final CarriageContentsAllowList EMPTY =
        new CarriageContentsAllowList(Collections.emptySet());

    public CarriageContentsAllowList {
        if (excluded == null) {
            excluded = Collections.emptySet();
        } else {
            // Lowercase + dedupe + immutable. TreeSet for stable JSON output ordering.
            TreeSet<String> norm = new TreeSet<>();
            for (String s : excluded) {
                if (s == null || s.isBlank()) continue;
                norm.add(s.toLowerCase(Locale.ROOT));
            }
            excluded = Collections.unmodifiableSet(norm);
        }
    }

    /** True iff {@code id} is NOT in the excluded set. */
    public boolean isAllowed(String id) {
        if (id == null) return true;
        return !excluded.contains(id.toLowerCase(Locale.ROOT));
    }

    /** Idempotent — returns this if {@code id} was already allowed. */
    public CarriageContentsAllowList withAllowed(String id) {
        if (id == null) return this;
        String norm = id.toLowerCase(Locale.ROOT);
        if (!excluded.contains(norm)) return this;
        Set<String> next = new LinkedHashSet<>(excluded);
        next.remove(norm);
        return new CarriageContentsAllowList(next);
    }

    /** Idempotent — returns this if {@code id} was already excluded. */
    public CarriageContentsAllowList withExcluded(String id) {
        if (id == null) return this;
        String norm = id.toLowerCase(Locale.ROOT);
        if (excluded.contains(norm)) return this;
        Set<String> next = new LinkedHashSet<>(excluded);
        next.add(norm);
        return new CarriageContentsAllowList(next);
    }

    /** Flip the membership of {@code id}. */
    public CarriageContentsAllowList toggle(String id) {
        if (id == null) return this;
        return isAllowed(id) ? withExcluded(id) : withAllowed(id);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty(SCHEMA_KEY, SCHEMA_VERSION);
        JsonArray arr = new JsonArray();
        for (String s : excluded) arr.add(s);
        o.add("excluded", arr);
        return o;
    }

    /**
     * Tolerant reader. Missing or non-array {@code excluded} → empty set.
     * Non-string entries are skipped. Schema version is read but ignored;
     * the format is forward-compatible by being additive only.
     */
    public static CarriageContentsAllowList fromJson(JsonObject o) {
        if (o == null) return EMPTY;
        JsonElement el = o.get("excluded");
        if (el == null || !el.isJsonArray()) return EMPTY;
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) continue;
            JsonPrimitive p = item.getAsJsonPrimitive();
            if (!p.isString()) continue;
            String s = p.getAsString();
            if (s.isBlank()) continue;
            out.add(s);
        }
        return new CarriageContentsAllowList(out);
    }
}
