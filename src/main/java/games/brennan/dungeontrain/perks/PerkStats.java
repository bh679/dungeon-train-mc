package games.brennan.dungeontrain.perks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The key stats each perk moves, read from {@code data/dungeontrain/perks/stats.json}: per perk id, a
 * list of stats, each worth {@code per_point} for every point in the perk. Kept apart from
 * {@code tree.json} so regenerating the tree from the editor leaves them alone. The menu shows them
 * as chips and previews a point's value on hover.
 */
public final class PerkStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String RESOURCE = "/data/dungeontrain/perks/stats.json";
    /** Units a stat can be shown in; each has a lang key {@code gui.dungeontrain.perks.stat.unit.<unit>}. */
    public static final Set<String> UNITS = Set.of("", "seconds");

    /** One stat: its lang id ({@code gui.dungeontrain.perks.stat.<id>}), value per point, and unit. */
    public record Stat(String id, double perPoint, String unit) {
        public double at(int points) {
            return perPoint * Math.max(0, points);
        }
    }

    private static volatile Map<String, List<Stat>> loaded;

    private PerkStats() {}

    /** A perk's stats; empty when it has none. */
    public static List<Stat> of(String perkId) {
        return all().getOrDefault(perkId, List.of());
    }

    static Map<String, List<Stat>> all() {
        Map<String, List<Stat>> m = loaded;
        if (m == null) {
            synchronized (PerkStats.class) {
                m = loaded;
                if (m == null) {
                    m = loadBundled();
                    loaded = m;
                }
            }
        }
        return m;
    }

    private static Map<String, List<Stat>> loadBundled() {
        try (InputStream in = PerkStats.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return Map.of();
            return parse(new StringReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] perk stats invalid; perks show no stats", e);
            return Map.of();
        }
    }

    /** Parse and validate; throws {@link IllegalArgumentException} on a bad file. */
    public static Map<String, List<Stat>> parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        Map<String, List<Stat>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().startsWith("_")) continue; // _comment
            List<Stat> stats = new ArrayList<>();
            for (JsonElement s : e.getValue().getAsJsonArray()) {
                JsonObject o = s.getAsJsonObject();
                if (!o.has("stat") || !o.has("per_point")) throw new IllegalArgumentException("stat on " + e.getKey() + " needs stat and per_point");
                String id = o.get("stat").getAsString();
                if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("bad stat id " + id);
                String unit = o.has("unit") ? o.get("unit").getAsString() : "";
                if (!UNITS.contains(unit)) throw new IllegalArgumentException("unknown unit " + unit + " on " + e.getKey());
                stats.add(new Stat(id, o.get("per_point").getAsDouble(), unit));
            }
            out.put(e.getKey(), List.copyOf(stats));
        }
        return Map.copyOf(out);
    }
}
