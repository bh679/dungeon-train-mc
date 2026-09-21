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
 * The Whole section's one type-level setting: <b>how often a carriage group is a whole group</b>.
 *
 * <p>{@link #every()} is the {@code N} in "one group in every N", the same shape as the portal
 * lottery's {@code carriageEvery} — {@code 0} switches whole groups off. Unlike the portal rate it
 * is editor content, not world state: it ships bundled at
 * {@code /data/dungeontrain/whole/group/settings.json}, is overridden whole-file by
 * {@code config/dungeontrain/user/carriagegroups/settings.json}, and travels with a package the
 * same way the group weights beside it do. Editing it mid-run only affects groups that have not been
 * stamped yet, which is the same posture every weight edit already takes.</p>
 *
 * <p>{@link #force(int)} is a session-only override for testing: a positive value makes exactly
 * every Nth group a whole group regardless of the seed, like the portal's creative cadence.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WholeGroupSettings {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE = "settings.json";
    public static final String BUNDLED_RESOURCE = "/data/dungeontrain/whole/group/" + FILE;
    private static final String K_EVERY = "every";

    public static final int OFF = 0;
    public static final int DEFAULT_EVERY = 12;
    public static final int MAX_EVERY = 64;

    private static volatile int every = DEFAULT_EVERY;
    private static volatile int forced = OFF;

    private WholeGroupSettings() {}

    /** One group in every N is a whole group; {@link #OFF} means never. */
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
        if (WholeWeights.sourceTreeAvailable()) {
            try {
                Path src = sourceFile();
                Files.createDirectories(src.getParent());
                write(src);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Failed to write whole-group settings to source tree: {}", e.toString());
            }
        }
        LOGGER.info("[DungeonTrain] Whole group every={} (persisted to {}).", every, file);
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
        return UserContentPaths.activeSubDir(WholeKind.GROUP.userSubdir()).resolve(FILE);
    }

    private static Path sourceFile() {
        return WholeWeights.sourceFile(WholeKind.GROUP).getParent().resolve(FILE);
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
        LOGGER.info("[DungeonTrain] Whole group settings loaded — every={}", every);
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
            LOGGER.warn("[DungeonTrain] Could not read whole-group settings {}: {}", where, e.toString());
            return null;
        }
    }

    private static Reader openResource() {
        InputStream in = WholeGroupSettings.class.getResourceAsStream(BUNDLED_RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openFile(Path file) {
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not open {}: {}", file, e.toString());
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
