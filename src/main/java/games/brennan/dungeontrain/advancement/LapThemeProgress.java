package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.worldgen.LapTheme;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How far each player has ever got through a lap of each {@link LapTheme}, across every run (each run
 * is its own world, so this lives outside the save): the best fraction {@code [0, 1]} of a theme lap
 * reached, per theme. {@code 1.0} means the player crossed the end of that lap's End band — the theme
 * is 100% complete. Read when a world decides a new lap's theme ({@code LapThemePicker}).
 *
 * <p>{@code <gameDir>/dungeontrain/lap_themes/<uuid>.json} → {@code {"vanilla":1.0,"bop":0.42,"better":0.0}}.
 * A lazy per-UUID cache like {@link GlobalPlayerStats}: written at each 25% milestone, on logout and
 * on server stop ({@code LapThemeProgressEvents}). A brand-new store, so it has no legacy location
 * in {@link PlayerDataPaths#RELOCATIONS}; backups take the whole root.</p>
 */
public final class LapThemeProgress {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Subdirectory under {@link PlayerDataPaths#root()}. */
    public static final String DIR = "lap_themes";

    /** Progress is written to disk each time it crosses one of these steps. */
    static final double MILESTONE = 0.25;

    private static final Map<UUID, Map<LapTheme, Double>> CACHE = new ConcurrentHashMap<>();

    private LapThemeProgress() {}

    public static Path file(UUID uuid) {
        return PlayerDataPaths.dir(DIR).resolve(uuid + ".json");
    }

    /** The player's best fraction per theme (a copy; every theme present, missing = 0). */
    public static Map<LapTheme, Double> of(UUID uuid) {
        return filled(cached(uuid));
    }

    /**
     * Raise {@code uuid}'s best fraction for {@code theme} to {@code fraction} if it is higher; writes
     * the file when the raise crosses a {@link #MILESTONE}. Returns true when the stored value rose.
     */
    public static boolean raise(UUID uuid, LapTheme theme, double fraction) {
        if (uuid == null || theme == null || !(fraction > 0.0)) return false;
        double f = Math.min(1.0, fraction);
        Map<LapTheme, Double> map = cached(uuid);
        double before;
        synchronized (map) {
            before = map.getOrDefault(theme, 0.0);
            if (f <= before) return false;
            map.put(theme, f);
        }
        if (crossesMilestone(before, f)) flush(uuid);
        return true;
    }

    /** Overwrite {@code uuid}'s progress for {@code theme} (debug command) and write it straight away. */
    public static void set(UUID uuid, LapTheme theme, double fraction) {
        Map<LapTheme, Double> map = cached(uuid);
        synchronized (map) {
            map.put(theme, Math.max(0.0, Math.min(1.0, fraction)));
        }
        flush(uuid);
    }

    /**
     * The mean progress per theme over {@code players} — the input a multiplayer world decides a lap
     * from. Empty input gives all zeros.
     */
    public static Map<LapTheme, Double> average(Collection<UUID> players) {
        Map<LapTheme, Double> sum = filled(Map.of());
        if (players == null || players.isEmpty()) return sum;
        for (UUID uuid : players) {
            Map<LapTheme, Double> p = of(uuid);
            for (LapTheme t : LapTheme.values()) sum.merge(t, p.get(t), Double::sum);
        }
        Map<LapTheme, Double> mean = new EnumMap<>(LapTheme.class);
        for (LapTheme t : LapTheme.values()) mean.put(t, sum.get(t) / players.size());
        return mean;
    }

    /** True when going from {@code before} to {@code after} passes a 25% step (or reaches 100%). */
    static boolean crossesMilestone(double before, double after) {
        if (after >= 1.0 && before < 1.0) return true;
        return Math.floor(after / MILESTONE) > Math.floor(before / MILESTONE);
    }

    static Map<LapTheme, Double> filled(Map<LapTheme, Double> in) {
        Map<LapTheme, Double> out = new EnumMap<>(LapTheme.class);
        for (LapTheme t : LapTheme.values()) out.put(t, 0.0);
        synchronized (in) {
            in.forEach((t, v) -> out.put(t, v == null || v.isNaN() ? 0.0 : Math.max(0.0, Math.min(1.0, v))));
        }
        return out;
    }

    public static void flush(UUID uuid) {
        Map<LapTheme, Double> map = CACHE.get(uuid);
        if (map != null) save(uuid, filled(map));
    }

    public static void flushAll() {
        for (UUID uuid : new HashMap<>(CACHE).keySet()) flush(uuid);
    }

    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    private static Map<LapTheme, Double> cached(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, LapThemeProgress::load);
    }

    private static Map<LapTheme, Double> load(UUID uuid) {
        Map<LapTheme, Double> out = new EnumMap<>(LapTheme.class);
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) return out;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!(root instanceof JsonObject obj)) return out;
            for (LapTheme t : LapTheme.values()) {
                JsonElement v = obj.get(t.id());
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double d = v.getAsDouble();
                    if (!Double.isNaN(d)) out.put(t, Math.max(0.0, Math.min(1.0, d)));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] LapThemeProgress: could not read {}: {}", path, e.getMessage());
        }
        return out;
    }

    private static synchronized void save(UUID uuid, Map<LapTheme, Double> progress) {
        Path path = file(uuid);
        JsonObject obj = new JsonObject();
        progress.forEach((t, v) -> obj.addProperty(t.id(), v));
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                writer.write(obj.toString());
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] LapThemeProgress: failed to write {}: {}", path, e.getMessage());
        }
    }
}
