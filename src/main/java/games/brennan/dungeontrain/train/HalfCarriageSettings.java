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
 * How a Half pair joins its two halves ({@link HalfJoinMode}). How <i>often</i> a group is a Half pair
 * is not here — that is the Half shells' own weights, see {@link HalfCarriageSelection}.
 *
 * <p>Bundled at {@code /data/dungeontrain/templates/half-settings.json}, overridden whole-file by
 * {@code config/dungeontrain/user/half-settings.json} — the shape of {@link FullCarriageSettings}.</p>
 *
 * <pre>{"join": "wall"}</pre>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HalfCarriageSettings {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE = "half-settings.json";
    public static final String BUNDLED_RESOURCE = "/data/dungeontrain/templates/" + FILE;
    private static final String K_JOIN = "join";

    public static final HalfJoinMode DEFAULT_JOIN = HalfJoinMode.WALL;

    private static volatile HalfJoinMode join = DEFAULT_JOIN;

    private HalfCarriageSettings() {}

    public static HalfJoinMode join() {
        return join;
    }

    /** Persist a new join mode to the user overlay (and the source tree in a dev checkout). */
    public static synchronized HalfJoinMode set(HalfJoinMode mode) throws IOException {
        join = mode;
        Path file = configPath();
        Files.createDirectories(file.getParent());
        write(file);
        Path src = sourceFileOrNull();
        if (src != null) {
            try {
                write(src);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Failed to write Half-carriage settings to source tree: {}", e.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Half carriage join={} (persisted to {}).", join.key(), file);
        return join;
    }

    private static void write(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty(K_JOIN, join.key());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
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
        HalfJoinMode value = DEFAULT_JOIN;
        HalfJoinMode bundled = read(openResource(), BUNDLED_RESOURCE);
        if (bundled != null) value = bundled;
        if (TemplateWeightOverlay.overlayReadable()) {
            Path file = configPath();
            if (Files.isRegularFile(file)) {
                HalfJoinMode user = read(openFile(file), file.toString());
                if (user != null) value = user;
            }
        }
        join = value;
        LOGGER.info("[DungeonTrain] Half carriage settings loaded — join={}", join.key());
    }

    public static synchronized void clear() {
        join = DEFAULT_JOIN;
    }

    private static HalfJoinMode read(Reader reader, String where) {
        if (reader == null) return null;
        try (Reader r = reader) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject() || !root.getAsJsonObject().has(K_JOIN)) return null;
            String raw = root.getAsJsonObject().get(K_JOIN).getAsString();
            HalfJoinMode mode = HalfJoinMode.parse(raw).orElse(null);
            if (mode == null) LOGGER.warn("[DungeonTrain] Unknown Half join '{}' in {}", raw, where);
            return mode;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Could not read Half-carriage settings {}: {}", where, e.toString());
            return null;
        }
    }

    private static Reader openResource() {
        InputStream in = HalfCarriageSettings.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openFile(Path file) {
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not open Half-carriage settings {}: {}", file, e.toString());
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
