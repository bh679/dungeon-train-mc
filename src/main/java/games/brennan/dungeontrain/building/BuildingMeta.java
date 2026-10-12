package games.brennan.dungeontrain.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A new building's roster weight — how often the new-building slot picks it over the others — kept in a
 * {@code <name>.building.json} sidecar beside its template: {@code {"weight": 3}}.
 *
 * <p>Only new buildings have one. A shipped building's weight is its entry in the Lost City structure set,
 * which an edit leaves alone.</p>
 */
public record BuildingMeta(int weight) {

    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 10;
    public static final int DEFAULT_WEIGHT = 3;
    public static final BuildingMeta DEFAULT = new BuildingMeta(DEFAULT_WEIGHT);

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String EXT = ".building.json";

    public BuildingMeta {
        weight = clampWeight(weight);
    }

    /** {@code weight} kept within {@link #MIN_WEIGHT}..{@link #MAX_WEIGHT}. */
    public static int clampWeight(int weight) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
    }

    /** This meta with {@code weight}. */
    public BuildingMeta withWeight(int newWeight) {
        return new BuildingMeta(newWeight);
    }

    /** {@code name}'s meta: the player's sidecar, else the jar's, else {@link #DEFAULT}. */
    public static BuildingMeta load(String name) {
        Path file = UserContentPaths.findFile(Buildings.SUBDIR, name + EXT);
        try {
            if (file != null) return parse(Files.readString(file, StandardCharsets.UTF_8));
            try (var in = BuildingMeta.class.getResourceAsStream(BuildingStore.BUNDLED_NEW_RESOURCE + name + EXT)) {
                if (in != null) return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Unreadable building sidecar for {}: {} — using the default weight", name, e.toString());
        }
        return DEFAULT;
    }

    /** Write {@code meta} as {@code name}'s sidecar in the active package — and beside its source copy when {@code toSource}. */
    public static void save(String name, BuildingMeta meta, boolean toSource) throws IOException {
        String json = meta.toJson();
        Path file = UserContentPaths.activeSubDir(Buildings.SUBDIR).resolve(name + EXT);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        if (!toSource) return;
        Path source = BuildingStore.sourceFileFor(name);
        if (source == null) return;
        Path sidecar = source.resolveSibling(name + EXT);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, json, StandardCharsets.UTF_8);
    }

    /** Delete {@code name}'s sidecars from every package. */
    public static boolean delete(String name) throws IOException {
        boolean deleted = false;
        Path file;
        while ((file = UserContentPaths.findFile(Buildings.SUBDIR, name + EXT)) != null) {
            if (!Files.deleteIfExists(file)) break;
            deleted = true;
        }
        return deleted;
    }

    static BuildingMeta parse(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        return new BuildingMeta(obj.has("weight") ? obj.get("weight").getAsInt() : DEFAULT_WEIGHT);
    }

    String toJson() {
        return "{\n  \"weight\": " + weight + "\n}\n";
    }
}
