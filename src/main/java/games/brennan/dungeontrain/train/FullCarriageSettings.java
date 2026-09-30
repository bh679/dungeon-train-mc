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
 * How often a carriage group is one <b>Full carriage</b> — a single shell as long as the group, with
 * Full contents inside ({@link FullCarriageSelection}).
 *
 * <p>{@link #every()} is the {@code N} in "one group in every N", the shape of
 * {@link WholeGroupSettings}; {@code 0} switches Full carriages off. Bundled at
 * {@code /data/dungeontrain/templates/full-settings.json}, overridden whole-file by
 * {@code config/dungeontrain/user/full-settings.json}. {@link #force(int)} is the session-only
 * testing cadence: exactly every Nth group, seed ignored.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FullCarriageSettings {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE = "full-settings.json";
    public static final String BUNDLED_RESOURCE = "/data/dungeontrain/templates/" + FILE;
    private static final String K_EVERY = "every";

    public static final int OFF = 0;
    /**
     * Off unless switched on: a Full carriage is a new kind of carriage on every player's train, so
     * it waits until there are Full contents worth riding through.
     */
    public static final int DEFAULT_EVERY = OFF;
    public static final int MAX_EVERY = 1000;

    private static volatile int every = DEFAULT_EVERY;
    private static volatile int forced = OFF;

    private FullCarriageSettings() {}

    /** One group in every N is a Full carriage; {@link #OFF} means never. */
    public static int every() {
        return every;
    }

    /** The session-only periodic override, or {@link #OFF} when none. */
    public static int forced() {
        return forced;
    }

    public static void force(int n) {
        forced = clamp(n);
    }

    public static int clamp(int n) {
        return Math.max(OFF, Math.min(MAX_EVERY, n));
    }

    /** Persist a new N to the user overlay (and the source tree in a dev checkout). */
    public static synchronized int set(int n) throws IOException {
        every = clamp(n);
        Path file = configPath();
        Files.createDirectories(file.getParent());
        write(file);
        Path src = sourceFileOrNull();
        if (src != null) {
            try {
                write(src);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Failed to write Full-carriage settings to source tree: {}", e.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Full carriage every={} (persisted to {}).", every, file);
        return every;
    }

    private static void write(Path file) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty(K_EVERY, every);
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
        int value = DEFAULT_EVERY;
        Integer bundled = read(openResource(), BUNDLED_RESOURCE);
        if (bundled != null) value = bundled;
        if (TemplateWeightOverlay.overlayReadable()) {
            Path file = configPath();
            if (Files.isRegularFile(file)) {
                Integer user = read(openFile(file), file.toString());
                if (user != null) value = user;
            }
        }
        every = clamp(value);
        forced = OFF;
        LOGGER.info("[DungeonTrain] Full carriage settings loaded — every={}", every);
    }

    public static synchronized void clear() {
        every = DEFAULT_EVERY;
        forced = OFF;
    }

    private static Integer read(Reader reader, String where) {
        if (reader == null) return null;
        try (Reader r = reader) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject() || !root.getAsJsonObject().has(K_EVERY)) return null;
            return root.getAsJsonObject().get(K_EVERY).getAsInt();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Could not read Full-carriage settings {}: {}", where, e.toString());
            return null;
        }
    }

    private static Reader openResource() {
        InputStream in = FullCarriageSettings.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openFile(Path file) {
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not open Full-carriage settings {}: {}", file, e.toString());
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
