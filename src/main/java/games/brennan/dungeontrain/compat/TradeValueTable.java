package games.brennan.dungeontrain.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * The community-approved Trade Everything item values, bundled at build time.
 *
 * <p>Source of truth is the relay's item-values page
 * ({@code brennan.games/dungeontrain/items/}): members suggest, the approver settles, and
 * {@code ./gradlew pullTradeValues} copies {@code GET /items/export} into
 * {@link #RESOURCE}. That file is committed, so a build never touches the network and each
 * pull is its own reviewable diff. Values are integer sixteenths of an emerald.</p>
 *
 * <p>Loaded once, never throws: a missing or malformed file logs and yields an empty table, so
 * Trade Everything's own defaults (and {@link TradeEverythingBridge}'s hand-tuned constants)
 * apply as before.</p>
 */
public final class TradeValueTable {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String RESOURCE = "/data/dungeontrain/trade_values.json";

    /** Same ceiling as the relay: 4096 emeralds. */
    static final int MAX_VALUE = 65536;

    private static volatile Map<String, Integer> values = null;

    private TradeValueTable() {}

    /** The approved value for {@code itemId}, or empty when the page has not set one. */
    public static OptionalInt valueOf(ResourceLocation itemId) {
        if (itemId == null) return OptionalInt.empty();
        Integer v = table().get(itemId.toString());
        return v == null ? OptionalInt.empty() : OptionalInt.of(v);
    }

    /** How many items carry an approved value. */
    public static int size() {
        return table().size();
    }

    /** An immutable, id-sorted view of every approved value. */
    public static Map<String, Integer> all() {
        return table();
    }

    private static Map<String, Integer> table() {
        Map<String, Integer> t = values;
        if (t == null) {
            synchronized (TradeValueTable.class) {
                t = values;
                if (t == null) {
                    t = load();
                    values = t;
                }
            }
        }
        return t;
    }

    private static Map<String, Integer> load() {
        try (InputStream in = TradeValueTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[trade-values] {} missing from the jar; no approved trade values", RESOURCE);
                return Collections.emptyMap();
            }
            Map<String, Integer> parsed = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!parsed.isEmpty()) LOGGER.info("[trade-values] {} approved item values loaded", parsed.size());
            return parsed;
        } catch (Exception e) {
            LOGGER.warn("[trade-values] could not read {}; no approved trade values: {}", RESOURCE, e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * {@code {"values": {"minecraft:diamond": 64, …}}} → an immutable sorted map. Entries that are
     * not a valid registry id, or whose value is not an integer in {@code 1..MAX_VALUE}, are dropped
     * with a warning rather than failing the whole table; anything unparsable yields an empty map.
     */
    static Map<String, Integer> parse(String body) {
        TreeMap<String, Integer> out = new TreeMap<>();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return Collections.emptyMap();
            JsonElement valuesEl = root.getAsJsonObject().get("values");
            if (valuesEl == null || !valuesEl.isJsonObject()) return Collections.emptyMap();
            JsonObject values = valuesEl.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : values.entrySet()) {
                String id = e.getKey();
                JsonElement v = e.getValue();
                if (ResourceLocation.tryParse(id) == null || id.indexOf(':') < 0) {
                    LOGGER.warn("[trade-values] skipping '{}': not an item id", id);
                    continue;
                }
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                    LOGGER.warn("[trade-values] skipping '{}': value is not a number", id);
                    continue;
                }
                double d = v.getAsDouble();
                int n = (int) d;
                if (d != n || n < 1 || n > MAX_VALUE) {
                    LOGGER.warn("[trade-values] skipping '{}': {} is not a whole number of sixteenths in 1..{}", id, d, MAX_VALUE);
                    continue;
                }
                out.put(id, n);
            }
        } catch (Exception e) {
            LOGGER.warn("[trade-values] malformed table: {}", e.toString());
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(out);
    }

    /** Test seam: replace the loaded table ({@code null} reloads from the jar on next use). */
    static void setForTest(Map<String, Integer> table) {
        values = table == null ? null : Collections.unmodifiableMap(new TreeMap<>(table));
    }
}
