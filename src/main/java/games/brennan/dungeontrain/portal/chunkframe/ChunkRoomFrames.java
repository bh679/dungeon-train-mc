package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The frames a dimensional carriage room draws from, by weight — its {@code <room>.frames.json}.
 *
 * <p>Immutable: every edit returns a new list. A room with no entries has no frame and stands in its
 * lock skin.</p>
 */
public record ChunkRoomFrames(List<Entry> entries) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 100;

    public static final ChunkRoomFrames EMPTY = new ChunkRoomFrames(List.of());

    public record Entry(String name, int weight) {
        public Entry {
            weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
        }
    }

    public ChunkRoomFrames {
        entries = List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** {@code name} at {@code weight} — replacing its entry in place when it already has one. */
    public ChunkRoomFrames with(String name, int weight) {
        List<Entry> out = new ArrayList<>(entries);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).name().equals(name)) {
                out.set(i, new Entry(name, weight));
                return new ChunkRoomFrames(out);
            }
        }
        out.add(new Entry(name, weight));
        return new ChunkRoomFrames(out);
    }

    public ChunkRoomFrames without(String name) {
        List<Entry> out = new ArrayList<>(entries);
        out.removeIf(e -> e.name().equals(name));
        return new ChunkRoomFrames(out);
    }

    /** The frame {@code seed} lands on, by weight, or null when there are none. Deterministic in the seed. */
    public String pick(long seed) {
        if (entries.isEmpty()) return null;
        int total = 0;
        for (Entry e : entries) total += e.weight();
        long mixed = seed * 0x9E3779B97F4A7C15L;
        int roll = (int) Math.floorMod(mixed ^ (mixed >>> 31), (long) total);
        for (Entry e : entries) {
            roll -= e.weight();
            if (roll < 0) return e.name();
        }
        return entries.get(entries.size() - 1).name();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", e.name());
            item.addProperty("weight", e.weight());
            arr.add(item);
        }
        o.add("frames", arr);
        return o;
    }

    /** Total: an unreadable entry is skipped, a missing list reads as no frames. */
    public static ChunkRoomFrames fromJson(JsonObject o) {
        List<Entry> out = new ArrayList<>();
        if (o.has("frames") && o.get("frames").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("frames")) {
                if (el.isJsonPrimitive()) {
                    out.add(new Entry(el.getAsString(), MIN_WEIGHT));
                } else if (el.isJsonObject() && el.getAsJsonObject().has("name")) {
                    JsonObject item = el.getAsJsonObject();
                    int weight = item.has("weight") ? item.get("weight").getAsInt() : MIN_WEIGHT;
                    out.add(new Entry(item.get("name").getAsString(), weight));
                }
            }
        }
        return new ChunkRoomFrames(out);
    }
}
