package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What the {@code dungeontrain:random_totem} loot placeholder becomes: one Balanced Totem, picked by
 * the weights in {@link #RESOURCE}. The vanilla Totem of Undying stays in ominous vaults; everywhere
 * else DT's loot carries this placeholder instead (see {@code ContainerContentsRoller}).
 *
 * <p>Loaded once, never throws: a missing or malformed file logs and yields an empty table, and an
 * empty table (or one whose ids are none of them registered) makes {@link #pick} answer empty so the
 * roller falls back to the vanilla totem.</p>
 */
public final class RandomTotemTable {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String RESOURCE = "/data/dungeontrain/loot/random_totem.json";

    /** Left out of every roll until the train reaches the void ({@link VoidTotemGate}). */
    public static final ResourceLocation VOID_TOTEM = ResourceLocation.fromNamespaceAndPath("balancedtotems", "void_totem");

    private static volatile Map<ResourceLocation, Integer> weights = null;

    private RandomTotemTable() {}

    /**
     * One totem id for {@code seed}, among the ids {@code registered} accepts, leaving out the Void
     * Totem unless {@code voidReached}; empty when none qualifies.
     */
    public static Optional<ResourceLocation> pick(long seed, Predicate<ResourceLocation> registered, boolean voidReached) {
        return pick(table(), seed, eligible(registered, voidReached));
    }

    /** {@code registered}, minus the Void Totem before the void. */
    static Predicate<ResourceLocation> eligible(Predicate<ResourceLocation> registered, boolean voidReached) {
        return voidReached ? registered : id -> !VOID_TOTEM.equals(id) && registered.test(id);
    }

    /** Whether {@code id} is one of the totems this table rolls. */
    public static boolean contains(ResourceLocation id) {
        return table().containsKey(id);
    }

    /** {@code id}'s weight, or 0 when the table does not roll it. */
    public static int weightOf(ResourceLocation id) {
        return table().getOrDefault(id, 0);
    }

    /** The lightest weight in the table (the rarest totem), or 0 when the table is empty. */
    public static int minWeight() {
        return table().values().stream().mapToInt(Integer::intValue).min().orElse(0);
    }

    /** Weighted pick over the entries {@code registered} accepts. Same seed, same answer. */
    static Optional<ResourceLocation> pick(Map<ResourceLocation, Integer> table, long seed,
                                           Predicate<ResourceLocation> registered) {
        long total = 0;
        for (Map.Entry<ResourceLocation, Integer> e : table.entrySet()) {
            if (registered.test(e.getKey())) total += e.getValue();
        }
        if (total <= 0) return Optional.empty();
        long roll = Math.floorMod(seed, total);
        for (Map.Entry<ResourceLocation, Integer> e : table.entrySet()) {
            if (!registered.test(e.getKey())) continue;
            roll -= e.getValue();
            if (roll < 0) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** The loaded weights, in file order. */
    public static Map<ResourceLocation, Integer> all() {
        return table();
    }

    private static Map<ResourceLocation, Integer> table() {
        Map<ResourceLocation, Integer> t = weights;
        if (t == null) {
            synchronized (RandomTotemTable.class) {
                t = weights;
                if (t == null) {
                    t = load();
                    weights = t;
                }
            }
        }
        return t;
    }

    private static Map<ResourceLocation, Integer> load() {
        try (InputStream in = RandomTotemTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[random-totem] {} missing from the jar; random totems fall back to vanilla", RESOURCE);
                return Collections.emptyMap();
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.warn("[random-totem] could not read {}; random totems fall back to vanilla: {}", RESOURCE, e.toString());
            return Collections.emptyMap();
        }
    }

    /**
     * {@code {"weights": {"balancedtotems:void_totem": 6, …}}} → an immutable map in file order.
     * Entries that are not an item id or whose weight is not a positive integer are dropped with a
     * warning; anything unparsable yields an empty map.
     */
    static Map<ResourceLocation, Integer> parse(String body) {
        LinkedHashMap<ResourceLocation, Integer> out = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return Collections.emptyMap();
            JsonElement weightsEl = root.getAsJsonObject().get("weights");
            if (weightsEl == null || !weightsEl.isJsonObject()) return Collections.emptyMap();
            JsonObject all = weightsEl.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : all.entrySet()) {
                ResourceLocation id = e.getKey().indexOf(':') < 0 ? null : ResourceLocation.tryParse(e.getKey());
                JsonElement v = e.getValue();
                if (id == null) {
                    LOGGER.warn("[random-totem] skipping '{}': not an item id", e.getKey());
                    continue;
                }
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()
                        || v.getAsDouble() != Math.rint(v.getAsDouble()) || v.getAsInt() <= 0) {
                    LOGGER.warn("[random-totem] skipping '{}': weight is not a positive integer", e.getKey());
                    continue;
                }
                out.put(id, v.getAsInt());
            }
        } catch (Exception e) {
            LOGGER.warn("[random-totem] unparsable weights: {}", e.toString());
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(out);
    }
}
