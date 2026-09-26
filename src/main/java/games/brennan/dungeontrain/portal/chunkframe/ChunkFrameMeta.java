package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a frame says about where it is used — its {@code <name>.frame.json}, beside its structure.
 *
 * <p>{@link #rooms} is the chunk-dimension room variants the frame dresses. Null means <b>every</b>
 * chunk dimension, which is what a frame with no file (or a file without the list) does, so a new
 * frame is live everywhere until its author narrows it. An empty set is "nowhere" — a frame parked
 * without deleting it.</p>
 *
 * <p>{@link #weight} is how often the frame is picked against the other frames that dress the same
 * room.</p>
 */
public record ChunkFrameMeta(Set<String> rooms, int weight) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 100;

    /** Every chunk dimension, weight 1 — a frame with nothing said about it. */
    public static final ChunkFrameMeta DEFAULT = new ChunkFrameMeta(null, MIN_WEIGHT);

    public ChunkFrameMeta {
        rooms = rooms == null ? null : Set.copyOf(new LinkedHashSet<>(rooms));
        weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
    }

    /** True when the frame dresses every chunk dimension. */
    public boolean allRooms() {
        return rooms == null;
    }

    /** True when the frame dresses {@code room}. */
    public boolean appliesTo(String room) {
        return rooms == null || rooms.contains(room);
    }

    /**
     * This meta with {@code room} switched on or off. Switching one off from "every room" first
     * spells "every room" out as {@code allRooms}, so the others stay on.
     */
    public ChunkFrameMeta toggled(String room, boolean on, List<String> allRooms) {
        Set<String> next = new LinkedHashSet<>(rooms == null ? allRooms : rooms);
        if (on) next.add(room); else next.remove(room);
        // Back to "every room" when it is every room, so a later chunk dimension joins it too.
        if (next.containsAll(allRooms)) return new ChunkFrameMeta(null, weight);
        return new ChunkFrameMeta(next, weight);
    }

    public ChunkFrameMeta withAllRooms() {
        return new ChunkFrameMeta(null, weight);
    }

    public ChunkFrameMeta withNoRooms() {
        return new ChunkFrameMeta(Set.of(), weight);
    }

    public ChunkFrameMeta withWeight(int newWeight) {
        return new ChunkFrameMeta(rooms, newWeight);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", SCHEMA_VERSION);
        o.addProperty("weight", weight);
        if (rooms != null) {
            JsonArray arr = new JsonArray();
            rooms.stream().sorted().forEach(arr::add);
            o.add("rooms", arr);
        }
        return o;
    }

    /** Total: anything unreadable falls back to the default for that field. */
    public static ChunkFrameMeta fromJson(JsonObject o) {
        int weight = o.has("weight") && o.get("weight").isJsonPrimitive() ? o.get("weight").getAsInt() : MIN_WEIGHT;
        Set<String> rooms = null;
        if (o.has("rooms") && o.get("rooms").isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("rooms")) {
                if (el.isJsonPrimitive()) list.add(el.getAsString());
            }
            rooms = new LinkedHashSet<>(list);
        }
        return new ChunkFrameMeta(rooms, weight);
    }
}
