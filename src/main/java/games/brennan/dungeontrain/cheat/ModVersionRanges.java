package games.brennan.dungeontrain.cheat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version requirements on approved mods — "approved from 1.7.0 up" rather than "approved, any
 * version". A whitelist entry with no requirement approves every version, which is how every entry
 * behaved before requirements existed.
 *
 * <p>A requirement is written the way mods.toml writes one: a Maven range ({@code "[1.7.0,2.0)"},
 * {@code "[1.2,1.5],[1.8,)"}), or a <b>bare version</b> ({@code "1.7.0"}) which means <b>that
 * version and above</b>. The bare form is deliberately NOT Maven's "recommended version" (which
 * matches anything) — "above a floor" is the case this exists for, so it gets the short spelling.</p>
 *
 * <p>Parsing is defensive in the direction that hurts nobody: a requirement that cannot be read is
 * dropped, leaving the mod approved at any version. The other direction would Free-Play every
 * honest player running that mod over a typo.</p>
 */
public final class ModVersionRanges {

    /** A parsed requirement plus the text it came from (for messages). */
    public record Requirement(String spec, VersionRange range) {
        /** Does {@code version} satisfy this requirement? An unreadable version never does. */
        public boolean allows(String version) {
            if (version == null || version.isBlank()) return false;
            return range.containsVersion(new DefaultArtifactVersion(version.trim()));
        }

        /** Short human form: {@code "1.7.0+"} for a bare floor, the range text otherwise. */
        public String describe() {
            return isBare(spec) ? spec + "+" : spec;
        }
    }

    private static final int MAX_ENTRIES = 20000;

    private ModVersionRanges() {}

    /** Parse one requirement, or {@code null} if it is blank or not a valid version/range. */
    public static Requirement parse(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;
        try {
            String maven = isBare(s) ? "[" + s + ",)" : s;
            VersionRange range = VersionRange.createFromVersionSpec(maven);
            // A spec like "[]" or one that yields no restriction is not a requirement at all.
            if (range.getRestrictions().isEmpty()) return null;
            return new Requirement(s, range);
        } catch (InvalidVersionSpecificationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Read a {@code {"modid": "spec", …}} object into id → requirement. IDs are normalised and
     * validated like every other whitelist ID; invalid IDs and unreadable specs are skipped.
     * Returns an immutable map; empty when the element is missing or not an object.
     */
    public static Map<String, Requirement> fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) return Map.of();
        Map<String, Requirement> out = new LinkedHashMap<>();
        for (var e : el.getAsJsonObject().entrySet()) {
            if (out.size() >= MAX_ENTRIES) break;
            String id = ModIds.normalise(e.getKey());
            if (id.isEmpty() || !ModIds.isValid(id)) continue;
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) continue;
            Requirement r = parse(v.getAsString());
            if (r != null) out.put(id, r);
        }
        return Map.copyOf(out);
    }

    /** The inverse of {@link #fromJson}: id → original spec text. */
    public static JsonObject toJson(Map<String, Requirement> requirements) {
        JsonObject obj = new JsonObject();
        requirements.forEach((id, r) -> obj.addProperty(id, r.spec()));
        return obj;
    }

    private static boolean isBare(String s) {
        return !s.isEmpty() && s.charAt(0) != '[' && s.charAt(0) != '(';
    }
}
