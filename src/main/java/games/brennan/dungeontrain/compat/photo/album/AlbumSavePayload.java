package games.brennan.dungeontrain.compat.photo.album;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The wire shapes of an album, without any game state: the save the relay keeps
 * ({@code POST /albums/save}), the album it hands back ({@code GET /albums/mine}, {@code /albums/pool}),
 * and the rule for numbering saves.
 */
public final class AlbumSavePayload {

    /** Exposure's {@code AlbumContent.MAX_PAGES}. */
    public static final int MAX_PAGES = 16;

    /** One page as the relay knows it: its picture's hash (null for an empty or private page) and its note. */
    public record Page(String hash, String note) {
        public Page {
            hash = AlbumImageHash.isHash(hash) ? hash : null;
            note = note == null ? "" : note;
        }
    }

    /** An album from the relay. */
    public record Album(UUID owner, String name, long rev, List<Page> pages) {
        public Album {
            pages = List.copyOf(pages);
        }
    }

    private AlbumSavePayload() {}

    /**
     * The next save's number: the clock, so a save from any world outranks an older one from any
     * other, but always past the last save this world made, so a fast second edit is never lost.
     */
    public static long nextRev(long previous, long nowMillis) {
        return Math.max(nowMillis, previous + 1);
    }

    public static String bare(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    static UUID fromBare(String bare) {
        if (bare == null || bare.length() != 32) throw new IllegalArgumentException("bad uuid " + bare);
        return UUID.fromString(bare.substring(0, 8) + "-" + bare.substring(8, 12) + "-" + bare.substring(12, 16)
                + "-" + bare.substring(16, 20) + "-" + bare.substring(20));
    }

    public static JsonObject save(UUID owner, String name, long rev, List<Page> pages) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", bare(owner));
        body.addProperty("name", name == null ? "" : name);
        body.addProperty("rev", rev);
        JsonArray array = new JsonArray();
        for (Page page : pages.subList(0, Math.min(pages.size(), MAX_PAGES))) {
            JsonObject p = new JsonObject();
            if (page.hash() == null) p.add("hash", JsonNull.INSTANCE);
            else p.addProperty("hash", page.hash());
            p.addProperty("note", page.note());
            array.add(p);
        }
        body.add("pages", array);
        return body;
    }

    public static JsonObject image(UUID owner, String base64Png) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", bare(owner));
        body.addProperty("image", base64Png);
        return body;
    }

    /** An album object from the relay, or empty when it is missing or malformed. */
    public static Optional<Album> parse(JsonElement element) {
        if (element == null || !element.isJsonObject()) return Optional.empty();
        try {
            JsonObject o = element.getAsJsonObject();
            UUID owner = fromBare(o.get("uuid").getAsString());
            String name = o.has("name") ? o.get("name").getAsString() : "";
            long rev = o.get("rev").getAsLong();
            List<Page> pages = new ArrayList<>();
            JsonArray array = o.getAsJsonArray("pages");
            for (int i = 0; array != null && i < array.size() && i < MAX_PAGES; i++) {
                JsonObject p = array.get(i).getAsJsonObject();
                String hash = p.has("hash") && !p.get("hash").isJsonNull() ? p.get("hash").getAsString() : null;
                String note = p.has("note") && !p.get("note").isJsonNull() ? p.get("note").getAsString() : "";
                pages.add(new Page(hash, note));
            }
            return Optional.of(new Album(owner, name, rev, pages));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
