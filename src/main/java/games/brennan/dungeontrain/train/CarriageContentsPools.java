package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Contents pools ({@code data/dungeontrain/contents/pools.json}): top-level group parents that share one
 * slot of the top-level draw instead of each holding its own.
 *
 * <pre>{@code
 * {"enemies": {"weight": 8, "members": ["enemies_undead", "enemies_odd", ...]}}
 * }</pre>
 *
 * <p>The slot carries the pool's {@code weight}. When it wins, one member is drawn by its own weights.json
 * weight {@code G}, scaled by the share of its room weight its gates leave open at that spot
 * ({@code G × open/full}), and that group then draws its room as usual. {@code G} is how common the group is
 * with every room open, so adding rooms to a group shares its weight out rather than raising it. With
 * {@code G} set to the group's full room weight, a room's chance is {@code weight/T × w_room/E}, {@code E}
 * the open weight of every room in the pool: the same as one group holding all of them.</p>
 *
 * <p>Bundled only: pools are part of how the shipped contents are organised, not something an install edits.</p>
 */
public final class CarriageContentsPools {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "/data/dungeontrain/contents/pools.json";

    /** One pool: the shared slot's weight, the group parents that share it, and the editor's name for it. */
    public record Pool(String id, int weight, List<String> members, String name) {
        public Pool {
            members = List.copyOf(members);
            if (name == null || name.isBlank()) name = id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        }

        public Pool(String id, int weight, List<String> members) {
            this(id, weight, members, null);
        }
    }

    /** Every pool, in file order. */
    public static List<Pool> all() {
        List<Pool> out = new ArrayList<>();
        for (Pool p : byMember().values()) if (!out.contains(p)) out.add(p);
        return out;
    }

    private static volatile Map<String, Pool> BY_MEMBER;

    private CarriageContentsPools() {}

    /** The pool {@code contentsId} shares a slot in, or null when it draws on its own. */
    public static Pool poolOf(String contentsId) {
        if (contentsId == null) return null;
        return byMember().get(contentsId.toLowerCase(Locale.ROOT));
    }

    public static void clearCache() {
        BY_MEMBER = null;
    }

    /** Test seam: use {@code pools} instead of the bundled file until the next {@link #clearCache}. */
    static void injectForTesting(List<Pool> pools) {
        BY_MEMBER = indexByMember(pools);
    }

    /**
     * What a pooled group offers at one spot: its own {@code groupWeight}, scaled by the share of its room
     * weight open there ({@code openWeight} of {@code fullWeight}). Nothing when no room weight is open.
     */
    static double offeredShare(int groupWeight, int openWeight, int fullWeight) {
        if (groupWeight <= 0 || openWeight <= 0 || fullWeight <= 0) return 0;
        return groupWeight * (openWeight / (double) fullWeight);
    }

    /**
     * Index of the member drawn from {@code offered}, the weight each member can offer at this spot. Falls back
     * to a uniform draw when every member offers nothing, so the slot still resolves to a group (whose own
     * draw then keeps the old "self only" behaviour).
     */
    static int pickMember(double[] offered, Random rng) {
        double total = 0;
        for (double w : offered) total += Math.max(0, w);
        if (total <= 0) return rng.nextInt(offered.length);
        double r = rng.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < offered.length; i++) {
            cumulative += Math.max(0, offered[i]);
            if (r < cumulative) return i;
        }
        return offered.length - 1;
    }

    private static Map<String, Pool> byMember() {
        Map<String, Pool> cached = BY_MEMBER;
        if (cached == null) {
            cached = indexByMember(load());
            BY_MEMBER = cached;
        }
        return cached;
    }

    static Map<String, Pool> indexByMember(List<Pool> pools) {
        Map<String, Pool> out = new LinkedHashMap<>();
        for (Pool pool : pools) {
            for (String member : pool.members()) {
                Pool previous = out.putIfAbsent(member.toLowerCase(Locale.ROOT), pool);
                if (previous != null) {
                    LOGGER.warn("[DungeonTrain] Contents '{}' is in pools '{}' and '{}' — keeping '{}'.",
                        member, previous.id(), pool.id(), previous.id());
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<Pool> load() {
        try (InputStream in = CarriageContentsPools.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return List.of();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader));
            }
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not read {} — pooled groups draw on their own weights.", RESOURCE, e);
            return List.of();
        }
    }

    static List<Pool> parse(JsonElement root) {
        List<Pool> pools = new ArrayList<>();
        if (root == null || !root.isJsonObject()) return pools;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            if (e.getKey().startsWith("_") || !e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            int weight = o.has("weight") ? Math.max(0, o.get("weight").getAsInt()) : 1;
            List<String> members = new ArrayList<>();
            if (o.get("members") instanceof JsonArray array) {
                for (JsonElement m : array) members.add(m.getAsString().toLowerCase(Locale.ROOT));
            }
            String name = o.has("name") ? o.get("name").getAsString() : null;
            if (!members.isEmpty()) pools.add(new Pool(e.getKey(), weight, members, name));
        }
        return pools;
    }
}
