package games.brennan.dungeontrain.train;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.template.TemplateWeightOverlay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How often each {@link CarriageLayout} fills a group: Rooms ×3, Halves ×2, Group ×1, each a weight.
 *
 * <p>Bundled at {@code /data/dungeontrain/templates/layout-weights.json}, overridden whole-file by
 * {@code config/dungeontrain/user/layout-weights.json} (in a dev checkout the source copy is
 * written too) — the shape of {@link FullCarriageSettings}.</p>
 *
 * <pre>{"rooms": 100, "halves": 1, "group": 1}</pre>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public record LayoutWeights(int rooms, int halves, int group) {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE = "layout-weights.json";
    public static final String BUNDLED_RESOURCE = "/data/dungeontrain/templates/" + FILE;

    public static final int MIN = 0;
    public static final int MAX = 1000;
    /** Mostly rooms, as the train has always been, with a Half pair or a Group carriage among them. */
    public static final LayoutWeights DEFAULT = new LayoutWeights(100, 1, 1);

    private static volatile LayoutWeights current = DEFAULT;

    public LayoutWeights {
        rooms = clamp(rooms);
        halves = clamp(halves);
        group = clamp(group);
    }

    public static LayoutWeights current() {
        return current;
    }

    public int weightOf(CarriageLayout layout) {
        return switch (layout) {
            case ROOMS -> rooms;
            case HALVES -> halves;
            case GROUP -> group;
        };
    }

    /** A copy with {@code layout}'s weight replaced. */
    public LayoutWeights with(CarriageLayout layout, int weight) {
        return switch (layout) {
            case ROOMS -> new LayoutWeights(weight, halves, group);
            case HALVES -> new LayoutWeights(rooms, weight, group);
            case GROUP -> new LayoutWeights(rooms, halves, weight);
        };
    }

    public static int clamp(int n) {
        return Math.max(MIN, Math.min(MAX, n));
    }

    /** Persist {@code layout}'s new weight to the user overlay (and the source tree in a dev checkout). */
    public static synchronized LayoutWeights set(CarriageLayout layout, int weight) throws IOException {
        current = current.with(layout, weight);
        Path file = configPath();
        Files.createDirectories(file.getParent());
        write(file, current);
        Path src = sourceFileOrNull();
        if (src != null) {
            try {
                write(src, current);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Failed to write layout weights to source tree: {}", e.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Layout weights {} (persisted to {}).", current, file);
        return current;
    }

    private static void write(Path file, LayoutWeights w) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("rooms", w.rooms());
        o.addProperty("halves", w.halves());
        o.addProperty("group", w.group());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(o, out);
        }
    }

    public static Path configPath() {
        return UserContentPaths.root().resolve(FILE);
    }

    private static Path sourceFileOrNull() {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return resources.resolve(BUNDLED_RESOURCE.substring(1));
    }

    /** Bundled first, then the user overlay replaces it whole when readable. */
    public static synchronized void reload() {
        LayoutWeights value = DEFAULT;
        LayoutWeights bundled = read(openResource(), BUNDLED_RESOURCE);
        if (bundled != null) value = bundled;
        if (TemplateWeightOverlay.overlayReadable()) {
            Path file = configPath();
            if (Files.isRegularFile(file)) {
                LayoutWeights user = read(openFile(file), file.toString());
                if (user != null) value = user;
            }
        }
        current = value;
        LOGGER.info("[DungeonTrain] Layout weights loaded — {}", current);
    }

    public static synchronized void clear() {
        current = DEFAULT;
    }

    /** {@code reader}'s weights, any missing one at its default; null when unreadable. */
    static LayoutWeights read(Reader reader, String where) {
        if (reader == null) return null;
        try (Reader r = reader) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            return new LayoutWeights(
                o.has("rooms") ? o.get("rooms").getAsInt() : DEFAULT.rooms(),
                o.has("halves") ? o.get("halves").getAsInt() : DEFAULT.halves(),
                o.has("group") ? o.get("group").getAsInt() : DEFAULT.group());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Could not read layout weights {}: {}", where, e.toString());
            return null;
        }
    }

    private static Reader openResource() {
        InputStream in = LayoutWeights.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openFile(Path file) {
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not open layout weights {}: {}", file, e.toString());
            return null;
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
