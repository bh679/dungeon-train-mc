package games.brennan.dungeontrain.client.modcheck;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.ModIds;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * The mods this player has already suggested for the whitelist, kept in
 * {@code config/dungeontrain-mod-suggestions.json} so the Unsupported Mods screen can show them as
 * done (✔) on later launches instead of offering the button again.
 *
 * <p>A local convenience only — the relay is the record. Losing this file just re-offers the button,
 * and resending replaces the player's earlier vote rather than adding a second one. The set is
 * swapped whole on every change (never mutated), and the file is written atomically
 * (tmp-then-rename, like {@code ApprovedModList}'s cache).</p>
 */
public final class SuggestedMods {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String FILE_NAME = "dungeontrain-mod-suggestions.json";
    private static final int MAX_IDS = 500;

    private static volatile Set<String> ids;

    private SuggestedMods() {}

    /** Has this player suggested {@code modId} before? */
    public static boolean contains(String modId) {
        return load().contains(ModIds.normalise(modId));
    }

    /** Remember that {@code modId} was suggested. Best-effort — never throws. */
    public static synchronized void add(String modId) {
        String id = ModIds.normalise(modId);
        if (!ModIds.isValid(id)) return;
        Set<String> current = load();
        if (current.contains(id) || current.size() >= MAX_IDS) return;
        Set<String> next = new HashSet<>(current);
        next.add(id);
        ids = Set.copyOf(next);
        save(ids);
    }

    private static synchronized Set<String> load() {
        if (ids != null) return ids;
        ids = Set.of();
        Path file = file();
        if (file == null || !Files.exists(file)) return ids;
        try {
            ids = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] could not read {}: {}", file, e.toString());
        }
        return ids;
    }

    /** Pure: {@code {"suggested":[…]}} → a clean id set. Any malformed body → empty. */
    static Set<String> parse(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("suggested")
                || !root.getAsJsonObject().get("suggested").isJsonArray()) {
                return Set.of();
            }
            java.util.List<String> raw = new java.util.ArrayList<>();
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("suggested")) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) raw.add(e.getAsString());
            }
            return ModIds.sanitize(raw, MAX_IDS);
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** Pure: the id set → its file form. */
    static String toJson(Set<String> ids) {
        JsonArray arr = new JsonArray();
        ids.stream().sorted().forEach(arr::add);
        JsonObject o = new JsonObject();
        o.add("suggested", arr);
        return o.toString();
    }

    private static void save(Set<String> ids) {
        Path target = file();
        if (target == null) return;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(ids), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] could not write {}: {}", target, e.toString());
        }
    }

    private static Path file() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
