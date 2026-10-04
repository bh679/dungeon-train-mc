package games.brennan.dungeontrain.client.version;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The games {@link OutdatedVersionNotice} has already spoken in, so a player hears it once per game
 * rather than on every rejoin. A game is one world (one life, one world), keyed by
 * {@link OutdatedVersionNotice#gameKey}; the list lives in one small JSON file under the player-data
 * root, newest last, capped at {@link #CAP} so it never grows without bound.
 *
 * <p>Never throws: an unreadable file reads as empty and a failed write is logged, leaving the key
 * remembered in memory for the rest of the session.</p>
 */
final class OutdatedNoticeSeen {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FILE_NAME = "outdated-notice-seen.json";
    static final int CAP = 200;

    private final Path file;
    private Set<String> keys;

    OutdatedNoticeSeen(Path file) {
        this.file = file;
    }

    boolean contains(String key) {
        return loaded().contains(key);
    }

    /** Remember {@code key} (moved to newest if already known) and write the file. */
    void add(String key) {
        LinkedHashSet<String> next = new LinkedHashSet<>(loaded());
        next.remove(key);
        next.add(key);
        keys = capped(next);
        write(keys);
    }

    private Set<String> loaded() {
        if (keys == null) keys = read();
        return keys;
    }

    static Set<String> capped(LinkedHashSet<String> all) {
        if (all.size() <= CAP) return all;
        List<String> list = new ArrayList<>(all);
        return new LinkedHashSet<>(list.subList(list.size() - CAP, list.size()));
    }

    private Set<String> read() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) out.add(e.getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read {}; treating every game as unseen: {}", file, e.toString());
        }
        return capped(out);
    }

    private void write(Set<String> toWrite) {
        JsonArray arr = new JsonArray();
        toWrite.forEach(arr::add);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new Gson().toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't save {}; the outdated notice may repeat next launch: {}",
                    file, e.toString());
        }
    }
}
