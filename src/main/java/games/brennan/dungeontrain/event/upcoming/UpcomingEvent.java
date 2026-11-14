package games.brennan.dungeontrain.event.upcoming;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One community event from the update page's {@code events.json} (dp-relay
 * {@code public/dungeontrain/update/events.json}): what the in-game countdown needs of it. The
 * title and join label are the relay's English, shown verbatim. {@code url} is null when the event
 * has no join link (or an unsafe one).
 */
public record UpcomingEvent(String id, String title, long unixSec, @Nullable String url, @Nullable String joinLabel) {

    /**
     * Every well-formed event in the file. A broken entry is skipped rather than failing the rest;
     * a broken file is no events. Never throws.
     */
    public static List<UpcomingEvent> parseAll(String json) {
        List<UpcomingEvent> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return out;
            JsonElement events = root.getAsJsonObject().get("events");
            if (events == null || !events.isJsonArray()) return out;
            for (JsonElement e : (JsonArray) events) {
                UpcomingEvent event = e.isJsonObject() ? parse(e.getAsJsonObject()) : null;
                if (event != null) out.add(event);
            }
        } catch (RuntimeException ignored) {
            // Malformed JSON: nothing to announce.
        }
        return List.copyOf(out);
    }

    @Nullable
    static UpcomingEvent parse(JsonObject o) {
        String id = string(o, "id");
        String title = string(o, "title");
        long unix = o.has("unix") && o.get("unix").isJsonPrimitive() && o.getAsJsonPrimitive("unix").isNumber()
            ? o.get("unix").getAsLong() : 0L;
        if (id == null || title == null || unix <= 0L) return null;
        String url = string(o, "url");
        if (url != null && !url.startsWith("https://")) url = null;
        return new UpcomingEvent(id, title, unix, url, string(o, "joinLabel"));
    }

    @Nullable
    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString().strip();
        return s.isEmpty() ? null : s;
    }
}
