package games.brennan.dungeontrain.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import games.brennan.dungeonbackup.api.RestoreMerger;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * How a restore folds a backed-up cross-world progress file into the one already on disk.
 *
 * <p><b>Why.</b> Dungeon Backup restores are additive: a file already on disk is skipped. DT
 * rewrites the advancement sidecar on <em>every world join</em> (the login back-fill), so after a
 * data loss the player's first world recreates a near-empty file before they ever reach the
 * restore button, and the restore then kept that one and dropped every advancement in the backup.
 * The stats and narrative stores regenerate the same way.</p>
 *
 * <p><b>The rule.</b> Every value in these three stores only grows — counters are only ever added
 * to, id and index lists only ever gain entries; the one way anything shrinks is the Video Tools
 * profile reset, which deletes the file outright. So a merge can never be wrong by being generous:
 * objects merge key by key, numbers take the larger side, arrays take the union (live order first,
 * backed-up extras appended), and anything else keeps the live value. Being structural rather than
 * per-field, a counter added to a store later is merged with no change here.</p>
 *
 * <p>Either side failing to parse leaves the live file alone — the library treats an empty result
 * as "don't touch".</p>
 */
public final class RestoreMergers {

    private RestoreMergers() {}

    /** Globs (under DT's archive root label) whose existing files are merged, not skipped. */
    public static final Map<String, RestoreMerger> BY_GLOB = Map.of(
        PlayerDataPaths.ACHIEVEMENTS + "/*.json", growOnly(UnaryOperator.identity()),
        // Stats files predating the nested echoes/distance objects are migrated first, so a legacy
        // backup's top-level totals land in the same fields the live file keeps them in.
        PlayerDataPaths.STATS + "/*.json", growOnly(GlobalPlayerStats::migrateLegacy),
        PlayerDataPaths.NARRATIVE + "/*.json", growOnly(UnaryOperator.identity()));

    /** A merger that parses both sides, normalises each with {@code normalise}, and merges them. */
    static RestoreMerger growOnly(UnaryOperator<JsonElement> normalise) {
        return (live, backedUp) -> {
            Optional<JsonObject> a = parse(live).map(normalise).flatMap(RestoreMergers::asObject);
            Optional<JsonObject> b = parse(backedUp).map(normalise).flatMap(RestoreMergers::asObject);
            if (a.isEmpty() || b.isEmpty()) return Optional.empty();
            return Optional.of(merge(a.get(), b.get()).toString().getBytes(StandardCharsets.UTF_8));
        };
    }

    /**
     * The grow-only union of two JSON values — see the class doc for the rule. Returns a new tree;
     * neither argument is modified.
     */
    public static JsonElement merge(JsonElement live, JsonElement backedUp) {
        if (live == null || live.isJsonNull()) return backedUp == null ? live : backedUp.deepCopy();
        if (backedUp == null || backedUp.isJsonNull()) return live.deepCopy();
        if (live.isJsonObject() && backedUp.isJsonObject()) {
            return mergeObjects(live.getAsJsonObject(), backedUp.getAsJsonObject());
        }
        if (live.isJsonArray() && backedUp.isJsonArray()) {
            return unionArrays(live.getAsJsonArray(), backedUp.getAsJsonArray());
        }
        if (isNumber(live) && isNumber(backedUp)) {
            return larger(live.getAsJsonPrimitive(), backedUp.getAsJsonPrimitive());
        }
        return live.deepCopy();
    }

    private static JsonObject mergeObjects(JsonObject live, JsonObject backedUp) {
        JsonObject out = new JsonObject();
        for (var entry : live.entrySet()) {
            out.add(entry.getKey(), merge(entry.getValue(), backedUp.get(entry.getKey())));
        }
        for (var entry : backedUp.entrySet()) {
            if (!out.has(entry.getKey())) out.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return out;
    }

    private static JsonArray unionArrays(JsonArray live, JsonArray backedUp) {
        Set<JsonElement> seen = new LinkedHashSet<>();
        live.forEach(seen::add);
        backedUp.forEach(seen::add);
        JsonArray out = new JsonArray();
        seen.forEach(e -> out.add(e.deepCopy()));
        return out;
    }

    private static JsonPrimitive larger(JsonPrimitive a, JsonPrimitive b) {
        return a.getAsBigDecimal().compareTo(b.getAsBigDecimal()) >= 0 ? a.deepCopy() : b.deepCopy();
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static Optional<JsonElement> parse(byte[] bytes) {
        try {
            return Optional.of(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)));
        } catch (JsonParseException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static Optional<JsonObject> asObject(JsonElement e) {
        return e != null && e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
    }
}
