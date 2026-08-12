package games.brennan.dungeontrain.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One unit of synced progress on the wire. The {@code kind} prefix declares how the relay merges it,
 * which is what lets a single mechanism carry things with very different conflict behaviour:
 *
 * <ul>
 *   <li>{@code set:} — a union of string ids. Two machines that each earned different advancements
 *       both keep them; there is no conflict to resolve.</li>
 *   <li>{@code max:} — a per-key maximum of numbers (lifetime distance, best run). Also conflict-free.</li>
 *   <li>{@code lww:} — an opaque base64 blob, last-writer-wins guarded by {@link #seq}. The Ender Chest
 *       is the only thing that needs this, because item contents genuinely cannot be merged: two
 *       divergent chests are two different truths, and silently unioning them would duplicate items.</li>
 * </ul>
 *
 * <p>Only the field matching the strategy is populated; the others are null. See
 * {@code progress.js} on the relay for the authoritative contract.</p>
 */
public record ProgressBlob(String kind, int seq, List<String> ids, Map<String, Double> fields, String data) {

    /** The kinds the mod syncs. Kept here so the mod and the relay never drift on spelling. */
    public static final String ADVANCEMENTS = "set:advancements";
    public static final String NARRATIVE = "set:narrative";
    public static final String BIOMES = "set:biomes";
    public static final String STATS = "max:stats";
    public static final String BOOK_BURNS = "max:bookburns";

    /** Ender Chest kind for one ECP slot, e.g. {@code lww:enderchest.survival}. */
    public static String enderChest(String slotKey) {
        return "lww:enderchest." + slotKey;
    }

    public static ProgressBlob set(String kind, List<String> ids) {
        return new ProgressBlob(kind, 0, List.copyOf(ids), null, null);
    }

    public static ProgressBlob max(String kind, Map<String, Double> fields) {
        return new ProgressBlob(kind, 0, null, Map.copyOf(fields), null);
    }

    public static ProgressBlob lww(String kind, int seq, String base64) {
        return new ProgressBlob(kind, seq, null, null, base64);
    }

    /** The strategy prefix — {@code set}, {@code max} or {@code lww}. */
    public String strategy() {
        int i = kind.indexOf(':');
        return i < 0 ? "" : kind.substring(0, i);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        if (ids != null) {
            JsonArray arr = new JsonArray();
            for (String id : ids) arr.add(id);
            o.add("ids", arr);
        }
        if (fields != null) {
            JsonObject f = new JsonObject();
            for (Map.Entry<String, Double> e : fields.entrySet()) f.addProperty(e.getKey(), e.getValue());
            o.add("fields", f);
        }
        if (data != null) {
            o.addProperty("data", data);
            o.addProperty("seq", seq);
        }
        return o;
    }

    /** Parse a blob the relay returned. Returns null when the payload is unusable. */
    public static ProgressBlob fromJson(JsonObject o) {
        if (o == null || !o.has("kind")) return null;
        String kind = o.get("kind").getAsString();
        int seq = o.has("seq") ? o.get("seq").getAsInt() : 0;
        if (o.has("ids") && o.get("ids").isJsonArray()) {
            List<String> ids = new java.util.ArrayList<>();
            for (var e : o.getAsJsonArray("ids")) {
                if (e.isJsonPrimitive()) ids.add(e.getAsString());
            }
            return new ProgressBlob(kind, seq, List.copyOf(ids), null, null);
        }
        if (o.has("fields") && o.get("fields").isJsonObject()) {
            Map<String, Double> fields = new LinkedHashMap<>();
            for (var e : o.getAsJsonObject("fields").entrySet()) {
                if (e.getValue().isJsonPrimitive()) fields.put(e.getKey(), e.getValue().getAsDouble());
            }
            return new ProgressBlob(kind, seq, null, Map.copyOf(fields), null);
        }
        if (o.has("data") && o.get("data").isJsonPrimitive()) {
            return new ProgressBlob(kind, seq, null, null, o.get("data").getAsString());
        }
        return null;
    }
}
